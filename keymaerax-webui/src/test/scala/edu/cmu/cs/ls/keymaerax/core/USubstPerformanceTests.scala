/**
* Copyright (c) Carnegie Mellon University.
* See LICENSE.txt for the conditions of this license.
*/
package edu.cmu.cs.ls.keymaerax.core

import edu.cmu.cs.ls.keymaerax.btactics._
import edu.cmu.cs.ls.keymaerax.core._
import edu.cmu.cs.ls.keymaerax.parser.{KeYmaeraXParser, KeYmaeraXPrettyPrinter}
import edu.cmu.cs.ls.keymaerax.parser.StringConverter._
import edu.cmu.cs.ls.keymaerax.tags.USubstTest
import edu.cmu.cs.ls.keymaerax.utils.Statistics
import testHelper.CustomAssertions.withSafeClue
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FlatSpec, Matchers}

import scala.collection.immutable._
import scala.util.Random
import scala.concurrent.duration.Duration

/**
  * Repeatably randomly test and compare and stat uniform substitution application mechanisms USubstOne and USubstChurch.
  * @author Andre Platzer
  */
@USubstTest
class USubstPerformanceTests extends FlatSpec with Matchers with BeforeAndAfterEach with BeforeAndAfterAll {

  val randomTrials = 500
  val randomComplexity = 20
  val randomSubstitutions = 10
  val randRoot = RepeatableRandom()

  val logprint = false
  val yellAtClash = false

  /** Test setup */
  override def beforeEach() = {
    PrettyPrinter.setPrinter(KeYmaeraXPrettyPrinter.pp)
    //KeYmaeraXParser.setAnnotationListener((p: Program, inv: Formula) => None)
  }

  /* Test teardown */
  override def afterEach() = {
    PrettyPrinter.setPrinter(e => e.getClass.getName)
  }

  override def afterAll(): Unit = {
    println("RepeatableRandom(" + randRoot.seed + "L) seed will regenerate this class's random sequence\n\n")
    super.afterAll()
  }

  private def print(s: => String): Unit = {
    if (logprint) println(s)
  }

  /** How to measure the size of the result `r` of having applied uniform substitution `us` to `fml`. */
  private def measure(us: USubst, fml: Formula, r: Formula): Int = {
    Statistics.countFormulaOperators(r, true)
    //Statistics.countAtomicTerms(r)
    //r.toString.length
  }


  "USubst performance" should "substitute random formulas with random admissible uniform substitutions" in {
    var us1duration = 0L
    var us2duration = 0L
    val stats1 = scala.collection.mutable.Map[Int,scala.collection.mutable.ListBuffer[Long]]()
    val stats2 = scala.collection.mutable.Map[Int,scala.collection.mutable.ListBuffer[Long]]()
    for (k <- 1 to randomTrials) {
      val randTrial = randRoot.nextFormulaEpisode()
      val fml = randTrial.nextF(randTrial.nextNames("z", randomComplexity / 3 + 1), randomComplexity,
        modals=true, dotTs=false, dotFs=false, diffs=false, funcs=true, duals=true)
      for (i <- 1 to randomSubstitutions) {
        val randClue = "Uniform substitution produced for " + fml + " in\n\t " + i + "th run of " + randomTrials +
          " random trials,\n\t generated with " + randomComplexity + " random complexity\n\t from random root " + randRoot.seed + " so trial seed " + randTrial.seed

        val randSubst = randTrial.nextFormulaEpisode()
        val us = withSafeClue("Error generating uniform substitution\n\n" + randClue) {
          randSubst.nextAdmissibleUSubst(fml, randomComplexity, false)
        }

        withSafeClue("Random substitution " + us + "\n\n" + randClue) {
          print("usubst:  " + us)
          print("formula: " + fml)
          val t10 = System.nanoTime()
          val r1 = try { Some(USubstOne(us.subsDefsInput)(fml)) } catch {case e:SubstitutionClashException=> None}
          val locduration1 = System.nanoTime()-t10
          us1duration += locduration1
          print("result:  " + r1.getOrElse("clash"))

          val t20 = System.nanoTime()
          val r2 = try { Some(USubstChurch(us.subsDefsInput)(fml)) } catch {case e:SubstitutionClashException=> None}
          val locduration2 = System.nanoTime()-t20
          us2duration += locduration2
          print("result:  " + r2.getOrElse("clash"))
          r1 shouldBe r2
          if (yellAtClash) Some(USubst(us.subsDefsInput)(fml))

          val size1 = measure(us, fml, r1.getOrElse(fml))
          if (!stats1.contains(size1)) stats1.put(size1, new scala.collection.mutable.ListBuffer())
          stats1(size1) += locduration1
          val size2 = measure(us, fml, r2.getOrElse(fml))
          if (!stats2.contains(size2)) stats2.put(size2, new scala.collection.mutable.ListBuffer())
          stats2(size2) += locduration2
          print("")
        }
      }
    }
    println()
    println("===================================")
    println()
    printf("USubstOne duration:   \t%9d ms\n", Duration.fromNanos(us1duration).toMillis)
    printf("USubstChurch duration:\t%9d ms\n", Duration.fromNanos(us2duration).toMillis)
    println()
    println("===================================")
    println()
    println("USubstOne stats")
    println(statalize(stats1))
    println()
    println("USubstChurch stats")
    println(statalize(stats2))
    println()
    println("===================================")
    println()
    printf("USubstOne duration:   \t%9d ms\n", Duration.fromNanos(us1duration).toMillis)
    printf("USubstChurch duration:\t%9d ms\n", Duration.fromNanos(us2duration).toMillis)
    println()
  }

  /** Individual statistics of average duration per size */
  private def statalize(stat: scala.collection.mutable.Map[Int,scala.collection.mutable.ListBuffer[Long]]): String = {
    def average(l: scala.collection.mutable.ListBuffer[Long]): Long =
      l.foldRight(0L)((a,b)=>a+b) / l.size

    stat.keySet.toList.sortWith((a,b)=>a<b).map(n=> "%6d".format(n) + ": " + "%9d".format(Duration.fromNanos(average(stat(n))).toMillis) + " ms").mkString("\n")
  }
}
