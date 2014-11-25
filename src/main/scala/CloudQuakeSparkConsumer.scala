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
     val kinesisClient = new AmazonKinesisClient(new DefaultAWSCredentialsProviderChain()
                         .getCredentials())
     kinesisClient.setEndpoint(endpointUrl)
     val numShards = kinesisClient.describeStream(streamName)
                     .getStreamDescription().getShards().size()

     /* For now use 1 Kinesis Worker/Receiver/DStream for each shard. */
     val numStreams = numShards

     /* Hardcoded parameters */
     val batchInterval = Seconds(10) // A 10 Seconds batch interval is good for testing     
     val sta_win = 60 // In seconds
     val lta_win = 180 // In seconds, 180 for testing
     val m = 4.0     
     val b = 10.0
     val thres = 1.0

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
     def modify_sta(x:Double): Double = m*x + b

     /* Compute long term average in a window of 1h*/
     val lta_num = unionStreams.flatMap(byteArray => new String(byteArray).split("\n"))
                                        .countByWindow(Seconds(lta_win),batchInterval)
					.map(longToDouble)

     /* Compute short term average, including m and b values (see ... et al. 2011) */
     val sta_num = unionStreams.flatMap(byteArray => new String(byteArray).split("\n"))
                                        .countByWindow(Seconds(sta_win),batchInterval)
					.map(longToDouble).map(modify_sta)

     /* Prelinary union lta and sta streams */
     val sta_and_lta = sta_num.transformWith(lta_num, (rdd1: RDD[Double], 
          	  rdd2: RDD[Double]) => rdd1.union(rdd2))

     /* I need it in key-value pair format for joining with the actual tweets later */
     val cvalue = sta_and_lta.reduce((res_sta, res_lta) => res_lta / res_sta )
                   .map(doubleToString).map((1,_))  

     /* Print current cvalue */
     cvalue.map{case (x,y) => y}.print()     

     /* for storage I want a shortterm window in key-value pair form */
     val shortterm_window = tweets.window(Seconds(sta_win),batchInterval)
     val joined_stream = shortterm_window.map((1,_)).join(cvalue)

     /* Now detect earthquakes based on our defining equation */
     val detections = joined_stream.filter{case (x,(y,z)) => z.toDouble < 1.0}
	              .map{case (x,(y,z)) => (y,z)}

     /* Every minute save the c value and the earliest tweet in the window to S3*/
     joined_stream.map{case (x,(y,z)) => (y,z)}.foreachRDD(rdd_sta => {
            /* Only register, in case we have a tweet */
            if (rdd_sta.count > 0) {
                rdd_sta.saveAsTextFile("s3n://cqs3bucket/quiecence/no_"+System.currentTimeMillis)
            }
     })

     /* Register all tweets in the shortterm window associated with a detection as
     a schemaRDD, which can be queried in a simple SQL-style language */
     detections.foreachRDD(rdd_sta => {

            /* Only register, in case we have detected something */
            if (rdd_sta.count > 0) {

	        /* Just register the actual tweets */
	        val json_rdd_sta = sqlContext.jsonRDD(rdd_sta.map{case (x,y) => x})
		json_rdd_sta.registerAsTable("twitter_json_sta")
 	        json_rdd_sta.printSchema()
					
                /* Some sample requests on the data */
	        val res_sta = sqlContext.sql("SELECT COUNT(*) FROM twitter_json_sta").
	       	      	      collect().head.getLong(0)

                rdd_sta.saveAsTextFile("s3n://cqs3bucket/dections/eq_"
		                       +System.currentTimeMillis)
			      
	        println(s"Number of tweets per minute: $res_sta") 
            }
     })
      

     /* Start the streaming context and await termination */
     ssc.start()
     ssc.awaitTermination()

  }
}