package org.virtuslab.ash

import scala.collection.mutable
import scala.tools.nsc.Global
import scala.tools.nsc.Phase
import scala.tools.nsc.plugins.PluginComponent

import org.virtuslab.ash.CodecRegistrationCheckerCompilerPlugin.classSweepPhaseName
import org.virtuslab.ash.CodecRegistrationCheckerCompilerPlugin.serializabilityTraitType

class ClassSweepCompilerPluginComponent(options: CodecRegistrationCheckerOptions, override val global: Global)
    extends PluginComponent {
  import global._
  override val phaseName: String = classSweepPhaseName
  override val runsAfter: List[String] = List("refchecks")
  override def description: String = s"searches for direct descendants of classes annotated with serializability trait"

  val foundTypes: mutable.Set[(String, String)] = mutable.Set()
  val typesToUpdate: mutable.Set[(String, String)] = mutable.Set()

  override def newPhase(prev: Phase): Phase =
    new StdPhase(prev) {
      private val up = options.oldTypes.groupBy(_._2)

      override def apply(unit: global.CompilationUnit): Unit = {
        val body = unit.body
        foundTypes ++= body
          .collect {
            case x: ClassDef => x.impl
          }
          .flatMap(x => x.parents.map((_, x)))
          .filter(_._1.symbol.annotations.map(_.tpe.toString()).contains(serializabilityTraitType))
          .map(x => (x._1.tpe.typeSymbol.fullName, x._2.tpe.typeSymbol.fullName))
        typesToUpdate ++= body
          .collect {
            case x: ClassDef => x.impl.tpe.typeSymbol.fullName
          }
          .flatMap(up.get)
          .flatten
      }
    }
}
