package edu.cmu.cs.ls.keymaera.api

import edu.cmu.cs.ls.keymaera.api.KeYmaeraInterface.TaskManagement.TaskStatus
import edu.cmu.cs.ls.keymaera.core._
import edu.cmu.cs.ls.keymaera.parser.KeYmaeraParser
import edu.cmu.cs.ls.keymaera.tactics.Tactics.{PositionTactic, Tactic}
import edu.cmu.cs.ls.keymaera.tactics.{TacticWrapper, Tactics}
import edu.cmu.cs.ls.keymaera.core.Sequent

import scala.reflect.runtime.universe.{TypeTag,typeTag}

/**
 * Open issues:
 * - How do we identify where changes came from?
 *   Consider the following case: A user starts a tactic on a node and then does some steps himself. If we now poll the node
 *   to find out about its children we might get a bunch of alternatives. Which one is it the user wants to see and how do we identify it?
 * - How can we attach meta information for the GUI?
 * - What information do we want to attach?
 *
 * Created by jdq on 6/12/14.
 */
object KeYmaeraInterface {

  object TaskManagement {
    object TaskStatus extends Enumeration {
      type TaskStatus = Value
      val NotLoaded, Loading, Loaded = Value
    }

    /**
     * Map from task IDs to (maps from sequent ids to associated nodes)
     */
    @volatile var tasks: Map[String, (ProofNode, Map[String, ProofNode])] = Map()

    /**
     * Map from task IDs (map from proof node to associated node ID)
     */
    @volatile var proofNodeIds: Map[String, Map[ProofNode, String]] = Map()

    /**
     * The tasks currently loading.
     */
    @volatile var loading: Set[String] = Set()

    def startLoadingTask(taskId : String) {
      loading += taskId
    }

    def finishedLoadingTask(taskId : String) {
      loading -= taskId
    }

    def addTask(r: ProofNode, taskId: String) = this.synchronized {
      assert(r.children.isEmpty)
      tasks += (taskId -> (r, Map()))
      proofNodeIds += (taskId -> Map())
    }

    def addNode(tId: String, nId: String, p: ProofNode) = this.synchronized {
      tasks.get(tId) match {
        case Some(v) =>
          tasks += (tId -> (v._1, v._2 + (nId -> p)))
          proofNodeIds.get(tId) match {
            case Some(ids) => proofNodeIds += (tId -> (ids + (p -> nId)))
            case None => throw new IllegalStateException("Proof node IDs must have been initialized with task")
          }
        case None => throw new IllegalArgumentException("Task not found " + tId)
      }
    }

    def containsTask(taskId: String) = tasks.contains(taskId)

    def getRoot(id: String): Option[ProofNode] = tasks.get(id) match {
      case Some(t) => Some(t._1)
      case None => None
    }

    def getNode(tId: String, nId: String): Option[ProofNode] = {
      tasks.get(tId).map(_._2.get(nId)).flatten
    }
  }

  object TacticManagement {
    @volatile var tactics: Map[String, Tactic] = Map()
    @volatile var positionTactics: Map[String, PositionTactic] = Map()
    @volatile var inputTactics: Map[String, (TypeTag[_], _ => Tactic)] = Map()
    @volatile var input2Tactics: Map[String, ((TypeTag[_], TypeTag[_]), (_,_) => Tactic)] = Map()
    @volatile var input3Tactics: Map[String, ((TypeTag[_], TypeTag[_], TypeTag[_]), (_,_,_) => Tactic)] = Map()
    @volatile var inputPositionTactics: Map[String, (TypeTag[_], _ => PositionTactic)] = Map()

    def addTactic(id: String, t: Tactic) = this.synchronized {
      if (tactics.contains(id)) throw new IllegalArgumentException("Duplicate ID " + id)
      tactics += (id -> t)
    }

    def addInputTactic[T](id: String, t: T => Tactic)(implicit m: TypeTag[T]) = this.synchronized {
      if (inputTactics.contains(id)) throw new IllegalArgumentException("Duplicate ID " + id)
      inputTactics += (id -> (m, t))
    }
    def addInputTactic[T,U](id: String, t: (T,U) => Tactic)(implicit m: TypeTag[T], n: TypeTag[U]) =
      this.synchronized {
        if (input2Tactics.contains(id)) throw new IllegalArgumentException("Duplicate ID " + id)
        input2Tactics += (id -> ((m,n), t))
      }
    def addInputTactic[T,U,V](id: String, t: (T,U,V) => Tactic)(implicit m: TypeTag[T], n: TypeTag[U], o: TypeTag[V]) =
        this.synchronized {
      if (input3Tactics.contains(id)) throw new IllegalArgumentException("Duplicate ID " + id)
      input3Tactics += (id -> ((m,n,o), t))
    }

    def addPositionTactic(id: String, t: PositionTactic) = this.synchronized {
      if (positionTactics.contains(id)) throw new IllegalArgumentException("Duplicate ID " + id)
      positionTactics += (id -> t)
    }

    def addInputPositionTactic[T](id: String, t: T => PositionTactic)(implicit m : TypeTag[T]) = this.synchronized {
      if (inputPositionTactics.contains(id)) throw new IllegalArgumentException("Duplicate ID " + id)
      inputPositionTactics += (id -> (m, t))
    }

    def getTactic(id: String): Option[Tactic] = tactics.get(id)

    def getPositionTactic(id: String): Option[PositionTactic] = positionTactics.get(id)

    def getTactics: List[(String, String)] = tactics.foldLeft(Nil: List[(String, String)])((a, p) => a :+ (p._1, p._2.name))

    def getPositionTactics: List[(String, String)] = positionTactics.foldLeft(Nil: List[(String, String)])((a, p) => a :+ (p._1, p._2.name))

    def getInputTactic(id: String, input: Map[Int,String]): Option[Tactic] = {
      if (inputTactics.contains(id)) {
        val inputType = inputTactics.get(id).map({ case (t, _) => t}) match { case Some(t) => t }
        val theInput = TacticInputConverter.convert(input, inputType)
        getInputTactic(id, theInput)
      } else if (input2Tactics.contains(id)) {
        val inputType = input2Tactics.get(id).map({ case (t, _) => t}) match { case Some(t) => t }
        val theInput = TacticInputConverter.convert2(input, inputType)
        getInputTactic(id, theInput)
      } else if (input3Tactics.contains(id)) {
        val inputType = input3Tactics.get(id).map({ case (t, _) => t}) match { case Some(t) => t }
        val theInput = TacticInputConverter.convert3(input, inputType)
        getInputTactic(id, theInput)
      } else None
    }

    def getInputPositionTactic(id: String, input: Map[Int,String]): Option[PositionTactic] = {
      val inputType = inputPositionTactics.get(id).map({ case (t, _) => t}) match { case Some(t) => t }
      val theInput = TacticInputConverter.convert(input, inputType)
      getInputPositionTactic(id, theInput)
    }

    private def getInputTactic[T](id: String, input: T): Option[Tactic] = inputTactics.get(id).map({
      case (om, f : (T => Tactic)) => f(input)
      case _ => throw new IllegalArgumentException("Unexpected parameter type")
    })
    private def getInputTactic[T,U](id: String, input : (T,U)): Option[Tactic] = input2Tactics.get(id).map({
      case ((om, on), f : ((T,U) => Tactic)) => f(input._1, input._2)
      case _ => throw new IllegalArgumentException("Unexpected parameter type")
    })
    private def getInputTactic[T,U,V](id: String, input : (T,U,V)): Option[Tactic] = input3Tactics.get(id).map({
      case ((om, on, oo), f : ((T,U,V) => Tactic)) => f(input._1, input._2, input._3)
      case _ => throw new IllegalArgumentException("Unexpected parameter type")
    })

    private def getInputPositionTactic[T](id: String, input : T): Option[PositionTactic] = inputPositionTactics.get(id).map({
      case (om, f : (T => PositionTactic)) => f(input)
      case _ => throw new IllegalArgumentException("Unexpected parameter type")
    })
  }

  object RunningTactics {
    private var tactics: Map[String, Tactic] = Map()
    def add(t: Tactic, tId: String) = this.synchronized {
      tactics += (tId -> t)
    }

    def get(id: String): Option[Tactic] = tactics.get(id)
  }

  /**
   * Indicates whether the specified task is known (loaded) to the prover.
   * @param taskId Identifies the task.
   * @return True, if the task is known (loaded). False otherwise.
   */
  def containsTask(taskId: String): Boolean = TaskManagement.containsTask(taskId)

  /**
   * Indicates whether the specified task is currently loading.
   * @param taskId Identifies the task.
   * @return True, if the task is currently loading. False otherwise.
   */
  def isLoadingTask(taskId : String) : Boolean = TaskManagement.loading.contains(taskId)

  /**
   * Gets the status of the specified task.
   * @param taskId Identifies the task.
   * @return The task status.
   */
  def getTaskStatus(taskId : String) : TaskStatus.Value = {
    if (containsTask(taskId)) TaskStatus.Loaded
    else if (isLoadingTask(taskId)) TaskStatus.Loading
    else TaskStatus.NotLoaded
  }

  /**
   * Parse the problem and add it to the tasks management system
   *
   * @param taskId Identifies the task.
   * @param content The model content (keyFile).
   * @return JSON representation of the content.
   */
  def addTask(taskId: String, content: String): String = {
    if (TaskManagement.containsTask(taskId)) throw new IllegalArgumentException("Duplicate task ID " + taskId)
    TaskManagement.startLoadingTask(taskId)
    new KeYmaeraParser().runParser(content) match {
      case f: Formula =>
        val seq = Sequent(List(), collection.immutable.IndexedSeq[Formula](), collection.immutable.IndexedSeq[Formula](f) )
        val r = new RootNode(seq)
        TaskManagement.addTask(r, taskId)
        val result = json(r, taskId, 0, taskId, printSequent = false)
        TaskManagement.finishedLoadingTask(taskId)
        result
      case a => throw new IllegalArgumentException("Parsing the input did not result in a formula but in: " + a)
    }
  }

  def getNode(taskId: String, nodeId: Option[String]): Option[String] = nodeId match {
      case Some(id) => TaskManagement.getNode(taskId, id).map(json(_: ProofNode, id, 0, taskId, printSequent = true))
      case None => TaskManagement.getRoot(taskId).map(json(_: ProofNode, taskId.toString, 0, taskId, printSequent = true))
  }

  def addTactic(id : String, t : Tactic) = {
    TacticManagement.addTactic(id, t)
  }
  def addTactic[T](id : String, t : T => Tactic)(implicit m : TypeTag[T]) = {
    TacticManagement.addInputTactic(id, t)
  }
  def addTactic[T,U,V](id : String, t : (T,U,V) => Tactic)(implicit m : TypeTag[T], n : TypeTag[U], o : TypeTag[V]) = {
    TacticManagement.addInputTactic(id, t)
  }

  def addPositionTactic(id : String, t : PositionTactic) = {
    TacticManagement.addPositionTactic(id, t)
  }
  def addPositionTactic[T](id : String, t : T => PositionTactic)(implicit m : TypeTag[T]) = {
    TacticManagement.addInputPositionTactic(id, t)
  }

  def getTactics: List[(String, String)] = TacticManagement.getTactics
  def getTactic(id : String) : Option[Tactic] = TacticManagement.getTactic(id)

  def getPositionTactics: List[(String, String)] = TacticManagement.getPositionTactics
  def getPositionTactic(id : String) : Option[PositionTactic] = TacticManagement.getPositionTactic(id)

  /**
   * Gets the position of an (optional) formula in the node identified by the task ID and node ID.
   * @param taskId Identifies the task.
   * @param nodeId Identifies the node. If None, it identifies the "root" task node.
   * @param formulaId Identifies the formula. If None, it searches for no specific position within the node.
   * @return A tuple of proof node and optional position within the node.
   */
  def getPosition(taskId: String, nodeId: Option[String], formulaId: Option[String]) : (ProofNode, Option[Position]) = {
    (nodeId match {
      case Some(id) => TaskManagement.getNode(taskId, id)
      case None => TaskManagement.getRoot(taskId)
    }) match {
      case Some(n) =>
        formulaId match {
          case Some(fid) =>
            val tail = fid.split(":")(1).toInt
            if(fid.startsWith("ante:")) (n, Some(new AntePosition(tail))) else (n, Some(new SuccPosition(tail)))
          case None => (n, None)
        }
      case None => throw new IllegalArgumentException("Unknown task " + taskId + "/" + nodeId)
    }
  }

  /**
   * Gets the tactics that are applicable to a formula in a sequent identified by task ID and node ID.
   * @param taskId Identifies the task.
   * @param nodeId Identifies the node. If None, this method considers the "root" task node.
   * @param formulaId Identifies the formula. If None, this method returns the tactics applicable to the sequent itself.
   * @return A list of tactic IDs.
   */
  def getApplicableTactics(taskId: String, nodeId: Option[String], formulaId: Option[String]) = {
    getPosition(taskId, nodeId, formulaId) match {
      case (n, Some(p)) =>
        TacticManagement.positionTactics.filter(t => t._2.applies(n.sequent, p)).map(_._1) ++:
        TacticManagement.inputPositionTactics
          .filter(t => t._2 match {
          case (om, f: ((_) => PositionTactic)) => if (om.tpe =:= typeTag[Option[Formula]].tpe)
            f.asInstanceOf[Option[Formula] => PositionTactic].apply(Some(null)).applies(n.sequent, p)
          else false
          case _ => throw new IllegalArgumentException("Tactics " + t + " with unknown input type ")
        })
          .map(t => t._1)
      case (n, None) =>
        TacticManagement.tactics.filter(t => t._2.applicable(n)).map(_._1) ++:
        TacticManagement.inputTactics.filter(t => t._2 match {
          case (om, f: ((_) => Tactic)) =>
            if (om.tpe =:= typeTag[Option[Formula]].tpe)
              f.asInstanceOf[Option[Formula] => Tactic].apply(Some(null)).applicable(n)
            else if (om.tpe =:= typeTag[String].tpe)
              // TODO axiomT... how should we ever find out which ones? just try all of them?
              f.asInstanceOf[String => Tactic].apply(null).applicable(n)
            else false
//          case (f: (Int => PositionTactic)) => f(0).applicable(n)
          case _ => throw new IllegalArgumentException("Position tactics " + t + " with unknown input type ")
        }).map(t => t._1) ++:
        // TODO filter
        TacticManagement.input2Tactics.map(_._1) ++:
        TacticManagement.input3Tactics.map(_._1)
    }
  }

  /**
   * Runs the specified tactic on the formula of a sequent identified by task ID and node ID.
   * @param taskId the task for which this tactic is dispatched
   * @param nodeId the proof node on which to run the tactic (None to execute on the root node)
   * @param tacticId the tactic to dispatch
   * @param formulaId the formula (None to execute on the sequent)
   * @param tId the ID of the dispatched tactic instance
   * @param callback callback executed when the tactic finishes
   * @param input the input in case tacticId refers to an input tactic
   */
  def runTactic(taskId: String, nodeId: Option[String], tacticId: String, formulaId: Option[String], tId: String,
                callback: Option[String => ((String, Option[String], String) => Unit)] = None,
                input: Map[Int,String] = Map.empty) = {
    val (node,position) = getPosition(taskId, nodeId, formulaId)
    val tactic = position match {
      case Some(p) =>
        TacticManagement.getPositionTactic(tacticId) match {
          case Some(t) => Some(t(p))
          case None => TacticManagement.getInputPositionTactic(tacticId, input) match {
            case Some(t) => Some(t(p))
            case None => None
          }
        }
      case None => TacticManagement.getTactic(tacticId) match {
        case Some(t) => Some(t)
        case None => TacticManagement.getInputTactic(tacticId, input)
      }
    }
    tactic match {
      case Some(t) =>
        // attach a info transformation function which later on allows us to track then changes performed by this tactic
        // observe that since all nodes should be produced by tactics spawned off here, the nodes will all have a tactic label
        RunningTactics.add(t, tId)
        // register listener to react on tactic completion
        // this way the business logic can react to the completion if required
        t.registerCompletionEventListener(_ => {
          generateIds()(tId)(taskId, nodeId, tacticId)
          callback.foreach(_(tId)(taskId, nodeId, tacticId))
        })
        t.updateInfo = (p: ProofNodeInfo) => p.infos += ("tactic" -> tId.toString)
        t.updateStepInfo = (p: ProofStepInfo) => p.infos += ("tactic" -> tId.toString)
        Tactics.KeYmaeraScheduler.dispatch(new TacticWrapper(t, node))
      case None => None
    }
  }

  def isRunning(tacticInstanceId: String): Boolean = {
    RunningTactics.get(tacticInstanceId) match {
      case Some(t) => !t.isComplete
      case None => false
    }
  }

  /**
   * Gets the open goals under the specified node (identified by task ID and node ID).
   * @param taskId Identifies the task.
   * @param nodeId Identifies the node. If None, then identifies the root node.
   * @return A list of IDs of open goals (proof nodes without children).
   */
  def getOpenGoals(taskId: String, nodeId: Option[String] = None) : List[String] = {
    (nodeId match {
      case Some(nid) => TaskManagement.getNode(taskId, nid)
      case None => TaskManagement.getRoot(taskId)
    }) match {
      case Some(pn)=> pn.openGoals().map(p => TaskManagement.proofNodeIds.get(taskId) match {
        case Some(ids) => ids.get(p) match {
          case Some(pnId) => pnId
          case None => throw new IllegalStateException("Proof node with unknown ID")
        }
        case None => throw new IllegalStateException("Proof node IDs must have been initialized with task")
      })
      case _ => List()
    }
  }

  /**
   * Returns the number of open goals under the specified node (identified by task ID and node ID).
   * @param taskId Identifies the task.
   * @param nodeId Identifies the node. If None, then identifies the root node.
   * @return The number of open goals.
   */
  def getOpenGoalCount(taskId: String, nodeId: Option[String] = None) : Int = {
    (nodeId match {
      case Some(nid) => TaskManagement.getNode(taskId, nid)
      case None => TaskManagement.getRoot(taskId)
    }) match {
      case Some(pn)=> pn.openGoals().size
      case _ => 0
    }
  }

  /**
   * This methods allows to poll for updates downwards from a given node
   * @param taskId Identifies the task.
   * @param nodeId Identifies the proof node. If None, identifies the root node of the proof.
   * @param depth The depth of the sub tree
   * @return The sub tree serialized to JSON (schema as per prooftree.js)
   */
  def getSubtree(taskId: String, nodeId: Option[String], depth: Int, printSequent: Boolean): Option[String] = {
    (nodeId match {
      case Some(id) => (id, TaskManagement.getNode(taskId, id))
      case None => (taskId, TaskManagement.getRoot(taskId))
    }) match {
      case (id, Some(n)) => Some(getSubtree(n, id, depth, taskId, printSequent))
      case (_, None) => None
    }
  }

  def getSubtree(taskId: String, nodeId: Option[String], filter: (ProofStepInfo => Boolean), printSequent: Boolean): Option[String] = {
    (nodeId match {
      case Some(id) => (id, TaskManagement.getNode(taskId, id))
      case None => (taskId, TaskManagement.getRoot(taskId))
    }) match {
      case (id, Some(n)) => Some(getSubtree(n, id, filter, taskId, printSequent))
      case (_, None) => None
    }

  }

  def getSequent(taskId : String, nodeId : String, branchId : String) : Option[String] = {
    this.getActualNode(taskId, Some(nodeId)) match {
      case Some(node) => None
      case None => None
    }
  }

  /**
   * Generates IDs for new proof nodes created by the specified tactic.
   * @param tId Identifies the tactic instance.
   * @param taskId Identifies the task.
   * @param nId Identifies the node.
   * @param tacticId Identifies the tactic.
   */
  private def generateIds()(tId: String)(taskId: String, nId: Option[String], tacticId: String) {
    getSubtree(taskId, nId, (p: ProofStepInfo) => { p.infos.get("tactic") == Some(tId.toString) },
        printSequent = false) match {
      case Some(s) =>
        // s is JSON representation of proof node. This proof node gets an ID as a side effect of generating
        // the JSON representation. This is the result that we want. Nothing else to do.
        // TODO refactor the JSON generation. Generating IDs should not be a side effect of it.
      case None => // tactic did not yield any result. Nothing to do
    }
  }

  private def getSubtree(n: ProofNode, id: String, depth: Int, rootId: String, printSequent: Boolean): String =
    json(n, id, depth, rootId, printSequent)

  private def getSubtree(n: ProofNode, id: String, filter: (ProofStepInfo => Boolean), rootId: String,
                         printSequent: Boolean): String = json(n, id, filter, rootId, printSequent)

  // TODO: maybe allow listeners to node change events

 // def json(p: ProofNode): String = JSONConverter(p)

  private def json(p: ProofNode, id: String, l: Int, rootId: String, printSequent: Boolean): String = JSONConverter(p, id, l, (x: ProofNode, i: String) => TaskManagement.addNode(rootId, i, x), printSequent).prettyPrint

  private def json(p: ProofNode, id: String, filter: (ProofStepInfo => Boolean), rootId: String, printSequent: Boolean): String = JSONConverter(p, id, filter, (x: ProofNode, i: String) => TaskManagement.addNode(rootId, i, x), printSequent).prettyPrint

  private def getActualNode(taskId : String, nodeIdOpt : Option[String]) : Option[ProofNode] = nodeIdOpt match {
    case Some(nodeId) => TaskManagement.getNode(taskId, nodeId)
    case None         => TaskManagement.getRoot(taskId)
  }
}
