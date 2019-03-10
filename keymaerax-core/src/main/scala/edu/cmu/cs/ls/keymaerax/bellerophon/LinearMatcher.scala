package edu.cmu.cs.ls.keymaerax.bellerophon

import edu.cmu.cs.ls.keymaerax.core._

import scala.collection.immutable._

/**
  * LinearMatcher(shape, input) matches second argument `input` against the LINEAR pattern `shape` of the first argument but not vice versa.
  * Matcher leaves input alone and only substitutes into shape.
  *
  * Linear matchers require linear shapes, so no symbol can occur twice in the shape.
  * Or if a symbol does occur twice, then it is assumed that the identical match is found.
  * Implemented by a fast single pass.
  * @author Andre Platzer
  */
object LinearMatcher extends SchematicUnificationMatch {
  override protected def unifier(e1: Expression, e2: Expression, us: List[SubstRepl]): Subst = {
    val s = Subst(us.distinct)
    logger.debug("  unify: " + e1.prettyString + "\n  with:  " + e2.prettyString + "\n  via:   " + s)
    s
  }

  override protected def unifier(e1: Sequent, e2: Sequent, us: List[SubstRepl]): Subst = {
    val s = Subst(us.distinct)
    logger.debug("  unify: " + e1.prettyString + "\n  with:  " + e2.prettyString + "\n  via:   " + s)
    s
  }

  /** Composition of renaming substitution representations: compose(after, before) gives the representation of `after` performed after `before`. */
  override protected def compose(after: List[(Expression, Expression)], before: List[(Expression, Expression)]): List[(Expression, Expression)] = before ++ after

  /** unifies(s1,s2, t1,t2) unifies (s1,s2) against (t1,t2) by matching.
    * Note: because this is for matching purposes, the unifier u1 is not applied to t2 on the right premise.
    */
  override protected def unifies(s1: Expression, s2: Expression, t1: Expression, t2: Expression): List[(Expression, Expression)] =
    compose(unify(s1,t1), unify(s2,t2))

  override protected def unifies(s1: Term, s2: Term, t1: Term, t2: Term): List[(Expression, Expression)] =
    compose(unify(s1,t1), unify(s2,t2))

  override protected def unifies(s1: Formula, s2: Formula, t1: Formula, t2: Formula): List[(Expression, Expression)] =
    compose(unify(s1,t1), unify(s2,t2))

  override protected def unifies(s1: Program, s2: Program, t1: Program, t2: Program): List[(Expression, Expression)] =
    compose(unify(s1,t1), unify(s2,t2))

  override protected def unifiesODE(s1: DifferentialProgram, s2: DifferentialProgram, t1: DifferentialProgram, t2: DifferentialProgram): List[(Expression, Expression)] =
    compose(unifyODE(s1,t1), unifyODE(s2,t2))

  override protected def unify(s1: Sequent, s2: Sequent): List[SubstRepl] =
    if (!(s1.ante.length == s2.ante.length && s1.succ.length == s2.succ.length)) ununifiable(s1,s2)
    else {
      val combine = (us:List[SubstRepl],st:(Formula,Formula)) => compose(us, unify(st._1,st._2))
      compose(s1.ante.zip(s2.ante).foldLeft(id)(combine), s1.succ.zip(s2.succ).foldLeft(id)(combine))
    }
}
