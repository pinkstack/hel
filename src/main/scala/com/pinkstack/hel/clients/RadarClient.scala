package com.pinkstack.hel.clients

import cats._
import cats.implicits._
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.unmarshalling.Unmarshal
import cats.data.OptionT
import com.typesafe.config.Config
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import io.circe.{Json, JsonObject}
import io.circe.optics.JsonPath.root
import io.circe.syntax._

import scala.concurrent.Future

final case class RadarClient()(implicit system: ActorSystem, config: Config) extends JsonOptics {

  import FailFastCirceSupport._
  import system.dispatcher

  private[this] val transformEvents: Json => Option[Vector[Json]] = {
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
      transformKeys
    ).reduceLeft(_ andThen _)

    _.hcursor.downField("events").focus.map(transformation).flatMap(_.asArray)
  }

  def activeEvents: Future[Option[Vector[Json]]] = {
    for {
      token <- OptionT.fromOption[Future](config.getString("hel.radar.token").some)
      request = HttpRequest(
        uri = s"${config.getString("hel.radar.url")}/mobile/api/v1/events/active",
        headers = Seq(RawHeader("Authorization", token))
      )
      r <- OptionT.liftF(Http().singleRequest(request).filter(_.status.isSuccess()))
      json <- OptionT.liftF(Unmarshal(r).to[Json])
      events <- OptionT.fromOption[Future](transformEvents(json))
    } yield events
  }.value
}
