package com.hel

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Merge, RunnableGraph, Sink}
import akka.stream.{ClosedShape, SystemMaterializer, ThrottleMode}
import cats.data._
import cats.implicits._
import com.hel.clients.{RadarFlow, SpinFlow}
import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging
import io.circe.Json
import pureconfig.ConfigSource

import scala.concurrent.duration._

object Application {
  type Environment = (ActorSystem, Configuration.Config)

  val run: (Configuration.Config, Config) => Option[NotUsed] = { (appConfig, systemConfig) =>
    val build: ReaderT[Option, Environment, RunnableGraph[NotUsed]] = {
      for {
        ticker <- Ticker.fromConfig.local[Environment](_._2.collection)
        spin <- SpinFlow.fromConfig.local[Environment](c => (c._1, c._2.spin))
        radar <- RadarFlow.fromConfig.local[Environment](c => (c._1, c._2.radar))
      } yield {
        RunnableGraph.fromGraph(GraphDSL.create() { implicit b =>
          import GraphDSL.Implicits._

          val out = Sink.foreach(println)

          val broadcast = b.add(Broadcast[Ticker.Tick](1))
          val merge = b.add(Merge[Json](1))

          val throttle = Flow[Json]
            .throttle(10, 100.millis, 10, ThrottleMode.Shaping)
            .map { json => json.noSpacesSortKeys }

          // @formatter:off
          ticker ~> broadcast ~> spin ~> merge ~> throttle ~> out
                    broadcast ~> radar ~> merge
          // @formatter:on

          ClosedShape
        })
      }
    }

    Some(ActorSystem("hel", systemConfig)).map { system =>
      build.run(system, appConfig).map { graph =>
        graph.run()(SystemMaterializer(system).materializer)
      }
    }.getOrElse(throw new Exception("Never"))
  }
}

object Main extends App with LazyLogging {
  (for {
    appConfig <- Configuration.load
    systemConfig <- ConfigSource.default.config()
  } yield Application.run(appConfig, systemConfig)) match {
    case Left(value) =>
      System.err.println("💥 " * 10)
      System.err.println(value.prettyPrint())
    case Right(_) =>
      logger.info("Booted,...")
  }
}
