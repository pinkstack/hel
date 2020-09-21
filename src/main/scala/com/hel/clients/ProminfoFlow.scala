package com.hel.clients

import java.time.format.DateTimeFormatter
import java.time.{LocalDateTime, ZoneId}

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, Uri}
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.FlowShape
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Merge, RestartFlow, Source}
import cats.data.{Kleisli, OptionT}
import cats.implicits._
import com.hel.{Configuration, Ticker}
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import io.circe.Json

import scala.concurrent.Future

object ProminfoFlow extends JsonOptics {
  type Transformation = Json => Json
  type Transformer = (String, Transformation)

  import FailFastCirceSupport._
  import io.circe._

  private def defaultQueryTransformation(f: Transformation): Json => Option[Vector[Json]] = {
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

  private[this] def requestTransformWith(uri: Uri)(k: Transformation => Json => Option[Vector[Json]])(f: Transformation)
                                        (implicit system: ActorSystem, config: Configuration.Prominfo): Future[Option[Vector[Json]]] = {
    import system.dispatcher

    for {
      request <- OptionT.fromOption[Future](HttpRequest(uri = uri).some)
      r <- OptionT.liftF(Http().singleRequest(request).filter(_.status.isSuccess()))
      json <- OptionT.liftF(Unmarshal(r).to[Json])
      events <- OptionT.fromOption[Future](k(f)(json))
    } yield events
  }.value

  private[this] def requestWithDefaultTransform(uri: Uri)(f: Transformation)(
    implicit system: ActorSystem, config: Configuration.Prominfo): Future[Option[Vector[Json]]] = {
    requestTransformWith(uri)(defaultQueryTransformation)(f)
  }

  private[this] def requestTransform(uri: Uri)(f: Transformation => Json => Option[Vector[Json]])(
    implicit system: ActorSystem, config: Configuration.Prominfo): Future[Option[Vector[Json]]] = {
    requestTransformWith(uri)(f)(identity)
  }

  object Endpoints {
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
      nestInto("hel_meta", "entity_id", "source", "section", "entity", "categories")
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

  val defaultQueryAttributes: Map[String, String] = Map(
    "returnGeometry" -> "true",
    "where" -> "1=1",
    "outSr" -> "4326",
    "outFields" -> "*",
    "inSr" -> "4326",
    "geometry" -> """{"xmin":14.0321,"ymin":45.7881,"xmax":14.8499,"ymax":46.218,"spatialReference":{"wkid":4326}}""",
    "geometryType" -> "esriGeometryEnvelope",
    "spatialRel" -> "esriSpatialRelContains",
    "f" -> "json"
  )

  val defaultFindAttributes: Map[String, String] = Map(
    "sr" -> "4326",
    "contains" -> "true",
    "returnGeometry" -> "true",
    "returnZ" -> "true",
    "returnM" -> "false",
    "f" -> "json"
  )

  def queryFlow(transformer: Transformer)(implicit actorSystem: ActorSystem, config: Configuration.Prominfo): Flow[Ticker.Tick, Json, NotUsed] = {
    val uri: Uri = Uri(s"${config.url}/web/api/MapService/Query/${transformer._1}/query")
      .withQuery(Uri.Query(defaultQueryAttributes))

    Flow[Ticker.Tick]
      .mapAsyncUnordered(config.parallelism)(_ => requestWithDefaultTransform(uri)(transformer._2)(actorSystem, config))
      .collect {
        case Some(value) => value
        case _ => throw new Exception(s"${transformer._1} crashed")
      }.mapConcat(identity)
  }

  def drscStevnaMestaFlow(implicit actorSystem: ActorSystem, config: Configuration.Prominfo): Flow[Ticker.Tick, Json, NotUsed] = {
    def findInfo(attributeID: String): Future[Option[Vector[Json]]] = {
      val uri: Uri = Uri(s"${config.url}/web/api/MapService/find")
        .withQuery(Uri.Query(defaultFindAttributes ++ Map[String, String](
          "searchFields" -> "road_location",
          "layers" -> "lay_drscstevci",
          "searchText" -> attributeID
        )))

      requestTransform(uri) { t =>
        val transform: Json => Json = Seq(
          unnestObject("geometry"),
          unnestObject("attributes"),
          renameField("geometry_y", "lon"),
          renameField("geometry_x", "lat"),
          nestInto("location", "lon", "lat"),
          transformKeysWith(_.toLowerCase),
          mutateField("attributes_id") { json =>
            Json.obj("entity_id" -> Json.fromString("prominfo::" + hashString(json.toString())))
          },
          mutateField("entity_id") { _ =>
            Json.obj(
              "source" -> Json.fromString("prominfo"),
              "section" -> Json.fromString("lay_drsc_stevna_mesta/counter"),
              "entity" -> Json.fromString("Traffic Counter"),
              "categories" -> Json.fromValues(Seq(
                "location", "counter"
              ).map(Json.fromString))
            )
          },
          nestInto("hel_meta", "entity_id", "source", "section", "entity", "categories"),
          removeFields("geometry", "lon", "lat", "attributes", "geometry_spatialreference"),
        ).reduceLeft(_ andThen _)

        _.hcursor.downField("results").focus.map(t andThen transform).flatMap(_.asArray)
      }
    }

    queryFlow(Endpoints.drscStevnaMesta)
      .map(_.hcursor.downField("attributes_id").as[String].toOption)
      .collect {
        case Some(attributeID) => attributeID
        case None => throw new Exception("Could not fetch attribute ID")
      }
      .mapAsyncUnordered(1)(findInfo)
      .collect {
        case Some(value) => value
        case None => throw new Exception("Data could not be transformed.")
      }.mapConcat(identity)
  }

  def mikrobitStevnaMestaFlow(implicit actorSystem: ActorSystem, config: Configuration.Prominfo): Flow[Ticker.Tick, Json, NotUsed] = {
    def findInfo(attributeID: String): Future[Option[Vector[Json]]] = {
      val uri: Uri = Uri(s"${config.url}/web/api/MapService/find")
        .withQuery(Uri.Query(defaultFindAttributes ++ Map[String, String](
          "searchFields" -> "road_location",
          "layers" -> "lay_mikrobitstevci",
          "searchText" -> attributeID
        )))

      requestTransform(uri) { t =>
        val transform: Json => Json = Seq(
          unnestObject("geometry"),
          unnestObject("attributes"),
          renameField("geometry_y", "lon"),
          renameField("geometry_x", "lat"),
          nestInto("location", "lon", "lat"),
          transformKeysWith(_.toLowerCase),
          mutateField("attributes_id") { json =>
            Json.obj("entity_id" -> Json.fromString("prominfo::" + hashString(json.toString())))
          },
          mutateField("entity_id") { _ =>
            Json.obj(
              "source" -> Json.fromString("prominfo"),
              "section" -> Json.fromString("lay_mikrobitstevci/counter"),
              "entity" -> Json.fromString("Traffic Counter"),
              "categories" -> Json.fromValues(Seq(
                "location", "counter"
              ).map(Json.fromString))
            )
          },
          nestInto("hel_meta", "entity_id", "source", "section", "entity", "categories"),
          removeFields("geometry", "lon", "lat", "attributes", "geometry_spatialreference"),
        ).reduceLeft(_ andThen _)

        _.hcursor.downField("results").focus.map(t andThen transform).flatMap(_.asArray)
      }
    }

    queryFlow(Endpoints.mikrobitStevnaMesta)
      .map(_.hcursor.downField("attributes_id").as[String].toOption)
      .collect {
        case Some(attributeID) => attributeID
        case None => throw new Exception("Could not fetch attribute ID")
      }
      .mapAsyncUnordered(1)(findInfo)
      .collect {
        case Some(value) => value
        case None => throw new Exception("Data could not be transformed.")
      }.mapConcat(identity)
  }

  val fromConfig: Kleisli[Option, (ActorSystem, Configuration.Prominfo), Flow[Ticker.Tick, Json, NotUsed]] = Kleisli {
    case (actorSystem: ActorSystem, config: Configuration.Prominfo) =>
      implicit val system: ActorSystem = actorSystem
      implicit val localConfig: Configuration.Prominfo = config

      RestartFlow.onFailuresWithBackoff(config.minBackoff, config.maxBackoff, config.randomFactor, config.maxRestarts) { () =>

        Flow.fromGraph(GraphDSL.create() { implicit b =>
          import GraphDSL.Implicits._

          val broadcast = b.add(Broadcast[Ticker.Tick](2))
          val merge = b.add(Merge[Json](2))
          val output = b.add(Broadcast[Json](1))

          // @formatter:off
          broadcast.out(0) ~> drscStevnaMestaFlow       ~> merge.in(0)
          broadcast.out(1) ~> mikrobitStevnaMestaFlow   ~> merge.in(1)

          merge ~> output
          // @formatter:on

          FlowShape(broadcast.in, output.out(0))
        })
      }.some
  }
}
