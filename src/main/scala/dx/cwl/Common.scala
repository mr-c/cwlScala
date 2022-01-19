package dx.cwl

import dx.cwl.Utils.{translateOptional, translateOptionalArray, translateOptionalObject}
import org.w3id.cwl.cwl1_2.{CWLVersion, LoadListingEnum, SecondaryFileSchemaImpl}

import java.net.URI
import java.nio.file.Path
import scala.util.matching.Regex

/**
  * An identifier of the form [namespace]#frag, where frag is a '/'-delimited string. For frag "foo/bar/baz",
  * parent="foo/bar" and name="baz".
  * Note that two `Identifier`s are considered equal if their `frag`s are equal - namespaces are not considered.
  */
case class Identifier(namespace: Option[String], frag: String) {
  lazy val fullyQualifiedName: String = s"${namespace.getOrElse("")}#${frag}"

  lazy val parent: Option[String] = {
    if (frag.contains('/')) {
      Some(frag.substring(0, frag.lastIndexOf('/')))
    } else {
      None
    }
  }

  lazy val parentId: Option[Identifier] = {
    parent.map(p => Identifier(namespace, p))
  }

  def hasParent: Boolean = parent.isDefined

  lazy val name: String = {
    if (frag.contains('/')) {
      frag.substring(frag.lastIndexOf('/') + 1)
    } else {
      frag
    }
  }

  override def hashCode(): Int = frag.hashCode

  override def equals(obj: Any): Boolean = {
    (this, obj) match {
      case (Identifier(_, frag1), Identifier(_, frag2)) => frag1 == frag2
      case _                                            => false
    }
  }

  def equalsWithNamespace(that: Identifier): Boolean = {
    (this, that) match {
      case (Identifier(Some(ns1), frag1), Identifier(Some(ns2), frag2)) =>
        ns1 == ns2 && frag1 == frag2
      case _ => this.frag == that.frag
    }
  }
}

object Identifier {
  val CwlExtensions = Vector(".cwl", ".cwl.json", ".json")
  val MainFrag = "main"
  val MainId: Identifier = Identifier(namespace = None, frag = MainFrag)
  private val ImportNamespaceRegex = "^(.+\\.(?:cwl|yml|yaml))/(.+)".r

  def stripCwlExtension(fileName: String): String = {
    CwlExtensions
      .collectFirst {
        case ext if fileName.endsWith(ext) => fileName.dropRight(ext.length)
      }
      .getOrElse(fileName)
  }

  def fromUri(uri: URI): Identifier = {
    val (namespace, name) = Utils.normalizeAndSplitUri(uri)
    val frag = name.getOrElse(throw new Exception(s"uri ${uri} does not contain a fragment"))
    Identifier(namespace, frag)
  }

  def fromSource(source: Path, namespace: Option[String]): Identifier = {
    Identifier(namespace, stripCwlExtension(source.getFileName.toString))
  }

  def parseUri(uri: String,
               defaultNamespace: Option[String] = None): (Option[String], Option[String]) = {
    val (namespace, frag) =
      try {
        Utils.normalizeAndSplitUri(URI.create(uri))
      } catch {
        case _: IllegalArgumentException if uri.startsWith("_:") =>
          // this is a random id generated by the Java parser for an anonymous/inline process
          (None, Some(uri.drop(2)))
        case _: Throwable => Utils.splitUri(uri)
      }
    val (importNamespace, strippedFrag) = frag match {
      case Some(ImportNamespaceRegex(importNamespace, f)) =>
        // The frag may start with a different prefix, indicating the element was imported from another document -
        // try to parse out that prefix. If the uri is absolute, then prepend the existing namespace.
        (namespace.map(ns => s"${ns}#${importNamespace}").orElse(Some(importNamespace)), Some(f))
      case _ => (None, frag)
    }
    (importNamespace.orElse(namespace).orElse(defaultNamespace), strippedFrag)
  }

  def parse(uri: String, defaultNamespace: Option[String] = None): Identifier = {
    val (namespace, frag) = parseUri(uri, defaultNamespace)
    Identifier(namespace, frag.getOrElse(s"uri ${uri} does not contain a fragment"))
  }

  def get(id: java.util.Optional[String],
          defaultNamespace: Option[String],
          defaultFrag: Option[String] = None,
          source: Option[Path] = None): Option[Identifier] = {
    translateOptional(id).map {
      Identifier.parseUri(_, defaultNamespace) match {
        case (namespace, Some(frag))                 => Identifier(namespace, frag)
        case (namespace, _) if defaultFrag.isDefined => Identifier(namespace, defaultFrag.get)
        case (namespace, _) if source.isDefined      => fromSource(source.get, namespace)
        case _ =>
          throw new Exception(s"could not determine frag for id ${id}")
      }
    }
  }
}

object Document {
  type Document = Map[Identifier, Process]
  val empty: Document = Map.empty[Identifier, Process]

  implicit class DocumentAdder(doc: Document) {
    def addProcess(proc: Process): Document = {
      val id = proc.id
        .getOrElse(
            throw new Exception(s"process ${proc} has no ID")
        )
      if (!doc.contains(id)) {
        doc + (id -> proc)
      } else if (doc(id) == proc) {
        // two identical processes with the same id - most likely two separate imports of the same process
        doc
      } else {
        throw new Exception(
            s"two different processes have the same ID ${id}: ${doc(id)} != ${proc}"
        )
      }
    }
  }
}

trait Identifiable {
  val id: Option[Identifier]

  def getName: Option[String] = id.map(_.frag)

  def hasName: Boolean = getName.isDefined

  def frag: String = id.map(_.frag).getOrElse(throw new Exception(s"${this} has no ID"))

  def parent: Option[String] = {
    if (hasName) {
      id.flatMap(_.parent)
    } else {
      throw new Exception(s"${this} has no name")
    }
  }

  def name: String = {
    id.map(_.name).getOrElse(throw new Exception(s"${this} has no name"))
  }
}

trait Meta extends Identifiable {
  val label: Option[String]
  val doc: Option[String]
}

trait Loadable {
  val loadContents: Boolean
  val loadListing: LoadListing.LoadListing
}

trait Parameter extends Meta {
  val cwlType: CwlType
  val secondaryFiles: Vector[SecondaryFile]
  val streamable: Boolean
}

trait InputParameter extends Parameter with Loadable {
  val default: Option[CwlValue]
  val format: Vector[CwlValue]
}

trait OutputParameter extends Parameter {
  val format: Option[CwlValue]
}

/**
  * Marker trait for top-level elements (CommandLineTool, Workflow, ExpressionTool, etc)
  */
trait Process extends Meta {
  val source: Option[String]
  val cwlVersion: Option[CWLVersion]
  val label: Option[String]
  val doc: Option[String]
  val intent: Vector[String]
  val inputs: Vector[InputParameter]
  val outputs: Vector[OutputParameter]
  val requirements: Vector[Requirement]
  val hints: Vector[Hint]

  // packing a workflow with `cwlpack --add-ids` automatically adds any missing IDs of the form
  // `(<workflow_filename>:step_<step_id>:)?<tool_filename>.cwl`. This function strips off the
  // prefix (if any) and the .cwl suffix.
  def simpleName: String = Process.simplifyName(name)

  def copySimplifyId: Process
}

object Process {
  val autoNameRegex: Regex = "([^@]+)@step_([^@]+)@(.+?)".r

  def simplifyName(name: String): String = {
    Identifier.stripCwlExtension(name match {
      case Process.autoNameRegex(_, stepName, "run") =>
        // anonymous process - prepend the step name to increase the chance it is unique
        s"${stepName}_run"
      case Process.autoNameRegex(_, _, fileName) => fileName
      case _                                     => name
    })
  }

  def simplifyId(id: Identifier): Identifier = {
    id.copy(namespace = None, frag = simplifyName(id.name))
  }
}

// https://www.commonwl.org/v1.2/CommandLineTool.html#SecondaryFileSchema
case class SecondaryFile(pattern: CwlValue, required: CwlValue)

object SecondaryFile {
  def apply(secondaryFile: SecondaryFileSchemaImpl,
            schemaDefs: Map[String, CwlSchema],
            isInput: Boolean): SecondaryFile = {
    val required = translateOptionalObject(secondaryFile.getRequired)
      .map(CwlValue(_, schemaDefs))
      .getOrElse(BooleanValue(isInput))
    SecondaryFile(CwlValue(secondaryFile.getPattern, schemaDefs), required)
  }

  def applyArray(secondaryFiles: java.lang.Object,
                 schemaDefs: Map[String, CwlSchema],
                 isInput: Boolean): Vector[SecondaryFile] = {
    translateOptionalArray(secondaryFiles).map {
      case sf: SecondaryFileSchemaImpl => SecondaryFile(sf, schemaDefs, isInput)
      case other =>
        throw new RuntimeException(s"unexpected SecondaryFile value ${other}")
    }
  }
}

// https://www.commonwl.org/v1.2/CommandLineTool.html#LoadListingEnum
object LoadListing extends Enumeration {
  type LoadListing = Value
  val No, Shallow, Deep = Value

  def from(loadListing: LoadListingEnum): LoadListing = {
    loadListing match {
      case LoadListingEnum.NO_LISTING      => No
      case LoadListingEnum.SHALLOW_LISTING => Shallow
      case LoadListingEnum.DEEP_LISTING    => Deep
    }
  }
}
