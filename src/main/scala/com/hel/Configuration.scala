package com.hel

import java.net.URL

import pureconfig.ConfigReader.Result
import pureconfig.ConfigSource

import scala.concurrent.duration.FiniteDuration

object Configuration {

  import pureconfig.generic.auto._

  final case class Config(ticker: Ticker,
                          radar: Radar,
                          spin: Spin)

  final case class Ticker(initialDelay: FiniteDuration,
                          interval: FiniteDuration)

  final case class Radar(url: URL,
                         token: String,
                         parallelism: Int,
                         minBackoff: FiniteDuration,
                         maxBackoff: FiniteDuration,
                         randomFactor: Double,
                         maxRestarts: Int)

  final case class Spin(url: URL,
                        parallelism: Int,
                        minBackoff: FiniteDuration,
                        maxBackoff: FiniteDuration,
                        randomFactor: Double,
                        maxRestarts: Int)

  final def load: Result[Config] = ConfigSource.default.at("hel").load[Config]
}
