import sbt._

object Dependencies {
  val scalaVersion213 = "2.13.5"
  val scalaVersion212 = "2.12.13"
  val akkaVersion = "2.6.13"
  val borerVersion = "1.7.2"

  val scalaTest = "org.scalatest" %% "scalatest" % "3.2.2"
  val logger = "org.slf4j" % "slf4j-simple" % "1.7.30"
  val enumeratum = "com.beachape" %% "enumeratum" % "1.6.1"
  val reflections = "net.oneandone.reflections8" % "reflections8" % "0.11.7"

  val akkaTyped = "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion
  val akkaStream = "com.typesafe.akka" %% "akka-stream" % akkaVersion
  val akkaPersistence = "com.typesafe.akka" %% "akka-persistence-typed" % akkaVersion
  val akkaProjections = "com.lightbend.akka" %% "akka-projection-eventsourced" % "1.2.0"
  val akkaTestKit = "com.typesafe.akka" %% "akka-actor-testkit-typed" % akkaVersion
  val akkaStreamTestKit = "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion

  val borerCore = "io.bullet" %% "borer-core" % borerVersion
  val borerDerivation = "io.bullet" %% "borer-derivation" % borerVersion
  val borerAkka = "io.bullet" %% "borer-compat-akka" % borerVersion

  val scalaCompiler = "org.scala-lang" % "scala-compiler"
  val scalaLibrary = "org.scala-lang" % "scala-library"
  val scalaReflect = "org.scala-lang" % "scala-reflect"

  val commonDeps = Seq(scalaTest % Test, logger % Test)

  private val scalaPluginDeps = Seq(scalaCompiler, scalaLibrary, scalaReflect)
  val scalaPluginDeps213: Seq[ModuleID] = scalaPluginDeps.map(_ % scalaVersion213)
  val scalaPluginDeps212: Seq[ModuleID] = scalaPluginDeps.map(_ % scalaVersion212)
}