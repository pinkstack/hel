package com.hel.clients

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, Uri}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.scaladsl._
import akka.stream.{FlowShape, ThrottleMode}
import cats.data.Kleisli
import cats.implicits._
import com.hel.{Configuration, Ticker}
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import io.circe.Json
import io.circe.optics.JsonPath.root

import scala.concurrent.Future
import scala.concurrent.duration._

object PromInfoFlow extends JsonOptics {

  def process(section: PromInfoEndpoints.Section): Flow[Ticker.Tick, PromInfoEndpoints.Layer, NotUsed] =
    Flow[Ticker.Tick]
      .filter(_ => section._3)
      .flatMapConcat { _ => Source(section._2) }

  def query: ActorSystem => Flow[PromInfoEndpoints.Layer, (String, String, Json), NotUsed] = {
    implicit system: ActorSystem =>
      import FailFastCirceSupport._
      import io.circe._
      import system.dispatcher

      val fetch: PromInfoEndpoints.Layer => Future[(PromInfoEndpoints.SectionName, PromInfoEndpoints.LayerName, Option[Vector[Json]])] = {
        case (sectionName, layerName, request, transformation) =>
          Http().singleRequest(request)
            .flatMap(r => Unmarshal(r).to[Json])
            .map(transformation)
            .map(r => (sectionName, layerName, r))
      }

      Flow[PromInfoEndpoints.Layer]
        .mapAsyncUnordered(2)(fetch)
        .collect {
          case (sectionName, layerName, Some(jsons)) =>
            jsons.map { json => (sectionName, layerName, json) }
          case (sectionName, layerName, None) =>
            throw new Exception(s"Failed retrieval in $sectionName / $layerName")
        }
        .mapConcat(identity)
  }


  def fetchCounters(implicit system: ActorSystem, config: Configuration.Prominfo): Flow[(PromInfoEndpoints.LayerName, PromInfoEndpoints.SectionName, Json), Json, NotUsed] = {
    import FailFastCirceSupport._
    import io.circe._
    import system.dispatcher

    val jsonToRequest: ((PromInfoEndpoints.SectionName, PromInfoEndpoints.LayerName, Json)) => HttpRequest = {
      case (_, layerName: PromInfoEndpoints.LayerName, json: Json) =>
        (for {
          attributeID <- json.hcursor.downField("id").as[String].toOption
          subLayerName <- config.sectionCountersMapping.get(layerName)
        } yield {
          HttpRequest(uri = Uri(s"${config.url}/web/api/MapService/find")
            .withQuery(Uri.Query(config.defaultFindAttributes ++ Map[String, String](
              "searchFields" -> "road_location",
              "layers" -> subLayerName,
              "searchText" -> attributeID
            ))))
        }).getOrElse(throw new Exception("Could not fetch attribute ID or configuration for mapping"))
    }

    val transform: (PromInfoEndpoints.SectionName, PromInfoEndpoints.LayerName, Json) => Vector[Json] = { (sectionName, layerName, json) =>
      val resultTransform: Json => Json = PromInfoEndpoints.defaultTransformation andThen {
        root.each.obj.modify { jsonObject =>
          val helMeta: Map[String, String] = (for {
            id <- jsonObject.toMap.get("id").flatMap(_.as[String].toOption)
            roadID <- jsonObject.toMap.get("road_id").flatMap(_.as[String].toOption)
            subLayerName <- config.sectionCountersMapping.get(layerName)
            keyFragments = List("prominfo", sectionName, layerName, subLayerName, roadID, id)
            helEntityID = keyFragments.mkString("::")
            helEntityHashID = hashString(keyFragments.mkString("::"))
          } yield Map(
            ("hel_entity_id", helEntityID),
            ("hel_entity_hash_id", helEntityHashID),
            ("section_name", sectionName),
            ("layer_name", layerName),
            ("road_id", roadID)
          )).getOrElse(Map.empty[String, String])

          jsonObject.deepMerge(Json.obj((helMeta.map { case (k, v) => (k, Json.fromString(v)) }.toSeq): _*).asObject.get)
        }
      }

      json.hcursor.downField("results").focus.map(resultTransform).flatMap(_.asArray)
        .getOrElse(Vector.empty[Json])
    }

    Flow[(PromInfoEndpoints.SectionName, PromInfoEndpoints.LayerName, Json)]
      .mapAsyncUnordered(1) { layerData =>
        Http().singleRequest(jsonToRequest(layerData))
          .flatMap(Unmarshal(_).to[Json])
          .map(json => transform(layerData._1, layerData._2, json))
      }.mapConcat(identity)
  }

  val fromConfig: Kleisli[Option, (ActorSystem, Configuration.Prominfo), Flow[Ticker.Tick, Json, NotUsed]] = Kleisli {
    case (actorSystem, prominfoConfig) =>
      implicit val system: ActorSystem = actorSystem
      implicit val config: Configuration.Prominfo = prominfoConfig

      RestartFlow.onFailuresWithBackoff(config.minBackoff, config.maxBackoff, config.randomFactor, config.maxRestarts) {
        () =>
          Flow.fromGraph(GraphDSL.create() { implicit b =>
            import GraphDSL.Implicits._

            val input = b.add(Broadcast[Ticker.Tick](10))
            val merge = b.add(Merge[PromInfoEndpoints.Layer](9))

            val throttle = Flow[PromInfoEndpoints.Layer]
              .throttle(100, 1.second, 120, ThrottleMode.Shaping)

            val output = b.add(Merge[Json](3))
            val broadcastStevci = b.add(Broadcast[(String, String, Json)](2))
            val throttleST = Flow[(PromInfoEndpoints.LayerName, PromInfoEndpoints.SectionName, Json)]
              .throttle(20, 1.second, 40, ThrottleMode.Shaping)

            val JsonFromLayers = Flow[(String, String, Json)].map(r => r._3)

            // @formatter:off
            input.out(0) ~> process(PromInfoEndpoints.section("trenutno-stanje"))    ~> merge.in(0)
            input.out(1) ~> process(PromInfoEndpoints.section("delne-zapore-cest"))  ~> merge.in(1)
            input.out(2) ~> process(PromInfoEndpoints.section("izredni-dogodki"))    ~> merge.in(2)
            input.out(3) ~> process(PromInfoEndpoints.section("bicikelj"))           ~> merge.in(3)
            input.out(4) ~> process(PromInfoEndpoints.section("garazne-hise"))       ~> merge.in(4)
            input.out(5) ~> process(PromInfoEndpoints.section("parkirisca"))         ~> merge.in(5)
            input.out(6) ~> process(PromInfoEndpoints.section("parkiraj-prestopi"))  ~> merge.in(6)
            input.out(7) ~> process(PromInfoEndpoints.section("elektro-polnilnice")) ~> merge.in(7)
            input.out(8) ~> process(PromInfoEndpoints.section("car-sharing"))        ~> merge.in(8)

            input.out(9) ~> process(PromInfoEndpoints.section("stevci-prometa"))     ~> query(system) ~> broadcastStevci
            broadcastStevci.out(0) ~> throttleST ~> fetchCounters ~>  output.in(0)
            broadcastStevci.out(1) ~> JsonFromLayers ~> output.in(1)

            merge.out ~> throttle ~> query(system).map(r => r._3) ~> output.in(2)
            // @formatter:on

            FlowShape(input.in, output.out)
          })
      }.some
  }
}
