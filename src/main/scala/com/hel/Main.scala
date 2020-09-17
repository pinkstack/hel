package com.hel

import com.typesafe.scalalogging.LazyLogging
import pureconfig.ConfigSource

object Main extends App with LazyLogging {
  (for {
    appConfig <- Configuration.load
    systemConfig <- ConfigSource.default.config()
  } yield Application.run(appConfig, systemConfig)) match {
    case Left(value) =>
      System.err.println("💥 " * 10)
      System.err.println(value.prettyPrint())
    case Right(_) =>
      logger.info("Booted,...")
  }
}
