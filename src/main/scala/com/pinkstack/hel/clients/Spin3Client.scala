package com.pinkstack.hel.clients

import java.math.BigInteger
import java.security.MessageDigest

import cats._
import cats.implicits._
import cats.syntax._
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.unmarshalling.Unmarshal
import cats.data.OptionT
import com.typesafe.config.Config
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import io.circe.{Json, JsonObject}
import java.time._
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatter._

import scala.concurrent.Future

final case class Spin3Client()(implicit system: ActorSystem, config: Config) extends JsonOptics {

  import FailFastCirceSupport._
  import system.dispatcher

  private[this] val transformEvents: Json => Option[Vector[Json]] = {
    val toUTCEpoch: String => String =
      LocalDateTime.parse(_, ISO_OFFSET_DATE_TIME).toEpochSecond(ZoneOffset.UTC).toString

    def mutateToEpoch(key: String): Json => Json = mutateField(key) { json =>
      Json.obj((key, Json.fromString(json.asString.map(toUTCEpoch).orNull)))
    }

    val transformations: Json => Json = Seq(
      renameField("wgsLat", "lat"),
      renameField("wgsLon", "lon"),
      transformKeys,
      nestInto("location", "lat", "lon"),
      removeFields("lat", "lon"),
      mutateToEpoch("prijava_cas"),
      mutateToEpoch("nastanek_cas"),
      nestInto("timestamps", "prijava_cas", "nastanek_cas"),
      mutateField("timestamps") { json =>
        Json.obj("event_id" -> Json.fromString("spin3::" + hashString(json.toString())))
      },
      mutateField("id") { _ =>
        Json.obj("source" -> Json.fromString("spin3"))
      },

    ).reduceLeft(_ andThen _)

    _.hcursor.downField("value").focus.map(transformations).flatMap(_.asArray)
  }

  val locationEvents: Future[Option[Vector[Json]]] = {
    val request = HttpRequest(uri = s"https://spin3.sos112.si/javno/assets/data/lokacija.json")
    for {
      r <- OptionT.liftF(Http().singleRequest(request).filter(_.status.isSuccess()))
      json <- OptionT.liftF(Unmarshal(r).to[Json])
      events <- OptionT.fromOption[Future](transformEvents(json))
    } yield events
  }.value
}
