# Akka Serialization Helper

[![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.virtuslab.ash/sbt-akka-serialization-helper/badge.svg)](https://maven-badges.herokuapp.com/maven-central/org.virtuslab.ash/sbt-akka-serialization-helper)
[![Github Actions](https://github.com/VirtusLab/akka-serialization-helper/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/VirtusLab/akka-serialization-helper/actions)
[![License: MIT](https://img.shields.io/github/license/VirtusLab/akka-serialization-helper)](https://github.com/VirtusLab/akka-serialization-helper/blob/master/LICENSE)

![logo_ash_horizontal@4x](https://user-images.githubusercontent.com/25779550/135059025-4cfade5b-bfcb-47e8-872f-8a3d78ce0c25.png)

Serialization toolbox for Akka messages, events and persistent state that helps achieve compile-time guarantee on
serializability.

## Install

Add the following line to `plugins.sbt` (take `Version` from the above maven badge or [GitHub Releases](https://github.com/VirtusLab/akka-serialization-helper/releases)):

```scala
addSbtPlugin("org.virtuslab.ash" % "sbt-akka-serialization-helper" % Version)
```

and enable the sbt plugin in the target project:
```scala
lazy val app = (project in file("app"))
  .enablePlugins(AkkaSerializationHelperPlugin)
```

## Features

### 1. Serializability Checker

A Scala compiler plugin that detects messages, events and persistent states, and checks whether they extend the given base
trait and report an error when they don't. This ensures that the specified serializer is used by Akka and protects
against an accidental use
of [Java serialization](https://doc.akka.io/docs/akka/current/serialization.html#java-serialization) or outright serialization failure.

To use, annotate your base trait with `@org.virtuslab.ash.SerializabilityTrait`:

```scala
@SerializabilityTrait
trait MySerializable
```

It allows to catch errors like these:
```scala
import akka.actor.typed.Behavior

object BehaviorTest {
  sealed trait Command //extends MySerializable
  def method(msg: Command): Behavior[Command] = ???
}
```

And results in a compile error, preventing non-runtime-safe code from being executed:
```
test0.scala:7: error: org.random.project.BehaviorTest.Command is used as Akka message but does not extend a trait annotated with org.virtuslab.ash.annotation.SerializabilityTrait.
Passing an object of class NOT extending SerializabilityTrait as a message may cause Akka to fall back to Java serialization during runtime.


  def method(msg: Command): Behavior[Command] = ???
                            ^
test0.scala:6: error: Make sure this type is itself annotated, or extends a type annotated with  @org.virtuslab.ash.annotation.SerializabilityTrait.
  sealed trait Command extends MySerializable
               ^
```

The compiler plugin only checks the classes in the sbt modules where `AkkaSerializationHelperPlugin` is explicitly enabled.
It may happen that your base trait (like `MySerializable` in the example) lives in an sbt module like `core` where you do **not** want to enable the plugin
(e.g. for compilation performance reasons).
You still need to reference `org.virtuslab.ash.SerializabilityTrait` to annotate `MySerializable`, however.
In order to have access to the `SerializabilityTrait` annotation without enabling the entire suite of compiler plugins,
add `AkkaSerializationHelperPlugin.annotation` to `libraryDependencies`:

```scala
import org.virtuslab.ash.AkkaSerializationHelperPlugin

lazy val core = (project in file("core"))
  .settings(libraryDependencies += AkkaSerializationHelperPlugin.annotation)
```

### 2. Dump Persistence Schema

A mix of a compiler plugin and an sbt task for dumping schema
of [akka-persistence](https://doc.akka.io/docs/akka/current/typed/persistence.html#example-and-core-api) to a
file. It can be used for detecting accidental changes of events (journal) and states (snapshots) with a simple `diff`.

To dump persistence schema for each sbt module where `AkkaSerializationHelperPlugin` is enabled, run:

```shell
sbt ashDumpPersistenceSchema
```

Default file is `target/<sbt-module-name>-dump-persistence-schema-<version>.yaml` (`target/` of top-level module!) but it can be changed using sbt keys:
```scala
ashDumpPersistenceSchemaOutputFilename := "file.yaml" // Changes filename
ashDumpPersistenceSchemaOutputDirectoryPath := "~" // Changes directory
```

#### Example dump
```yaml
- name: org.random.project.Data
  typeSymbol: trait
- name: org.random.project.Data.ClassTest
  typeSymbol: class
  fields:
  - name: a
    typeName: java.lang.String
  - name: b
    typeName: scala.Int
  - name: c
    typeName: scala.Double
  parents:
  - org.random.project.Data
- name: org.random.project.Data.ClassWithAdditionData
  typeSymbol: class
  fields:
  - name: ad
    typeName: org.random.project.Data.AdditionalData
  parents:
  - org.random.project.Data
```

### 3. Serializer

[Circe-based](https://circe.github.io/circe/) Akka serializer. It uses Circe codecs, derived using [Shapeless](https://circe.github.io/circe/codecs/auto-derivation.html),
that are generated during compile time (so serializer won't crash during runtime like reflection-based serializers may do).
For a comparison of Circe with other serializers, read [Appendix A](#appendix-a-comparison-of-available-akka-serializers).

Note that it is **not** obligatory to use our serializer for the other features (serializability checker, persistence schema dump) to work.
They work as well with e.g. when [Jackson serializer](https://doc.akka.io/docs/akka/current/serialization-jackson.html) is selected.

#### Usage

Add the following to project dependencies:

```scala
import org.virtuslab.ash.AkkaSerializationHelperPlugin

lazy val app = (project in file("app"))
  // ...
  .settings(libraryDependencies += AkkaSerializationHelperPlugin.circeAkkaSerializer)
```

Note that enabling `AkkaSerializationHelperPlugin` does **not** add Circe serializer to the classpath automatically,
as this sbt plugin can be used to improve safety of other serializers as well.

Create a custom serializer by extending `CirceAkkaSerializer` base class:
```scala
import org.virtuslab.ash.circe.CirceAkkaSerializer

class ExampleSerializer(actorSystem: ExtendedActorSystem)
    extends CirceAkkaSerializer[MySerializable](actorSystem) {

  override def identifier: Int = 41

  override lazy val codecs = Seq(Register[CommandOne], Register[CommandTwo])

  override lazy val manifestMigrations = Nil

  override lazy val packagePrefix = "org.project"
}
```

For more guidelines on how to use the serializer,
read [Akka documentation about serialization](https://doc.akka.io/docs/akka/current/serialization.html),
[`CirceAkkaSerializer`](https://github.com/VirtusLab/akka-serialization-helper/blob/main/circe-akka-serializer/src/main/scala/org/virtuslab/ash/circe/CirceAkkaSerializer.scala) Scaladoc
and look at the [examples](https://github.com/VirtusLab/akka-serialization-helper/tree/main/examples).

### 4. Codec Registration Checker

Compiler plugin for checking whether all codecs are registered.
During compilation, it gathers all direct descendants of the class marked with `@org.virtuslab.ash.SerializabilityTrait`
and checks the body of classes annotated with `@org.virtuslab.ash.Serializer` if they reference all these direct descendants in any way.

In practice, this is used for checking a class extending `CirceAkkaSerializer`, like this:

```scala
import org.virtuslab.ash.circe.CirceAkkaSerializer
import org.virtuslab.ash.circe.Register

@Serializer(
  classOf[MySerializable],
  typeRegexPattern = Register.REGISTRATION_REGEX)
class ExampleSerializer(actorSystem: ExtendedActorSystem)
  extends CirceAkkaSerializer[MySerializable](actorSystem) {
    // ...
    override lazy val codecs = Seq(Register[CommandOne]) // WHOOPS someone forgot to register CommandTwo...
    // ... but Codec Registration Checker will throw a compilation error here:
    // `No codec for `CommandOne` is registered in class annotated with @org.virtuslab.ash.annotation.Serializer`
}
```

Note that as with Serializability Checker and Dump Persistence Schema,
this compiler plugin only runs in the sbt modules where `AkkaSerializationHelperPlugin` is explicitly enabled.

For more information, read [`@Serializer` scaladoc](https://github.com/VirtusLab/akka-serialization-helper/blob/main/annotation/src/main/scala/org/virtuslab/ash/annotation/Serializer.scala).

### 5. Additional configuration for compiler plugins

You can enable/disable all compiler plugins and enable/disable their verbose mode using two sbt keys:

```scala
ashCompilerPluginEnable := false // default is true
ashCompilerPluginVerbose := true // default is false
```

This can be done for all compiler plugins, like above, or just one:
```scala
ashCodecRegistrationCheckerCompilerPlugin / ashCompilerPluginEnable := false
ashDumpPersistenceSchemaCompilerPlugin / ashCompilerPluginVerbose := true
```

Additionally, `Compile` and `Test` scope can be specified:

```scala
Compile / ashDumpPersistenceSchemaCompilerPlugin / ashCompilerPluginVerbose := true
Test / ashCompilerPluginEnable := false
```

For full list of sbt keys, check [`org.virtuslab.ash.AkkaSerializationHelperKeys`](https://github.com/VirtusLab/akka-serialization-helper/blob/main/sbt-akka-serialization-helper/src/main/scala/org/virtuslab/ash/AkkaSerializationHelperKeys.scala).


## Appendix A: Comparison of available Akka Serializers

| Serializer             | [Jackson](https://github.com/FasterXML/jackson)                                                                                                                                                                                                                                                                                                                                                 | [Circe](https://circe.github.io/circe/)                                                                                        | [Protobuf v3](https://developers.google.com/protocol-buffers)                                  | [Avro](https://avro.apache.org/docs/current/)                                                    | [Borer](https://github.com/sirthias/borer)                                                                                        | [Kryo](https://github.com/EsotericSoftware/kryo)                                                                                                             |
|:-----------------------|:------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|:-------------------------------------------------------------------------------------------------------------------------------|:-----------------------------------------------------------------------------------------------|:-------------------------------------------------------------------------------------------------|:----------------------------------------------------------------------------------------------------------------------------------|:-------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Data formats           | JSON or [CBOR](https://cbor.io)                                                                                                                                                                                                                                                                                                                                                                 | JSON                                                                                                                           | JSON or custom binary                                                                          | JSON or custom binary                                                                            | JSON or CBOR                                                                                                                      | custom binary                                                                                                                                                |
| Scala support          | very poor, even with [jackson-module-scala](https://github.com/FasterXML/jackson-module-scala): <ul><li>poor support for Scala objects, without configuration creates new instances of singleton types (`Foo$`), breaking pattern matching</li><li>lacks support of basic scala types like `Unit`</li><li>without explicit annotation doesn't work with generics extending `AnyVal`</li></ul>   | perfect out of the box                                                                                                         | perfect with [ScalaPB](https://scalapb.github.io)                                              | perfect with [Avro4s](https://github.com/sksamuel/avro4s)                                        | perfect out of the box                                                                                                            | perfect out of the box                                                                                                                                       |
| Akka support           | [akka-serialization-jackson](https://doc.akka.io/docs/akka/current/serialization-jackson.html)                                                                                                                                                                                                                                                                                                  | [serializer provided by this project](#3-serializer)                                                                           | used by [akka-remote](https://doc.akka.io/docs/akka/current/serialization.html) internally     | requires custom serializer                                                                       | requires custom serializer                                                                                                        | [akka-kryo](https://github.com/altoo-ag/akka-kryo-serialization)                                                                                             |
| Compile-time mechanics | nothing happens in compile time; everything based on runtime reflection                                                                                                                                                                                                                                                                                                                         | derives codecs via [Shapeless](https://circe.github.io/circe/codecs/auto-derivation.html)                                      | with ScalaPB, generates Scala classes based on \*.proto files                                  | with Avro4s, derives Avro schemas using [Magnolia](https://github.com/softwaremill/magnolia)     | derives codecs **without** Magnolia                                                                                               | with akka-kryo, optionally derives codecs in compile time, but otherwise uses reflection in runtime                                                          |
| Runtime safety         | none, uses reflection                                                                                                                                                                                                                                                                                                                                                                           | encoders and decoders are created during compilation                                                                           | \*.proto files are validated before compilation                                                | Avro schema is created during compilation                                                        | encoders and decoders are created during compilation                                                                              | depends on whether codecs were derived in compile time (then standard for Scala code), or not (than none)                                                    |
| Boilerplate            | a lot: <ul><li>ADTs requires amount of annotation equal to or exceeding the actual type definitions</li><li>requires explicit serializers and deserializers in certain cases (e.g. enums)</li></ul>                                                                                                                                                                                             | every top-level sealed trait must be registered manually - but see [Codec Registration Checker](#4-codec-registration-checker) | in case of custom types, a second layer of models is needed                                    | sometimes requires annotations                                                                   | every top-level sealed trait must be registered manually; every transitively included class must have an explicitly defined codec | every top-level sealed trait must be registered manually                                                                                                     |
| Schema evolution       | <ul><li>removing field</li><li>adding optional field</li></ul> with [`JacksonMigration`](https://doc.akka.io/docs/akka/current/serialization-jackson.html#schema-evolution): <ul><li>adding mandatory field</li><li>renaming field</li><li>renaming class</li><li>support of forward versioning for rolling updates</li></ul>| <ul><li>adding optional field</li><li>removing optional field</li><li>adding required field with default value</li><li>removing required field</li><li>renaming field</li><li>reordering fields</li><li>transforming data before deserialization</li></ul> | <ul><li>adding optional field</li><li>removing optional field</li><li>adding required field with default value</li><li>removing required field</li><li>renaming field</li><li>reordering fields</li><li>changing between compatible types</li></ul>  | <ul><li>reordering fields</li><li>renaming fields</li><li>adding optional field</li><li>adding required field with default value</li><li>removing field with default value</li></ul> | <ul><li>renaming fields</li><li>transforming data before deserialization</li></ul> | <ul><li>adding field</li><li>removing field</li><li>renaming field</li><li>renaming class</li></ul> |
