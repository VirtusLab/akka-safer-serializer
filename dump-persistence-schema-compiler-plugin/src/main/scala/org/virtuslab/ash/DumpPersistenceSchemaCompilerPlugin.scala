package org.virtuslab.ash

import java.io.File
import java.io.IOException

import scala.tools.nsc.Global
import scala.tools.nsc.plugins.Plugin
import scala.tools.nsc.plugins.PluginComponent

class DumpPersistenceSchemaCompilerPlugin(override val global: Global) extends Plugin {
  override val name: String = "dump-persistence-schema-plugin"
  override val description: String = ""

  //Placeholder options
  private val pluginOptions = new DumpPersistenceSchemaOptions("/tmp", verbose = false)

  override val components: List[PluginComponent] = List(
    new DumpPersistenceSchemaCompilerPluginComponent(pluginOptions, global))

  override def init(options: List[String], error: String => Unit): Boolean = {
    if (options.contains("-v"))
      pluginOptions.verbose = true

    val filename = options.find(_.startsWith("--file"))
    filename match {
      case Some(value) =>
        val split = value.split(" ", 2)
        try {
          val path = split(1)
          new File(path).getCanonicalPath
          pluginOptions.outputDir = path
          true
        } catch {
          case _: IndexOutOfBoundsException =>
            error("No directory specified")
            false
          case _: IOException =>
            error("Invalid path for output directory")
            false
        }
      case None => false
    }
  }

  override val optionsHelp: Option[String] = None
}
