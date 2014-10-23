#!/bin/bash

/root/spark/bin/spark-submit \
    --class "CloudQuakeSparkConsumer" \
    --master local[4] \
    target/scala-2.10/cloudquake-spark-consumer_2.10-1.0.jar \
    TwitterStreamTest \
    https://kinesis.us-east-1.amazonaws.com
    