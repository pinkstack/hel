package com.pinkstack.experimental_one

import cats._
import cats.implicits._
import cats.data.Kleisli
import com.typesafe.scalalogging.LazyLogging
import akka.{Done, NotUsed}
import akka.actor.{ActorSystem, Cancellable, ClassicActorSystemProvider}
import akka.stream.{ActorMaterializer, Attributes, Materializer, SystemMaterializer}
import akka.stream.scaladsl._
import com.pinkstack.Tick
import com.typesafe.config.Config
import io.circe.Json
import pureconfig.ConfigReader.Result
import pureconfig.error.ConfigReaderFailures

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import pureconfig._
import pureconfig.generic.auto._

case class ClientsConfig(parallelism: Int)

case class CollectionConfig(initialDelay: FiniteDuration, interval: FiniteDuration)

case class RadarConfig(token: String)

case class HelConfig(collection: CollectionConfig, clients: ClientsConfig, radar: RadarConfig)

object HelConfig {
  val load: Result[HelConfig] =
    ConfigSource.default.at("hel").load[HelConfig]
}

object Ticker {
  val fromConfig: Kleisli[Option, CollectionConfig, Source[Tick, Cancellable]] =
    Kleisli { c => Source.tick(c.initialDelay, c.interval, Tick).some }
}

object RadarFlow {
  val fromConfig: Kleisli[Option, RadarConfig, Flow[Tick, String, NotUsed]] =
    Kleisli { c =>
      Flow[Tick]
        .map(_ => s"Tock with ${c}")
        //.map { _ =>
        //  throw new Exception("boom")
        //  "yolo"
        //}
        .some
    }
}

object Main extends App with LazyLogging {
  def appFromConfig(system: ActorSystem): Kleisli[Option, HelConfig, Future[Done]] = {
    for {
      ticker <- Ticker.fromConfig.local[HelConfig](_.collection)
      flow <- RadarFlow.fromConfig.local[HelConfig](_.radar)
    } yield {
      ticker
        .log("ticker")
        .addAttributes(Attributes.logLevels(
          onElement = Attributes.LogLevels.Info,
          onFinish = Attributes.LogLevels.Info,
          onFailure = Attributes.LogLevels.Info))
        .via(flow)
        .runWith(Sink.foreach(println))(SystemMaterializer(system).materializer)
    }
  }

  val f: Either[ConfigReaderFailures, Option[Future[Done]]] = for {
    helConfig <- HelConfig.load
    applicationConfig <- ConfigSource.default.config()
    app = appFromConfig(ActorSystem("tick", applicationConfig))(helConfig)
  } yield app

  f match {
    case Left(value) =>
      println(value)
    case Right(value) =>
      println(value)
  }

}
