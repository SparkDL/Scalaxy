package scalaxy.loops
package test

import org.junit._
import org.junit.Assert._

class ZipWithIndexOpsTest extends StreamComponentsTestBase with Streams {
  import global._

  @Test
  def testMapExtractor {
    val v @ SomeZipWithIndexOp(_, ZipWithIndexOp(_)) = typeCheck(q"Array(1).zipWithIndex")
    val SomeStreamOp(_, _ :: Nil) = v
  }
}
