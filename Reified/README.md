# Scalaxy/Reified

Simple reified values / functions framework that leverages Scala 2.10 macros ([Scaladoc](http://ochafik.github.io/Scalaxy/Reified/latest/api/index.html)).

Package `scalaxy.reified` provides a `reify` method that goes beyond the stock `Universe.reify` method, by taking care of captured values and allowing composition of reified functions for improved flexibility of dynamic usage of ASTs. 
The original expression is also available at runtime, without having to compile it with `ToolBox.eval`.

This is still highly experimental, documentation will come soon enough.

```scala
import scalaxy.reified._

def comp(capture1: Int): ReifiedFunction1[Int, Int] = {
  val capture2 = Seq(10, 20, 30)
  val f = reify((x: Int) => capture1 + capture2(x))
  val g = reify((x: Int) => x * x)
  
  g.compose(f)
}

val f = comp(10)
// Normal evaluation, using regular function:
println(f(1))

// Get the function's AST, inlining all captured values and captured reified values:
val ast = f.expr().tree
println(ast) 

// Compile the AST at runtime (needs scala-compiler.jar in the classpath):
val compiledF = ast.compile()()
// Evaluation, using the freshly-compiled function:
println(compiledF(1))
```

# Usage

If you're using `sbt` 0.12.2+, just put the following lines in `build.sbt`:
```scala
scalaVersion := "2.10.2"

// Dependency at compilation-time only (not at runtime).
libraryDependencies += "com.nativelibs4java" %% "scalaxy-reified" % "0.3-SNAPSHOT" % "provided"

// Scalaxy/Reified snapshots are published on the Sonatype repository.
resolvers += Resolver.sonatypeRepo("snapshots")
```

# Why?

To make it easy to deal with dynamic computations that could benefit from re-compilation at runtime for optimization purposes, or from conversion to other forms of executables (e.g. conversion to SQL, to OpenCL with ScalaCL, etc...).

For instance, let's say you have a complex financial derivatives valuation framework. It depends on lots of data (eventually stored in arrays and maps, e.g. dividend dates and values), which are fetched dynamically by your program, and it is composed of many pieces that can be assembled in many different ways (you might have several valuation algorithms, several yield curve types, and so on).
If each of these pieces returns a reified value (an instanceof `ReifiedValue[_]` returned by the `scalaxy.reified.reify` method, e.g. a `ReifiedValue[(Date, Map[Product, Double]) => Double]`), then thanks to reified values being composable your top level will be able to return a reified value as well, which will be a function of, say, the evaluation date, and maybe a map of market data bumps.
You can evaluate that function straight away, since every reified value holds the original value: evaluation will then be classically dynamic, with functions calling functions and all.
Or... if you need better performance from that function (which your program might call thousands of times), you can fetch that function's AST, compile it _at runtime_ with a `scala.tool.ToolBox` and get a fresh function with the same signature, but with all the static analysis optimizations the compiler was able to shove in. 

More detailed examples will hopefully come soon...

# TODO

- Add many more tests
- Fix case where same term symbol might point to different values.
- Debug `ReifiedValue.optimizedExpr` and remove `ReifiedValue.stableExpr` (which copies captures to their call site, probably producing bad performance at the moment).
- Write an end-to-end usage example with benchmarks, once `optimizedExpr` is the default (maybe an algebraic expressions parser / compiler?)
- Fix `ReifiedFunction2.curried`
- Convert captured reified functions to defs for better performance (perform static analysis on AST to see if a function's only references are of the form `f.apply(...)`)
- Embed Scalaxy loop optimizations
- Provide a `ReifiedPartialFunction` wrapper with an `orElse` method that extracts match cases and recomposes a match that's optimizable by the compiler
- Handle case where some captured values refer to others (e.g. nested immutable collections)

# Hacking

If you want to build / test / hack on this project:
- Make sure to use [paulp's sbt script](https://github.com/paulp/sbt-extras) with `sbt` 0.12.2+
- Use the following commands to checkout the sources and build the tests continuously: 

    ```
    git clone git://github.com/ochafik/Scalaxy.git
    cd Scalaxy
    sbt "project scalaxy-reified" "; clean ; ~test"
    ```
