package testHelper

import edu.cmu.cs.ls.keymaera.core._
import edu.cmu.cs.ls.keymaera.parser.KeYmaeraParser

/**
 * Implicit conversions from strings into core data structures.
 * Created by smitsch on 1/8/15.
 * @author Stefan Mitsch
 */
object StringConverter {
  import scala.language.implicitConversions
  implicit def StringToStringConverter(s: String): StringConverter = new StringConverter(s)
}

class StringConverter(val s: String) {
  def asExpr: Expr = new KeYmaeraParser().parseBareExpression(s) match {
    case Some(e) => e
    case None => throw new IllegalArgumentException(s + " is not an Expr")
  }
  def asTerm: Term = new KeYmaeraParser().parseBareTerm(s) match {
    case Some(t) => t
    case None => throw new IllegalArgumentException(s + " is not a Term")
  }
  def asFormula: Formula = new KeYmaeraParser().parseBareFormulaUnquantified(s) match {
    case Some(f) => f
    case None => throw new IllegalArgumentException(s + " is not a Formula")
  }

  def asProgram: Program = new KeYmaeraParser().parseBareExpression("[" + s + "] true") match {
    case Some(BoxModality(p, f)) => p
    case None => throw new IllegalArgumentException(s + " is not a Program")
  }
}