import Merging.customMergeStrategy
import sbt.Keys._
import sbt.ThisBuild
import sbtassembly.AssemblyPlugin.autoImport._
import com.typesafe.config._
import sbtghpackages.GitHubPackagesPlugin.autoImport.githubOwner

name := "cwlScala"

def getVersion: String = {
  val confPath =
    Option(System.getProperty("config.file")).getOrElse("src/main/resources/application.conf")
  val conf = ConfigFactory.parseFile(new File(confPath)).resolve()
  conf.getString("cwlScala.version")
}

ThisBuild / organization := "com.dnanexus"
ThisBuild / scalaVersion := "2.13.2"
ThisBuild / developers := List(
    Developer(
        "jdidion",
        "jdidion",
        "jdidion@dnanexus.com",
        url("https://github.com/dnanexus")
    )
)
ThisBuild / homepage := Some(url("https://github.com/dnanexus/cwlScala"))
ThisBuild / scmInfo := Some(
    ScmInfo(
        url("https://github.com/dnanexus/cwlScala"),
        "git@github.com:dnanexus/cwlScala"
    )
)
ThisBuild / licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0"))

lazy val root = project.in(file("."))
lazy val cwlScala = root.settings(
    name := "cwlScala",
    version := getVersion,
    settings,
    assemblySettings,
    libraryDependencies ++= dependencies,
    assemblyJarName in assembly := "cwlScala.jar"
)

lazy val dependencies = {
  val dxCommonVersion = "0.10.0"
  val dxYamlVersion = "0.1.0"
  val typesafeVersion = "1.4.1"
  val sprayVersion = "1.3.6"
  val scalatestVersion = "3.2.9"
  val yamlVersion = "2.3"
  val rhinoVersion = "1.7.13"
  val antlr4Version = "4.9.2"
  val junitVersion = "4.13.2"

  Seq(
      "com.dnanexus" % "dxcommon" % dxCommonVersion,
      "com.dnanexus" % "dxyaml" % dxYamlVersion,
      "com.typesafe" % "config" % typesafeVersion,
      "io.spray" %% "spray-json" % sprayVersion,
      // cwljava dependencies
      "org.snakeyaml" % "snakeyaml-engine" % yamlVersion,
      // rhino dependencies
      "org.mozilla" % "rhino" % rhinoVersion,
      // antlr4 dependencies
      "org.antlr" % "antlr4" % antlr4Version,
      //---------- Test libraries -------------------//
      "junit" % "junit" % junitVersion % Test,
      "org.scalatest" % "scalatest_2.13" % scalatestVersion % Test
  )
}

val githubDxScalaResolver = Resolver.githubPackages("dnanexus", "dxScala")
val githubCwlScalaResolver = Resolver.githubPackages("dnanexus", "cwlScala")
resolvers ++= Vector(githubCwlScalaResolver, githubDxScalaResolver)

val releaseTarget = Option(System.getProperty("releaseTarget")).getOrElse("github")

lazy val settings = Seq(
    scalacOptions ++= compilerOptions,
    // exclude Java sources from scaladoc
    scalacOptions in (Compile, doc) ++= Seq("-no-java-comments", "-no-link-warnings"),
    javacOptions ++= Seq("-Xlint:deprecation"),
    // Add Java sources
    Compile / unmanagedSourceDirectories += baseDirectory.value / "cwljava" / "src" / "main" / "java",
    // reduce the maximum number of errors shown by the Scala compiler
    maxErrors := 20,
    // scalafmt
    scalafmtConfig := root.base / ".scalafmt.conf",
    // disable publish with scala version, otherwise artifact name will include scala version
    // e.g wdlTools_2.11
    crossPaths := false,
    // add sonatype repository settings
    // snapshot versions publish to sonatype snapshot repository
    // other versions publish to sonatype staging repository
    publishTo := Some(
        if (isSnapshot.value || releaseTarget == "github") {
          githubCwlScalaResolver
        } else {
          Opts.resolver.sonatypeStaging
        }
    ),
    githubOwner := "dnanexus",
    githubRepository := "cwlScala",
    publishMavenStyle := true,
    // If an exception is thrown during tests, show the full
    // stack trace, by adding the "-oF" option to the list.
    //
    // exclude the native tests, they are slow.
    // to do this from the command line:
    // sbt testOnly -- -l native
    //
    // comment out this line in order to allow native
    // tests
    // Test / testOptions += Tests.Argument("-l", "native")
    Test / testOptions += Tests.Argument("-oF"),
    Test / parallelExecution := false

    // Coverage
    //
    // sbt clean coverage test
    // sbt coverageReport
    // To turn it off do:
    // sbt coverageOff
    //coverageEnabled := true
    // Ignore code parts that cannot be checked in the unit
    // test environment
    //coverageExcludedPackages := "dxWDL.Main;dxWDL.compiler.DxNI;dxWDL.compiler.DxObjectDirectory;dxWDL.compiler.Native"
)

// Show deprecation warnings
val compilerOptions = Seq(
    "-unchecked",
    "-deprecation",
    "-feature",
    "-explaintypes",
    "-encoding",
    "UTF-8",
    "-Xlint:constant",
    "-Xlint:delayedinit-select",
    "-Xlint:doc-detached",
    "-Xlint:inaccessible",
    "-Xlint:infer-any",
    "-Xlint:nullary-override",
    "-Xlint:nullary-unit",
    "-Xlint:option-implicit",
    "-Xlint:package-object-classes",
    "-Xlint:poly-implicit-overload",
    "-Xlint:private-shadow",
    "-Xlint:stars-align",
    "-Xlint:type-parameter-shadow",
    "-Ywarn-dead-code",
    "-Ywarn-unused:implicits",
    "-Ywarn-unused:privates",
    "-Ywarn-unused:locals",
    "-Ywarn-unused:imports", // warns about every unused import on every command.
    "-Xfatal-warnings" // makes those warnings fatal.
)

// Assembly
lazy val assemblySettings = Seq(
    logLevel in assembly := Level.Info,
    // comment out this line to enable tests in assembly
    test in assembly := {},
    assemblyMergeStrategy in assembly := {
      {
        case PathList("javax", "xml", xs @ _*)               => MergeStrategy.first
        case PathList("org", "w3c", "dom", "TypeInfo.class") => MergeStrategy.first
        case PathList("META_INF", xs @ _*) =>
          xs map { _.toLowerCase } match {
            case "manifest.mf" :: Nil | "index.list" :: Nil | "dependencies" :: Nil =>
              MergeStrategy.discard
            case _ => MergeStrategy.last
          }
        case x =>
          customMergeStrategy.value(x)
      }
    }
)
