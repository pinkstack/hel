package com.pinkstack.hel

import cats._
import cats.implicits._
import akka.NotUsed
import akka.actor.{ActorSystem, Cancellable}
import akka.stream.{Attributes, ClosedShape, ThrottleMode}
import akka.stream.scaladsl._
import com.pinkstack.hel.clients.{RadarClient, Spin3Client}
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.LazyLogging
import io.circe.{Decoder, Encoder, Json}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}
import scala.jdk.DurationConverters._

sealed trait Tick
case object Tick extends Tick

sealed trait GenericClientFlow {
  def config: Config

  def fetchMethod: Future[Option[Vector[Json]]]

  val flow: Flow[Tick, Json, NotUsed] =
    Flow[Tick]
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

object MainOld extends App with LazyLogging {
  implicit val config: Config = ConfigFactory.load
  implicit val system: ActorSystem = ActorSystem("hel")

  RunnableGraph.fromGraph(GraphDSL.create() { implicit b =>
    import GraphDSL.Implicits._

    val in = Ticker().tick
    val out = Sink.foreach(println)

    val broadcast = b.add(Broadcast[Tick](2))
    val merge = b.add(Merge[Json](2))

    val throttle = Flow[Json].throttle(1, 200.millis, 10, ThrottleMode.Shaping)


    // @formatter:off
    in ~> broadcast ~> RadarFlow().flow ~> merge ~> throttle ~> out
          broadcast ~> Spin3Flow().flow ~> merge
    // @formatter:on

    ClosedShape
  }).run()
}
