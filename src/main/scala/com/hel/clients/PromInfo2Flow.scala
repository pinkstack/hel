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
import com.hel.{Configuration, Ticker}
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import io.circe.Json
import scala.concurrent.Future
import scala.concurrent.duration._

object Endpoints extends JsonOptics {
  type SectionName = String
  type LayerName = String
  type Enabled = Boolean
  type Transformation = Json => Option[Vector[Json]]
  type Layer = (LayerName, HttpRequest, Transformation)
  type Section = (SectionName, List[Layer], Enabled)

  private[this] val defaultQueryTransformation: Transformation = {
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

    _.hcursor.downField("features").focus.map(transformations).flatMap(_.asArray)
  }

  private[this] def defineSection(sectionName: SectionName)
                                 (layers: Layer*)
                                 (implicit config: Configuration.Prominfo): Section = {

    val sectionOpt: Option[Configuration.Section] = config.sections.get(sectionName)
    val layersConfiguration: List[Configuration.Layer] =
      sectionOpt.map(section => section.layers.filter(_.enabled)).getOrElse(List.empty[Configuration.Layer])

    val sectionTransformations: Option[Vector[Json]] => Option[Vector[Json]] = { jsons =>
      def t: Json => Json = { element =>
        val ids: Json =
          element.hcursor.downField("id").focus
            .map { json =>
              val entityID = List("prominfo", sectionName, json.toString).mkString("::")
                .replaceAll("""\"""", "")
              Json.obj(
                ("hel_entity_id", Json.fromString(entityID)),
                ("hel_entity_hash_id", Json.fromString(hashString(entityID))))
            }.getOrElse {
            throw new Exception("""Missing "id" field in the original JSON payload""")
          }

        element.deepMerge(Json.obj(("hel_meta", Json.obj(
          ("section_name", Json.fromString(sectionName)),
        ).deepMerge(ids)))).deepMerge(ids)
      }

      jsons.map(_.map(t))
    }

    (
      sectionName,
      layers.toList.filter(layer => layersConfiguration.map(_.name).contains(layer._1)).map { layer: Layer =>
        layer.copy(_3 = layer._3.andThen(sectionTransformations))
      },
      sectionOpt.exists(_.enabled)
    )
  }

  private[this] def queryLayer(layerName: LayerName,
                               queryAttributes: Map[String, String] = Map.empty)
                              (implicit config: Configuration.Prominfo,
                               transformation: Transformation = defaultQueryTransformation): Layer = {
    val attributes: Map[String, String] = Option.when(queryAttributes.isEmpty)(config.defaultQueryAttributes).getOrElse(queryAttributes)

    (
      layerName,
      HttpRequest(uri = Uri(s"${config.url}/web/api/MapService/Query/$layerName/query")
        .withQuery(Uri.Query(attributes))),
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


    defineSection("trenutno-stanje")(
      queryLayer("lay_prometnezaporemol")(config, transformation),
      queryLayer("lay_prometnidogodkizapore")(config, transformation),
      queryLayer("lay_popolneZaporeMolPoligoni")(config, transformation)
    )
  }

  private[this] val sections: Configuration.Prominfo => Map[String, Section] = { implicit config =>
    config.sections.map { case (sectionName: SectionName, sectionConfig: Configuration.Section) =>
      (sectionName, defineSection(sectionName)(
        sectionConfig.layers.map(layerConfig => queryLayer(layerConfig.name)(config)): _*
      ))
    }
  }

  def section(name: SectionName)(implicit config: Configuration.Prominfo): Section =
    sections(config).getOrElse(name, throw new Exception(s"Unknown section $name"))
}

object PromInfo2Flow {

  def processSection(section: Endpoints.Section): Flow[Ticker.Tick, Endpoints.Layer, NotUsed] =
    Flow[Ticker.Tick]
      .filter(_ => section._3)
      .flatMapConcat { _ => Source(section._2) }

  def query: ActorSystem => Flow[Endpoints.Layer, Json, NotUsed] = {
    implicit system: ActorSystem =>
      import system.dispatcher
      import FailFastCirceSupport._
      import io.circe._

      val fetch: Endpoints.Layer => Future[(Endpoints.LayerName, Option[Vector[Json]])] = {
        case (layer, request, transformation) =>
          Http().singleRequest(request)
            .flatMap(r => Unmarshal(r).to[Json])
            .map(transformation)
            .map(r => (layer, r))
      }

      Flow[Endpoints.Layer]
        .mapAsyncUnordered(1)(fetch)
        .collect {
          case (_, Some(v)) => v
          case (layer: String, None) =>
            throw new Exception(s"Failed retrieval of layer: ${layer}")
        }
        .mapConcat(identity)
  }

  val fromConfig: Kleisli[Option, (ActorSystem, Configuration.Prominfo), Flow[Ticker.Tick, Json, NotUsed]] = Kleisli {
    case (actorSystem, prominfoConfig) =>
      implicit val system: ActorSystem = actorSystem
      implicit val config: Configuration.Prominfo = prominfoConfig

      RestartFlow.onFailuresWithBackoff(config.minBackoff, config.maxBackoff, config.randomFactor, config.maxRestarts) {
        () =>
          Flow.fromGraph(GraphDSL.create() { implicit b =>
            import GraphDSL.Implicits._

            val broadcast = b.add(Broadcast[Ticker.Tick](10))

            val merge = b.add(Merge[Endpoints.Layer](10))

            val throttle = Flow[Endpoints.Layer]
              .throttle(10, 200.millis, 10, ThrottleMode.Shaping)

            val output = b.add(Broadcast[Json](1))

            /**
             * "dat_dov" : 1598572800000, / 1000 = 28. 08. 2020
             */

            // @formatter:off
            // broadcast.out(0) ~> processSection(Endpoints.trenutnoStanje(config))               ~> merge.in(0)
            broadcast.out(0) ~> processSection(Endpoints.section("trenutno-stanje"))    ~> merge.in(0)

            broadcast.out(1) ~> processSection(Endpoints.section("delne-zapore-cest"))  ~> merge.in(1)
            broadcast.out(2) ~> processSection(Endpoints.section("izredni-dogodki"))    ~> merge.in(2)
            broadcast.out(3) ~> processSection(Endpoints.section("bicikelj"))           ~> merge.in(3)
            broadcast.out(4) ~> processSection(Endpoints.section("garazne-hise"))       ~> merge.in(4)
            broadcast.out(5) ~> processSection(Endpoints.section("parkirisca"))         ~> merge.in(5)
            broadcast.out(6) ~> processSection(Endpoints.section("parkiraj-prestopi"))  ~> merge.in(6)
            broadcast.out(7) ~> processSection(Endpoints.section("elektro-polnilnice")) ~> merge.in(7)
            broadcast.out(8) ~> processSection(Endpoints.section("car-sharing"))        ~> merge.in(8)
            broadcast.out(9) ~> processSection(Endpoints.section("stevci-prometa"))     ~> merge.in(9)
            
            merge.out ~> throttle ~> query(system) ~> output.in
            // @formatter:on

            FlowShape(broadcast.in, output.out(0))
          })
      }.some
  }
}
