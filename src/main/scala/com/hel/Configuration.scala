package com.hel

import java.net.URL
import java.util

import pureconfig.ConfigReader.Result
import pureconfig.ConfigSource

import scala.concurrent.duration.FiniteDuration

object Configuration {

  import pureconfig.generic.auto._

  final case class Config(ticker: Ticker,
                          radar: Radar,
                          spin: Spin,
                          prominfo: Prominfo)

  final case class Ticker(initialDelay: FiniteDuration,
                          interval: FiniteDuration)

  final case class Radar(enabled: Boolean,
                         url: URL,
                         token: String,
                         parallelism: Int,
                         minBackoff: FiniteDuration,
                         maxBackoff: FiniteDuration,
                         randomFactor: Double,
                         maxRestarts: Int)

  final case class Spin(enabled: Boolean,
                        url: URL,
                        parallelism: Int,
                        minBackoff: FiniteDuration,
                        maxBackoff: FiniteDuration,
                        randomFactor: Double,
                        maxRestarts: Int)


  final case class Prominfo(enabled: Boolean,
                            url: URL,
                            parallelism: Int,
                            minBackoff: FiniteDuration,
                            maxBackoff: FiniteDuration,
                            randomFactor: Double,
                            maxRestarts: Int,
                            defaultQueryAttributes: Map[String, String],
                            defaultFindAttributes: Map[String, String],
                            sectionCountersMapping: Map[String, String],
                            sections: Map[String, Section])

  final case class Layer(name: String, enabled: Boolean)

  final case class Section(enabled: Boolean, layers: List[Layer])

  final def load: Result[Config] = ConfigSource.default.at("hel").load[Config]
}
