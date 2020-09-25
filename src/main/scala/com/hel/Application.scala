package com.hel

import akka.actor.{ActorSystem, Cancellable}
import akka.stream.scaladsl.{Broadcast, Concat, Flow, GraphDSL, Merge, Sink, Source}
import akka.stream.{FlowShape, Outlet, SystemMaterializer}
import akka.{Done, NotUsed}
import cats.data.ReaderT
import cats.implicits._
import com.hel.clients.{PromInfoFlow, RadarFlow, SpinFlow}
import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging
import io.circe.Json

import scala.concurrent.Future
import scala.util.{Failure, Success}

object Application extends LazyLogging {
  type Environment = (ActorSystem, Configuration.Config)
  type CollectionFlow = Flow[Ticker.Tick, Json, NotUsed]
  type OutFlow = Flow[Ticker.Tick, String, NotUsed]

  val run: (Configuration.Config, Config) => Option[Future[Done]] = { (appConfig, systemConfig) =>
    val tickToCollection: (CollectionFlow, CollectionFlow, CollectionFlow) => OutFlow =
      (spin, radar, prominfo) =>
        Flow.fromGraph(GraphDSL.create() { implicit b =>
          import GraphDSL.Implicits._

          val toggled: (String, Outlet[Ticker.Tick]) => PortOps[Ticker.Tick] = (key, outlet) =>
            outlet.takeWhile(_ => key match {
              case "spin" => appConfig.spin.enabled
              case "radar" => appConfig.radar.enabled
              case "prominfo" => appConfig.prominfo.enabled
              case _ => false
            })

          val broadcast = b.add(Broadcast[Ticker.Tick](3))
          val merge = b.add(Merge[Json](3))
          val throttle = Flow[Json]
            // .throttle(10, 100.millis, 10, ThrottleMode.Shaping)
            .map { json =>
              // json.toString()
              //json.hcursor.downField("hel_entity_id").focus // .map(_.noSpacesSortKeys)
              //  .getOrElse(throw new Exception("hel_entity_id is missing!"))
              json.hcursor.downField("hel_entity_id").focus.map(_.noSpacesSortKeys).getOrElse("NO hel_entity_id")
              // json.noSpaces
              // json.hcursor.downField("hel_meta").focus.map(_.noSpacesSortKeys).getOrElse("hel_meta not found")
              // json.hcursor.downField("hel_meta").focus.map(_.toString).getOrElse("hel_meta not found")
              // json.hcursor.downField("entity").focus.map(_.toString).getOrElse("x")
            }
          // .take(1)

          val output = b.add(Broadcast[String](1))

          // @formatter:off
          toggled("spin",     broadcast.out(0))   ~> spin     ~> merge.in(0)
          toggled("radar",    broadcast.out(1))   ~> radar    ~> merge.in(1)
          toggled("prominfo", broadcast.out(2))   ~> prominfo ~> merge.in(2)

          merge.out ~> throttle ~> output
          // @formatter:on

          FlowShape(broadcast.in, output.out(0))
        })

    val collectionSource: ReaderT[Option, Environment, Source[String, Cancellable]] = for {
      ticker <- Ticker.fromConfig.local[Environment](_._2.ticker)
      spin <- SpinFlow.fromConfig.local[Environment](c => (c._1, c._2.spin))
      radar <- RadarFlow.fromConfig.local[Environment](c => (c._1, c._2.radar))
      // prominfo <- PromInfoFlow.fromConfig.local[Environment](c => (c._1, c._2.prominfo))
      prominfo <- PromInfoFlow.fromConfig.local[Environment](c => (c._1, c._2.prominfo))
    } yield ticker.via(tickToCollection(spin, radar, prominfo))

    ActorSystem("hel", systemConfig).some.map { system =>
      collectionSource(system, appConfig).map { graph =>
        import system.dispatcher

        graph.runWith(Sink.foreach(println))(SystemMaterializer(system).materializer) andThen {
          case Success(_) =>
          case Failure(exception) =>
            logger.error(exception.getMessage)
            system.terminate()
        }
      }
    }.getOrElse(throw new Exception("Never!"))
  }
}
