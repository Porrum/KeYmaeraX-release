/**
  * Copyright (c) Carnegie Mellon University.
  * See LICENSE.txt for the conditions of this license.
  */
/**
  * Stripped down exception hierarchy.
  */
package edu.cmu.cs.ls.keymaerax.core

/**
  * KeYmaera X Prover Exceptions.
  */
class ProverException(msg: String, cause: Throwable = null) extends RuntimeException(msg, cause) {
  def inContext(context: String, additionalMessage : String = ""): ProverException = this
  def getContext: String = ""
}


/** Reasoning exceptions directly from the KeYmaera X Prover Kernel.
  * The most important distinction of prover kernel exceptions are
  * [[CriticalCoreException]] for unsound logical mistakes versus
  * [[NoncriticalCoreException]] for plausible but inappropriate reasoning attempts. */
class CoreException(msg: String) extends ProverException(msg)


// critical logical prover kernel exceptions

/** Critical reasoning exceptions directly from the KeYmaera X Prover Core that indicate a proof step was
  * attempted that would be unsound, and was consequently denied. */
class CriticalCoreException(msg: String) extends CoreException(msg)


/** Exceptions that arise from trying to substitute a subderivation in for a subgoal that does not equal the conclusion of the subderivation.
  * @see [[edu.cmu.cs.ls.keymaerax.core.Provable.apply(rule:edu\.cmu\.cs\.ls\.keymaerax\.core\.Provable,subgoal:edu\.cmu\.cs\.ls\.keymaerax\.core\.Provable\.Subgoal):edu\.cmu\.cs\.ls\.keymaerax\.core\.Provable*]] */
case class SubderivationSubstitutionException(subderivation: String/*Provable*/, conclusion: String, subgoal: String/*Sequent*/, subgoalid: Int, provable: String/*Provable*/)
  extends CriticalCoreException("Subderivation substitution for subgoal does not fit to the subderivation's conclusion.\nsubderivation " + subderivation + "\nconclude: " + conclusion + "\nexpected: " + subgoal + " @" + subgoalid + " into\n" + provable)

/** Substitution clashes are raised for unsound substitution reasoning attempts.
  * @see [[USubstOne]]
  * @see [[edu.cmu.cs.ls.keymaerax.core.Provable.apply(subst:edu\.cmu\.cs\.ls\.keymaerax\.core\.USubstChurch):edu\.cmu\.cs\.ls\.keymaerax\.core\.Provable*]]
  */
case class SubstitutionClashException(subst: String/*USubst*/, U: String/*SetLattice[NamedSymbol]*/, e: String/*Expression*/, context: String/*Expression*/, clashes: String/*SetLattice[NamedSymbol]*/, info: String = "")
  extends CriticalCoreException("Substitution clash:\n" + subst + "\nis not (" + U + ")-admissible\nfor " + e + "\nwhen substituting in " + context + "\n" + info)

/** Uniform or bound renaming clashes are unsound renaming reasoning attempts.
  * @see [[BoundRenaming]]
  * @see [[UniformRenaming]]
  * @see [[URename]]
  * @see [[edu.cmu.cs.ls.keymaerax.core.Provable.apply(ren:edu\.cmu\.cs\.ls\.keymaerax\.core\.URename):edu\.cmu\.cs\.ls\.keymaerax\.core\.Provable*]]
  */
case class RenamingClashException(msg: String, ren: String/*URename*/, e: String/*Expression*/, info: String = "")
  extends CriticalCoreException(msg + "\nRenaming " + e + " via " + ren + "\nin " + info)

/** Skolem symbol clashes are unsound Skolemization reasoning attempts.
  * @see [[Skolemize]]
  */
case class SkolemClashException(msg: String, clashedNames:SetLattice[Variable], vars:String/*Seq[Variable]*/, s:String/*Sequent*/)
  extends CriticalCoreException(msg + " " + clashedNames + "\nwhen skolemizing variables " + vars + "\nin " + s)


// mediocre prover kernel exceptions whose presence does not indicate logical errors but still malfunctioning uses

/** Exception indicating an attempt to steal a proved sequent from a Provable that was not proved.
  * @see [[Provable.proved]]
  * @see [[Provable.isProved]]
  */
class UnprovedException(msg: String, provable: String) extends CoreException("Unproved provable: " + msg + "\n" + provable)

/** Exception indicating that a Provable Storage representation as a String cannot be read, because it has been tampered with.
  * @see [[Provable.fromStorageString]]
  */
class ProvableStorageException(msg: String, storedProvable: String) extends CoreException("Stored Provable " + msg + "\n" + storedProvable)


// noncritical prover kernel exceptions

/** Noncritical reasoning exceptions directly from the KeYmaera X Prover Core that indicate a proof step was
  * perfectly plausible to try, just did not fit the expected pattern, and consequently turned out impossible. */
class NoncriticalCoreException(msg: String) extends CoreException(msg)

// internal prover kernel exceptions

/** Internal core assertions that fail in the prover */
case class ProverAssertionError(msg: String) extends ProverException("Assertion failed " + msg)

/** Thrown to indicate when an unknown operator occurs */
case class UnknownOperatorException(msg: String, e: Expression) extends ProverException(msg + ": " + e.prettyString + " of " + e.getClass + " " + e) {}

/** Nil case for exceptions, which is almost useless except when an exception type is needed to indicate that there really was none. */
object NoProverException extends ProverException("No prover exception has occurred")
