cloudquake-spark
================

This README.md describes how to set up an Amazon EC2 instance
with Apache Spark and running an example of the cloudquake-spark-
consumer.

* Downloads spark to your own compute from apache
  https://spark.apache.org/ You just need this to start up 
  a Spark-EC2-Instance at AWS

* Set your environment variables in ~/.bashrc and source it
  export AWS_ACCESS_KEY_ID='AKIAJ7L3MHIHH4ISVBPA'
  export AWS_SECRET_KEY='Gbs8X6pkP8+tR2f6/8cXZPPYkvGVoS/GXEpNKhml'dd 

* Start an ec2 instance in the spark/ec2 folder with
  ./spark-ec2 -k auerl -i auerl.pem -s 1 launch spark_test --region=us-east-1
  ./spark-ec2 -k auerl -i auerl.pem login spark_test --region=us-east-1
  check http://spark.apache.org/docs/0.6.1/ec2-scripts.html for more info

* Install simple build tool
  sudo yum install http://repo.scala-sbt.org/scalasbt/sbt-native-packages/org/scala-sbt/sbt/0.13.1/sbt.rpm

* Build your project like
  http://spark.apache.org/docs/latest/quick-start.html

* You need to create a "fat jar" otherwise the whole thing 
  does not work, this is important. To this end we need to install
  the sbt assembly plugin https://github.com/sbt/sbt-assembly
  
* Add an assembly.sbt to the root folder of your project, containing
===============================================================
import AssemblyKeys._ // put this at the top of the file

assemblySettings

mergeStrategy in assembly <<= (mergeStrategy in assembly) { (old) =>
  {
    case PathList("javax", "servlet", xs @ _*)         => MergeStrategy.first
    case PathList(ps @ _*) if ps.last endsWith ".html" => MergeStrategy.first
    case "application.conf" => MergeStrategy.concat
    case "unwanted.txt"     => MergeStrategy.discard
    case x => old(x)
  }
}
===============================================================

* Add an assembly.sbt to your project folder, containing
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.11.2")

* In build.sbt in the main root folder, put, to load the important
libraries and dismiss other libraries (to avoid conflicts when building
the fat jar essentially)
===============================================================
import AssemblyKeys._

assemblySettings

name := "Simple Project"

version := "1.0"

scalaVersion := "2.10.4"

jarName in assembly := "simple-project_2.10-1.0.jar"

assemblyOption in assembly ~= { _.copy(includeScala = false) }

libraryDependencies ++= Seq(
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
================================================================


* run command "sbt", will download the assembly plugin

* in the sbt shell run command "assembly", to create a fat jar

* To run locally do the following (test1 and test2 are cmdline arguments that are processed)
  /root/spark/bin/spark-submit --class "SimpleApp" --master local[4] target/scala-2.10/simple-project_2.10-1.0.jar test1 test2

* You can also submit to the cluster. See
  http://spark.apache.org/docs/latest/submitting-applications.html

* Set your environment variables in ~/.bashrc and source it on your EC2 instance

* We don't want all screen output written to the screen, so we put the following in
  /root/spark/conf/log4j.properties
=================================================================
# Initialize root logger                                                                             
log4j.rootLogger=INFO, FILE
# Set everything to be logged to the console                                                         
log4j.rootCategory=INFO, FILE

# Ignore messages below warning level from Jetty, because it's a bit verbose                         
log4j.logger.org.eclipse.jetty=WARN

# Set the appender named FILE to be a File appender                                                  
log4j.appender.FILE=org.apache.log4j.FileAppender

# Change the path to where you want the log file to reside                                           
log4j.appender.FILE.File=/root/spark/logs/SparkOut.log

# Prettify output a bit                                                                              
log4j.appender.FILE.layout=org.apache.log4j.PatternLayout
log4j.appender.FILE.layout.ConversionPattern=%d{yy/MM/dd HH:mm:ss} %p %c{1}: %m%n
=================================================================
  and in /root/spark/conf/java-opts we put
  -Dlog4j.configuration=file:///root/spark/conf/log4j.properties

* Make emacs scala ready

* This is how you could proceed in setting up your Scala KinesisStreamConsumer
  https://github.com/apache/spark/blob/master/extras/kinesis-asl/src/main/scala/org/apache/spark/examples/streaming/KinesisWordCountASL.scala
  http://spark.apache.org/docs/latest/streaming-programming-guide.html
  http://spark.apache.org/docs/latest/streaming-kinesis-integration.html
  http://mbonaci.github.io/mbo-spark/  ->> a nice spark example	
  http://spark.apache.org/docs/latest/quick-start.html
  https://github.com/apache/spark/blob/master/examples/src/main/scala/org/apache/spark/examples/streaming/TwitterPopularTags.scala
  http://stackoverflow.com/questions/4170949/how-to-parse-json-in-scala-using-standard-scala-classes





