# -*- coding: utf-8 -*-

import json
import urllib2
import boto
import oauth2 as oauth
import time
from functools import wraps

def retry(ExceptionToCheck, tries=4, delay=3, backoff=2, logger=None):
    def deco_retry(f):
        @wraps(f)
        def f_retry(*args, **kwargs):
            mtries, mdelay = tries, delay
            while mtries > 1:
                try:
                    return f(*args, **kwargs)
                except ExceptionToCheck, e:
                    msg = "%s, Retrying in %d seconds..." % (str(e), mdelay)
                    if logger:
                        logger.warning(msg)
                    else:
                        print msg
                    time.sleep(mdelay)
                    # mtries -= 1
                    mdelay *= backoff
            return f(*args, **kwargs)
        return f_retry
    return deco_retry

@retry(Exception, tries=4, delay=5, backoff=1)
def streaming():
    global kinesis
    
    CONSUMER_KEY = ''
    CONSUMER_SECRET = ''
    ACCESS_TOKEN_KEY = ''
    ACCESS_TOKEN_SECRET = ''
    consumer = oauth.Consumer(key=CONSUMER_KEY, secret=CONSUMER_SECRET)
    token = oauth.Token(key=ACCESS_TOKEN_KEY, secret=ACCESS_TOKEN_SECRET)
    url = 'https://stream.twitter.com/1.1/statuses/filter.json?track=earthquake'
    params = {}
    request = oauth.Request.from_consumer_and_token(consumer, token, http_url=url, parameters=params)
    request.sign_request(oauth.SignatureMethod_HMAC_SHA1(), consumer, token)
    res = urllib2.urlopen(request.to_url())
    
    if res == urllib2.URLError:
        raise Exception
    else:
        for r in res:
            try:
                data = json.loads(r)
		tweet = data['text'].encode('utf-8').replace('\n', '')
                time = str(data['created_at']).split(' ')
                #time_div = time[3].split(':')
                send_data = {'content':tweet,'year':time[5],'month':time[1],'date':time[2],'time':time[3]}
                send_json = json.dumps(send_data)
		kinesis.put_record('TwitterStreamTime',send_json,'one')
            except:
                continue

if __name__ == '__main__':
    global kinesis
    kinesis = boto.connect_kinesis(aws_access_key_id='',aws_secret_access_key='')
    streaming()
