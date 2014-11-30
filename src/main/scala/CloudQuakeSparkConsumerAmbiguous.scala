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
   * Partly based on the Spark Word Count example

     Authors: Ludwig Auer, Shoma Arita, Lin Shen Husan
  
*/


import java.nio.ByteBuffer
import scala.util.Random
import org.apache.spark.Logging
import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.sql.SQLContext
import org.apache.spark.SparkConf
import org.apache.spark.rdd.RDD
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
import java.lang.Math

object CloudQuakeSparkConsumerAmbiguous {
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
     val kinesisClient = new AmazonKinesisClient(new DefaultAWSCredentialsProviderChain()
                         .getCredentials())
     kinesisClient.setEndpoint(endpointUrl)
     val numShards = kinesisClient.describeStream(streamName)
                     .getStreamDescription().getShards().size()

     /* For now use 1 Kinesis Worker/Receiver/DStream for each shard. */
     val numStreams = numShards

     /* Hardcoded parameters */
     val batchInterval = Seconds(10) // A 10 Seconds batch interval is good for testing     

     /* Setup the and SparkConfig and StreamingContext */
     val sparkConfig = new SparkConf().setAppName("CloudQuakeSparkConsumer")
                                      .set("spark.cleaner.ttl","7200") // clean every 2 hours 
     val ssc = new StreamingContext(sparkConfig, batchInterval)
     val sc = new SparkContext(sparkConfig)


     /* This we need to interact with Amazon S3 database */
     val hadoopConf=sc.hadoopConfiguration;
     hadoopConf.set("fs.s3.impl", "org.apache.hadoop.fs.s3native.NativeS3FileSystem")
     val myAccessKey = sys.env("AWS_ACCESS_KEY_ID")
     val mySecretKey = sys.env("AWS_SECRET_KEY")
     hadoopConf.set("fs.s3.awsAccessKeyId",myAccessKey)
     hadoopConf.set("fs.s3.awsSecretAccessKey",mySecretKey)

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
     val tweets = unionStreams.flatMap(byteArray => new String(byteArray).split("\n"))     

     /* Some helper functions */
     def square(x:Double): Double = x*x 
     def longToDouble(x:Long): Double = x.toDouble
     def doubleToString(x:Double): String = x.toString


     /* This selects all hashtags */
     val hashTags = unionStreams.flatMap(byteArray => new String(byteArray).
     	 	    split(" ").filter(_.startsWith("#")))

     
     /* Most popular hashtags in the last 60 seconds */
     val topHashCounts60 = hashTags.map((_, 1)).reduceByKeyAndWindow(_ + _, Seconds(60))
     	              .map{case (topic, count) => (count, topic)}
              	      .transform(_.sortByKey(false))

     /* Most popular hashtags in the last 10 seconds */
     val topHashCounts10 = hashTags.map((_, 1)).reduceByKeyAndWindow(_ + _, Seconds(60))
     	              .map{case (topic, count) => (count, topic)}
              	      .transform(_.sortByKey(false))


     /* Print popular hashtags */
     topHashCounts60.foreachRDD(rdd => {
     val topList = rdd.take(3)
     println("\nTop 3 topics in last 60 seconds (%s total):".format(rdd.count()))
     topList.foreach{case (count, tag) => println("%s (%s tweets)".format(tag, count))}
     })     

     topHashCounts10.foreachRDD(rdd => {
     val topList = rdd.take(3)
     println("\nTop 3 topics in last 10 seconds (%s total):".format(rdd.count()))
     topList.foreach{case (count, tag) => println("%s (%s tweets)".format(tag, count))}
     })

      

     /* Start the streaming context and await termination */
     ssc.start()
     ssc.awaitTermination()

  }
}