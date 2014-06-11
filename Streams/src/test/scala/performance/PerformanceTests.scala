package scalaxy.streams
package test

import org.junit._
import org.junit.Assert._

object PerformanceTests {
  lazy val skip = {
  	val perfEnvVar = "SCALAXY_TEST_PERF"
    val test = "1" == System.getenv(perfEnvVar)
    if (!test)
      println(s"You can run ${getClass.getName} by setting the environment variable $perfEnvVar=1")
    !test
  }
}

trait PerformanceTests extends PerformanceTestBase {
  val skip: Boolean
  
  val oddPred = "x => (x % 2) != 0"
  val firstHalfPred = "x => x < n / 2"
  val midPred = "x => x == n / 2"
  
  def testToList(cc: (String, String)) = if (!skip)
    ensureFasterCodeWithSameResult(cc._1, cc._2 + ".toList")

  def testToArray(cc: (String, String)) = if (!skip)
    ensureFasterCodeWithSameResult(cc._1, cc._2 + ".toArray.toSeq")

  def testFilter(cc: (String, String)) = if (!skip)
    ensureFasterCodeWithSameResult(cc._1, cc._2 + ".filter(" + oddPred + ").toSeq")

  def testFilterNot(cc: (String, String)) = if (!skip) 
    ensureFasterCodeWithSameResult(cc._1, cc._2 + ".filterNot(" + oddPred + ").toSeq")

  def testCount(cc: (String, String)) = if (!skip)
    ensureFasterCodeWithSameResult(cc._1, cc._2 + ".count(" + oddPred + ")")

  def testForeach(cc: (String, String)) = if (!skip)
    ensureFasterCodeWithSameResult(cc._1, "var tot = 0L; for (v <- " + cc._2 + ") { tot += v }; tot")

  def testMap(cc: (String, String)) = if (!skip)
    ensureFasterCodeWithSameResult(cc._1, cc._2 + ".map(_ + 1).toSeq")

  def testTakeWhile(cc: (String, String)) = if (!skip) 
    ensureFasterCodeWithSameResult(cc._1, cc._2 + ".takeWhile(" + firstHalfPred + ").toSeq")

  def testDropWhile(cc: (String, String)) = if (!skip)
    ensureFasterCodeWithSameResult(cc._1, cc._2 + ".dropWhile(" + firstHalfPred + ").toSeq")

  def testExists(cc: (String, String)) = if (!skip)
    ensureFasterCodeWithSameResult(cc._1, cc._2 + ".exists(" + midPred + ")")

  def testForall(cc: (String, String)) = if (!skip)
    ensureFasterCodeWithSameResult(cc._1, cc._2 + ".forall(" + firstHalfPred + ")")

  def testSum(cc: (String, String)) = if (!skip)
    ensureFasterCodeWithSameResult(cc._1, cc._2 + ".sum")

  def testProduct(cc: (String, String)) = if (!skip)
    ensureFasterCodeWithSameResult(cc._1, cc._2 + ".product")

  def testMin(cc: (String, String)) = if (!skip)
    ensureFasterCodeWithSameResult(cc._1, cc._2 + ".min")

  def testMax(cc: (String, String)) = if (!skip)
    ensureFasterCodeWithSameResult(cc._1, cc._2 + ".max")

  def testReduceLeft(cc: (String, String)) = if (!skip)
    ensureFasterCodeWithSameResult(cc._1, cc._2 + ".reduceLeft(_ + _)")

  def testReduceRight(cc: (String, String)) = if (!skip)
    ensureFasterCodeWithSameResult(cc._1, cc._2 + ".reduceRight(_ + _)")

  def testFoldLeft(cc: (String, String)) = if (!skip)
    ensureFasterCodeWithSameResult(cc._1, cc._2 + ".foldLeft(0)(_ + _)")

  def testFoldRight(cc: (String, String)) = if (!skip)
    ensureFasterCodeWithSameResult(cc._1, cc._2 + ".foldRight(0)(_ + _)")

  def testScanLeft(cc: (String, String)) = if (!skip)
    ensureFasterCodeWithSameResult(cc._1, cc._2 + ".scanLeft(0)(_ + _).toSeq")

  def testScanRight(cc: (String, String)) = if (!skip)
    ensureFasterCodeWithSameResult(cc._1, cc._2 + ".scanRight(0)(_ + _).toSeq")

}
