#!/system/bin/sh
curl -s -o /dev/null -w 'status=%{http_code}\n' http://127.0.0.1:8080/_status
curl -s -c /data/local/tmp/ck.txt -o /dev/null -w 'login=%{http_code}\n' 'http://127.0.0.1:8080/_login/quick?u=admin&p=123456'
cat /data/local/tmp/ck.txt
