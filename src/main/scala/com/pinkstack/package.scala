package com

import akka.NotUsed
import akka.actor.{ActorSystem, Cancellable}
import akka.stream.ThrottleMode
import akka.stream.scaladsl.{Flow, Source}
import com.pinkstack.hel.clients.{RadarClient, Spin3Client}
import com.typesafe.config.Config
import io.circe.Json
import scala.jdk.DurationConverters._
import scala.concurrent.Future
import scala.concurrent.duration._

package object pinkstack {

  case object Tick

  sealed trait GenericClientFlow {
    def config: Config

    def fetchMethod: Future[Option[Vector[Json]]]

    val flow: Flow[Tick.type, Json, NotUsed] =
      Flow[Tick.type]
        .mapAsync(config.getInt("hel.clients.parallelism")) { _ => fetchMethod }
        .collect {
          case Some(value: Vector[Json]) => value
          case None => throw new Exception("Problem with fetching events.")
        }
        .flatMapConcat(Source(_))
        .throttle(1, 100.millis, 10, ThrottleMode.Shaping)
  }

  final case class RadarFlow()(implicit val system: ActorSystem, val config: Config) extends GenericClientFlow {
    val fetchMethod: Future[Option[Vector[Json]]] = RadarClient().activeEvents
  }

  final case class Spin3Flow()(implicit val system: ActorSystem, val config: Config) extends GenericClientFlow {
    val fetchMethod: Future[Option[Vector[Json]]] = Spin3Client().locationEvents
  }

  final case class Ticker()(implicit config: Config) {
    val tick: Source[Tick.type, Cancellable] = {
      val Seq(initialDelay, interval) = Seq("hel.collection.initial_delay", "hel.collection.interval")
        .map(config.getDuration(_).toScala)

      Source.tick(initialDelay, interval, Tick)
    }
  }

}
