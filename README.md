# Simple Java Class File Optimiser

Peephole optimisation is a simple technique that can yield surprisingly good results with little complexity.
Optimizer.java implements a simple peephole optimiser using the Java Class-File API.

Two optimisations are implemented, as an example:

* Removal of redundant addition/subtraction of zero e.g. int x = y + 0;
* Merging of consecutive StringBuilder.append calls with constant strings 
e.g. sb.append("foo").append("bar") -> sb.append("foobar");

The optimiser uses the Java Class-File API (JEP 457 / 466 / 484) that is currently in 
preview in Java 23 and targeted for Java 24. The Optimizer.java source file is self-contained
and does not require any external dependencies apart from the Java 23 standard library.

# Run

You'll need JDK 23, the easiest way to install this, on Linux, is with [SDK man](https://sdkman.io/):

```shell
sdk install java 23-open
```

You can run the optimizer directly from source using the java command:

```shell
$ java --enable-preview Optimizer.java input.jar output.jar
```

# Test case

The TestJarGenerator.java can generate a test jar to showcase the two optimisations
that are applied:

```shell
$ java --enable-preview TestJarGenerator.java test.jar
$ java --enable-preview Optimizer.java test.jar optimized.jar
$ java -jar test.jar
$ java -jar optimized.jar
```

The script `run.sh` runs the test jar generator and the optimizer in one go.
