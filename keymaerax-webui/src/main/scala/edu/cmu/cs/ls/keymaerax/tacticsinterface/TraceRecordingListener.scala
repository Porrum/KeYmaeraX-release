package edu.cmu.cs.ls.keymaerax.tacticsinterface

import edu.cmu.cs.ls.keymaerax.bellerophon._
import edu.cmu.cs.ls.keymaerax.pt.ProvableSig
import edu.cmu.cs.ls.keymaerax.hydra.{DBAbstraction, ExecutionStepPOJO, ExecutionStepStatus, ProofPOJO}
import edu.cmu.cs.ls.keymaerax.hydra.ExecutionStepStatus.ExecutionStepStatus

import scala.collection.mutable.ListBuffer

/**
  * Created by bbohrer on 11/20/15.
  */

/**
  * @param ruleName A display name merely for UI purposes
  */
class TraceRecordingListener(db: DBAbstraction,
                             proofId: Int,
                             initialSibling: Option[Int],
                             globalProvable:ProvableSig,
                             branch:Int,
                             recursive: Boolean,
                             ruleName: String) extends IOListener {
  class TraceNode (isFirstNode: Boolean){
    var id: Option[Int] = None
    var parent: TraceNode = null
    var sibling: Option[Int] = None
    var output: ProvableSig = null
    var local: ProvableSig = null
    var executable: BelleExpr = null
    var status: ExecutionStepStatus = null
    var reverseChildren: List[TraceNode] = Nil
    def children = reverseChildren.reverse
    /* This is generated by the DB, so it will not be present when we first create an object for the step. However,
       we need to set it once it has been generated so other steps can get the appropriate ID.
     */
    var stepId: Option[Int] = None
    val branchLabel: String = null
    val branchOrder: Int = branch
    val userExe = isFirstNode

    var localProvableId: Option[Int] = None
    var executableId: Option[Int] = None

    def getLocalProvableId:Option[Int] = {
      if (local != null && localProvableId.isEmpty)
        localProvableId = Some(db.createProvable(local))
      localProvableId
    }

    def getExecutableId:Int = {
      if (executable != null && executableId.isEmpty)
        executableId = Some(db.addBelleExpr(executable))
      executableId.get
    }

    def asPOJO: ExecutionStepPOJO = {
      //val parentStep = if (parent == null) None else parent.stepId
      ExecutionStepPOJO (stepId, proofId, sibling, branchOrder,
        status, getExecutableId, None, None,
        getLocalProvableId, userExe, ruleName,
        if (local != null) local.subgoals.size else -1,
        if (local != null) local.subgoals.size else -1)
    }
  }

  var youngestSibling: Option[Int] = initialSibling
  var node: TraceNode = null
  var isDead: Boolean = false
  val nodesWritten: ListBuffer[TraceNode] = ListBuffer()

  /* Debug info: Records how deep inside the tree of begin-end pairs we are */
  var depth: Int = 0
  def begin(v: BelleValue, expr: BelleExpr): Unit = {
    synchronized {
      depth = depth + 1
      if(isDead) return
      val parent = node
      node = new TraceNode(isFirstNode = parent == null)
      node.parent = parent
      node.sibling = youngestSibling
      node.executable = expr
      node.status = ExecutionStepStatus.Running

      if (parent != null) {
        parent.status = ExecutionStepStatus.DependsOnChildren
        parent.reverseChildren = node :: parent.reverseChildren
        if (recursive) {
          db.updateExecutionStep(parent.stepId.get, parent.asPOJO)
        }
      }
      if (parent == null || recursive) {
        // delete recorded steps that would become unwanted siblings of the current step
        // (steps can succeed and be later undone with a failing & done; problematic when in a | combinator)
        if (node.sibling.isDefined) {
          val undoSiblings = db.getPlainStepSuccessors(proofId, node.sibling.get, node.branchOrder)
          undoSiblings.foreach(f => db.deleteExecutionStep(proofId, f.stepId.get))
        } else db.getFirstExecutionStep(proofId) match {
          case Some(undo) if undo.branchOrder == node.branchOrder => db.deleteExecutionStep(proofId, undo.stepId.get)
          case _ => //
        }
        node.stepId = Some(db.addExecutionStep(node.asPOJO))
        nodesWritten.prepend(node)
      }
    }
  }

  def end(v: BelleValue, expr: BelleExpr, result: Either[BelleValue, BelleThrowable]): Unit = {
    synchronized {
      depth = depth - 1
      if(isDead) return
      val current = node
      node = node.parent
      youngestSibling = current.id
      current.status =
        result match {
          case Left(_) => ExecutionStepStatus.Finished
          case Right(_) => ExecutionStepStatus.Error
        }
      if (node != null && !recursive) return
//      db.updateExecutionStep(current.stepId.get, current.asPOJO)
//      if (node == null) {
//        result match {
//          // Only reconstruct provables for the top-level because the meaning of "branch" can change inside a tactic
//          case Left(BelleProvable(p, _)) =>
//            current.output = globalProvable(p, branch)
//            current.local = p
//          case _ =>
//        }
//        if (current.output != null) {
//          db.updateExecutionStep(current.stepId.get, current.asPOJO)
//          if (current.output.isProved) {
//            val p = db.getProofInfo(proofId)
//            val provedProof = new ProofPOJO(p.proofId, p.modelId, p.name, p.description, p.date, p.stepCount,
//              closed = true, p.provableId, p.temporary)
//            db.updateProofInfo(provedProof)
//          }
//        }
//      }
      if (node == null) {
        result match {
          // Only reconstruct provables for the top-level because the meaning of "branch" can change inside a tactic
          case Left(BelleProvable(p, _)) =>
            // no longer want to construct global provables (want to allow halfway done substitutions)
            current.local = p
            db.updateExecutionStep(current.stepId.get, current.asPOJO)
            if (db.getPlainOpenSteps(proofId).isEmpty) {
              //@note proof might be done
              val p = db.getProofInfo(proofId)
              val provedProof = new ProofPOJO(p.proofId, p.modelId, p.name, p.description, p.date, p.stepCount,
                closed = true, p.provableId, p.temporary, p.tactic)
              db.updateProofInfo(provedProof)
            }
          case _ =>
            db.updateExecutionStep(current.stepId.get, current.asPOJO)
        }
      }
    }
  }

  /** Called by HyDRA before killing the interpreter's thread. Updates the database to reflect that the computation
    * was interrupted. There are two race conditions to worry about here:
    * (1) kill() can race with a call to begin/end that was in progress when kill() started. This is resolved with
    * a mutex (synchronized{} blocks)
    * (2) An in-progress computation can race with a kill signal (sent externally after kill() is called). This is
    * resolved by setting a flag during kill() which turns future operations into a no-op. */
  def kill(): Unit = {
    synchronized {
      isDead = true
      nodesWritten.foreach(node =>
      node.stepId.foreach{case id =>
        node.status = ExecutionStepStatus.Aborted
        db.updateExecutionStep(id, node.asPOJO)})
    }
  }
}