package com.pinkstack.experimental

import akka.actor.ActorSystem
import akka.{Done, NotUsed}
import akka.stream.scaladsl._
import com.typesafe.scalalogging.LazyLogging
import scala.concurrent.duration._

case object Tick

case object Tock

object GraphsApp extends App with LazyLogging {
  logger.debug("Booting up,...")
  implicit val system: ActorSystem = ActorSystem("experimental")

  import system.dispatcher

  val ticks = Source.tick(0.seconds, 5.seconds, Tick)
  val tocks = Source.tick(3.seconds, 1.seconds, Tock)

  val r = ticks.merge(tocks)
    .runWith(Sink.foreach(println))

  r.onComplete(_ => system.terminate())
}
