import sbtrelease.ReleaseStateTransformations._

sbtPlugin := true

name := "sbt-avro"

organization := "com.iadvize"

scalaVersion := "2.12.4"

sbtVersion in Global := "1.0.4"

scalaCompilerBridgeSource := {
  val sv = appConfiguration.value.provider.id.version
  ("org.scala-sbt" % "compiler-interface" % sv % "component").sources
}

sbtPlugin := true

resolvers += "confluent" at "http://packages.confluent.io/maven"

libraryDependencies ++= Seq(
  "org.apache.avro" % "avro-compiler"                % "1.8.2",
  "io.confluent"    % "kafka-schema-registry-client" % "3.3.0"
)

crossSbtVersions := Vector("0.13.16", "1.0.0")

releaseCrossBuild := true

publishMavenStyle := false
bintrayOrganization := Some("iduffy")
bintrayRepository := "sbt-plugins"

ScriptedPlugin.scriptedSettings

scriptedSettings

scriptedBufferLog := false

scriptedLaunchOpts ++= Seq("-Xmx1G", "-Dplugin.version=" + version.value)

releaseProcess := Seq[ReleaseStep](
  checkSnapshotDependencies,
  inquireVersions,
  runClean,
  releaseStepCommandAndRemaining("^ test"),
  setReleaseVersion,
  commitReleaseVersion,
  tagRelease,
  releaseStepCommandAndRemaining("^ publish"),
  setNextVersion,
  commitNextVersion,
  pushChanges
)
