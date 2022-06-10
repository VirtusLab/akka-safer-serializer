package org.virtuslab.ash

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.regex.PatternSyntaxException

import scala.annotation.tailrec
import scala.collection.mutable
import scala.reflect.ClassTag
import scala.reflect.classTag
import scala.tools.nsc.Global
import scala.tools.nsc.Phase
import scala.tools.nsc.plugins.PluginComponent

import org.virtuslab.ash.CodecRegistrationCheckerCompilerPlugin.classSweepPhaseName
import org.virtuslab.ash.CodecRegistrationCheckerCompilerPlugin.serializabilityTraitType
import org.virtuslab.ash.CodecRegistrationCheckerCompilerPlugin.serializerCheckPhaseName
import org.virtuslab.ash.CodecRegistrationCheckerCompilerPlugin.serializerType

class SerializerCheckCompilerPluginComponent(
    classSweep: ClassSweepCompilerPluginComponent,
    options: CodecRegistrationCheckerOptions,
    override val global: Global)
    extends PluginComponent {
  import global._
  override val phaseName: String = serializerCheckPhaseName
  override val runsAfter: List[String] = List(classSweepPhaseName)
  override def description: String =
    s"Checks marked serializer for references to classes found in $serializerCheckPhaseName"

  private var typesNotDumped = true
  private val typeNamesToCheck =
    mutable.Map[String, List[ParentChildFullyQualifiedClassNamePair]]().withDefaultValue(Nil)

  private lazy val classSweepFoundTypeNamePairs = classSweep.foundParentChildFullyQualifiedClassNamePairs.toSet
  private lazy val classSweepTypeNamePairsToUpdate = classSweep.parentChildFullyQualifiedClassNamePairsToUpdate.toSet

  override def newPhase(prev: Phase): Phase = {
    new StdPhase(prev) {
      override def apply(unit: global.CompilationUnit): Unit = {
        if (typesNotDumped) {
          val raf = new RandomAccessFile(options.directClassDescendantsCacheFile, "rw")
          try {
            val channel = raf.getChannel
            val lock = channel.lock()
            try {
              val buffer = ByteBuffer.allocate(channel.size().toInt)
              channel.read(buffer)

              val parentChildFullyQualifiedClassNamePairsFromCacheFile =
                CodecRegistrationCheckerCompilerPlugin.parseCacheFile(buffer.rewind()).toSet
              val outParentChildFullyQualifiedClassNamePairs =
                ((parentChildFullyQualifiedClassNamePairsFromCacheFile -- classSweepTypeNamePairsToUpdate) |
                classSweepFoundTypeNamePairs).toList

              val outData: String =
                outParentChildFullyQualifiedClassNamePairs
                  .map(pair => pair.parentFullyQualifiedClassName + "," + pair.childFullyQualifiedClassName)
                  .sorted
                  .reduceOption(_ + "\n" + _)
                  .getOrElse("")
              channel.truncate(0)
              channel.write(ByteBuffer.wrap(outData.getBytes(StandardCharsets.UTF_8)))

              typeNamesToCheck ++= outParentChildFullyQualifiedClassNamePairs.groupBy(_.parentFullyQualifiedClassName)
              typesNotDumped = false
            } finally {
              lock.close()
            }

          } finally {
            raf.close()
          }
        }

        unit.body
          .collect {
            case implDef: ImplDef => (implDef, implDef.symbol.annotations)
          }
          .map(implDefAnnotationsTuple =>
            (implDefAnnotationsTuple._1, implDefAnnotationsTuple._2.filter(_.tpe.toString == serializerType)))
          .filter(_._2.nonEmpty)
          .foreach { implDefAnnotationsTuple =>
            val (implDef, annotations) = implDefAnnotationsTuple
            if (annotations.size > 1) {
              reporter.warning(
                implDefAnnotationsTuple._2.head.pos,
                s"Class can only have one @Serializer annotation. Currently it has ${annotations.size}. Using the one found first.")
            }
            processSerializerClass(implDef, annotations.head)
          }
      }

      private def processSerializerClass(serializerImplDef: ImplDef, serializerAnnotation: AnnotationInfo): Unit = {
        val (fullyQualifiedClassName, filterRegex) = serializerAnnotation.args match {
          case List(clazzTree, regexTree) =>
            val fullyQualifiedClassNameOption = extractValueOfLiteralConstantFromTree[Type](clazzTree).flatMap { tpe =>
              if (tpe.typeSymbol.annotations.map(_.tpe.toString()).contains(serializabilityTraitType))
                Some(tpe.typeSymbol.fullName)
              else {
                reporter.error(
                  serializerAnnotation.pos,
                  s"Type given as `clazz` argument to @$serializerType must be annotated with $serializabilityTraitType")
                None
              }
            }
            val filterRegexOption =
              regexTree match {
                case Select(_, TermName("$lessinit$greater$default$2")) => Some(".*")
                case other                                              => extractValueOfLiteralConstantFromTree[String](other)
              }

            (fullyQualifiedClassNameOption, filterRegexOption) match {
              case (Some(fullyQualifiedClassName), Some(regex)) => (fullyQualifiedClassName, regex)
              case _                                            => return
            }

          case _ => throw new IllegalStateException()
        }

        val foundTypes = {
          try {
            serializerImplDef
              .collect {
                case tree: Tree if tree.tpe != null => tree.tpe
              }
              .distinct
              .filter(_.toString.matches(filterRegex))
          } catch {
            case e: PatternSyntaxException =>
              reporter.error(serializerImplDef.pos, "Exception throw during the use of filter regex: " + e.getMessage)
              return
          }
        }

        @tailrec
        def collectTypeArgs(current: Set[Type], prev: Set[Type] = Set.empty): Set[Type] = {
          val next = current.flatMap(_.typeArgs)
          val acc = prev | current
          if ((next &~ acc).isEmpty)
            acc
          else
            collectTypeArgs(next, acc)
        }

        val fullyQualifiedClassNamesFromFoundTypes = collectTypeArgs(foundTypes.toSet).map(_.typeSymbol.fullName)

        val missingFullyQualifiedClassNames =
          typeNamesToCheck(fullyQualifiedClassName)
            .map(_.childFullyQualifiedClassName)
            .filterNot(fullyQualifiedClassNamesFromFoundTypes)
        if (missingFullyQualifiedClassNames.nonEmpty) {
          reporter.error(
            serializerImplDef.pos,
            s"""No codecs for ${missingFullyQualifiedClassNames
              .mkString(", ")} are registered in class annotated with @$serializerType.
               |This will lead to a missing codec for Akka serialization in the runtime.
               |Current filtering regex: $filterRegex""".stripMargin)
        }
      }

      @tailrec
      private def extractValueOfLiteralConstantFromTree[A: ClassTag: TypeTag](tree: Tree): Option[A] = {
        tree match {
          case Typed(literal, tpeTree) if tpeTree.tpe =:= typeOf[A] =>
            extractValueOfLiteralConstantFromTree[A](literal)
          case literal @ Literal(Constant(value)) =>
            value match {
              case res: A => Some(res)
              case other =>
                reporter.error(
                  literal.pos,
                  s"Annotation argument must have a type during compilation of [${classTag[
                    A].runtimeClass.toString}]. Current type is [${other.getClass.toString}]")
                None
            }
          case other =>
            reporter.error(
              other.pos,
              s"Annotation argument must be a literal constant. Currently: ${other.summaryString}")
            None
        }
      }
    }
  }
}
