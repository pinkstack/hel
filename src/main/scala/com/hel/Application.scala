package com.hel

import cats._
import cats.implicits._
import akka.actor.{ActorSystem, Cancellable}
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Merge, Sink, Source}
import akka.stream.{FlowShape, SystemMaterializer, ThrottleMode}
import akka.{Done, NotUsed}
import cats.data.ReaderT
import com.hel.clients.{RadarFlow, SpinFlow}
import com.typesafe.config.Config
import io.circe.Json

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object Application {
  type Environment = (ActorSystem, Configuration.Config)
  type CollectionFlow = Flow[Ticker.Tick, Json, NotUsed]
  type OutFlow = Flow[Ticker.Tick, String, NotUsed]

  val run: (Configuration.Config, Config) => Option[Future[Done]] = { (appConfig, systemConfig) =>
    val tickToCollection: (CollectionFlow, CollectionFlow) => OutFlow =
      (spin, radar) =>
        Flow.fromGraph(GraphDSL.create() { implicit b =>
          import GraphDSL.Implicits._

          val broadcast = b.add(Broadcast[Ticker.Tick](2))
          val merge = b.add(Merge[Json](2))
          val throttle = Flow[Json]
            .throttle(10, 100.millis, 10, ThrottleMode.Shaping)
            .map(_.noSpacesSortKeys)
          val out = b.add(Broadcast[String](1))

          // @formatter:off
          broadcast.out(0) ~> spin  ~> merge.in(0)
          broadcast.out(1) ~> radar ~> merge.in(1)
                                       merge.out ~> throttle ~> out
          // @formatter:on

          FlowShape(broadcast.in, out.out(0))
        })

    val collectionSource: ReaderT[Option, Environment, Source[String, Cancellable]] = for {
      ticker <- Ticker.fromConfig.local[Environment](_._2.ticker)
      spin <- SpinFlow.fromConfig.local[Environment](c => (c._1, c._2.spin))
      radar <- RadarFlow.fromConfig.local[Environment](c => (c._1, c._2.radar))
    } yield ticker.via(tickToCollection(spin, radar))

    ActorSystem("hel", systemConfig).some.map { system =>
      collectionSource(system, appConfig).map { graph =>
        import system.dispatcher

        graph.runWith(Sink.foreach(println))(SystemMaterializer(system).materializer) andThen {
          case Success(_) =>
          case Failure(exception) =>
            System.err.println(exception)
            system.terminate()
        }
      }
    }.getOrElse(throw new Exception("Never!"))
  }
}
