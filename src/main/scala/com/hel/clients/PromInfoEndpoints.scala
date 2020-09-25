package com.hel.clients

import akka.http.scaladsl.model.{HttpRequest, Uri}
import com.hel.Configuration
import io.circe.Json

object PromInfoEndpoints extends JsonOptics {
  type SectionName = String
  type LayerName = String
  final val NoLayerName: LayerName = ""
  type Enabled = Boolean
  type Transformation = Json => Option[Vector[Json]]
  type Layer = (SectionName, LayerName, HttpRequest, Transformation)
  type Section = (SectionName, List[Layer], Enabled)

  val defaultTransformation: Json => Json =
    Seq(
      unnestObject("geometry"),
      unnestObject("attributes"),
      renameField("geometry_y", "lon"),
      renameField("geometry_x", "lat"),
      nestInto("location", "lon", "lat"),
      transformKeysWith(_.toLowerCase),
      transformKeysWith(_.replaceFirst("attributes_", "")),
      removeFields("geometry", "lon", "lat", "attributes"),
    ).reduceLeft((a, b) => a andThen b)

  private[this] val defaultQueryTransformation: Transformation =
    _.hcursor.downField("features").focus.map(defaultTransformation).flatMap(_.asArray)

  private[this] def metaTransformation(sectionName: SectionName, layerName: LayerName): Option[Vector[Json]] => Option[Vector[Json]] = { jsons =>
    val elementTranslation: Json => Json = { json =>
      val helMeta: Json = (for {
        id <- json.hcursor.downField("id").focus.map(_.as[String].toOption)

        secondID <- json.hcursor.downField("objectid").focus.flatMap(_.as[String].toOption)
        _ = println(secondID)
        theID = if (id.getOrElse("") != "" && id.getOrElse("") != "null") {
          id.getOrElse("")
        } else secondID

        _ = println(theID)
        keyFragments = List("prominfo", sectionName, layerName, theID)
        helEntityID = keyFragments.mkString("::")
        helEntityHashID = hashString(keyFragments.mkString("::"))
      } yield Json.obj(
        ("hel_entity_id", Json.fromString(helEntityID)),
        ("hel_entity_hash_id", Json.fromString(helEntityHashID)),
        ("section_name", Json.fromString(sectionName)),
        ("layer_name", Json.fromString(layerName))
      )).getOrElse(Json.obj(("hel_entity_id", Json.fromString("XXX"))))

      json.deepMerge(Json.obj(("hel_meta", helMeta))).deepMerge(helMeta)
    }
    jsons.map(_.map(elementTranslation))
  }

  private[this] def defineSection(sectionName: SectionName)
                                 (layers: Layer*)
                                 (implicit config: Configuration.Prominfo): (SectionName, List[Layer], Enabled) = {

    val sectionOpt: Option[Configuration.Section] = config.sections.get(sectionName)
    val layersConfiguration: List[Configuration.Layer] =
      sectionOpt.map(section => section.layers.filter(_.enabled)).getOrElse(List.empty[Configuration.Layer])

    (
      sectionName,
      layers.toList.filter(layer => layersConfiguration.map(_.name).contains(layer._2)).map { layer: Layer =>
        layer.copy(_4 = layer._4.andThen(metaTransformation(sectionName, layer._2)), _1 = sectionName)
      },
      sectionOpt.exists(_.enabled)
    )
  }

  private[this] def queryLayer(layerName: LayerName,
                               queryAttributes: Map[String, String] = Map.empty)
                              (implicit config: Configuration.Prominfo,
                               transformation: Transformation = defaultQueryTransformation): Layer = {
    val attributes: Map[String, String] = Option.when(queryAttributes.isEmpty)(config.defaultQueryAttributes).getOrElse(queryAttributes)
    val request: HttpRequest = HttpRequest(uri = Uri(s"${config.url}/web/api/MapService/Query/$layerName/query")
      .withQuery(Uri.Query(attributes)))
    (NoLayerName, layerName, request, transformation)
  }

  private[this] val sections: Configuration.Prominfo => Map[String, Section] = { implicit config =>
    config.sections.map { case (sectionName: SectionName, sectionConfig: Configuration.Section) =>
      (sectionName, defineSection(sectionName)(
        sectionConfig.layers.map(layerConfig => queryLayer(layerConfig.name)(config)): _*
      ))
    }
  }

  def section(name: SectionName)(implicit config: Configuration.Prominfo): Section =
    sections(config).getOrElse(name, throw new Exception(s"Unknown section $name"))
}
