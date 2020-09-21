package com.hel.clients

import java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME
import java.time.{LocalDateTime, ZoneOffset}

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.scaladsl.{Flow, RestartFlow}
import cats.data.{Kleisli, OptionT}
import cats.implicits._
import com.hel.clients.RadarFlow.{mutateField, nestInto}
import com.hel.{Configuration, Ticker}
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport

import scala.concurrent.Future
import scala.concurrent.duration._

object SpinFlow extends JsonOptics {

  import FailFastCirceSupport._
  import io.circe._

  private[this] val transformEvents: Json => Option[Vector[Json]] = {
    val toUTCEpoch: String => Long =
      LocalDateTime.parse(_, ISO_OFFSET_DATE_TIME).toEpochSecond(ZoneOffset.UTC)

    val transformations: Json => Json = Seq(
      renameField("wgsLat", "lat"),
      renameField("wgsLon", "lon"),
      transformKeys,
      nestInto("location", "lat", "lon"),
      removeFields("lat", "lon"),
      mutateToEpoch("prijava_cas")(toUTCEpoch),
      mutateToEpoch("nastanek_cas")(toUTCEpoch),
      nestInto("timestamps", "prijava_cas", "nastanek_cas"),
      mutateField("timestamps") { json =>
        Json.obj("entity_id" -> Json.fromString("spin3::" + hashString(json.toString())))
      },
      mutateField("id") { _ =>
        Json.obj("source" -> Json.fromString("spin3"))
      },
      mutateField("entity_id") { _ =>
        Json.obj(
          "source" -> Json.fromString("spin"),
          "section" -> Json.fromString("spin/event"),
          "entity" -> Json.fromString("Event"),
          "categories" -> Json.fromValues(Seq(
            "location", "event"
          ).map(Json.fromString))
        )
      },
      nestInto("hel_meta", "entity_id", "source", "section", "entity", "categories"),
    ).reduceLeft(_ andThen _)

    _.hcursor.downField("value").focus.map(transformations).flatMap(_.asArray)
  }

  private[this] def fetch(implicit system: ActorSystem, config: Configuration.Spin): Future[Option[Vector[Json]]] = {
    import system.dispatcher
    for {
      request <- OptionT.fromOption[Future](HttpRequest(uri = s"${config.url}/javno/assets/data/lokacija.json").some)
      result <- OptionT.liftF(Http().singleRequest(request).filter(_.status.isSuccess()))
      json <- OptionT.liftF(Unmarshal(result).to[Json])
      events <- OptionT.fromOption[Future](transformEvents(json))
    } yield events
  }.value

  val fromConfig: Kleisli[Option, (ActorSystem, Configuration.Spin), Flow[Ticker.Tick, Json, NotUsed]] =
    Kleisli { case (actorSystem: ActorSystem, config: Configuration.Spin) =>
      RestartFlow.onFailuresWithBackoff(
        config.minBackoff, config.maxBackoff,
        config.randomFactor, config.maxRestarts) { () =>

        Flow[Ticker.Tick]
          .mapAsyncUnordered(config.parallelism)(_ => fetch(actorSystem, config))
          .collect {
            case Some(value) => value
            case None => throw new Exception("Transformation failed.")
          }.mapConcat(identity)
      }.some
    }
}

