(cd target && java -Xmx30G -cp ../lib/commons-math3-3.6.1.jar:../lib/guava-19.0.jar:TaoServer-1.0-SNAPSHOT.jar TaoClient.InsecureTaoClient --runType load_test --clients $1 --load_test_length $2 --rwRatio $3 --zipfExp $4 --warmup_operations $5)