package com.hel.clients

import java.time.format.DateTimeFormatter
import java.time.{LocalDateTime, ZoneId}

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, Uri}
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.scaladsl.{Flow, RestartFlow, Source}
import cats.data.{Kleisli, OptionT}
import cats.implicits._
import com.hel.{Configuration, Ticker}
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport

import scala.concurrent.Future

object ProminfoFlow extends JsonOptics {

  import FailFastCirceSupport._
  import io.circe._

  private[this] def transformEvents(f: Json => Json): Json => Option[Vector[Json]] = {
    val transform: Json => Json = Seq(
      unnestObject("geometry"),
      unnestObject("attributes"),
      renameField("geometry_y", "lon"),
      renameField("geometry_x", "lat"),
      nestInto("location", "lon", "lat"),
      removeFields("geometry", "lon", "lat", "attributes"),
      transformKeysWith(_.toLowerCase)
    ).reduceLeft(_ andThen _)

    _.hcursor.downField("features").focus.map(transform andThen f).flatMap(_.asArray)
  }

  private[this] def fetchUri(uri: Uri, f: Json => Json)(implicit system: ActorSystem, config: Configuration.Prominfo): Future[Option[Vector[Json]]] = {
    import system.dispatcher
    for {
      request <- OptionT.fromOption[Future](HttpRequest(uri = uri).some)
      r <- OptionT.liftF(Http().singleRequest(request).filter(_.status.isSuccess()))
      json <- OptionT.liftF(Unmarshal(r).to[Json])
      events <- OptionT.fromOption[Future](transformEvents(f)(json))
    } yield events
  }.value

  object Endpoints {
    type Transformation = Json => Json
    type Transformer = (String, Transformation)

    private def define(name: String)(transformations: Transformation*): Transformer =
      (name, transformations.reduceLeft(_ andThen _))

    def parkiriscaGarazneHise: Transformer = define("lay_vParkiriscagaraznehise")(
      mutateField("attributes_naziv") { json =>
        Json.obj("entity_id" -> Json.fromString("prominfo::" + hashString(json.toString())))
      },
      mutateField("entity_id") { _ =>
        Json.obj(
          "source" -> Json.fromString("prominfo"),
          "section" -> Json.fromString("lay_vParkiriscagaraznehise"),
          "entity" -> Json.fromString("Parking garage"),
          "categories" -> Json.fromValues(Seq(
            "location", "counter"
          ).map(Json.fromString))
        )
      },
      nestInto("hel_meta", "entity_id", "source", "section", "entity")
    )

    def drscStevnaMesta: Transformer = define("lay_drsc_stevna_mesta")(
      mutateField("attributes_road_name") { json =>
        Json.obj("entity_id" -> Json.fromString("prominfo::" + hashString(json.toString())))
      },
      mutateField("entity_id") { _ =>
        Json.obj(
          "source" -> Json.fromString("prominfo"),
          "section" -> Json.fromString("lay_drsc_stevna_mesta"),
          "entity" -> Json.fromString("Traffic Counter"),
          "categories" -> Json.fromValues(Seq(
            "location", "counter"
          ).map(Json.fromString))
        )
      },
      nestInto("hel_meta", "entity_id", "source", "section", "entity", "categories")
    )

    def mikrobitStevnaMesta: Transformer = define("lay_MikrobitStevnaMesta")(
      mutateField("attributes_road_name") { json =>
        Json.obj("entity_id" -> Json.fromString("prominfo::" + hashString(json.toString())))
      },
      mutateField("entity_id") { _ =>
        Json.obj(
          "source" -> Json.fromString("prominfo"),
          "section" -> Json.fromString("lay_MikrobitStevnaMesta"),
          "entity" -> Json.fromString("Traffic Counter"),
          //"categories" -> Json.fromValues(Seq(
          //  "location", "counter"
          //).map(Json.fromString))
        )
      },
      nestInto("hel_meta", "entity_id", "source", "section", "entity")
    )

    def carsharing: Transformer = define("lay_vCarsharing")(
      mutateField("attributes_id") { json =>
        Json.obj("entity_id" -> Json.fromString("prominfo::" + hashString(json.toString())))
      },
      mutateField("entity_id") { _ =>
        Json.obj(
          "source" -> Json.fromString("prominfo"),
          "section" -> Json.fromString("lay_vCarsharing"),
          "entity" -> Json.fromString("Car-sharing Spot"),
          //"categories" -> Json.fromValues(Seq(
          //  "location", "counter"
          //).map(Json.fromString))
        )
      },
      nestInto("hel_meta", "entity_id", "source", "section", "entity")
    )

    val endpoints = Seq(parkiriscaGarazneHise, drscStevnaMesta, mikrobitStevnaMesta, carsharing)
  }

  def endpoints(implicit config: Configuration.Prominfo): Map[String, (Uri, Json => Json)] = {
    val defaultQuery = Uri.Query(Map[String, String](
      "returnGeometry" -> "true",
      "where" -> "1=1",
      "outSr" -> "4326",
      "outFields" -> "*",
      "inSr" -> "4326",
      "geometry" -> """{"xmin":14.0321,"ymin":45.7881,"xmax":14.8499,"ymax":46.218,"spatialReference":{"wkid":4326}}""",
      "geometryType" -> "esriGeometryEnvelope",
      "spatialRel" -> "esriSpatialRelContains",
      "f" -> "json"
    ))

    Endpoints.endpoints.filter(e => config.sections.contains(e._1))
      .map { case (k, transformation) =>
        (k, (Uri(s"${config.url}/web/api/MapService/Query/$k/query").withQuery(defaultQuery), transformation))
      }
      .toMap
  }

  val fromConfig: Kleisli[Option, (ActorSystem, Configuration.Prominfo), Flow[Ticker.Tick, Json, NotUsed]] = Kleisli {
    case (actorSystem: ActorSystem, config: Configuration.Prominfo) =>
      RestartFlow.onFailuresWithBackoff(config.minBackoff, config.maxBackoff, config.randomFactor, config.maxRestarts) { () =>
        Flow[Ticker.Tick]
          .flatMapConcat { _ =>
            implicit val system: ActorSystem = actorSystem
            implicit val localConfig: Configuration.Prominfo = config
            Source(endpoints.values.map(p => fetchUri(p._1, p._2)).toList)
          }
          .mapAsync(config.parallelism)(identity)
          .collect {
            case Some(value) => value
            case _ =>
              System.out.println("Crash!")
              throw new Exception("Something else...")
          }.mapConcat(identity)
      }.some
  }
}
