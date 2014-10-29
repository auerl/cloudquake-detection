import AssemblyKeys._

assemblySettings

name := "CloudQuake Spark Consumer"

version := "1.0"

scalaVersion := "2.10.4"

jarName in assembly := "cloudquake-spark-consumer_2.10-1.0.jar"

assemblyOption in assembly ~= { _.copy(includeScala = false) }


libraryDependencies ++= Seq(
  "org.apache.spark" %% "spark-sql" % "1.1.0" % "provided",
  "org.apache.spark" %% "spark-core" % "1.1.0" % "provided",
  "org.apache.spark" % "spark-streaming_2.10" % "1.1.0" % "provided",
  ("org.apache.spark" % "spark-streaming-kinesis-asl_2.10" % "1.1.0").
    exclude("commons-beanutils", "commons-beanutils").
    exclude("commons-collections", "commons-collections").
    exclude("commons-logging", "commons-logging").
    exclude("org.slf4j", "jcl-over-slf4j").
    exclude("com.esotericsoftware.minlog", "minlog").
    exclude("org.apache.hadoop", "hadoop-core")
)

mergeStrategy in assembly <<= (mergeStrategy in assembly) { (old) =>
  {
    case x if x.startsWith("META-INF/ECLIPSEF.RSA") => MergeStrategy.last
    case x if x.startsWith("META-INF/mailcap") => MergeStrategy.last
    case x if x.startsWith("plugin.properties") => MergeStrategy.last
    case x => old(x)
  }
}

