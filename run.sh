#!/bin/bash

PASSES=2
JAVA_MAJOR_VERSION=$(java -version 2>&1 | sed -E -n 's/.* version "([^.-]*).*"/\1/p' | cut -d' ' -f1)

if [[ "$JAVA_MAJOR_VERSION" -lt 23 ]]; then
  echo "Java version 23 required"
  exit 1
fi

java --enable-preview TestJarGenerator.java out/test.jar
java --enable-preview Optimizer.java out/test.jar out/optimized.jar $PASSES

echo -n "test.jar output     : "
java -jar out/test.jar arg1 arg2
echo -n "optimized.jar output: "
java -jar out/optimized.jar arg1 arg2

original_size=$(stat -c %s out/test.jar)
optimized_size=$(stat -c %s out/optimized.jar)

if (( original_size > optimized_size )) ; then
    diff=$((original_size - optimized_size))
    echo "optimized.jar is $diff bytes smaller"
else
  echo "expected the optimized jar to be smaller; diff is $diff"
  exit 2
fi
