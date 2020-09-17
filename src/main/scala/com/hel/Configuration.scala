package com.hel

import java.net.URL

import pureconfig.ConfigReader.Result
import pureconfig.ConfigSource

import scala.concurrent.duration.FiniteDuration

object Configuration {
  import pureconfig.generic.auto._

  final case class Config(collection: Collection,
                          clients: Clients,
                          radar: Radar,
                          spin: Spin)

  final case class Collection(initialDelay: FiniteDuration,
                              interval: FiniteDuration)

  final case class Clients(parallelism: Int)

  final case class Radar(url: URL, token: String)

  final case class Spin(url: URL)

  final def load: Result[Config] = ConfigSource.default.at("hel").load[Config]
}
