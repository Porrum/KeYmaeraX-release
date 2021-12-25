package edu.cmu.cs.ls.keymaerax.hydra

import edu.cmu.cs.ls.keymaerax.bellerophon._
import edu.cmu.cs.ls.keymaerax.bellerophon.parser.BelleParser.{EMPTY_BRANCHES, EMPTY_TACTIC, NEWLINE, NIL_TACTIC, SKIP_TACTIC, TODO_TACTIC}
import edu.cmu.cs.ls.keymaerax.bellerophon.parser.{BRANCH_COMBINATOR, BelleParser, BellePrettyPrinter, CLOSE_PAREN, COLON, COMMA, OPEN_PAREN, SEQ_COMBINATOR}
import edu.cmu.cs.ls.keymaerax.hydra.TacticExtractionErrors.TacticExtractionError
import edu.cmu.cs.ls.keymaerax.infrastruct.{AntePosition, Position, SuccPosition}
import edu.cmu.cs.ls.keymaerax.infrastruct.Augmentors._
import edu.cmu.cs.ls.keymaerax.parser.{Declaration, Location, Region}
import edu.cmu.cs.ls.keymaerax.pt.ProvableSig

import scala.annotation.tailrec
import scala.collection.immutable.StringOps
import scala.util.Try

trait TraceToTacticConverter {
  def getTacticString(tree: ProofTree): (String, Map[Location, ProofTreeNode]) = getTacticString(tree.root, "")
  def getTacticString(node: ProofTreeNode, indent: String): (String, Map[Location, ProofTreeNode])
}

class VerboseTraceToTacticConverter(defs: Declaration) extends TraceToTacticConverter {
  private val INDENT_INCREMENT = BelleParser.TAB

  /** Tactic definitions from the tactic script (if any). */
  private val tacticDefs = scala.collection.mutable.Map.empty[String, BelleExpr]

  private def makeLocNodeMap(maker: String, node: ProofTreeNode): Map[Location, ProofTreeNode] = {
    if (maker.nonEmpty) {
      val makerLines = (maker:StringOps).lines.toList
      val makerRegion = Region(0, 0, makerLines.length - 1, makerLines.last.length)
      Map[Location, ProofTreeNode](makerRegion -> node)
    } else Map.empty
  }

  def getTacticString(node: ProofTreeNode, indent: String): (String, Map[Location, ProofTreeNode]) = {
    assert(!node.children.contains(node), "A node should not be its own child.")
    val (nodeMaker, nodeMakerLoc) = tacticStringAt(node) match {
      case (nm, Some(DefTactic(name, t))) =>
        tacticDefs(name) = t
        (nm, makeLocNodeMap(nm, node))
      case (nm, _) => (nm, makeLocNodeMap(nm, node))
    }
    val subgoalTactics = node.children.map(getTacticString(_, indent + (if (node.children.size <= 1) "" else INDENT_INCREMENT)))

    //@note expensive: labels are temporary, need to rerun tactic to create labels
    val labels = if (node.children.size > 1) {
      node.goal.map(s => BelleProvable(ProvableSig.startProof(s), None, defs)) match {
        case Some(p) =>
          node.children.headOption.flatMap(_.maker.map(m => tacticDefs.getOrElse(m, BelleParser.parseWithInvGen(m, None, defs)))) match {
            case Some(t) =>
              LazySequentialInterpreter()(t, p) match {
                case BelleProvable(_, Some(labels), _) => labels
                case _ => Nil
              }
            case None =>
              Nil
          }
      }
    } else Nil

    if (subgoalTactics.isEmpty) (indent + nodeMaker, shift(nodeMakerLoc, 0, indent.length))
    else if (subgoalTactics.size == 1) sequentialTactic((nodeMaker, nodeMakerLoc), subgoalTactics.head, indent)
    else if (subgoalTactics.size == labels.size) sequentialTactic((nodeMaker, nodeMakerLoc), {
        val (lines, locs) = subgoalTactics.zip(minimize(labels)).foldLeft[(String, Map[Location, ProofTreeNode])](("", Map.empty))({
          case ((rtext, rloc), ((ttext, tloc), label)) =>
            val indented = indent + INDENT_INCREMENT + "\"" + label.prettyString + "\"" + COLON.img + NEWLINE +
              ttext.linesWithSeparators.map(INDENT_INCREMENT + _).mkString
            val newLoc = shift(tloc, (rtext:StringOps).lines.length + 1, INDENT_INCREMENT.length)
            (rtext + indented + COMMA.img + NEWLINE, rloc ++ newLoc)
        })
        // first line has 1 indent less because sequentialTactic will add it
        (lines.stripSuffix(NEWLINE).stripSuffix(COMMA.img) + NEWLINE + indent + CLOSE_PAREN.img, locs)
      },
      indent, SEQ_COMBINATOR.img + " " + BRANCH_COMBINATOR.img + OPEN_PAREN.img)
    else sequentialTactic((nodeMaker, nodeMakerLoc), {
        val (lines, locs) = subgoalTactics.foldLeft[(String, Map[Location, ProofTreeNode])](EMPTY_TACTIC, Map.empty)({
          case ((rtext, rloc), (ttext, tloc)) =>
            (rtext + ttext + COMMA.img + NEWLINE, rloc ++ shift(tloc, rtext.length, 0))
        })
      // first line has 1 indent less because sequentialTactic will add it
      (lines.stripSuffix(NEWLINE).stripSuffix(COMMA.img) + NEWLINE + indent + CLOSE_PAREN.img, locs)
      },
      indent, SEQ_COMBINATOR.img + " " + BRANCH_COMBINATOR.img + OPEN_PAREN.img)
  }

  /** Returns the unique tails of labels `l` as labels. */
  @tailrec
  private def minimize(l: List[BelleLabel], depth: Int = 1): List[BelleLabel] = {
    if (depth > l.map(_.components.size).max) throw new IllegalArgumentException("Duplicate label in " +
      l.map(_.prettyString).mkString("::") + "\n(verbose) " + l.map(_.toString).mkString("::"))
    val projectedLabels = l.map(_.components.takeRight(depth).map(_.label))
    if (projectedLabels.size == projectedLabels.toSet.size) projectedLabels.map(l =>
      l.tail.foldLeft[BelleLabel](BelleTopLevelLabel(l.head))(BelleSubLabel))
    else minimize(l, depth + 1)
  }

  /** Shifts the location by `lines` and `columns`. */
  private def shift(loc: Map[Location, ProofTreeNode], lines: Int, columns: Int): Map[Location, ProofTreeNode] =
    loc.map({ case (l: Region, n) => (Region(l.line+lines, l.column+columns, l.endLine+lines, l.endColumn+columns), n) })

  /** Composes `ts1` and `ts2` sequentially, trims `ts1` and single-line `ts2` before composing them. */
  private def sequentialTactic(ts1: (String, Map[Location, ProofTreeNode]),
                               ts2: (String, Map[Location, ProofTreeNode]),
                               indent: String, sep: String = SEQ_COMBINATOR.img): (String, Map[Location, ProofTreeNode]) = {
    val (ts1t, ts2t) = if ((ts2._1:StringOps).lines.length <= 1) (ts1._1.trim, ts2._1.trim) else (ts1._1.trim, ts2._1.stripPrefix(indent))
    //@note ts2 location columns are already correct, but indent prefix is temporarily stripped, so add indent prefix back in but shift by 0 columns
    (ts1t, ts2t) match {
      case (EMPTY_TACTIC | EMPTY_BRANCHES, EMPTY_TACTIC | EMPTY_BRANCHES) => (EMPTY_TACTIC, Map.empty)
      case (_, EMPTY_TACTIC | EMPTY_BRANCHES) => (indent + ts1t, shift(ts1._2, 0, indent.length))
      case (EMPTY_TACTIC | EMPTY_BRANCHES, _) => (indent + ts2t, shift(ts2._2, 0, 0))
      case _ =>
        (indent + ts1t + sep + NEWLINE + indent + ts2t,
        shift(ts1._2, 0, indent.length) ++ shift(ts2._2, (ts1t:StringOps).lines.length, 0))
    }
  }

  /** Returns a copy of `pos` with index 0. */
  private def firstInSuccOrAnte(pos: Position): Position =
    if (pos.isAnte) AntePosition.base0(0, pos.inExpr) else SuccPosition.base0(0, pos.inExpr)

  /** Converts fixed position locators into search locators. */
  private def convertLocator(loc: PositionLocator, node: ProofTreeNode): PositionLocator = loc match {
    case Fixed(pos, None, _) => node.goal.flatMap(_.sub(pos.top)) match {
      case Some(e) => Find(0, Some(e), firstInSuccOrAnte(pos), exact=true, defs)
      case None => throw TacticExtractionError("Recorded position " + pos.prettyString + " does not exist in " +
        node.localProvable.subgoals.head.prettyString)
    }
    case Fixed(pos, Some(f), exact) => Find(0, Some(f), firstInSuccOrAnte(pos), exact, defs)
    case Find(goal, None, start, exact, _) =>
      val childGoal = node.children.headOption.flatMap(_.goal)
      val affected =
        if (start.isAnte) node.goal.map(_.ante.filterNot(childGoal.map(_.ante).getOrElse(IndexedSeq.empty).toSet).toList)
        else node.goal.map(_.succ.filterNot(childGoal.map(_.succ).getOrElse(IndexedSeq.empty).toSet).toList)
      affected match {
        case Some(e :: Nil) => Find(goal, Some(e), start, exact, defs)
        case _ => throw TacticExtractionError("Recorded position " + loc.prettyString + " does not exist in " + node.localProvable)
      }
    case _ => loc
  }

  //@note all children share the same maker
  private def tacticStringAt(node: ProofTreeNode): (String, Option[BelleExpr]) = node.children.headOption match {
    case None =>
      if (node.isProved) (EMPTY_TACTIC, None)
      else (TODO_TACTIC, None)
    case Some(c) => c.maker match {
      case None => (SKIP_TACTIC, None)
      case Some(NIL_TACTIC | SKIP_TACTIC) => (EMPTY_TACTIC, None) //@note do not print internal skip steps
      case Some(TODO_TACTIC) => (EMPTY_TACTIC, None) //@note was recorded as TODO_TACTIC, but non-empty children indicate progress since then
      case Some(m) =>
        if (BelleExpr.isInternal(m)) ("???", None) //@note internal tactics are not serializable (and should not be in the trace)
        else Try(BelleParser.parseWithTacticDefs(m, tacticDefs.toMap)).toOption match {
          case Some(t: AppliedPositionTactic) => (BellePrettyPrinter(convertLocator(t, node)), Some(t))
          case Some(t: AppliedDependentPositionTactic) => (BellePrettyPrinter(convertLocator(t, node)), Some(t))
          case Some(t: AppliedDependentPositionTacticWithAppliedInput) => (BellePrettyPrinter(convertLocator(t, node)), Some(t))
          case Some(using@Using(es, t)) => (BellePrettyPrinter(Using(es, convertLocator(t, node))), Some(using))
          case Some(t) => (BellePrettyPrinter(convertLocator(t, node)), Some(t))
          case _ => (m, None)
        }
    }
  }

  /** Converts fixed positions into searchy locators. */
  private def convertLocator(tactic: BelleExpr, node: ProofTreeNode): BelleExpr = tactic match {
    case t@AppliedPositionTactic(_, l) => t.copy(locator = convertLocator(l, node))
    case t: AppliedDependentPositionTactic => new AppliedDependentPositionTactic(t.pt, convertLocator(t.locator, node))
    case t: AppliedDependentPositionTacticWithAppliedInput =>
      new AppliedDependentPositionTacticWithAppliedInput(t.pt, convertLocator(t.locator, node))
    case t => t
  }
}

/** A verbatim trace to tactic converter whose tactics are as recorded in the database. */
class VerbatimTraceToTacticConverter extends TraceToTacticConverter {

  def getTacticString(node: ProofTreeNode, indent: String): (String, Map[Location, ProofTreeNode]) = {
    assert(!node.children.contains(node), "A node should not be its own child.")
    val thisTactic = tacticStringAt(node)
    val subgoals = node.children.map(getTacticString(_, indent + (if (node.children.size <= 1) "" else "  ")))

    //@todo does pretty-printing
    if (subgoals.isEmpty) (thisTactic, Map.empty)
    else if (subgoals.size == 1) (sequentialTactic(thisTactic, subgoals.head._1), Map.empty)
    else (sequentialTactic(thisTactic, BRANCH_COMBINATOR.img + OPEN_PAREN.img + NEWLINE +
      indent + subgoals.map(_._1).mkString(COMMA + NEWLINE + indent) + NEWLINE + indent + CLOSE_PAREN.img), Map.empty)
  }
  
  private def sequentialTactic(ts1: String, ts2: String): String = (ts1.trim(), ts2.trim()) match {
    case (NIL_TACTIC, _) | (SKIP_TACTIC, _) | (TODO_TACTIC, _) => ts2
    case (_, NIL_TACTIC) | (_, SKIP_TACTIC) | (TODO_TACTIC, _)=> ts1
    case (EMPTY_TACTIC | EMPTY_BRANCHES, EMPTY_TACTIC | EMPTY_BRANCHES) => EMPTY_TACTIC
    case (_, EMPTY_TACTIC | EMPTY_BRANCHES) => ts1
    case (EMPTY_TACTIC | EMPTY_BRANCHES, _) => ts2
    case _ => ts1 + " " + SEQ_COMBINATOR.img + " " + ts2
  }

  //@note all children share the same maker
  private def tacticStringAt(node: ProofTreeNode): String = node.children.headOption match {
    case None => TODO_TACTIC
    case Some(c) => c.maker match {
      case None => SKIP_TACTIC
      case Some(m) =>
        if (BelleExpr.isInternal(m)) "???" //@note internal tactics are not serializable (and should not be in the trace)
        else m
    }
  }
}

object TacticExtractionErrors {
  class TacticExtractionError(message: String, cause: Option[Throwable]) extends Exception(message + {cause match {case Some(e) => ". Caused by: " + e.getMessage; case None => ""}})
  object TacticExtractionError {
    def apply(message: String, cause: Exception) = new TacticExtractionError(message, Some(cause))
    def apply(message: String, cause: Throwable) = new TacticExtractionError(message, Some(cause))
    def apply(message: String) = new TacticExtractionError(message, None)
  }
}

