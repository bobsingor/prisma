package cool.graph.deploy.migration

import cool.graph.deploy.gc_value.GCStringConverter
import cool.graph.gc_values.{GCValue, InvalidValueForScalarType}
import cool.graph.shared.models._
import cool.graph.utils.or.OrExtensions
import org.scalactic.{Bad, Good, Or}
import sangria.ast.Document

trait NextProjectInferer {
  def infer(baseProject: Project, graphQlSdl: Document): Project Or ProjectSyntaxError
}

sealed trait ProjectSyntaxError
case class RelationDirectiveNeeded(type1: String, type1Fields: Vector[String], type2: String, type2Fields: Vector[String]) extends ProjectSyntaxError
case class InvalidGCValue(err: InvalidValueForScalarType)                                                                  extends ProjectSyntaxError

object NextProjectInferer {
  def apply() = new NextProjectInferer {
    override def infer(baseProject: Project, graphQlSdl: Document) = NextProjectInfererImpl(baseProject, graphQlSdl).infer()
  }
}

case class NextProjectInfererImpl(
    baseProject: Project,
    sdl: Document
) {
  import DataSchemaAstExtensions._

  def infer(): Project Or ProjectSyntaxError = {
    for {
      models <- nextModels
    } yield {
      val newProject = Project(
        id = baseProject.id,
        ownerId = baseProject.ownerId,
        models = models.toList,
        relations = nextRelations.toList,
        enums = nextEnums.toList
      )

      newProject
    }
  }

  lazy val nextModels: Vector[Model] Or ProjectSyntaxError = {
    val models = sdl.objectTypes.map { objectType =>
      val fields: Seq[Or[Field, InvalidGCValue]] = objectType.fields.flatMap { fieldDef =>
        val typeIdentifier = typeIdentifierForTypename(fieldDef.typeName)
        //val relation       = fieldDef.relationName.flatMap(relationName => nextRelations.find(_.name == relationName))
        val relation = nextRelations.find { relation =>
          relation.connectsTheModels(objectType.name, fieldDef.typeName)
        }

        def fieldWithDefault(default: Option[GCValue]) = {
          Field(
            id = fieldDef.name,
            name = fieldDef.name,
            typeIdentifier = typeIdentifier,
            isRequired = fieldDef.isRequired,
            isList = fieldDef.isList,
            isUnique = fieldDef.isUnique,
            enum = nextEnums.find(_.name == fieldDef.typeName),
            defaultValue = default,
            relation = relation,
            relationSide = relation.map { relation =>
              if (relation.modelAId == objectType.name) {
                RelationSide.A
              } else {
                RelationSide.B
              }
            }
          )
        }

        fieldDef.defaultValue.map(x => GCStringConverter(typeIdentifier, fieldDef.isList).toGCValue(x)) match {
          case Some(Good(gcValue)) => Some(Good(fieldWithDefault(Some(gcValue))))
          case Some(Bad(err))      => Some(Bad(InvalidGCValue(err)))
          case None                => Some(Good(fieldWithDefault(None)))
        }
      }

      OrExtensions.sequence(fields.toVector) match {
        case Good(fields: Seq[Field]) =>
          val fieldNames            = fields.map(_.name)
          val missingReservedFields = ReservedFields.reservedFieldNames.filterNot(fieldNames.contains)
          val hiddenReservedFields  = missingReservedFields.map(ReservedFields.reservedFieldFor(_).copy(isHidden = true))

          Good(
            Model(
              id = objectType.name,
              name = objectType.name,
              fields = fields.toList ++ hiddenReservedFields
            ))

        case Bad(err) =>
          Bad(err)
      }
    }

    OrExtensions.sequence(models)
  }

  lazy val nextRelations: Set[Relation] = {
    val tmp = for {
      objectType    <- sdl.objectTypes
      relationField <- objectType.fields.filter(!_.hasScalarType)
    } yield {
      val relationName = relationField.relationName match {
        case Some(name) =>
          name
        case None =>
          val modelA = objectType.name
          val modelB = relationField.typeName
          if (modelA < modelB) { // we want the generation of relation names to be deterministic
            s"${modelA}To${modelB}"
          } else {
            s"${modelB}To${modelA}"
          }
      }
      Relation(
        id = relationName,
        name = relationName,
        modelAId = objectType.name,
        modelBId = relationField.typeName
      )
    }

    tmp.groupBy(_.name).values.flatMap(_.headOption).toSet
  }

  lazy val nextEnums: Vector[Enum] = {
    sdl.enumTypes.map { enumDef =>
      Enum(
        id = enumDef.name,
        name = enumDef.name,
        values = enumDef.values.map(_.name)
      )
    }
  }

  private def typeIdentifierForTypename(typeName: String): TypeIdentifier.Value = {
    if (sdl.objectType(typeName).isDefined) {
      TypeIdentifier.Relation
    } else if (sdl.enumType(typeName).isDefined) {
      TypeIdentifier.Enum
    } else {
      TypeIdentifier.withNameHacked(typeName)
    }
  }
}
