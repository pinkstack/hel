package com.hel.clients

import com.hel.clients.PromInfoFlow.{Transformation, Transformer, hashString, mutateField, nestInto}
import io.circe.Json

object PromInfoEndpoints extends JsonOptics {
  private def define(name: String)(transformations: Transformation*): Transformer =
    (name, transformations.reduceLeft(_ andThen _))

  def parkiriscaGarazneHise: Transformer = define("lay_vParkiriscagaraznehise")(
    mutateField("attributes_naziv") { json =>
      Json.obj("entity_id" -> Json.fromString("prominfo::" + hashString(json.toString())))
    },
    mutateField("entity_id") { _ =>
      Json.obj(
        "source" -> Json.fromString("prominfo"),
        "section" -> Json.fromString("lay_vParkiriscagaraznehise"),
        "entity" -> Json.fromString("Parking garage"),
        "categories" -> Json.fromValues(Seq(
          "location", "counter"
        ).map(Json.fromString))
      )
    },
    nestInto("hel_meta", "entity_id", "source", "section", "entity", "categories")
  )

  def drscStevnaMesta: Transformer = define("lay_drsc_stevna_mesta")(
    mutateField("attributes_road_name") { json =>
      Json.obj("entity_id" -> Json.fromString("prominfo::" + hashString(json.toString())))
    },
    mutateField("entity_id") { _ =>
      Json.obj(
        "source" -> Json.fromString("prominfo"),
        "section" -> Json.fromString("lay_drsc_stevna_mesta"),
        "entity" -> Json.fromString("Traffic Counter"),
        "categories" -> Json.fromValues(Seq(
          "location", "counter"
        ).map(Json.fromString))
      )
    },
    nestInto("hel_meta", "entity_id", "source", "section", "entity", "categories")
  )

  def mikrobitStevnaMesta: Transformer = define("lay_MikrobitStevnaMesta")(
    mutateField("attributes_road_name") { json =>
      Json.obj("entity_id" -> Json.fromString("prominfo::" + hashString(json.toString())))
    },
    mutateField("entity_id") { _ =>
      Json.obj(
        "source" -> Json.fromString("prominfo"),
        "section" -> Json.fromString("lay_MikrobitStevnaMesta"),
        "entity" -> Json.fromString("Traffic Counter"),
        //"categories" -> Json.fromValues(Seq(
        //  "location", "counter"
        //).map(Json.fromString))
      )
    },
    nestInto("hel_meta", "entity_id", "source", "section", "entity")
  )

  def carsharing: Transformer = define("lay_vCarsharing")(
    mutateField("attributes_id") { json =>
      Json.obj("entity_id" -> Json.fromString("prominfo::" + hashString(json.toString())))
    },
    mutateField("entity_id") { _ =>
      Json.obj(
        "source" -> Json.fromString("prominfo"),
        "section" -> Json.fromString("lay_vCarsharing"),
        "entity" -> Json.fromString("Car-sharing Spot"),
        //"categories" -> Json.fromValues(Seq(
        //  "location", "counter"
        //).map(Json.fromString))
      )
    },
    nestInto("hel_meta", "entity_id", "source", "section", "entity")
  )

  val endpoints = Seq(parkiriscaGarazneHise, drscStevnaMesta, mikrobitStevnaMesta, carsharing)
}
