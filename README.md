# Simple Java Class File Optimiser

Peephole optimisation is a simple technique that can yield surprisingly good results with little complexity.
Optimizer.java implements a simple peephole optimiser using the Java Class-File API.

Two optimisations are implemented, as an example:

* Removal of redundant addition/subtraction of zero e.g. int x = y + 0;
* Merging of consecutive StringBuilder.append calls with constant strings 
e.g. sb.append("foo").append("bar") -> sb.append("foobar");

Each optimisation implements the small `Peephole` interface, returning its
replacement as a `Rewrite`; new optimisations can be added to the `PEEPHOLES`
list in Optimizer.java. The driver splices each rewrite in place and backs the
sliding window up over the seam, so optimisations enabled by other
optimisations are found in the same sweep; a rewrite budget guards against
optimisation sets that never converge. A summary of how often each
optimisation matched is printed at the end.

The optimiser uses the Java Class-File API (JEP 457 / 466 / 484) released
in final form in Java 24. The Optimizer.java source file is self-contained
and does not require any external dependencies apart from the Java 24 standard library.

Further details can be found in [the accompanying blog post](https://jameshamilton.eu/programming/peering-through-peephole-build-peephole-optimiser-using-new-java-24-class-file-api).

# Run

You'll need JDK 26, the easiest way to install this, on Linux, is with [SDK man](https://sdkman.io/):

```shell
sdk install java 26.0.2-open
```

You can run the optimizer directly from source using the java command:

```shell
$ java Optimizer.java input.jar output.jar
```

# Test case

The TestJarGenerator.java can generate a test jar to showcase the two optimisations
that are applied:

```shell
$ java TestJarGenerator.java test.jar
$ java Optimizer.java test.jar optimized.jar
$ java -jar test.jar
$ java -jar optimized.jar
```

The script `run.sh` runs the test jar generator and the optimizer in one go.
