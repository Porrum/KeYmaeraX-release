package edu.cmu.cs.ls.keymaerax.fcpsutils

import java.io.File

import edu.cmu.cs.ls.keymaerax.bellerophon.{BelleProvable, SequentialInterpreter, BTacticParser}
import edu.cmu.cs.ls.keymaerax.btactics.{NoneGenerate, TactixLibrary, DerivedAxioms}
import edu.cmu.cs.ls.keymaerax.core.{Provable, Formula}
import edu.cmu.cs.ls.keymaerax.parser.{KeYmaeraXProblemParser, ParseException}
import edu.cmu.cs.ls.keymaerax.tools.Mathematica

/**
  * Created by nfulton on 1/17/16.
  */
object CourseMain {
  implicit def qeTool = new Mathematica()
  DerivedAxioms.qeTool = qeTool
  TactixLibrary.tool = qeTool

  def main(input : Array[String]) = {
    val args : Map[String, ArgValue] = GetOpt(Map(
      "bparse" -> StrArgType(),
      "tparse" -> StrArgType(),
      "exists" -> StrArgType(),
      "problem" -> StrArgType(),
      "solution" -> StrArgType(),
      "is-exported-db" -> StrArgType()
    ))(input)

    try {
      args.foreach(pv => {
        val parameterName = pv._1
        val value = pv._2
        if (parameterName == "bparse") parseProblemFileOrFail(value)
        else if (parameterName == "tparse") parseTacticFileOrFail(value)
        else if (parameterName == "exists") fileExistsOrFail(value)
        else if (parameterName == "is-exported-db") isExportedDatabaseOrFail(value)
      })

      if(args.keySet.contains("problem") || args.keySet.contains("solution")) {
        val problemFile = args.getOrElse("problem",   throw new Exception("--problem and --solution flags should be both defined or both undefined."))
        val solutionFile = args.getOrElse("solution", throw new Exception("--problem and --solution flags should be both defined or both undefined."))
        isSolutionOrFail(problemFile, solutionFile)
      }

      System.exit(0)
    }
    catch {
      case e : Error => {
        e.printStackTrace()
        System.exit(-1)
      }
    }
  }

  private def isSolutionOrFail(problem: ArgValue, solution: ArgValue) = {
    val f = parseProblemFileOrFail(problem)
    val expr = parseTacticFileOrFail(solution)

    val result = SequentialInterpreter(List())(expr, BelleProvable(Provable.startProof(f)))
    result match {
      case BelleProvable(p) => {
        if(p.isProved) {
          //ok
        }
        else {
          println(s"Proof of ${fileExistsOrFail(problem)} using ${fileExistsOrFail(solution)} did not close. Remaining open goals follow:")
          println(p.prettyString)
          System.exit(-1)
        }
      }
      case _ => throw new Exception("expected tactic execution to result in provable but found something else.")
    }
  }

  /** Returns string contained within value */
  private def fileExistsOrFail(v : ArgValue) : String = {
    val fileName = v.asInstanceOf[StringValue].s
    assert({
      val file = new File(fileName)
      file.exists() && file.canRead()
    }, s"File named ${fileName} should exist and be readable.")
    fileName
  }

  private def parseTacticFileOrFail(v: ArgValue) = {
    val fileName = fileExistsOrFail(v)
    BTacticParser(scala.io.Source.fromFile(new File(fileName)).mkString, false, Some(new NoneGenerate[Formula]())) match {
      case Some(e) => e
      case None => {
        println(s"Tactic in ${fileName} did not parse")
        System.exit(-1)
        ???
      }
    }
  }

  private def parseProblemFileOrFail(v: ArgValue) : Formula = {
    val fileName = fileExistsOrFail(v)
    try {
      edu.cmu.cs.ls.keymaerax.parser.KeYmaeraXProblemParser(scala.io.Source.fromFile(fileName).mkString)
    }
    catch {
      case e : ParseException => {
        println(s"Auto-grader failed because file ${fileName} needs to exist and parse but failed to parse.")
        e.printStackTrace()
        System.exit(-1)
        ???
      }
      case e : Error => {
        println(s"Unkown error encountered while parsing ${fileName}")
        System.exit(-1)
        ???
      }
    }
  }

  private def isExportedDatabaseOrFail(v : ArgValue) = {
    fileExistsOrFail(v)
  }
}