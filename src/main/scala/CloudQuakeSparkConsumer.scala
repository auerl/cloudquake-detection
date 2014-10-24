/* SimpleApp.scala */
import java.nio.ByteBuffer
import scala.util.Random
import org.apache.spark.Logging
import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.SparkConf
import org.apache.spark.storage.StorageLevel
import org.apache.spark.streaming.{Milliseconds,Seconds}
import org.apache.spark.streaming.StreamingContext
import org.apache.spark.streaming.StreamingContext.toPairDStreamFunctions
import org.apache.spark.streaming.kinesis.KinesisUtils
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.services.kinesis.AmazonKinesisClient
import com.amazonaws.services.kinesis.clientlibrary.lib.worker.InitialPositionInStream
import com.amazonaws.services.kinesis.model.PutRecordRequest
import org.apache.log4j.Logger
import org.apache.log4j.Level




// testing json4s parsing
/* import util.parsing.json.JSON
import java.io._
import org.scalastuff.json.JsonParser	
import org.scalastuff.json.JsonPrinter
import org.apache.spark.sql._
import org.json4s.jackson.JsonMethods
import org.json4s.jackson.JsonMethods._
import org.json4s.JsonAST._
import org.json4s.DefaultFormats 
*/


import org.apache.spark.sql._

object CloudQuakeSparkConsumer {
   def main(args: Array[String]) {

     /* Check that all required args were passed in. */
     if (args.length < 2) {
        System.err.println(
	   """
	      |Usage: KinesisWordCount <stream-name> <endpoint-url>
	      | <stream-name> is the name of the Kinesis stream
	      | <endpoint-url> is the endpoint of the Kinesis service
	      | (e.g. https://kinesis.us-east-1.amazonaws.com)
	   """.stripMargin)
	   System.exit(1) 
     }





     // =========================================
     // THIS IS SOME PRELIMINARY TESTING

     // This shows how to read files and do something with them
     val logFile = "file:///root/spark/README.md" // search in local path not hdfs
     val conf = new SparkConf().setAppName("Simple Application")
     val sc = new SparkContext(conf)
     val logData = sc.textFile(logFile, 2).cache()
     val numAs = logData.filter(line => line.contains("a")).count()
     val numBs = logData.filter(line => line.contains("b")).count()
     println("Lines with a: %s, Lines with b: %s".format(numAs, numBs))

     // =========================================



     val sqlContext = new org.apache.spark.sql.SQLContext(sc)



     // =========================================
     //     StreamingExamples.setStreamingLogLevels()

     /* Populate the appropriate variables from the given args */
     val Array(streamName, endpointUrl) = args
     println("Argument 1: %s, Argument 2: %s".format(streamName, endpointUrl))



     /* Determine the number of shards from the stream */
     val kinesisClient = new AmazonKinesisClient(new DefaultAWSCredentialsProviderChain().getCredentials())
     kinesisClient.setEndpoint(endpointUrl)
     val numShards = kinesisClient.describeStream(streamName).getStreamDescription().getShards()
      .size()

     /* In this example, we're going to create 1 Kinesis Worker/Receiver/DStream for each shard. */
     val numStreams = numShards

     /* Setup the and SparkConfig and StreamingContext */
     /* Spark Streaming batch interval */
     val batchInterval = Milliseconds(2000)
     val sparkConfig = new SparkConf().setAppName("KinesisWordCount")
     val ssc = new StreamingContext(sparkConfig, batchInterval)


     /* Kinesis checkpoint interval.  Same as batchInterval for this example. */
     val kinesisCheckpointInterval = batchInterval

     /* Create the same number of Kinesis DStreams/Receivers as Kinesis stream's shards */
     val kinesisStreams = (0 until numStreams).map { i =>
         KinesisUtils.createStream(ssc, streamName, endpointUrl, kinesisCheckpointInterval,
         InitialPositionInStream.LATEST, StorageLevel.MEMORY_AND_DISK_2)
     }


     /* Union all the streams */
     val unionStreams = ssc.union(kinesisStreams)

     // val windowed_unionStreams = unionStreams.window(new Duration(60000), new Duration(5000))






     // This just outputs everything contained in the tweet
     val words = unionStreams.flatMap(byteArray => new String(byteArray).split(" "))
     // words.print()

     // This
     val hashTags = unionStreams.flatMap(byteArray => new String(byteArray).split(" ").filter(_.startsWith("#")))
     // hashTags.print()
     
     // Most popular hashtags in the last 60 seconds
     val topCounts60 = hashTags.map((_, 1)).reduceByKeyAndWindow(_ + _, Seconds(60))
     	              .map{case (topic, count) => (count, topic)}
              	      .transform(_.sortByKey(false))

     // Most popular hashtags in the last 10 seconds
     val topCounts10 = hashTags.map((_, 1)).reduceByKeyAndWindow(_ + _, Seconds(10))
     	              .map{case (topic, count) => (count, topic)}
              	      .transform(_.sortByKey(false))


      // Print popular hashtags
      topCounts60.foreachRDD(rdd => {
      val topList = rdd.take(10)
      println("\nPopular topics in last 60 seconds (%s total):".format(rdd.count()))
      topList.foreach{case (count, tag) => println("%s (%s tweets)".format(tag, count))}
      })

      topCounts10.foreachRDD(rdd => {
      val topList = rdd.take(10)
      println("\nPopular topics in last 10 seconds (%s total):".format(rdd.count()))
      topList.foreach{case (count, tag) => println("%s (%s tweets)".format(tag, count))}
      })


     // val wordCounts = words.map(word => (word, 1)).reduceByKey(_ + _)
     // wordCounts.print()


     // unionStreams.foreachRDD( rdd => { for(item <- rdd.collect().toArray) { println(item); } })
     // val anotherTweet = sqlContext.jsonRDD(unionStreams.foreachRDD( rdd => for(item <- rdd.collect().toArray) ))
     // val anotherTweet = sqlContext.jsonRDD(words.foreachRDD( rdd:RDD[String] => { for(item <- rdd.collect().toArray) { println(item); } }))
     // val anotherTweet = sqlContext.jsonRDD(unionStreams.foreachRDD((x:RDD[byteArray]) => flatMap(byteArray => new String(byteArray))))

/*
     unionStreams.foreachRDD(rdd => {
       if (rdd.count > 0) {		  
       //           val json_rdd = sqlContext.jsonRDD(rdd)
       //	       json_rdd.registerAsTable("data_table")
       //	       json_rdd.printSchema
       //           val json = JsonMethods.parse(rdd)

        // rdd.collect().foreach(println)



	 }
      })

*/


//   val parser = new JsonParser(new MyJsonHandler)	
//   val result: JsValue = SprayJsonParser.parse(unionStreams)
//   val json = JsonMethods.parse(unionStreams)



     /* Now our processing logic starts, for now I just count popular hashtags 
        another idea might be to count the most popular words. But will require
        analysing the json format. How can I open such a format.
     */
     

//   val kinesisClient = new AmazonKinesisClient()
//   val kinesisClient = new AmazonKinesisClient(new DefaultAWSCredentialsProviderChain().getCredentials())


    /* Start the streaming context and await termination */
    ssc.start()
    ssc.awaitTermination()



  }
}