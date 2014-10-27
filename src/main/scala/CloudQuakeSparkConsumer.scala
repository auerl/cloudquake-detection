/* 

   CloudQuake Spark consumer

   ======================================

   * Ingests kinesis streams (with the stream name 
     being the first cmd-line argument), pushed to
     Kinesis by various producers.
   * Applies our processing logic to the stream of
     tweets. See Earle et al. (2011) for details.
   * Requires environment variables AWS_ACCESS_KEY_ID 
     and AWS_SECRET_KEY to be set.
 
     Authors: Ludwig Auer, Shoma Arita, Lin Shen Husan
  
*/


import java.nio.ByteBuffer
import scala.util.Random
import org.apache.spark.Logging
import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.sql.SQLContext
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

object CloudQuakeSparkConsumer {
   def main(args: Array[String]) {

     /* Check that all required args were passed in. */
     if (args.length < 2) {
        System.err.println(
	   """
	      |Usage: CloudQuakeSparkConsumer <stream-name> <endpoint-url>
	      | <stream-name> is the name of the Kinesis stream
	      | <endpoint-url> is the endpoint of the Kinesis service
	      | (e.g. https://kinesis.us-east-1.amazonaws.com)
	   """.stripMargin)
	   System.exit(1) 
     }


     /* Populate the appropriate variables from the given args */
     val Array(streamName, endpointUrl) = args
     println("Stream to connect: %s, Amazon backend: %s".format(streamName, endpointUrl))

     /* Determine the number of shards from the stream */
     val kinesisClient = new AmazonKinesisClient(new DefaultAWSCredentialsProviderChain().getCredentials())
     kinesisClient.setEndpoint(endpointUrl)
     val numShards = kinesisClient.describeStream(streamName).getStreamDescription().getShards()
      .size()

     /* For now use 1 Kinesis Worker/Receiver/DStream for each shard. */
     val numStreams = numShards

     /* Setup the and SparkConfig and StreamingContext */
     /* Spark Streaming batch interval */
     val batchInterval = Milliseconds(10000) // 10 seconds batchInterval
     val sparkConfig = new SparkConf().setAppName("CloudQuakeSparkConsumer")
                                      .set("spark.cleaner.ttl","7200") // clean every 2 hours 
     val ssc = new StreamingContext(sparkConfig, batchInterval)
     val sc = new SparkContext(sparkConfig)

     /* Start up a new Spark SQL context */
     val sqlContext = new org.apache.spark.sql.SQLContext(sc)	


     /* Set checkpoint directory */
     ssc.checkpoint("/root/check/")

     /* Kinesis checkpoint interval.  Same as batchInterval for this example. */
     val kinesisCheckpointInterval = batchInterval

     /* Create the same number of Kinesis DStreams/Receivers as Kinesis stream's shards */
     val kinesisStreams = (0 until numStreams).map { i =>
         KinesisUtils.createStream(ssc, streamName, endpointUrl, kinesisCheckpointInterval,
         InitialPositionInStream.LATEST, StorageLevel.MEMORY_AND_DISK_2)
     }

     /* Union all the streams */
     val unionStreams = ssc.union(kinesisStreams)

     val words = unionStreams.flatMap(byteArray => new String(byteArray).split(" "))
     val lines = unionStreams.flatMap(byteArray => new String(byteArray).split("\n"))     

     /* This selects all hashtags */
     val hashTags = unionStreams.flatMap(byteArray => new String(byteArray).split(" ").filter(_.startsWith("#")))


     /* list of words to search for */
//     val keyWords = List("this")
//     val containsthis1 = unionStreams.flatMap(byteArray => new String(byteArray).split(" ").filter(_=="this"))
//     val containsthis2 = unionStreams.flatMap(byteArray => new String(byteArray).split(" ").filter(_.exists(keyWords contains _)))

//     containsthis1.countByWindow(Seconds(60),batchInterval).print()
//     containsthis2.countByWindow(Seconds(60),batchInterval).print()

     val countalltweets = unionStreams.flatMap(byteArray => new String(byteArray).split("\n"))
                                        .countByWindow(Seconds(60),batchInterval)
     countalltweets.print()


     
     // Most popular hashtags in the last 60 seconds
     val topHashCounts60 = hashTags.map((_, 1)).reduceByKeyAndWindow(_ + _, Seconds(60))
     	              .map{case (topic, count) => (count, topic)}
              	      .transform(_.sortByKey(false))

     // Most popular hashtags in the last 10 seconds
     val topHashCounts10 = hashTags.map((_, 1)).reduceByKeyAndWindow(_ + _, Seconds(10))
     	              .map{case (topic, count) => (count, topic)}
              	      .transform(_.sortByKey(false))

     // Most popular words in the last 60 seconds
     val topWordCounts60 = words.map((_, 1)).reduceByKeyAndWindow(_ + _, Seconds(60))
     	              .map{case (topic, count) => (count, topic)}
              	      .transform(_.sortByKey(false))




      // Print popular hashtags
      topHashCounts60.foreachRDD(rdd => {
      val topList = rdd.take(3)
      println("\nTop 3 topics in last 60 seconds (%s total):".format(rdd.count()))
      topList.foreach{case (count, tag) => println("%s (%s tweets)".format(tag, count))}
      })     

      /*
      topHashCounts10.foreachRDD(rdd => {
      val topList = rdd.take(3)
      println("\nTop 3 topics in last 10 seconds (%s total):".format(rdd.count()))
      topList.foreach{case (count, tag) => println("%s (%s tweets)".format(tag, count))}
      })

      topWordCounts60.foreachRDD(rdd => {
      val topList = rdd.take(3)
      println("\nTop 3 words topics in last 60 seconds (%s total):".format(rdd.count()))
      topList.foreach{case (count, tag) => println("%s (%s tweets)".format(tag, count))}
      })
      */

      val shortterm_window = lines.window(Seconds(60),batchInterval)

      /* Now our processing logic starts */
      windowed_dstream.foreachRDD(rdd => {
           if (rdd.count > 0) {
               val json_rdd = sqlContext.jsonRDD(rdd)
               json_rdd.registerAsTable("twitter_json")
	       json_rdd.printSchema()

               // Some sample requests on the data
	       // val query1_results = sqlContext.sql("SELECT * FROM twitter_json WHERE content LIKE '%this%' ")
 	       // val query1_results = sqlContext.sql("SELECT * FROM twitter_json")
	       val query1_results = sqlContext.sql("SELECT COUNT(*) FROM twitter_json").collect().head.getLong(0)

	       // val contains_this = query1_results.collect.map(row => row.getString(5)) //get time
	       // println("Stocks above market value: \n ======================= \n" + contains_this.mkString(",")) 
               println(s"Number of tweets per minute: $query1_results") 

              }
           })
       
     

      /* Start the streaming context and await termination */
      ssc.start()
      ssc.awaitTermination()



  }
}