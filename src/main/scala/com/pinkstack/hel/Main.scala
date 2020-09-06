package com.pinkstack.hel

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.{Attributes, ThrottleMode}
import akka.stream.scaladsl._
import com.pinkstack.hel.clients.{RadarClient, Spin3Client}
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.LazyLogging
import io.circe.{Decoder, Encoder, Json}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}

case object Tick

sealed trait GenericClientFlow {
  def fetchMethod: Future[Option[Vector[Json]]]

  val flow: Flow[Tick.type, Json, NotUsed] =
    Flow[Tick.type]
      .mapAsync(4) { _ => fetchMethod }
      .collect {
        case Some(value: Vector[Json]) => value
        case None => throw new Exception("Problem with fetching events.")
      }
      .flatMapConcat(Source(_))
      .throttle(1, 100.millis, 10, ThrottleMode.Shaping)
}

final case class RadarFlow()(implicit val system: ActorSystem, config: Config) extends GenericClientFlow {
  val fetchMethod: Future[Option[Vector[Json]]] = RadarClient().activeEvents
}

final case class Spin3Flow()(implicit val system: ActorSystem, config: Config) extends GenericClientFlow {
  val fetchMethod: Future[Option[Vector[Json]]] = Spin3Client().locationEvents
}

object Main extends App with LazyLogging {
  implicit val config: Config = ConfigFactory.load
  implicit val system: ActorSystem = ActorSystem("promet")

  import system.dispatcher

  val loggingAttributes = Attributes.logLevels(
    onElement = Attributes.LogLevels.Debug,
    onFinish = Attributes.LogLevels.Info,
    onFailure = Attributes.LogLevels.Debug)

  val g = Source.tick(0.seconds, 5.seconds, Tick)
    .log(name = "tickStream")
    .addAttributes(loggingAttributes)
    .via {
      // RadarFlow().flow
      Spin3Flow().flow
    }
    // .map(_.noSpaces)
    .runWith(Sink.foreach(println))

  g.onComplete {
    case Success(value) =>
      println(value)
      system.terminate()
    case Failure(exception) =>
      System.err.println(exception)
      system.terminate()
  }

}
