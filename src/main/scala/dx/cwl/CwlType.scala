package dx.cwl

import dx.cwl.Utils.{translateDoc, translateOptional, translateOptionalArray}
import org.w3id.cwl.cwl1_2.{
  ArraySchema,
  CWLType,
  CommandInputArraySchema,
  CommandInputEnumSchema,
  CommandInputRecordField,
  CommandInputRecordFieldImpl,
  CommandInputRecordSchema,
  CommandLineBindingImpl,
  CommandOutputBindingImpl,
  CommandOutputRecordField,
  CommandOutputRecordFieldImpl,
  EnumSchema,
  IOSchema,
  InputArraySchema,
  InputEnumSchema,
  InputRecordField,
  InputRecordSchema,
  InputSchema,
  OutputArraySchema,
  OutputEnumSchema,
  OutputRecordField,
  OutputRecordSchema,
  OutputSchema,
  SecondaryFileSchemaImpl,
  stderr => CWLStderr,
  stdin => CWLStdin,
  stdout => CWLStdout
}

import scala.annotation.tailrec
import scala.collection.immutable.{SeqMap, TreeSeqMap}
import scala.jdk.CollectionConverters._

/**
  * Marker trait for all CWL data types.
  */
sealed trait CwlType {

  /**
    * Returns true if this type is coercible to the specified type
    */
  def coercibleTo(targetType: CwlType): Boolean
}

object CwlType {

  /**
    * Translates a Java type object to a [[CwlType]]. Since CWL allows parameters
    * to be polymorphic (i.e. accept multiple types), the value returned is a
    * [[Vector[CwlType]]]. If the type is any of `stdin`, `stdout`, `stderr`, it is
    * converted to a [[CwlFile]] and a [[Some(StdFile.StdFile)]] is also returned
    * indicating which std file is represented.
    *
    * TODO: we need to handle optional types, but they currently fail to parse due to
    *  https://github.com/common-workflow-lab/cwljava/issues/29
    *
    * @param t the Java type object - may be a [[java.util.List]] of multiple types
    * @param schemaDefs schema definitions to use for resolving non-standard types
    * @return a tuple [[(Vector[CwlType], Option[StdFile.StdFile])]].
    */
  def translate(
      t: java.lang.Object,
      schemaDefs: Map[String, CwlSchema] = Map.empty,
      rawSchemaDefs: Map[String, IOSchema] = Map.empty
  ): (Vector[CwlType], Option[StdFile.StdFile], Map[String, CwlSchema]) = {
    def inner(
        innerType: java.lang.Object,
        innerSchemaDefs: Map[String, CwlSchema]
    ): (Vector[CwlType], Option[StdFile.StdFile], Map[String, CwlSchema]) = {
      innerType match {
        case a: java.util.List[_] =>
          a.asInstanceOf[java.util.List[java.lang.Object]]
            .asScala
            .foldLeft(Vector.empty[CwlType], Option.empty[StdFile.StdFile], innerSchemaDefs) {
              case ((cwlTypes, stdfile, newSchemaDefs), t) =>
                val (newTypes, newStdfile, updatedSchemaDefs) = inner(t, newSchemaDefs)
                val updatedStdfile = (stdfile, newStdfile) match {
                  case (None, None)         => None
                  case (s, None)            => s
                  case (None, s)            => s
                  case (s1, s2) if s1 == s2 => s1
                  case (s1, s2) =>
                    throw new RuntimeException(s"found multiple different std types ${s1},${s2}")
                }
                (cwlTypes ++ newTypes, updatedStdfile, updatedSchemaDefs)
            }
        case CWLStdin.STDIN   => (Vector(CwlFile), Some(StdFile.Stdin), innerSchemaDefs)
        case CWLStdout.STDOUT => (Vector(CwlFile), Some(StdFile.Stdout), innerSchemaDefs)
        case CWLStderr.STDERR => (Vector(CwlFile), Some(StdFile.Stderr), innerSchemaDefs)
        case schema: IOSchema =>
          val (newType, newSchemaDefs) =
            CwlSchema.translateSchema(schema, innerSchemaDefs, rawSchemaDefs)
          val updatedSchemaDefs = if (newType.name.isDefined) {
            innerSchemaDefs ++ newSchemaDefs + (newType.name.get -> newType)
          } else {
            innerSchemaDefs ++ newSchemaDefs
          }
          (Vector(newType), None, updatedSchemaDefs)
        case schemaName: String if schemaName.contains("#") =>
          // a schema reference
          val name = Utils.normalizeUri(schemaName)
          schemaDefs.get(name).orElse(innerSchemaDefs.get(name)) match {
            case Some(schemaDef) => (Vector(schemaDef), None, innerSchemaDefs)
            case None if rawSchemaDefs.contains(name) =>
              val (types, stdfile, updatedSchemaDefs) = inner(rawSchemaDefs(name), innerSchemaDefs)
              val newSchemaDef = types match {
                case Vector(s: CwlSchema) => s
                case other =>
                  throw new RuntimeException(s"expected single CwlSchema, not ${other}")
              }
              (types, stdfile, updatedSchemaDefs + (name -> newSchemaDef))
            case None =>
              throw new RuntimeException(s"missing definition for schema ${schemaName}")
          }
        case _ =>
          val cwlType: CwlType = innerType match {
            case "string"          => CwlString
            case "boolean"         => CwlBoolean
            case "int"             => CwlInt
            case "long"            => CwlLong
            case "float"           => CwlFloat
            case "double"          => CwlDouble
            case "null"            => CwlNull
            case "Any"             => CwlAny
            case CWLType.FILE      => CwlFile
            case CWLType.DIRECTORY => CwlDirectory
            case other =>
              throw new RuntimeException(s"unexpected type ${other}")
          }
          (Vector(cwlType), None, innerSchemaDefs)
      }
    }
    inner(t, Map.empty)
  }

  def apply(
      t: java.lang.Object,
      schemaDefs: Map[String, CwlSchema] = Map.empty
  ): (Vector[CwlType], Option[StdFile.StdFile]) = {
    val (types, stdfile, _) = translate(t, schemaDefs, Map.empty)
    (types, stdfile)
  }
}

case object CwlNull extends CwlType {
  override def coercibleTo(targetType: CwlType): Boolean = {
    targetType match {
      case CwlNull | CwlOptional(_) => true
      case _                        => false
    }
  }
}

case object CwlAny extends CwlType {
  override def coercibleTo(targetType: CwlType): Boolean = {
    CwlOptional.unwrapOptional(targetType) != CwlNull
  }
}

/**
  * An optional type.
  * @param t the inner type
  * @example {{{string?}}} is translated to {{{CwlOptional(CwlString)}}}
  */
case class CwlOptional(t: CwlType) extends CwlType {
  override def coercibleTo(targetType: CwlType): Boolean = {
    targetType match {
      case CwlAny | CwlNull | CwlOptional(CwlNull) => true
      case CwlOptional(other)                      => t.coercibleTo(other)
      case _                                       => false
    }
  }
}

object CwlOptional {
  def isOptional(t: CwlType): Boolean = {
    t match {
      case CwlOptional(_) => true
      case _              => false
    }
  }

  def anyOptional(types: Vector[CwlType]): Boolean = {
    types.exists(isOptional)
  }

  @tailrec
  def unwrapOptional(t: CwlType): CwlType = {
    t match {
      case CwlOptional(innerType) => unwrapOptional(innerType)
      case _                      => t
    }
  }

  def ensureOptional(t: CwlType): CwlType = {
    t match {
      case optType: CwlOptional => optType
      case _                    => CwlOptional(t)
    }
  }
}

/**
  * All valid CWL types are primitive, excepting `Any`, `null`, and schema types.
  */
sealed trait CwlPrimitive extends CwlType {
  def coercibleTo(targetType: CwlType): Boolean = {
    val nonOptType = CwlOptional.unwrapOptional(targetType)
    Set[CwlType](this, CwlAny).contains(nonOptType) || canBeCoercedTo(nonOptType)
  }

  /**
    * Returns true if this type can be coerced to targetType,
    * which is a non-optional, non-equal, and non-Any type.
    */
  protected def canBeCoercedTo(targetType: CwlType): Boolean = {
    CwlString == targetType
  }
}

case object CwlBoolean extends CwlPrimitive

case object CwlString extends CwlPrimitive {
  override protected def canBeCoercedTo(targetType: CwlType): Boolean = {
    targetType match {
      case _: CwlPrimitive => true
      case _               => false
    }
  }
}

/**
  * Parent trait of the four CWL numeric types
  */
sealed trait CwlNumber extends CwlPrimitive {
  override protected def canBeCoercedTo(targetType: CwlType): Boolean = {
    targetType match {
      case _: CwlNumber => true
      case CwlString    => true
      case _            => false
    }
  }
}

case object CwlInt extends CwlNumber
case object CwlLong extends CwlNumber
case object CwlFloat extends CwlNumber
case object CwlDouble extends CwlNumber

/**
  * Parent trait of the two CWL path types
  */
sealed trait CwlPath extends CwlPrimitive {
  def className: String
}
case object CwlFile extends CwlPath {
  val className: String = "File"
}
case object CwlDirectory extends CwlPath {
  val className: String = "Directory"
}

/**
  * Parent of CWL schema types. Note that input and output schema definitions
  * both use the same objects, but `inputBinding` will always be `None` for
  * output types.
  */
sealed trait CwlSchema extends CwlType {
  val name: Option[String]
  val label: Option[String]
  val doc: Option[String]

  def coercibleTo(targetType: CwlType): Boolean = {
    val nonOptType = CwlOptional.unwrapOptional(targetType)
    Set[CwlType](this, CwlAny).contains(nonOptType) || canBeCoercedTo(nonOptType)
  }

  /**
    * Returns true if this type can be coerced to targetType,
    * which is a non-optional, non-equal, and non-Any type.
    */
  protected def canBeCoercedTo(targetType: CwlType): Boolean = false
}

sealed trait CwlInputSchema extends CwlSchema {
  val inputBinding: Option[CommandInputBinding]
}

object CwlSchema {
  def apply(schema: InputSchema, schemaDefs: Map[String, CwlSchema]): CwlSchema = {
    schema match {
      case schema: CommandInputArraySchema  => CwlArray(schema, schemaDefs)
      case schema: InputArraySchema         => CwlArray(schema, schemaDefs)
      case schema: CommandInputRecordSchema => CwlInputRecord(schema, schemaDefs)
      case schema: InputRecordSchema        => CwlInputRecord(schema, schemaDefs)
      case schema: CommandInputEnumSchema   => CwlEnum(schema, schemaDefs)
      case schema: InputEnumSchema          => CwlEnum(schema, schemaDefs)
      case _ =>
        throw new Exception(s"unexpected input schema ${schema}")
    }
  }

  def apply(schema: OutputSchema,
            schemaDefs: Map[String, CwlSchema],
            rawSchemaDefs: Map[String, OutputSchema] = Map.empty): CwlSchema = {
    schema match {
      case schema: OutputArraySchema  => CwlArray(schema, schemaDefs)
      case schema: OutputRecordSchema => CwlOutputRecord(schema, schemaDefs)
      case schema: OutputEnumSchema   => CwlEnum(schema, schemaDefs)
      case _ =>
        throw new Exception(s"unexpected output schema ${schema}")
    }
  }

  def translateSchema(
      schema: IOSchema,
      schemaDefs: Map[String, CwlSchema],
      rawSchemaDefs: Map[String, IOSchema]
  ): (CwlSchema, Map[String, CwlSchema]) = {
    schema match {
      case arraySchema: ArraySchema =>
        CwlArray.translate(arraySchema, schemaDefs, rawSchemaDefs)
      case recordSchema: InputRecordSchema =>
        CwlInputRecord.translate(recordSchema, schemaDefs, rawSchemaDefs)
      case recordSchema: OutputRecordSchema =>
        CwlOutputRecord.translate(recordSchema, schemaDefs, rawSchemaDefs)
      case enumSchema: EnumSchema =>
        (CwlEnum(enumSchema, schemaDefs), Map.empty)
      case _ =>
        throw new Exception(s"unexpected input schema ${schema}")
    }
  }

  def translateSchemas(schemas: Map[String, IOSchema],
                       schemaDefs: Map[String, CwlSchema]): Map[String, CwlSchema] = {
    schemas.foldLeft(Map.empty[String, CwlSchema]) {
      case (accu, (name, _)) if schemaDefs.contains(name) || accu.contains(name) => accu
      case (accu, (name, schema)) =>
        val (newSchema, newSchemaDefs) = translateSchema(schema, schemaDefs ++ accu, schemas)
        accu ++ newSchemaDefs + (name -> newSchema)
    }
  }

  def translateAll(schemas: Vector[java.lang.Object],
                   schemaDefs: Map[String, CwlSchema]): Map[String, CwlSchema] = {
    // schemas may reference each other, so first we build a maps of
    // name -> schema for input and output schema types, then recursively
    // translate each schema
    val (inputSchemas, outputSchemas) = schemas
      .foldLeft(Map.empty[String, InputSchema], Map.empty[String, OutputSchema]) {
        case ((i, o), schema: InputArraySchema) =>
          translateOptional(schema.getName) match {
            case Some(name) => (i + (name -> schema), o)
            case _          => (i, o)
          }
        case ((i, o), schema: InputRecordSchema) =>
          translateOptional(schema.getName) match {
            case Some(name) => (i + (name -> schema), o)
            case _          => (i, o)
          }
        case ((i, o), schema: InputEnumSchema) =>
          translateOptional(schema.getName) match {
            case Some(name) => (i + (name -> schema), o)
            case _          => (i, o)
          }
        case ((i, o), schema: OutputArraySchema) =>
          translateOptional(schema.getName) match {
            case Some(name) => (i, o + (name -> schema))
            case _          => (i, o)
          }
        case ((i, o), schema: OutputRecordSchema) =>
          translateOptional(schema.getName) match {
            case Some(name) => (i, o + (name -> schema))
            case _          => (i, o)
          }
        case ((i, o), schema: OutputEnumSchema) =>
          translateOptional(schema.getName) match {
            case Some(name) => (i, o + (name -> schema))
            case _          => (i, o)
          }
        case (accu, _) => accu
      }
    val translatedInputSchemas = translateSchemas(inputSchemas, schemaDefs)
    val translatedOutputSchemas = translateSchemas(outputSchemas, schemaDefs)
    translatedInputSchemas ++ translatedOutputSchemas
  }
}

case class CwlArray(itemTypes: Vector[CwlType],
                    name: Option[String] = None,
                    label: Option[String] = None,
                    doc: Option[String] = None,
                    inputBinding: Option[CommandInputBinding] = None)
    extends CwlInputSchema {
  override protected def canBeCoercedTo(targetType: CwlType): Boolean = {
    targetType match {
      case targetSchema: CwlArray =>
        itemTypes.exists { fromType =>
          targetSchema.itemTypes.exists { toType =>
            fromType.coercibleTo(toType)
          }
        }
      case _ => false
    }
  }
}

object CwlArray {
  private def create(schema: ArraySchema,
                     types: Vector[CwlType],
                     schemaDefs: Map[String, CwlSchema]): CwlArray = {
    val (name, label, doc) = schema match {
      case schema: InputArraySchema  => (schema.getName, schema.getLabel, schema.getDoc)
      case schema: OutputArraySchema => (schema.getName, schema.getLabel, schema.getDoc)
      case other                     => throw new RuntimeException(s"unexpected array schema ${other}")
    }
    val inputBinding = schema match {
      case c: CommandInputArraySchema =>
        translateOptional(c.getInputBinding).map {
          case binding: CommandLineBindingImpl => CommandInputBinding(binding, schemaDefs)
          case other =>
            throw new RuntimeException(s"unexpected CommandLineBinding value ${other}")
        }
      case _ => None
    }
    CwlArray(
        types,
        translateOptional(name).map(Utils.normalizeUri),
        translateOptional(label),
        translateDoc(doc),
        inputBinding
    )
  }

  def apply(schema: ArraySchema, schemaDefs: Map[String, CwlSchema]): CwlArray = {
    val (types, stdfile) = CwlType(schema.getItems, schemaDefs)
    assert(stdfile.isEmpty)
    create(schema, types, schemaDefs)
  }

  def translate(
      schema: ArraySchema,
      schemaDefs: Map[String, CwlSchema],
      rawSchemaDefs: Map[String, IOSchema]
  ): (CwlArray, Map[String, CwlSchema]) = {
    val (types, stdfile, newSchemaDefs) =
      CwlType.translate(schema.getItems, schemaDefs, rawSchemaDefs)
    assert(stdfile.isEmpty)
    (create(schema, types, schemaDefs ++ newSchemaDefs), newSchemaDefs)
  }
}

sealed trait CwlRecordField {
  val name: String
  val types: Vector[CwlType]
  val label: Option[String]
  val doc: Option[String]
  val secondaryFiles: Vector[SecondaryFile]
  val format: Vector[CwlValue]
  val streamable: Option[Boolean]

  /**
    * the field is optional if any of the allowed types are optional
    */
  lazy val optional: Boolean = {
    types.exists {
      case CwlOptional(_) => true
      case _              => false
    }
  }
}

sealed trait CwlRecord extends CwlSchema {
  val fields: SeqMap[String, CwlRecordField]
}

case class CwlInputRecordField(name: String,
                               types: Vector[CwlType],
                               label: Option[String] = None,
                               doc: Option[String] = None,
                               inputBinding: Option[CommandInputBinding] = None,
                               secondaryFiles: Vector[SecondaryFile] = Vector.empty,
                               format: Vector[CwlValue] = Vector.empty,
                               streamable: Option[Boolean] = None,
                               loadContents: Option[Boolean] = None,
                               loadListing: Option[LoadListing.LoadListing] = None)
    extends CwlRecordField

object CwlInputRecordField {
  private def create(field: InputRecordField,
                     types: Vector[CwlType],
                     schemaDefs: Map[String, CwlSchema]): CwlInputRecordField = {
    val inputBinding = field match {
      case c: CommandInputRecordField =>
        translateOptional(c.getInputBinding).map {
          case binding: CommandLineBindingImpl => CommandInputBinding(binding, schemaDefs)
          case other =>
            throw new RuntimeException(s"unexpected CommandLineBinding value ${other}")
        }
      case _ => None
    }
    CwlInputRecordField(
        field.getName,
        types,
        translateOptional(field.getLabel),
        translateDoc(field.getDoc),
        inputBinding,
        translateOptionalArray(field.getSecondaryFiles).map {
          case sf: SecondaryFileSchemaImpl => SecondaryFile(sf, schemaDefs)
          case other =>
            throw new RuntimeException(s"unexpected SecondaryFile value ${other}")
        },
        translateOptionalArray(field.getFormat).map(CwlValue.apply(_, schemaDefs)),
        translateOptional(field.getStreamable).map(_.booleanValue()),
        translateOptional(field.getLoadContents).map(_.booleanValue()),
        translateOptional(field.getLoadListing).map(LoadListing.from)
    )
  }

  def apply(field: InputRecordField, schemaDefs: Map[String, CwlSchema]): CwlInputRecordField = {
    val (types, stdfile) = CwlType(field.getType, schemaDefs)
    assert(stdfile.isEmpty)
    create(field, types, schemaDefs)
  }

  def translate(
      field: InputRecordField,
      schemaDefs: Map[String, CwlSchema],
      rawSchemaDefs: Map[String, IOSchema]
  ): (CwlInputRecordField, Map[String, CwlSchema]) = {
    val (types, stdfile, newSchemaDefs) =
      CwlType.translate(field.getType, schemaDefs, rawSchemaDefs)
    assert(stdfile.isEmpty)
    (create(field, types, schemaDefs ++ newSchemaDefs), newSchemaDefs)
  }
}

case class CwlInputRecord(fields: SeqMap[String, CwlInputRecordField],
                          name: Option[String] = None,
                          label: Option[String] = None,
                          doc: Option[String] = None,
                          inputBinding: Option[CommandInputBinding] = None)
    extends CwlRecord
    with CwlInputSchema {
  override protected def canBeCoercedTo(targetType: CwlType): Boolean = {
    targetType match {
      case targetSchema: CwlInputRecord if fields.keySet == targetSchema.fields.keySet =>
        fields.forall {
          case (name, fromField) =>
            fromField.types.exists { fromType =>
              targetSchema.fields(name).types.exists { toType =>
                fromType.coercibleTo(toType)
              }
            }
        }
      case _ => false
    }
  }
}

object CwlInputRecord {
  private def create(schema: InputRecordSchema,
                     fields: SeqMap[String, CwlInputRecordField],
                     schemaDefs: Map[String, CwlSchema]): CwlInputRecord = {
    val inputBinding = schema match {
      case c: CommandInputRecordSchema =>
        translateOptional(c.getInputBinding).map {
          case binding: CommandLineBindingImpl => CommandInputBinding(binding, schemaDefs)
          case other =>
            throw new RuntimeException(s"unexpected CommandLineBinding value ${other}")
        }
      case _ => None
    }
    CwlInputRecord(
        fields,
        translateOptional(schema.getName).map(Utils.normalizeUri),
        translateOptional(schema.getLabel),
        translateDoc(schema.getDoc),
        inputBinding
    )
  }

  def apply(schema: InputRecordSchema, schemaDefs: Map[String, CwlSchema]): CwlInputRecord = {
    val fields = translateOptional(schema.getFields)
      .map(
          _.asScala
            .map {
              case field: CommandInputRecordFieldImpl =>
                val cwlField = CwlInputRecordField(field, schemaDefs)
                cwlField.name -> cwlField
              case other =>
                throw new RuntimeException(s"invalid record field ${other}")
            }
            .to(TreeSeqMap)
      )
      .getOrElse(SeqMap.empty[String, CwlInputRecordField])
    create(schema, fields, schemaDefs)
  }

  def translate(
      schema: InputRecordSchema,
      schemaDefs: Map[String, CwlSchema],
      rawSchemaDefs: Map[String, IOSchema]
  ): (CwlRecord, Map[String, CwlSchema]) = {
    val (fields, newSchemaDefs) = translateOptional(schema.getFields)
      .map { fields =>
        val (cwlFields, newSchemaDefs) =
          fields.asScala.foldLeft(Vector.empty[CwlInputRecordField], Map.empty[String, CwlSchema]) {
            case ((fieldAccu, schemaDefAccu), field: InputRecordField) =>
              val (cwlField, newSchemaDefs) =
                CwlInputRecordField.translate(field, schemaDefs ++ schemaDefAccu, rawSchemaDefs)
              (fieldAccu :+ cwlField, schemaDefAccu ++ newSchemaDefs)
            case other =>
              throw new RuntimeException(s"invalid record field ${other}")
          }
        (cwlFields.map(f => f.name -> f).to(TreeSeqMap), newSchemaDefs)
      }
      .getOrElse((SeqMap.empty[String, CwlInputRecordField], Map.empty[String, CwlSchema]))
    (create(schema, fields, schemaDefs), newSchemaDefs)
  }
}

case class CwlOutputRecordField(name: String,
                                types: Vector[CwlType],
                                label: Option[String] = None,
                                doc: Option[String] = None,
                                outputBinding: Option[CommandOutputBinding] = None,
                                secondaryFiles: Vector[SecondaryFile] = Vector.empty,
                                format: Vector[CwlValue] = Vector.empty,
                                streamable: Option[Boolean] = None)
    extends CwlRecordField

object CwlOutputRecordField {
  private def create(field: OutputRecordField,
                     types: Vector[CwlType],
                     schemaDefs: Map[String, CwlSchema]): CwlOutputRecordField = {
    val outputBinding = field match {
      case c: CommandOutputRecordField =>
        translateOptional(c.getOutputBinding).map {
          case binding: CommandOutputBindingImpl => CommandOutputBinding(binding, schemaDefs)
          case other =>
            throw new RuntimeException(s"unexpected CommandLineBinding value ${other}")
        }
      case _ => None
    }
    CwlOutputRecordField(
        field.getName,
        types,
        translateOptional(field.getLabel),
        translateDoc(field.getDoc),
        outputBinding,
        translateOptionalArray(field.getSecondaryFiles).map {
          case sf: SecondaryFileSchemaImpl => SecondaryFile(sf, schemaDefs)
          case other =>
            throw new RuntimeException(s"unexpected SecondaryFile value ${other}")
        },
        translateOptionalArray(field.getFormat).map(CwlValue.apply(_, schemaDefs)),
        translateOptional(field.getStreamable).map(_.booleanValue())
    )
  }

  def apply(field: OutputRecordField, schemaDefs: Map[String, CwlSchema]): CwlOutputRecordField = {
    val (types, stdfile) = CwlType(field.getType, schemaDefs)
    assert(stdfile.isEmpty)
    create(field, types, schemaDefs)
  }

  def translate(
      field: OutputRecordField,
      schemaDefs: Map[String, CwlSchema],
      rawSchemaDefs: Map[String, IOSchema]
  ): (CwlOutputRecordField, Map[String, CwlSchema]) = {
    val (types, stdfile, newSchemaDefs) =
      CwlType.translate(field.getType, schemaDefs, rawSchemaDefs)
    assert(stdfile.isEmpty)
    (create(field, types, schemaDefs ++ newSchemaDefs), newSchemaDefs)
  }
}

case class CwlOutputRecord(fields: SeqMap[String, CwlOutputRecordField],
                           name: Option[String] = None,
                           label: Option[String] = None,
                           doc: Option[String] = None)
    extends CwlRecord {
  override protected def canBeCoercedTo(targetType: CwlType): Boolean = {
    targetType match {
      case targetSchema: CwlOutputRecord if fields.keySet == targetSchema.fields.keySet =>
        fields.forall {
          case (name, fromField) =>
            fromField.types.exists { fromType =>
              targetSchema.fields(name).types.exists { toType =>
                fromType.coercibleTo(toType)
              }
            }
        }
      case _ => false
    }
  }
}

object CwlOutputRecord {
  private def create(schema: OutputRecordSchema,
                     fields: SeqMap[String, CwlOutputRecordField]): CwlOutputRecord = {
    CwlOutputRecord(
        fields,
        translateOptional(schema.getName).map(Utils.normalizeUri),
        translateOptional(schema.getLabel),
        translateDoc(schema.getDoc)
    )
  }

  def apply(schema: OutputRecordSchema, schemaDefs: Map[String, CwlSchema]): CwlOutputRecord = {
    val fields = translateOptional(schema.getFields)
      .map(
          _.asScala
            .map {
              case field: CommandOutputRecordFieldImpl =>
                val cwlField = CwlOutputRecordField(field, schemaDefs)
                cwlField.name -> cwlField
              case other =>
                throw new RuntimeException(s"invalid record field ${other}")
            }
            .to(TreeSeqMap)
      )
      .getOrElse(SeqMap.empty[String, CwlOutputRecordField])
    create(schema, fields)
  }

  def translate(
      schema: OutputRecordSchema,
      schemaDefs: Map[String, CwlSchema],
      rawSchemaDefs: Map[String, IOSchema]
  ): (CwlRecord, Map[String, CwlSchema]) = {
    val (fields, newSchemaDefs) = translateOptional(schema.getFields)
      .map { fields =>
        val (cwlFields, newSchemaDefs) =
          fields.asScala
            .foldLeft(Vector.empty[CwlOutputRecordField], Map.empty[String, CwlSchema]) {
              case ((fieldAccu, schemaDefAccu), field: OutputRecordField) =>
                val (cwlField, newSchemaDefs) =
                  CwlOutputRecordField.translate(field, schemaDefs ++ schemaDefAccu, rawSchemaDefs)
                (fieldAccu :+ cwlField, schemaDefAccu ++ newSchemaDefs)
              case other =>
                throw new RuntimeException(s"invalid record field ${other}")
            }
        (cwlFields.map(f => f.name -> f).to(TreeSeqMap), newSchemaDefs)
      }
      .getOrElse((SeqMap.empty[String, CwlOutputRecordField], Map.empty[String, CwlSchema]))
    (create(schema, fields), newSchemaDefs)
  }
}

case class CwlEnum(symbols: Vector[String],
                   name: Option[String] = None,
                   label: Option[String] = None,
                   doc: Option[String] = None,
                   inputBinding: Option[CommandInputBinding] = None)
    extends CwlInputSchema {
  override protected def canBeCoercedTo(targetType: CwlType): Boolean = {
    targetType match {
      case targetSchema: CwlEnum if this.symbols == targetSchema.symbols => true
      case CwlString                                                     => true
      case _                                                             => false
    }
  }
}

object CwlEnum {
  def apply(schema: EnumSchema, schemaDefs: Map[String, CwlSchema]): CwlEnum = {
    val (name, label, doc) = schema match {
      case schema: InputEnumSchema  => (schema.getName, schema.getLabel, schema.getDoc)
      case schema: OutputEnumSchema => (schema.getName, schema.getLabel, schema.getDoc)
      case other                    => throw new RuntimeException(s"unexpected array schema ${other}")
    }
    val inputBinding = schema match {
      case c: CommandInputEnumSchema =>
        translateOptional(c.getInputBinding).map {
          case binding: CommandLineBindingImpl => CommandInputBinding(binding, schemaDefs)
          case other =>
            throw new RuntimeException(s"unexpected CommandLineBinding value ${other}")
        }
      case _ => None
    }
    CwlEnum(
        schema.getSymbols.asScala.map {
          case s: String => s
          case other     => throw new Exception(s"unexpected symbol value ${other}")
        }.toVector,
        translateOptional(name).map(Utils.normalizeUri),
        translateOptional(label),
        translateDoc(doc),
        inputBinding
    )
  }
}
