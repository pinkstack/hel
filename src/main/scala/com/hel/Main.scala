package com.hel

import com.typesafe.scalalogging.LazyLogging
import pureconfig.ConfigSource

object Main extends App with LazyLogging {
  (for {
    appConfig <- Configuration.load
    systemConfig <- ConfigSource.default.config()
  } yield Application.run(appConfig, systemConfig)) match {
    case Left(value) =>
      logger.error("💥 " * 10)
      logger.error(value.prettyPrint())
    case Right(_) =>
      logger.info("Booted,...")
  }
}
