package com.hel

import akka.actor.Cancellable
import akka.stream.scaladsl.Source
import cats.data.Kleisli

object Ticker {

  sealed trait Tick

  final object Tick extends Tick

  val fromConfig: Kleisli[Option, Configuration.Ticker, Source[Tick, Cancellable]] =
    Kleisli(c => Some(Source.tick(c.initialDelay, c.interval, Tick)))
}
