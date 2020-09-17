package com.hel.clients

import java.time.format.DateTimeFormatter
import java.time.{LocalDateTime, ZoneId, ZoneOffset}
import java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME

import cats._
import cats.implicits._
import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.scaladsl.Flow
import cats.data.{Kleisli, OptionT}
import com.hel.clients.SpinFlow.mutateField
import com.hel.{Configuration, Ticker}
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport

import scala.concurrent.Future

object RadarFlow extends JsonOptics {

  import FailFastCirceSupport._
  import io.circe._

  private[this] val transformEvents: Json => Option[Vector[Json]] = {
    val toUTCEpoch: String => Long = {
      LocalDateTime.parse(_, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        .atZone(ZoneId.of("Europe/Paris"))
        .toEpochSecond
    }

    val transformation: Json => Json = Seq(
      nestInto("location", "lat", "lon"),
      unnestObject("event"),
      unnestObject("note"),
      removeFields(
        "user", "note", "reports", "alarmZones",
        "lon", "lat", "mp3url", "event",
        "prometId", "starred", "monolitId", "deleted",
        "address", "radioOneEvent", "note_user"
      ),
      renameField("created", "created_at"),
      mutateField("id") { json =>
        Json.obj("event_id" -> Json.fromString("radar::" + hashString(json.toString())))
      },
      mutateField("id") { _ =>
        Json.obj("source" -> Json.fromString("radar"))
      },
      transformKeys,
      mutateToEpochs("start_time", "created_at", "updated", "expires", "note_created")(toUTCEpoch)
    ).reduceLeft(_ andThen _)

    _.hcursor.downField("events").focus.map(transformation).flatMap(_.asArray)
  }

  private[this] def fetch(implicit system: ActorSystem, config: Configuration.Radar): Future[Option[Vector[Json]]] = {
    import system.dispatcher
    for {
      token <- OptionT.fromOption[Future](config.token.some)
      request = HttpRequest(
        uri = s"${config.url}/mobile/api/v1/events/active",
        headers = Seq(RawHeader("Authorization", token))
      )
      r <- OptionT.liftF(Http().singleRequest(request).filter(_.status.isSuccess()))
      json <- OptionT.liftF(Unmarshal(r).to[Json])
      events <- OptionT.fromOption[Future](transformEvents(json))
    } yield events
  }.value

  val fromConfig: Kleisli[Option, (ActorSystem, Configuration.Radar), Flow[Ticker.Tick, Json, NotUsed]] = Kleisli {
    case (actorSystem: ActorSystem, config: Configuration.Radar) =>
      Flow[Ticker.Tick].mapAsyncUnordered(1) { _ =>
        fetch(actorSystem, config)
      }.collect {
        case Some(value) => value
        case _ =>
          System.out.println("Crash!")
          throw new Exception("Something else...")
      }.mapConcat(identity).some
  }
}
