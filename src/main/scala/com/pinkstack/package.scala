package com

import cats._
import cats.implicits._
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

  sealed trait Tick

  case object Tick extends Tick

  sealed trait GenericClientFlow {
    def config: Config

    def fetchMethod: Future[Option[Vector[Json]]]

    def flow: Flow[Tick.type, Json, NotUsed] =
      Flow[Tick.type]
        .mapAsyncUnordered(1)(_ => fetchMethod)
        .collect {
          case Some(value: Vector[Json]) => value
          case None => throw new Exception("Problem with fetching events.")
        }
        .flatMapConcat(v =>
          Source(v)
        )
  }

  final case class RadarFlow()(implicit val system: ActorSystem, val config: Config) extends GenericClientFlow {
    def fetchMethod: Future[Option[Vector[Json]]] = RadarClient().activeEvents
  }

  final case class Spin3Flow()(implicit val system: ActorSystem, val config: Config) extends GenericClientFlow {
    def fetchMethod: Future[Option[Vector[Json]]] = Spin3Client().locationEvents
  }

  final case class Ticker()(implicit config: Config) {
    val tick: Source[Tick.type, Cancellable] = {
      val Seq(initialDelay, interval) = Seq(
        "hel.collection.initial-delay", "hel.collection.interval"
      ).map(config.getDuration(_).toScala)

      Source.tick(initialDelay, interval, Tick)
    }
  }

}
