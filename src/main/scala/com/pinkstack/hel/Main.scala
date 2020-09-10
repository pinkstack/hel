package com.pinkstack.hel

import cats._
import cats.implicits._
import akka.NotUsed
import akka.actor.{ActorSystem, Cancellable}
import akka.stream.{Attributes, ClosedShape, ThrottleMode}
import akka.stream.scaladsl._
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.LazyLogging
import io.circe.{Decoder, Encoder, Json}

import com.pinkstack._

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}
import scala.jdk.DurationConverters._

object Main extends App with LazyLogging {
  implicit val config: Config = ConfigFactory.load
  implicit val system: ActorSystem = ActorSystem("hel")

  RunnableGraph.fromGraph(GraphDSL.create() { implicit b =>
    import GraphDSL.Implicits._

    val in = Ticker().tick.log("ticker")
      .addAttributes(Attributes.logLevels(
        onElement = Attributes.LogLevels.Info,
        onFinish = Attributes.LogLevels.Info,
        onFailure = Attributes.LogLevels.Info))

    val out = Sink.foreach(println)

    val broadcast = b.add(Broadcast[Tick.type](1))
    val merge = b.add(Merge[Json](1))

    val throttle = Flow[Json]
      // .throttle(1, 200.millis, 10, ThrottleMode.Shaping)
      .map {
        json => json.hcursor.downField("event_id").focus
      }.collectType[Some[String]]

    // @formatter:off
    in ~> broadcast ~> Spin3Flow().flow ~> merge ~> throttle ~> out
    //      broadcast ~> RadarFlow().flow ~> merge
    // @formatter:on

    ClosedShape
  }).run()
}
