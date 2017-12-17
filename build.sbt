sbtPlugin := true

name := "sbt-avro"

organization := "com.iadvize"

scalaVersion := "2.12.3"

sbtPlugin := true

resolvers += "confluent" at "http://packages.confluent.io/maven"

libraryDependencies ++= Seq(
  "org.apache.avro" % "avro-compiler"                % "1.8.2",
  "io.confluent"    % "kafka-schema-registry-client" % "3.3.0"
)

crossScalaVersions := Seq(scalaVersion.value, "2.11.11", "2.10.6")

publishMavenStyle := false
bintrayRepository := "sbt-plugins"
bintrayPackageLabels := Seq("sbt","plugin")
licenses += ("MIT", url("http://opensource.org/licenses/MIT"))

scriptedBufferLog := false
scriptedLaunchOpts ++= Seq(
  "-Xmx1024M",
  s"-Dplugin.version=${version.value}")
