package com.hel.clients

import java.math.BigInteger
import java.security.MessageDigest

import cats.implicits._
import io.circe.optics.JsonPath.root
import io.circe.syntax._
import io.circe.{Json, JsonObject}

trait JsonOptics {
  def removeField(key: String): Json => Json = root.each.obj.modify(_.remove(key))

  def removeFields(keys: String*): Json => Json = keys.toList.map(removeField).reduceLeft(_ andThen _)

  def unnestObject(key: String, sep: String = "_"): Json => Json = root.each.obj.modify { e =>
    e.toMap.get(key).map { event =>
      val keys = event.hcursor.keys.toList.flatten
      val values = keys.flatMap(event.hcursor.downField(_).focus)

      keys.zip(values).foldLeft(e.self) { case (agg, (k, v)) =>
        agg.+:(Seq(key, k).mkString(sep), v)
      }
    }.getOrElse(e.self)
  }

  def renameField(from: String, to: String): Json => Json = root.each.obj.modify { e =>
    e.toMap.get(from).map(v => e.add(to, v).remove(from)).getOrElse(e.self)
  }

  def copyField(from: String, to: String): Json => Json = root.each.obj.modify { e =>
    e.toMap.get(from).map(v => e.add(to, v)).getOrElse(e.self)
  }

  def nestInto(to: String, fields: String*): Json => Json = root.each.obj.modify { e =>
    Option.when(fields.forall(e.contains))(
      e.add(to, fields.foldLeft(Map[String, Json]()) { (agg, c) =>
        agg + (c -> e.toMap.getOrElse(c, Json.Null))
      }.asJson)
    ).getOrElse(e.self)
  }

  def mutateField(key: String)(f: Json => Json): Json => Json = root.each.obj.modify { e =>
    e.toMap.get(key).flatMap(v => f(v).asObject.map(j => e.deepMerge(j))).getOrElse(e.self)
  }

  def mutateToEpoch(key: String)(f: String => Long): Json => Json = mutateField(key) { json =>
    Json.obj((key, json.asString.map(f).map(Json.fromLong).getOrElse(Json.Null)))
  }

  def mutateToEpochs(keys: String*)(f: String => Long): Json => Json =
    keys.toList.map(k => mutateToEpoch(k)(f)).reduceLeft(_ andThen _)

  val hashString: String => String = s =>
    String.format("%032x", new BigInteger(1,
      MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8"))))

  private[this] val stringToSnakeCase: String => String =
    _.replaceAll("(.)(\\p{Upper})", "$1_$2").toLowerCase()

  val transformKeys: Json => Json = { input =>
    def transform(js: Json)(f: String => String): Json =
      js.mapString(f)
        .mapArray(_.map(transform(_)(f)))
        .mapObject(obj => JsonObject.apply({
          obj.toMap.map { case (k, v) => f(k) -> v }
        }.toSeq: _*))

    transform(input)(stringToSnakeCase)
  }
}
