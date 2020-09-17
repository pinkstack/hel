package com.pinkstack.experimental_two

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.http.scaladsl._
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.OverflowStrategy
import akka.stream.scaladsl._
import com.typesafe.scalalogging.LazyLogging
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import io.circe.Json

import scala.concurrent.duration._
import scala.util.{Failure, Success}

sealed trait Tick

object Tick extends Tick

object Main extends App with LazyLogging {

  implicit val system: ActorSystem = ActorSystem("main-ex2")

  import system.dispatcher
  import FailFastCirceSupport._

  val fetchStuff = Flow[Tick]
    .mapAsyncUnordered(1) { _ =>
      val httpRequest = HttpRequest(uri = "http://localhost:7070/javno/assets/data/lokacija.json")
      Http()
        .singleRequest(httpRequest)
        .flatMap(Unmarshal(_).to[Json])
        .map(_.hcursor.downField("value").downArray.focus)
    }

  val fetchStuff2 = Flow

  val f2 = RestartFlow.onFailuresWithBackoff(1.seconds, 5.seconds, 0.2, 10) { () =>
    fetchStuff
  }

  val r = Source.tick(0.seconds, 5.seconds, Tick)
    .via(f2)
    .runWith(Sink.foreach(println))

  /*
  val r = Source.tick(0.seconds, 5.seconds, Tick)
    .via(fetchStuff)
    .runWith(Sink.foreach(println))
  */


  r.onComplete {
    case Failure(r) =>
      System.err.println("--- " * 10)
      System.err.println(r)
      System.err.println("--- " * 10)
      system.terminate()
    case _ =>
      println("Done.")
      system.terminate()
  }
}
