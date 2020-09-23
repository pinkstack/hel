package com.hel.clients

import cats._
import cats.implicits._
import cats.data._
import cats.free.Free
import cats.free.Free._
import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, Uri}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.{FlowShape, ThrottleMode}
import akka.stream.scaladsl._
import akka.actor.ActorSystem
import cats.data.Kleisli
import com.hel.clients.Endpoints.Transformation
import com.hel.clients.PromInfoFlow.{nestInto, removeFields, renameField, transformKeysWith, unnestObject}
import com.hel.{Configuration, Ticker}
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import io.circe.Json
import shapeless._

import scala.concurrent.Future
import scala.concurrent.duration._

object Endpoints {
  type SectionName = String
  type LayerName = String
  type Enabled = Boolean
  type Transformation = Json => Option[Vector[Json]]
  type Layer = (LayerName, HttpRequest, Transformation)
  type Section = (SectionName, List[Layer], Enabled)

  // case class Call(request: HttpRequest, transformation: Option[Json => Json] = None, calls: Call)

  final case class Call(request: HttpRequest)

  val defaultQueryTransformation: Transformation = { json =>
    val transformation = Seq(
      unnestObject("geometry"),
      unnestObject("attributes"),
      renameField("geometry_y", "lon"),
      renameField("geometry_x", "lat"),
      nestInto("location", "lon", "lat"),
      transformKeysWith(_.toLowerCase),
    ).reduceLeft(_ andThen _)

    json.hcursor.downField("results").focus.map(transformation)
      .flatMap(_.asArray)
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


  private[this] def section(name: SectionName)
                           (layers: (LayerName, HttpRequest, Transformation)*)
                           (implicit config: Configuration.Prominfo): Section = {

    val sectionOpt: Option[Configuration.Section] = config.sections.get(name)
    val layersConfiguration: List[Configuration.Layer] =
      sectionOpt.map(section => section.layers.filter(_.enabled)).getOrElse(List.empty[Configuration.Layer])

    (
      name,
      layers.toList.filter(layer => layersConfiguration.map(_.name).contains(layer._1)),
      sectionOpt.exists(_.enabled)
    )
  }

  private[this] def queryLayer(name: LayerName,
                               queryAttributes: Map[String, String] = defaultQueryAttributes)
                              (implicit config: Configuration.Prominfo,
                               transformation: Transformation = defaultQueryTransformation): (LayerName, HttpRequest, Transformation) = {
    (
      name,
      HttpRequest(uri = Uri(s"${config.url}/web/api/MapService/Query/$name/query").withQuery(Uri.Query(queryAttributes))),
      transformation
    )
  }

  val trenutnoStanje: Configuration.Prominfo => Section = { implicit config =>
    val transformation: Transformation = { json =>
      val transformations: Json => Json = Seq(
        unnestObject("geometry"),
        unnestObject("attributes"),
        renameField("geometry_y", "lon"),
        renameField("geometry_x", "lat"),
        nestInto("location", "lon", "lat"),
        transformKeysWith(_.toLowerCase),
        transformKeysWith(_.replaceFirst("attributes_", "")),
        removeFields("geometry", "lon", "lat", "attributes"),
      ).reduceLeft((a, b) => a andThen b)

      json.hcursor.downField("features").focus.map(transformations).flatMap(_.asArray)
    }

    section("trenutno-stanje")(
      queryLayer("lay_prometnezaporemol")(config, transformation),
      queryLayer("lay_prometnidogodkizapore")(config, transformation),
      queryLayer("lay_popolneZaporeMolPoligoni")(config, transformation))
  }

  val delneZaporeCest: Configuration.Prominfo => Section = { implicit config =>
    val transformation: Transformation = { json =>
      val transformations: Json => Json = Seq(
        unnestObject("geometry"),
        unnestObject("attributes"),
        renameField("geometry_y", "lon"),
        renameField("geometry_x", "lat"),
        nestInto("location", "lon", "lat"),
        transformKeysWith(_.toLowerCase),
        transformKeysWith(_.replaceFirst("attributes_", "")),
        removeFields("geometry", "lon", "lat", "attributes"),
      ).reduceLeft((a, b) => a andThen b)

      json.hcursor.downField("features").focus.map(transformations).flatMap(_.asArray)
    }

    section("delne-zapore-cest")(
      queryLayer("lay_ostaleZaporeMol")(config, transformation),
      queryLayer("lay_prometnidogodkidogodki")(config, transformation),
      queryLayer("lay_delneZaporeMolPoligoni")(config, transformation)
    )
  }

  val izredniDogodki: Configuration.Prominfo => Section = { implicit config =>
    val transformation: Transformation = { json =>
      val transformations: Json => Json = Seq(
        unnestObject("geometry"),
        unnestObject("attributes"),
        renameField("geometry_y", "lon"),
        renameField("geometry_x", "lat"),
        nestInto("location", "lon", "lat"),
        transformKeysWith(_.toLowerCase),
        transformKeysWith(_.replaceFirst("attributes_", "")),
        removeFields("geometry", "lon", "lat", "attributes"),
      ).reduceLeft((a, b) => a andThen b)

      json.hcursor.downField("features").focus.map(transformations).flatMap(_.asArray)
    }

    section("izredni-dogodki")(
      queryLayer("lay_prometnidogodkiizredni")(config, transformation)
    )
  }

  val bicikelj: Configuration.Prominfo => Section = { implicit config =>
    val transformation: Transformation = { json =>
      val transformations: Json => Json = Seq(
        unnestObject("geometry"),
        unnestObject("attributes"),
        renameField("geometry_y", "lon"),
        renameField("geometry_x", "lat"),
        nestInto("location", "lon", "lat"),
        transformKeysWith(_.toLowerCase),
        transformKeysWith(_.replaceFirst("attributes_", "")),
        removeFields("geometry", "lon", "lat", "attributes"),
      ).reduceLeft((a, b) => a andThen b)

      json.hcursor.downField("features").focus.map(transformations).flatMap(_.asArray)
    }

    section("bicikelj")(
      queryLayer("lay_bicikelj")(config, transformation)
    )
  }

  val garazneHise: Configuration.Prominfo => Section = { implicit config =>
    val transformation: Transformation = { json =>
      val transformations: Json => Json = Seq(
        unnestObject("geometry"),
        unnestObject("attributes"),
        renameField("geometry_y", "lon"),
        renameField("geometry_x", "lat"),
        nestInto("location", "lon", "lat"),
        transformKeysWith(_.toLowerCase),
        transformKeysWith(_.replaceFirst("attributes_", "")),
        removeFields("geometry", "lon", "lat", "attributes"),
      ).reduceLeft((a, b) => a andThen b)

      json.hcursor.downField("features").focus.map(transformations).flatMap(_.asArray)
    }

    section("garazne-hise")(
      queryLayer("lay_vParkiriscagaraznehise")(config, transformation)
    )
  }

  val parkirisca: Configuration.Prominfo => Section = { implicit config =>
    val transformation: Transformation = { json =>
      val transformations: Json => Json = Seq(
        unnestObject("geometry"),
        unnestObject("attributes"),
        renameField("geometry_y", "lon"),
        renameField("geometry_x", "lat"),
        nestInto("location", "lon", "lat"),
        transformKeysWith(_.toLowerCase),
        transformKeysWith(_.replaceFirst("attributes_", "")),
        removeFields("geometry", "lon", "lat", "attributes"),
      ).reduceLeft((a, b) => a andThen b)

      json.hcursor.downField("features").focus.map(transformations).flatMap(_.asArray)
    }

    section("parkirisca")(
      queryLayer("lay_vParkirisca")(config, transformation)
    )
  }

  val parkirajPrestopi: Configuration.Prominfo => Section = { implicit config =>
    val transformation: Transformation = { json =>
      val transformations: Json => Json = Seq(
        unnestObject("geometry"),
        unnestObject("attributes"),
        renameField("geometry_y", "lon"),
        renameField("geometry_x", "lat"),
        nestInto("location", "lon", "lat"),
        transformKeysWith(_.toLowerCase),
        transformKeysWith(_.replaceFirst("attributes_", "")),
        removeFields("geometry", "lon", "lat", "attributes"),
      ).reduceLeft((a, b) => a andThen b)

      json.hcursor.downField("features").focus.map(transformations).flatMap(_.asArray)
    }

    section("parkiraj-prestopi")(
      queryLayer("lay_vParkiriscapr")(config, transformation)
    )
  }

  val elektroPolnilnice: Configuration.Prominfo => Section = { implicit config =>
    val transformation: Transformation = { json =>
      val transformations: Json => Json = Seq(
        unnestObject("geometry"),
        unnestObject("attributes"),
        renameField("geometry_y", "lon"),
        renameField("geometry_x", "lat"),
        nestInto("location", "lon", "lat"),
        transformKeysWith(_.toLowerCase),
        transformKeysWith(_.replaceFirst("attributes_", "")),
        removeFields("geometry", "lon", "lat", "attributes"),
      ).reduceLeft((a, b) => a andThen b)

      json.hcursor.downField("features").focus.map(transformations).flatMap(_.asArray)
    }

    section("elektro-polnilnice")(
      queryLayer("lay_vPolnilnePostaje2")(config, transformation),
      queryLayer("lay_polnilnepostaje_staticne")(config, transformation)
    )
  }

  val carSharing: Configuration.Prominfo => Section = { implicit config =>
    val transformation: Transformation = { json =>
      val transformations: Json => Json = Seq(
        unnestObject("geometry"),
        unnestObject("attributes"),
        renameField("geometry_y", "lon"),
        renameField("geometry_x", "lat"),
        nestInto("location", "lon", "lat"),
        transformKeysWith(_.toLowerCase),
        transformKeysWith(_.replaceFirst("attributes_", "")),
        removeFields("geometry", "lon", "lat", "attributes"),
      ).reduceLeft((a, b) => a andThen b)

      json.hcursor.downField("features").focus.map(transformations).flatMap(_.asArray)
    }

    section("car-sharing")(
      queryLayer("lay_vCarsharing")(config, transformation),
    )
  }

  val stevciPrometa: Configuration.Prominfo => Section = { implicit config =>
    val transformation: Transformation = { json =>
      val transformations: Json => Json = Seq(
        unnestObject("geometry"),
        unnestObject("attributes"),
        renameField("geometry_y", "lon"),
        renameField("geometry_x", "lat"),
        nestInto("location", "lon", "lat"),
        transformKeysWith(_.toLowerCase),
        transformKeysWith(_.replaceFirst("attributes_", "")),
        removeFields("geometry", "lon", "lat", "attributes"),
      ).reduceLeft((a, b) => a andThen b)

      json.hcursor.downField("features").focus.map(transformations).flatMap(_.asArray)
    }

    section("stevci-prometa")(
      queryLayer("lay_drsc_stevna_mesta")(config, transformation),
      queryLayer("lay_MikrobitStevnaMesta")(config, transformation),
    )
  }
}

object PromInfo2Flow {

  def processSection(section: Endpoints.Section): Flow[Ticker.Tick, Endpoints.Layer, NotUsed] =
    Flow[Ticker.Tick]
      .filter(_ => section._3)
      .flatMapConcat { _ => Source(section._2) }

  def query: ActorSystem => Flow[(String, HttpRequest, Transformation), Json, NotUsed] = {
    implicit system: ActorSystem =>
      import system.dispatcher
      import FailFastCirceSupport._
      import io.circe._

      val fetch: ((String, HttpRequest, Transformation)) => Future[Option[Vector[Json]]] = {
        case (_, request, transformation) =>
          Http().singleRequest(request)
            .flatMap(r => Unmarshal(r).to[Json])
            .map(transformation)
      }

      Flow[(String, HttpRequest, Transformation)]
        .mapAsyncUnordered(1)(fetch)
        .collect {
          case Some(v) => v
          case None => throw new Exception(s"ooops")
        }
        .mapConcat(identity)
  }

  val fromConfig: Kleisli[Option, (ActorSystem, Configuration.Prominfo), Flow[Ticker.Tick, Json, NotUsed]] = Kleisli {
    case (actorSystem, prominfoConfig) =>
      implicit val system: ActorSystem = actorSystem
      implicit val config: Configuration.Prominfo = prominfoConfig

      println(config)

      RestartFlow.onFailuresWithBackoff(config.minBackoff, config.maxBackoff, config.randomFactor, config.maxRestarts) {
        () =>
          Flow.fromGraph(GraphDSL.create() { implicit b =>
            import GraphDSL.Implicits._

            val broadcast = b.add(Broadcast[Ticker.Tick](10))

            val merge = b.add(Merge[(String, HttpRequest, Transformation)](10))

            val throttle = Flow[(String, HttpRequest, Transformation)]
              .throttle(10, 200.millis, 10, ThrottleMode.Shaping)

            val output = b.add(Broadcast[Json](1))

            // @formatter:off
            broadcast.out(0) ~> processSection(Endpoints.trenutnoStanje(config))    ~> merge.in(0)
            broadcast.out(1) ~> processSection(Endpoints.delneZaporeCest(config))   ~> merge.in(1)
            broadcast.out(2) ~> processSection(Endpoints.izredniDogodki(config))    ~> merge.in(2)
            broadcast.out(3) ~> processSection(Endpoints.bicikelj(config))          ~> merge.in(3)
            broadcast.out(4) ~> processSection(Endpoints.garazneHise(config))       ~> merge.in(4)
            broadcast.out(5) ~> processSection(Endpoints.parkirisca(config))        ~> merge.in(5)
            broadcast.out(6) ~> processSection(Endpoints.parkirajPrestopi(config))  ~> merge.in(6)
            broadcast.out(7) ~> processSection(Endpoints.elektroPolnilnice(config)) ~> merge.in(7)
            broadcast.out(8) ~> processSection(Endpoints.carSharing(config))        ~> merge.in(8)
            broadcast.out(9) ~> processSection(Endpoints.stevciPrometa(config))     ~> merge.in(9)

            merge.out ~> throttle ~> query(system) ~> output.in
            // @formatter:on

            FlowShape(broadcast.in, output.out(0))
          })
      }.some
  }
}
