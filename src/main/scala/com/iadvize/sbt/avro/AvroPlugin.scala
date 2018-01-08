package com.iadvize.sbt.avro

import java.io.File

import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient
import java.nio.file.{Files, Path, Paths}

import avrohugger.Generator
import avrohugger.format.Standard
import org.apache.avro
import org.apache.avro.Schema.Parser
import org.json4s

import scala.collection.JavaConverters._
import sbt._
import sbt.Keys._
import org.json4s._
import org.json4s.native.JsonMethods._

import scala.util.{Failure, Success, Try}

case class Version(version: Int)

object Version {

  val Last = Version(-1)

}

case class Schema(subject: String, version: Version)

object AvroPlugin extends AutoPlugin {

  implicit val formats = DefaultFormats

  object autoImport {

    lazy val Avro = config("avro") extend Compile

    val download = taskKey[Seq[File]]("Download schemas from the registry.")
    val upload = taskKey[Unit]("Upload schemas to thee registry")
    val generate = taskKey[Seq[File]]("Generate Scala classes from schemas.")

    val schemaRegistryEndpoint = settingKey[String]("Schema registry endpoint, defaults to http://localhost:8081.")
    val schemas = settingKey[Seq[Schema]]("List of schemas to download, an empty list will download latest version of all schemas, defaults to an empty list.")
    val directoryName = settingKey[String]("Name of the directories which will contain Avro files, defaults to avro.")
    val classPathTemplatesDirectory = settingKey[String]("Name of the directory containing the templates used to generate the Scala files, defaults to /template/avro/.")

  }

  import autoImport._

  lazy val baseAvroSettings = Seq(
    directoryName := "avro",
    classPathTemplatesDirectory := "/template/avro/",
    schemaRegistryEndpoint := "http://localhost:8081",
    schemas := Seq.empty[Schema],
    resourceManaged := (resourceManaged in Compile).value / directoryName.value,
    resourceDirectory := (resourceDirectory in Compile).value / directoryName.value,
    sourceManaged := (sourceManaged in Compile).value / directoryName.value,
    download := downloadTask.value,
    upload := uploadTask.value,
    generate := generateTask.value,

    resourceGenerators in Compile += download.taskValue,
    managedResourceDirectories in Compile += (resourceManaged in Avro).value,

    sourceGenerators in Compile += generate.taskValue,
    managedSourceDirectories in Compile += (sourceManaged in Avro).value,

    cleanFiles ++= Seq(sourceManaged.value, resourceManaged.value)
  )

  lazy val downloadTask = Def.task {
    val logger = streams.value.log

    val configuredSchemas = schemas.value
    val configuredResourceManaged = resourceManaged.value
    val configuredSchemaRegistryEndpoint = schemaRegistryEndpoint.value

    val schemaRegistryClient = new CachedSchemaRegistryClient(configuredSchemaRegistryEndpoint, 10000)

    val schemasToDownload = if (configuredSchemas.isEmpty) {
      schemaRegistryClient.getAllSubjects.asScala.map(subject => Schema(subject, Version.Last))
    } else configuredSchemas

    logger.info("About to download:\n" + schemasToDownload.map(schema => "  " + schema.subject + " " + (if (schema.version == Version.Last) "latest" else "v" + schema.version)).mkString("\n") + "\nfrom " + configuredSchemaRegistryEndpoint)

    Files.createDirectories(configuredResourceManaged.toPath)

    schemasToDownload
      .map {
        case Schema(subject, Version.Last) => (subject, schemaRegistryClient.getLatestSchemaMetadata(subject))
        case Schema(subject, Version(version)) => (subject, schemaRegistryClient.getSchemaMetadata(subject, version))
      }
      .map { case (subject, schema) =>
        val path = Paths.get(configuredResourceManaged.absolutePath, subject + ".avsc")
        val writer = Files.newBufferedWriter(path)
        writer.write(schema.getSchema)
        writer.close()
        path.toFile
      }
      .toSeq
  }

  lazy val uploadTask = Def.task {
    val logger = streams.value.log

    val configuredSchemaRegistryEndpoint = schemaRegistryEndpoint.value
    val schemaRegistryClient = new CachedSchemaRegistryClient(configuredSchemaRegistryEndpoint, 10000)

    val schemasToRegister = parseSchemas(logger, resourceDirectory.value.toPath)

    schemasToRegister.foreach {
      case(subject: String, (file: File, schema: avro.Schema)) =>
        logger.info(s"Calling register $subject ${file.getAbsolutePath}")
        schemaRegistryClient.register(subject, schema)
    }
  }

  lazy val generateTask = Def.task {
    val logger = streams.value.log

    val configuredSourceManaged = sourceManaged.value

    val schemasToGenerate: Map[String, (File, avro.Schema)] = parseSchemas(logger, resourceManaged.value.toPath) ++
      parseSchemas(logger, resourceDirectory.value.toPath)

    Files.createDirectories(configuredSourceManaged.toPath)

    val scalaV = scalaVersion.value
    val isNumberOfFieldsRestricted = scalaV == "2.10"
    val gen = Generator(format = Standard,
      restrictedFieldNumber = isNumberOfFieldsRestricted)

    val parser = new avro.Schema.Parser()

    schemasToGenerate.foreach {
      case (_, (_, schema)) =>
        parse(schema.toString()).extractOpt[List[JValue]] match {
          case Some(list) =>
            list.foreach {jvalue =>
              gen.schemaToFile(parser.parse(compact(render(jvalue))), configuredSourceManaged.getPath)
            }
          case _ =>
            gen.schemaToFile(schema, configuredSourceManaged.getPath)
        }
    }

    (configuredSourceManaged ** ("*.java" | "*.scala")).get.distinct
  }

  private def parseSchemas(logger: Logger, path: Path) = {
    val parser = new Parser()
    if (path.toFile.exists())
      Files.newDirectoryStream(path).iterator().asScala.flatMap { schemaPath =>
        Try(parser.parse(schemaPath.toFile)) match {
          case Success(schema) =>
            Some(getFilename(schemaPath) -> (schemaPath.toFile, schema))
          case Failure(ex) =>
            logger.error(s"Can't parse schema $schemaPath, got error: ${ex.getMessage}")
            None
        }
      }.toMap
    else Map.empty[String, (File, avro.Schema)]
  }

  private def getFilename(path: Path): String = {
    val filename = path.getFileName.toString
    if (filename.indexOf(".") > 0) filename.substring(0, filename.lastIndexOf("."))
    else filename
  }

  override def requires = sbt.plugins.JvmPlugin

  override def trigger = allRequirements

  override val projectSettings = inConfig(Avro)(baseAvroSettings)

}
