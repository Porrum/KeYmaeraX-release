package edu.cmu.cs.ls.keymaerax.cli

import java.io.{ByteArrayOutputStream, PrintWriter}

import edu.cmu.cs.ls.keymaerax.Configuration
import edu.cmu.cs.ls.keymaerax.bellerophon.{IllFormedTacticApplicationException, TacticInapplicableFailure}
import edu.cmu.cs.ls.keymaerax.btactics.TacticTestBase
import edu.cmu.cs.ls.keymaerax.cli.AssessmentProver.{AnyChoiceGrader, Artifact, AskGrader, AskTFGrader, BoolArtifact, ChoiceArtifact, ExpressionArtifact, Grader, ListExpressionArtifact, MultiArtifact, MultiAskGrader, OneChoiceGrader, SequentArtifact, TexExpressionArtifact, TextArtifact}
import edu.cmu.cs.ls.keymaerax.cli.QuizExtractor._
import edu.cmu.cs.ls.keymaerax.cli.Submission.TextAnswer
import edu.cmu.cs.ls.keymaerax.core._
import edu.cmu.cs.ls.keymaerax.infrastruct.FormulaTools
import edu.cmu.cs.ls.keymaerax.parser.StringConverter._
import org.scalatest.Inside.inside
import org.scalatest.prop.TableDrivenPropertyChecks._
import org.scalatest.LoneElement._
import org.scalatest.EitherValues._
import spray.json._

import scala.io.Source

class AssessmentProverTests extends TacticTestBase {

  private val COURSE_PATH: String = "/Course-current"
  private val QUIZ_PATH: String = COURSE_PATH + "/diderot/quizzes"

  private val RANDOM_TRIALS = 3
  private val rand = RepeatableRandom()

  "Extractor" should "extract grading information" in {
    Problem.fromString("""\begin{problem}\label{prob:withoutpoints} \ask \sol{\kyxline"x>=0}" \end{problem}""") shouldBe 'empty
    inside (Problem.fromString("""\begin{problem}[1.0]\label{prob:first} \ask \sol{\kyxline"x>=0"} \end{problem}""")) {
      case p :: Nil =>
        p.name shouldBe 'empty
        p.points should contain (1.0)
        p.label should contain ("prob:first")
        p.questions shouldBe List(AskQuestion(None, Map.empty, ExpressionArtifact("x>=0".asFormula), List(ExpressionArtifact("x>=0".asFormula)), List.empty))
    }
    inside (Problem.fromString("""\begin{problem}[4.][Problem A] \ask Something simple \sol{\kyxline"x>=0"} \end{problem}""")) {
      case p :: Nil =>
        p.name should contain ("Problem A")
        p.points should contain (4.0)
        p.questions shouldBe List(AskQuestion(None, Map.empty, ExpressionArtifact("x>=0".asFormula), List(ExpressionArtifact("x>=0".asFormula)), List.empty))
    }
    inside (Problem.fromString("""\begin{problem}[4.][Problem A] \ask A tex question \sol{$\{1,(-2),x^2\}$} \end{problem}""")) {
      case p :: Nil =>
        p.name should contain ("Problem A")
        p.points should contain (4.0)
        p.questions shouldBe List(AskQuestion(None, Map.empty, TexExpressionArtifact("x=1|x=-2|x=x^2".asFormula), List(TexExpressionArtifact("x=1|x=-2|x=x^2".asFormula)), List.empty))
    }
    inside (Problem.fromString("""\begin{problem}[4.][Problem A] \ask A tex interval question \sol{$[-2.0,3) \cup (1,\infty)$} \end{problem}""")) {
      case p :: Nil =>
        p.name should contain ("Problem A")
        p.points should contain (4.0)
        p.questions shouldBe List(AskQuestion(None, Map.empty, TexExpressionArtifact("-2.0<=x&x<3|1<x&true".asFormula), List(TexExpressionArtifact("-2.0<=x&x<3|1<x&true".asFormula)), List.empty))
    }
    inside (Problem.fromString("""\begin{problem}[1.0][Problem B] \ask A syntactic equality \sol{\kyxline"x>=0"} \autog{syneq()} \end{problem}""")) {
      case p :: Nil =>
        p.name should contain ("Problem B")
        p.questions shouldBe
          List(AskQuestion(Some("syneq"), Map.empty, ExpressionArtifact("x>=0".asFormula), List(ExpressionArtifact("x>=0".asFormula)), List.empty))
    }
    inside (Problem.fromString(
      """\begin{problem}[1.0]
        |\ask \sol{\kyxline"x>=0"} \autog{syneq()}
        |\ask \sol{\kyxline"y=2"}
        |\autog{prove(question="#1 -> [{x'=v}]x>=0",
        |             tactic="auto")}
        |\end{problem}""".stripMargin)) {
      case p :: Nil =>
        p.questions shouldBe
          List(
            AskQuestion(Some("syneq"), Map.empty, ExpressionArtifact("x>=0".asFormula), List(ExpressionArtifact("x>=0".asFormula)), List.empty),
            AskQuestion(Some("prove"), Map("question" -> "#1 -> [{x'=v}]x>=0", "tactic" -> "auto"),
              ExpressionArtifact("y=2".asFormula), List(ExpressionArtifact("y=2".asFormula)), List.empty)
          )
    }
    inside (Problem.fromString("""\begin{problem}[1.0]\ask \sol{\kyxline"x>=0"} \autog{polyeq(vars="x")}\end{problem}""")) {
      case p :: Nil =>
        p.questions shouldBe
          List(AskQuestion(Some("polyeq"), Map("vars"->"x"), ExpressionArtifact("x>=0".asFormula), List(ExpressionArtifact("x>=0".asFormula)), List.empty))
    }
    inside (Problem.fromString("""\begin{problem}[1.0]\ask \sol{\kyxline"x>=0"} \testsol{\kyxline"x+1>=1"} \testsol{\kyxline"x+2>=2"} \autog{polyeq(vars="x", question="x>=0 -> [{x'=2}@invariant(x>=0)}*]x>=0")}\end{problem}""")) {
      case p :: Nil =>
        p.questions shouldBe
          List(AskQuestion(Some("polyeq"), Map("vars"->"x", "question"->"x>=0 -> [{x'=2}@invariant(x>=0)}*]x>=0"), ExpressionArtifact("x>=0".asFormula),
            List(ExpressionArtifact("x>=0".asFormula), ExpressionArtifact("x+1>=1".asFormula), ExpressionArtifact("x+2>=2".asFormula)), List.empty))
    }
    inside (Problem.fromString("""\begin{problem}[1.0]\ask \sol{\kyxline"x>=0"} \testsol{\kyxline"x+1>=1"} \nosol{\kyxline"x+1>=0"} \nosol{\kyxline"x-1>=2"} \autog{polyeq(vars="x")}\end{problem}""")) {
      case p :: Nil =>
        p.questions shouldBe
          List(AskQuestion(Some("polyeq"), Map("vars"->"x"), ExpressionArtifact("x>=0".asFormula),
            List(ExpressionArtifact("x>=0".asFormula), ExpressionArtifact("x+1>=1".asFormula)),
            List(ExpressionArtifact("x+1>=0".asFormula), ExpressionArtifact("x-1>=2".asFormula))))
    }
    inside (Problem.fromString(
      """\begin{problem}[1.0]
        |\ask A DI question
        |\sol{\kyxline"2*x=y"}
        |\autog{dI(vars="x",
        |          question="{x'=1,y'=2}")}
        |\end{problem}""".stripMargin)) {
      case p :: Nil =>
        p.questions shouldBe
          List(AskQuestion(Some("dI"), Map("vars"->"x", "question"->"{x'=1,y'=2}"), ExpressionArtifact("2*x=y".asFormula), List(ExpressionArtifact("2*x=y".asFormula)), List.empty))
    }
    inside (Problem.fromString("""\begin{problem}[1.0]\ask \sol{\kyxline"x>0 ==> x>=0 ;; y<0 ==> y<=0"}\end{problem}""")) {
      case p :: Nil =>
        p.questions shouldBe
          List(AskQuestion(None, Map.empty,
            SequentArtifact(List("x>0 ==> x>=0".asSequent, "y<0 ==> y<=0".asSequent)),
            List(SequentArtifact(List("x>0 ==> x>=0".asSequent, "y<0 ==> y<=0".asSequent))), List.empty))
    }
    inside (Problem.fromString("""\begin{problem}[1.0]\ask \sol{\kyxline"x>0,y>0,z>0"}\end{problem}""")) {
      case p :: Nil =>
        p.questions shouldBe
          List(AskQuestion(None, Map.empty,
            ListExpressionArtifact(List("x>0".asFormula, "y>0".asFormula, "z>0".asFormula)),
            List(ListExpressionArtifact(List("x>0".asFormula, "y>0".asFormula, "z>0".asFormula))), List.empty))
    }
    inside (Problem.fromString(
      """\begin{problem}[1.0]
        |\ask
        |\solfin
        |\begin{lstlisting}
        |x>=0 -> [{?____~~~~ true ~~~~____; x:=x+1;}*@invariant(____~~~~ x>=0 ~~~~____)]x>=0
        |\end{lstlisting}
        |\autog{loop()}
        |\end{problem}""".stripMargin)) {
      case p :: Nil =>
        p.questions shouldBe
          List(AskQuestion(Some("loop"), Map("question" -> "x>=0 -> [{?#1; x:=x+1;}*@invariant(#2)]x>=0"),
            ListExpressionArtifact("true".asFormula :: "x>=0".asFormula :: Nil),
            List(ListExpressionArtifact("true".asFormula :: "x>=0".asFormula :: Nil)), List.empty))
    }
    inside (Problem.fromString(
      """\begin{problem}[1.0]
        |\ask \sol{\kyxline"x>=0"}
        |\onechoice
        |A choice question
        |\choice  Wrong answer
        |\choice* Right answer
        |\choice  Another wrong answer
        |\onechoice
        |Another choice question
        |\choice* Correct
        |\choice Incorrect
        |\end{problem}""".stripMargin)) {
      case p :: Nil =>
        p.questions shouldBe List(
          AskQuestion(None, Map.empty, ExpressionArtifact("x>=0".asFormula),
            List(ExpressionArtifact("x>=0".asFormula)), List.empty),
          OneChoiceQuestion("A choice question", List(
            QuizExtractor.Choice("Wrong answer", isCorrect=false),
            QuizExtractor.Choice("Right answer", isCorrect=true),
            QuizExtractor.Choice("Another wrong answer", isCorrect=false))),
          OneChoiceQuestion("Another choice question", List(
            QuizExtractor.Choice("Correct", isCorrect=true),
            QuizExtractor.Choice("Incorrect", isCorrect=false)))
        )
    }
    inside (Problem.fromString(
      """\begin{problem}[1.0]
        |\ask Question 1 \sol{\kyxline"x*y^2=-1"}
        |\ask Question 2 \sol{\kyxline"y'=y/2"}
        |\autog{prove(question="x<0 -> [{x'=-x}]x<0",tactic="implyR(1); dG({`#1`},{`#-1`},1); dI(1.0); QE; done")}
        |\end{problem}""".stripMargin)) {
      case p :: Nil =>
        val first = AskQuestion(None, Map.empty, ExpressionArtifact("x*y^2=-1".asFormula), List(ExpressionArtifact("x*y^2=-1".asFormula)), List.empty)
        val second = AskQuestion(Some("prove"), Map("question"->"x<0 -> [{x'=-x}]x<0", "tactic"->"implyR(1); dG({`#1`},{`#-1`},1); dI(1.0); QE; done"),
          ExpressionArtifact("y'=y/2".asFormula),
          List(ExpressionArtifact("y'=y/2".asFormula)), List.empty)
        p.questions shouldBe List(first, MultiAskQuestion(second, Map(-1 -> first)))
    }
    // test that all test cases are read
    inside (Problem.fromString(
      """\begin{problem}[1.0]
        |\ask Question 1
        |\sol{\kyxline"3"}
        |\testsol{\kyxline"2+1"}
        |\testsol{$\{1,2,3\}$}
        |\testsol{$[3,4] \cup \lbrack -1,\infty)$}
        |\testsol{A text answer}
        |\nosol{\kyxline"2+2"}
        |\nosol{A wrong text answer}
        |\nosol{$[8,9] \cup \lbrack -2,4)$}
        |\nosol{$\{5,6,7\}$}
        |\nosol{ }
        |\autog{syneq()}
        |\end{problem}""".stripMargin)) {
      case p :: Nil =>
        p.questions shouldBe List(
          AskQuestion(
            grader=Some("syneq"),
            args=Map.empty,
            expected=ExpressionArtifact(Number(3)),
            testSols=List(
              ExpressionArtifact("3".asTerm),
              ExpressionArtifact("2+1".asTerm),
              TexExpressionArtifact("x=1|x=2|x=3".asFormula),
              TexExpressionArtifact("3<=x&x<=4 | -1<=x&true".asFormula),
              TextArtifact(Some("A text answer"))
            ),
            noSols=List(
              ExpressionArtifact("2+2".asTerm),
              TextArtifact(Some("A wrong text answer")),
              TexExpressionArtifact("8<=x&x<=9 | -2<=x&x<4".asFormula),
              TexExpressionArtifact("x=5|x=6|x=7".asFormula),
              TextArtifact(None)
            )
          )
        )
    }
  }

  "Polynomial equality" should "prove simple term examples" in withZ3 { _ =>
    AssessmentProver.polynomialEquality("2*x^2".asTerm, "x^2*2".asTerm) shouldBe 'proved
    AssessmentProver.polynomialEquality("x^3*y^2".asTerm, "y * x/1^2 * 4*x^2/2^2 * y".asTerm) shouldBe 'proved
    AssessmentProver.polynomialEquality("2*x^2".asTerm, "x^(1+3-2)*(3-1)/(-1+2)".asTerm) shouldBe 'proved
  }

  it should "prove simple formula examples" in withZ3 { _ =>
    AssessmentProver.polynomialEquality("true".asFormula, "true".asFormula, normalize=false) shouldBe 'proved
    AssessmentProver.polynomialEquality("[ctrl;]true".asFormula, "[ctrl;]true".asFormula, normalize=false) shouldBe 'proved
    AssessmentProver.polynomialEquality("[ctrl;]P()".asFormula, "[ctrl;]P()".asFormula, normalize=false) shouldBe 'proved
    inside (AssessmentProver.polynomialEquality("x>=0".asFormula, "x+0*5>=2-4*1/2".asFormula, normalize=false)) {
      case p =>
        p.conclusion shouldBe "==> x=x+0*5 & 0=2-4*1/2".asSequent
        p shouldBe 'proved
    }
    inside (AssessmentProver.polynomialEquality("[x:=2;][?x>=0;]x>=0".asFormula, "[x:=3-1;][?x+0*5>=2-4*1/2;]x+0>=1-1".asFormula, normalize=false)) {
      case p =>
        p.conclusion shouldBe "==> x=x & 2=3-1 & x=x+0*5&0=2-4*1/2 & x=x+0 & 0=1-1".asSequent
        p shouldBe 'proved
    }
    inside (AssessmentProver.polynomialEquality("[x':=2;]x>=0".asFormula, "[x':=3-1;]x>=0".asFormula, normalize=false)) {
      case p =>
        p.conclusion shouldBe "==> x'=x' & 2=3-1 & x=x & 0=0".asSequent
        p shouldBe 'proved
    }
    the [IllegalArgumentException] thrownBy AssessmentProver.polynomialEquality(
      "x>=0".asFormula, "x+0*5>2-4*1/2".asFormula, normalize=false) should have message
      "requirement failed: Formula operators do not match at position '.'; expected x>=0 (GreaterEqual) but got x+0*5>2-4*1/2 (Greater)"
    the [IllegalArgumentException] thrownBy AssessmentProver.polynomialEquality(
      "[x:=2;]x>=0".asFormula, "[?x=2;]x+0*5>2-4*1/2".asFormula, normalize=false) should have message
      "requirement failed: Program operators do not match at position '.0'; expected x:=2; (Assign) but got ?x=2; (Test)"
    the [IllFormedTacticApplicationException] thrownBy AssessmentProver.polynomialEquality(
      "[x:=2;]x>=0".asFormula, "[x':=2;]x>=0".asFormula, normalize=false) should have message
      "Unable to create dependent tactic 'ANON', cause: 1*x^1 and 1*x'^1 do not fit"
  }

  it should "prove formula examples with normalization" in withZ3 { _ =>
    inside (AssessmentProver.polynomialEquality("x>=0".asFormula, "x+0*5>=2-4*1/2".asFormula, normalize=true)) {
      case p =>
        p.conclusion shouldBe "==> x=x+0*5-(2-4*1/2) & 0=0".asSequent
        p shouldBe 'proved
    }
    inside (AssessmentProver.polynomialEquality("x>=0".asFormula, "-(x+0*5)<=-(2-4*1/2)".asFormula, normalize=true)) {
      case p =>
        p.conclusion shouldBe "==> x=-(2-4*1/2)--(x+0*5) & 0=0".asSequent
        p shouldBe 'proved
    }
    inside (AssessmentProver.polynomialEquality("x>=0 & y=2".asFormula, "!(!-(x+0*5)<=-(2-4*1/2) | y!=2)".asFormula, normalize=true)) {
      case p =>
        p.conclusion shouldBe "==> x=-(2-4*1/2)--(x+0*5) & 0=0 & y-2=y-2 & 0=0".asSequent
        p shouldBe 'proved
    }
    the [IllegalArgumentException] thrownBy AssessmentProver.polynomialEquality(
      "x>=0".asFormula, "-(x+0*5) < -(2-4*1/2)".asFormula, normalize=true) should have message
      "requirement failed: Formula operators do not match at position '.'; expected x>=0 (GreaterEqual) but got -(2-4*1/2)--(x+0*5)>0 (Greater)"
  }

  "Value equality" should "prove simple examples" in withZ3 { _ =>
    AssessmentProver.valueEquality("1".asTerm, "1".asTerm) shouldBe 'proved
    AssessmentProver.valueEquality("1".asTerm :: "2".asTerm :: Nil, "1+0".asTerm :: "4-2".asTerm :: Nil) shouldBe 'proved
  }

  "DI result check" should "prove simple DI examples" in withZ3 { _ =>
    AssessmentProver.dIPremiseCheck("==> [v':=w;][w':=-v;]2*v*v'+2*w*w'-0=0".asSequent, "==> [v':=w;][w':=-v;]2*v*v'+2*w*w'=0".asSequent, diffAssignsMandatory=true, normalize=false) shouldBe 'proved
    AssessmentProver.dIPremiseCheck("==> [v':=w;][w':=-v;]2*v*v'+2*w*w'-0=0".asSequent, "==> [w':=-v;][v':=w;]2*v*v'+2*w*w'=0".asSequent, diffAssignsMandatory=true, normalize=false) shouldBe 'proved
    AssessmentProver.dIPremiseCheck("==> [v':=w;][w':=-v;]2*v*v'+2*w*w'-0=0".asSequent, "==> [w':=-v;v':=w;]2*v*v'+2*w*w'=0".asSequent, diffAssignsMandatory=true, normalize=false) shouldBe 'proved
    the [IllegalArgumentException] thrownBy
      AssessmentProver.dIPremiseCheck("==> [v':=w;][w':=-v;]2*v*v'+2*w*w'-0=0".asSequent, "==> 2*v*w+2*w*v=0".asSequent, diffAssignsMandatory=true, normalize=false) should
      have message "requirement failed: Differential assignments do not match: expected assignments to v',w' but found none"
    the [IllegalArgumentException] thrownBy
      AssessmentProver.dIPremiseCheck("==> [v':=w;][w':=-v;]2*v*v'+2*w*w'-0=0".asSequent, "==> [v':=w;][w':=-v;]2*v*w<=2*w*v".asSequent, diffAssignsMandatory=true, normalize=false) should
      have message "requirement failed: Formula operators do not match at position '.1'; expected 2*v*w+2*w*(-v)-0=0 (Equal) but got 2*v*w<=2*w*v (LessEqual)"
  }

  it should "allow (partially) executed differential assignments on request" in withZ3 { _ =>
    AssessmentProver.dIPremiseCheck("==> [v':=w;][w':=-v;]2*v*v'+2*w*w'-0=0".asSequent, "==> [w':=-v;]2*v*w+2*w*w'=0".asSequent, diffAssignsMandatory=false, normalize=false) shouldBe 'proved
    AssessmentProver.dIPremiseCheck("==> [v':=w;][w':=-v;]2*v*v'+2*w*w'-0=0".asSequent, "==> 2*v*w-2*w*v=0".asSequent, diffAssignsMandatory=false, normalize=false) shouldBe 'proved
    the [TacticInapplicableFailure] thrownBy AssessmentProver.dIPremiseCheck("==> [v':=w;][w':=-v;]2*v*v'+2*w*w'-0=0".asSequent, "==> 2*v*w=2*w*v".asSequent, diffAssignsMandatory=false, normalize=false) should
      have message "Terms not equal (by equating coefficients): 2*v*w+2*w*(-v)-0, 2*v*w"
    the [TacticInapplicableFailure] thrownBy
      AssessmentProver.dIPremiseCheck("==> [v':=w;][w':=-v;]2*v*v'+2*w*w'-0=0".asSequent, "==> 2*v*w+2*w*v=0".asSequent, diffAssignsMandatory=false, normalize=false) should
      have message "Terms not equal (by equating coefficients): 2*v*w+2*w*(-v)-0, 2*v*w+2*w*v"
    the [IllegalArgumentException] thrownBy
      AssessmentProver.dIPremiseCheck("==> [v':=w;][w':=-v;]2*v*v'+2*w*w'-0=0".asSequent, "==> 2*v*w<=2*w*v".asSequent, diffAssignsMandatory=false, normalize=false) should
      have message "requirement failed: Formula operators do not match at position '.1'; expected 2*v*w+2*w*(-v)-0=0 (Equal) but got 2*v*w<=2*w*v (LessEqual)"
    the [IllegalArgumentException] thrownBy
      AssessmentProver.dIPremiseCheck("==> [v':=w;][w':=-v;]2*v*v'+2*w*w'-0=0".asSequent, "==> 2*v*w<=2*w*v & 0=0".asSequent, diffAssignsMandatory=false, normalize=false) should
      have message "requirement failed: Formula operators do not match at position '.1'; expected 2*v*w+2*w*(-v)-0=0 (Equal) but got 2*v*w<=2*w*v&0=0 (And)"
  }

  it should "normalize" in withZ3 { _ =>
    AssessmentProver.dIPremiseCheck("==> [v':=w;][w':=-v;]2*v*v'+2*w*w'-0=0".asSequent, "==> [v':=w;][w':=-v;]2*v*w=2*w*v".asSequent, diffAssignsMandatory=true, normalize=true) shouldBe 'proved
    AssessmentProver.dIPremiseCheck("==> [v':=w;][w':=-v;]2*v*v'+2*w*w'-0=0".asSequent, "==> 2*v*w=2*w*v".asSequent, diffAssignsMandatory=false, normalize=true) shouldBe 'proved
  }

  "QE equivalence" should "prove simple examples" in withZ3 { _ =>
    AssessmentProver.qe("x>=0".asFormula, "0<=x".asFormula, Equiv)
    AssessmentProver.qe("x>=4".asFormula, "x>=0 & x^2>=16".asFormula, Equiv) shouldBe 'proved
    AssessmentProver.qe("x=1".asFormula, "x^2>=1 & x^2<=x".asFormula, Equiv) shouldBe 'proved
    withTemporaryConfig(Map(Configuration.Keys.QE_ALLOW_INTERPRETED_FNS -> "true")) {
      AssessmentProver.qe("x>=4".asFormula, "abs(x)>=4 & abs(x)<=x".asFormula, Equiv) shouldBe 'proved
    }
    AssessmentProver.qe("x>=4".asFormula, "\\forall y (0<=y&y<=4 -> x>=y)".asFormula, Equiv) shouldBe 'proved
    AssessmentProver.qe("x>=4".asFormula, "\\exists y (y>=2 & x>=y^2)".asFormula, Equiv) shouldBe 'proved
  }

  "Syntactic sequent equality" should "prove simple examples" in {
    AssessmentProver.syntacticEquality(List("x>0 ==> x>=0".asSequent), List("x>0 ==> x>=0".asSequent)) shouldBe 'proved
    AssessmentProver.syntacticEquality(List("x>0,y>0 ==> x>=0".asSequent), List("y>0,x>0 ==> x>=0".asSequent)) shouldBe 'proved
    AssessmentProver.syntacticEquality(List("x>0 ==> x>=0,y>0".asSequent), List("x>0 ==> y>0,x>=0".asSequent)) shouldBe 'proved
    AssessmentProver.syntacticEquality(
      List("x>0 ==> x>=0".asSequent, "y>0 ==> y>=0".asSequent),
      List("x>0 ==> x>=0".asSequent, "y>0 ==> y>=0".asSequent)) shouldBe 'proved
    the [IllFormedTacticApplicationException] thrownBy AssessmentProver.syntacticEquality(
      List("y>0 ==> y>=0".asSequent, "x>0 ==> x>=0".asSequent),
      List("x>0 ==> x>=0".asSequent, "y>0 ==> y>=0".asSequent)) should have message
        """Unable to create dependent tactic 'equivReflexive', cause: requirement failed: Conclusion of fact
          |ElidingProvable(Provable(  ==>  (y>0->y>=0)&(x>0->x>=0)<->(y>0->y>=0)&(x>0->x>=0) proved))
          |must match sole open goal in
          |ElidingProvable(Provable(  ==>  (y>0->y>=0)&(x>0->x>=0)<->(x>0->x>=0)&(y>0->y>=0)
          |  from     ==>  (y>0->y>=0)&(x>0->x>=0)<->(x>0->x>=0)&(y>0->y>=0)))""".stripMargin
  }

  "Differential invariant checker" should "prove simple examples" in withZ3 { _ =>
    AssessmentProver.dICheck(ODESystem("{x'=1,y'=2}".asDifferentialProgram), "2*x=y".asFormula) shouldBe 'proved
    AssessmentProver.dICheck(ODESystem("{x'=x,y'=-y}".asDifferentialProgram), "x*y=1".asFormula) shouldBe 'proved
    AssessmentProver.dICheck(ODESystem("{x'=-y,y'=x}".asDifferentialProgram), "x^2+y^2=1".asFormula) shouldBe 'proved
    AssessmentProver.dICheck(ODESystem("{x'=1,y'=2}".asDifferentialProgram), "3*x=y".asFormula).subgoals.loneElement shouldBe "3*x=y, true ==> 3*1=2".asSequent
  }

  "Generic prove checker" should "prove simple examples" in withZ3 { _ =>
    AskGrader(Some(AskGrader.Modes.BELLE_PROOF), Map("tactic" -> "chase(1);prop"), ExpressionArtifact("A() -> [prg;]B()".asFormula)).
      check(ExpressionArtifact("A()->[prg;]B()".asFormula)).left.value shouldBe 'proved
    AskGrader(Some(AskGrader.Modes.BELLE_PROOF), Map("tactic" -> "chase(1);prop"), SequentArtifact("A() ==> [prg;]B()".asSequent::Nil)).
      check(SequentArtifact("==> A() -> [prg;]B()".asSequent::Nil)).left.value shouldBe 'proved
    val p = AskGrader(Some(AskGrader.Modes.BELLE_PROOF), Map("tactic" -> "chase(1);prop"), SequentArtifact("==> A() -> [prg;]B()".asSequent::"[sys;]C() ==> ".asSequent::Nil)).
      check(SequentArtifact("A() ==> [prg;]B()".asSequent::"==> [sys;]C() -> false&x=4".asSequent::Nil))
    p.left.value.conclusion shouldBe "==> ((A() -> [prg;]B()) <-> (true -> A() -> [prg;]B())) & ((true -> [sys;]C() -> false&x=4) <-> ([sys;]C() -> false))".asSequent
    p.left.value shouldBe 'proved
  }

  it should "prove optional question with solution as loop tactic input" in withZ3 { _ =>
    AskGrader(
      Some(AskGrader.Modes.BELLE_PROOF),
      Map(
        "question" -> "x>2 & y>=1 -> [{x:=x+y;y:=y+2;}*]x>1",
        "tactic" -> "implyR(1);loop({`#1`},1);auto;done"),
      ExpressionArtifact("false".asFormula)). //@note ignored because question will be used instead
      check(ExpressionArtifact("x>1&y>=0".asFormula)
    ).left.value shouldBe 'proved
  }

  it should "prove optional question with a list of diffcuts" in withZ3 { _ =>
    AskGrader(
      Some(AskGrader.Modes.BELLE_PROOF),
      Map(
        "question" -> "x>=3 & v>=2 & a>=1 & j>=0 -> [{x'=v,v'=a,a'=j}]x>=3",
        "tactic" -> "implyR(1); for #i do dC({`#i`},1); <(nil, dI(1); done) endfor; dW(1); QE; done"
      ),
      ExpressionArtifact("false".asFormula)). //@note ignored because question will be used instead
      check(ListExpressionArtifact("a>=1".asFormula :: "v>=2".asFormula :: "x>=3".asFormula :: Nil)
    ).left.value shouldBe 'proved
  }

  it should "prove optional question with solution as uniform substitution tactic input" in withZ3 { _ =>
    AskGrader(
      Some(AskGrader.Modes.BELLE_PROOF),
      Map(
        "question" -> "x<=m & V>=0 -> [{{v:=0; ++ ?Q(m,t,x,T,V);v:=V;};{x'=v,t'=1 & t<=T}}*]x<=m",
        "tactic" -> "US({`Q(m,t,x,T,V) ~> #1`});implyR(1);loop({`x<=m`},1);auto;done"),
      ExpressionArtifact("false".asFormula) //@note ignored because question will be used instead
    ).check(
      ExpressionArtifact("x<=m-V*(T-t)".asFormula)
    ).left.value shouldBe 'proved
  }

  it should "reply with expected answer type to wrong answer format" in {
    AskGrader(Some(AskGrader.Modes.SYN_EQ), Map.empty, ExpressionArtifact("x>0".asFormula)).
      check(SequentArtifact("==> x>0".asSequent :: Nil)).right.value shouldBe "Expected a Formula but got a Sequent"
  }

  "Program equivalence" should "prove simple examples" in withZ3 { _ =>
    AssessmentProver.prgEquivalence("a;b;c;".asProgram, "{a;b;};c;".asProgram) shouldBe 'proved
    AssessmentProver.prgEquivalence("a;++b;++c;".asProgram, "{a;++b;}++c;".asProgram) shouldBe 'proved
    AssessmentProver.prgEquivalence("{a;++b;}{c;++d;++e;}".asProgram, "{a;++b;}{{c;++d;}++e;}".asProgram) shouldBe 'proved
    AssessmentProver.prgEquivalence("x:=2;++y:=3;".asProgram, "y:=4-1;++z:=2;x:=z;".asProgram) shouldBe 'proved
  }

  it should "prove simple system loops" in withZ3 { _ =>
    AssessmentProver.prgEquivalence("{a{|^@|};++b{|^@|};}*".asProgram, "{b{|^@|};++a{|^@|};}*".asProgram) shouldBe 'proved
  }

  it should "elaborate to system loops when dual does not occur literally" in withZ3 { _ =>
    AssessmentProver.prgEquivalence("{a;++b;}*".asProgram, "{b;++a;}*".asProgram) shouldBe 'proved
  }

  "AnyChoice grading" should "not give points when no answer was selected" in withZ3 { _ =>
    val problems = (2 to 16).flatMap(i => extractProblems(QUIZ_PATH + "/" + i + "/main.tex"))
    val anyChoiceProblems = problems.map(p => p.copy(questions = p.questions.filter(_.isInstanceOf[AnyChoiceQuestion]))).toList
    val graders = anyChoiceProblems.flatMap(p => p.questions.map(toGrader)).map(_._1)
    graders.foreach(_.check(ChoiceArtifact(Nil)).right.value shouldBe "Incorrect answer")
  }

  "Quiz checking" should "prove quiz 2" in withZ3 { _ =>
    val problems = extractProblems(QUIZ_PATH + "/2/main.tex")
    problems.map(p => (p.name.getOrElse(""), p.questions.size)) shouldBe
      ("Solve ODEs", 5) :: ("Vector Field Examples", 4) :: ("Semantics of terms", 4) ::
      ("Semantics of formulas", 5) :: ("Formulas as evolution domain constraints", 4) :: Nil
    run(problems)
  }

  it should "prove quiz 3" in withZ3 { _ =>
    val problems = extractProblems(QUIZ_PATH + "/3/main.tex")
    problems.map(p => (p.name.getOrElse(""), p.questions.size)) shouldBe
      ("Programs vs. formulas vs. terms", 10) :: ("Misplaced parentheses", 3) ::
        ("Reachable Sets", 5) :: ("Program Shapes", 8) :: ("Modeling pitfalls", 4) :: Nil
    run(problems)
  }

  it should "prove quiz 4" in withZ3 { _ =>
    val problems = extractProblems(QUIZ_PATH + "/4/main.tex")
    problems.map(p => (p.name.getOrElse(""), p.questions.size)) shouldBe
      ("", 10) :: ("Truth Identification", 7) :: ("Multiple pre/postconditions", 4) ::
        ("Direct velocity control", 1) :: Nil
    run(problems)
  }

  it should "prove quiz 5" in withZ3 { _ =>
    val problems = extractProblems(QUIZ_PATH + "/5/main.tex")
    problems.map(p => (p.name.getOrElse(""), p.questions.size)) shouldBe
      ("Axiom application", 10) :: ("Axiom identification: Top", 6) :: ("Axiom identification: All", 6) ::
        ("Distributivity and non-distributivity", 5) :: ("If-then-else", 2) :: ("Nondeterministic assignments", 2) :: Nil
    run(problems)
  }

  it should "prove quiz 6" in withZ3 { _ =>
    val problems = extractProblems(QUIZ_PATH + "/6/main.tex")
    problems.map(p => (p.name.getOrElse(""), p.questions.size)) shouldBe
      ("Rule application", 10) :: ("Rule identification", 8) :: ("Arithmetic simplification", 6) ::
        ("Proof rule criticism", 10) :: Nil
    run(problems)
  }

  it should "prove quiz 7" in withZ3 { _ =>
    val problems = extractProblems(QUIZ_PATH + "/7/main.tex")
    problems.map(p => (p.name.getOrElse(""), p.questions.size)) shouldBe
      ("Loop invariants", 5) :: ("Other Loop Rules", 8) ::
        ("Incremental design in direct velocity control", 3) :: Nil
    run(problems)
  }

  it should "prove quiz 8" in withZ3 { _ =>
    val problems = extractProblems(QUIZ_PATH + "/8/main.tex")
    problems.map(p => (p.name.getOrElse(""), p.questions.size)) shouldBe
      ("Revisiting ping-pong events", 4) :: ("Faithful Event Models", 6) ::
        ("Identify event invariants", 3) :: ("Incremental design in velocity event control", 5) :: Nil
    run(problems)
  }

  it should "prove quiz 9" in withZ3 { _ =>
    val problems = extractProblems(QUIZ_PATH + "/9/main.tex")
    problems.map(p => (p.name.getOrElse(""), p.questions.size)) shouldBe
      ("Comparing event-triggered versus time-triggered controllers", 4) :: ("Reaction Times", 2) ::
        ("From event responses to reaction times", 5) :: Nil
    run(problems)
  }

  it should "prove quiz 10" in withZ3 { _ =>
    val problems = extractProblems(QUIZ_PATH + "/10/main.tex")
    problems.map(p => (p.name.getOrElse(""), p.questions.size)) shouldBe
      ("Differential invariance", 10) :: ("Identify differential invariants", 5) ::
        ("Differential Invariant Rules", 10) :: Nil
    run(problems)
  }

  it should "prove quiz 11" in withZ3 { _ =>
    val problems = extractProblems(QUIZ_PATH + "/11/main.tex")
    problems.map(p => (p.name.getOrElse(""), p.questions.size)) shouldBe
      ("Explain differential cuts", 8) :: ("Identify differential invariants to cut", 10) ::
        ("Differential Invariance Rules", 14) :: Nil
    run(problems)
  }

  it should "prove quiz 12" in withZ3 { _ =>
    val problems = extractProblems(QUIZ_PATH + "/12/main.tex")
    problems.map(p => (p.name.getOrElse(""), p.questions.size)) shouldBe
      ("Using differential ghosts", 3) :: ("Differential ghost construction", 16) ::
        ("Parachute", 2) :: Nil
    run(problems)
  }

  it should "prove quiz 13" in withZ3 { _ =>
    val problems = extractProblems(QUIZ_PATH + "/13/main.tex")
    problems.map(p => (p.name.getOrElse(""), p.questions.size)) shouldBe
      ("Provability with differential invariants", 3) :: ("Differential invariant reduction", 5) ::
        ("Differential invariant search", 2) :: Nil
    run(problems)
  }

  it should "prove quiz 14" in withZ3 { _ =>
    val problems = extractProblems(QUIZ_PATH + "/14/main.tex")
    problems.map(p => (p.name.getOrElse(""), p.questions.size)) shouldBe
      ("Player Count", 5) :: ("Strategically reachable set", 6) :: ("Game Shapes", 2) ::
        ("Truth Identification", 10) :: Nil
    run(problems)
  }

  it should "prove quiz 15" in withZ3 { _ =>
    val problems = extractProblems(QUIZ_PATH + "/15/main.tex")
    problems.map(p => (p.name.getOrElse(""), p.questions.size)) shouldBe
      ("Semantic comparisons", 4) :: ("Game Region Shapes", 6) :: ("Game loop semantics", 5) ::
        ("Direct velocity control", 1) :: Nil
    run(problems)
  }

  it should "prove quiz 16" in withZ3 { _ =>
    val problems = extractProblems(QUIZ_PATH + "/16/main.tex")
    problems.map(p => (p.name.getOrElse(""), p.questions.size)) shouldBe
      ("Truth Identification", 5) :: ("Axiom or not?", 10) :: ("Demon's controls", 5) ::
        ("Robot simple chase game", 1) :: Nil
    run(problems)
  }

  "Submission extraction" should "extract answers in the order listed in the file" in {
    //@todo assumes that there will be information to identify the chapter in the tex source (outermost title, or label)
    val s = Source.fromInputStream(getClass.getResourceAsStream("/edu/cmu/cs/ls/keymaerax/cli/submission.json")).mkString
    import Submission.SubmissionJsonFormat._
    s.parseJson.convertTo[Submission.Chapter] shouldBe Submission.Chapter(11, "ch:qdiffcut", List(
      Submission.Problem(25053, "Problem block 1 (2 questions)", "prob::1", List(
        Submission.Prompt(141, "\\ask", 2.0, List(Submission.TextAnswer(142, "prt-sol::1::a1", "\\sol",
          Some(Submission.GraderCookie(500, "\\algog", "valueeq()")), "3", """{\kyxline"2"}"""))),
        Submission.Prompt(143, "\\ask", 1.0, List(Submission.TextAnswer(144, "prt-sol::2::a1", "\\sol",
          Some(Submission.GraderCookie(501, "\\algog", "polyeq()")), "x^2>=+0", """{\kyxline"x^2>=0"}""")))
      )),
      Submission.Problem(25160, "Problem block 3 (single question)", "prob::3", List(
        Submission.Prompt(147, "\\ask", 1.0, List(Submission.TextAnswer(148, "prt::block3::a1", "\\sol",
          None, "1,2", """{${1,2,3}$}""")))
      )),
      Submission.Problem(25057, "Problem block in second segment", "prob::4", List(
        Submission.Prompt(149, "\\onechoice", 1.0, List(
          Submission.ChoiceAnswer(150, "prt::seg2block::a1", "\\choice*", None, "Sound", isSelected=true),
          Submission.ChoiceAnswer(151, "prt::seg2block::a2", "\\choice", None, "Unsound", isSelected=false)))
      ))
    ))
  }

  "Command line grader" should "grade random quiz 3 submissions" in withZ3 { _ =>
    val problems = extractProblems(QUIZ_PATH + "/3/main.tex")
    for (i <- 1 to RANDOM_TRIALS) { runGrader(problems, i, "ch:qchoicecontrol") }
  }

  /** Runs the autograder on the `i`th random submission (list of `problems`); uses `chapterLabel` to look up the
    * grading information currently missing from problems. Requires `lfcpsgrader.conf` to map `chapterLabel` to
    * an absolute file path pointing to the quiz tex source. */
  private def runGrader(problems: List[Problem], i: Int, chapterLabel: String): Unit = {
    val randClue = "Submission produced in " + i + "th run of " + RANDOM_TRIALS + " random trials from seed " + rand.seed
    val (submission, expected) = createSubmission(problems, chapterLabel, rand)
    val json = {
      import Submission.SubmissionJsonFormat._
      submission.toJson
    }
    val f = java.io.File.createTempFile("quiz", ".json")
    val w = new PrintWriter(f)
    w.print(json.compactPrint)
    w.flush()
    w.close()

    val options = Map('in -> f.getAbsolutePath, 'config -> "lfcpsgrader.conf")
    val msgsStream = new ByteArrayOutputStream()
    val resultsStream = new ByteArrayOutputStream()
    AssessmentProver.grade(options, msgsStream, resultsStream, "")
    val msgs = msgsStream.toString
    print(msgs)
    val msgLines = msgs.lines
    expected.foreach(e =>
      msgLines.find(_.startsWith("Grading question " + e._1.id)) match {
        case Some(t) => t should startWith ("Grading question " + e._1.id + "..." + (if (e._2) "PASSED" else "FAILED")) withClue randClue
        case _ => fail("Question " + e._1.id + " was not graded; " + randClue)
      }
    )
    val results = {
      import DefaultJsonProtocol._
      import Submission.GradeJsonFormat._
      resultsStream.toString.parseJson.convertTo[List[(Submission.Prompt, Double)]]
    }

    results.foreach({ case (prompt, grade) =>
      expected.find(_._1.id == prompt.id) match {
        case Some((_, answeredCorrectly)) => (if (answeredCorrectly) grade shouldBe 1.0 else grade shouldBe 0.0) withClue randClue
        case None => fail("Grade for unknown question " + prompt.id + "; " + randClue)
      }
    })
  }

  /** Creates a submission with randomly selected answers from the correct/incorrect sets in `problems`.
    * Returns the submission and the list of questions with indicator correctly/incorrectly answered. */
  private def createSubmission(problems: List[Problem], chapterLabel: String, r: RepeatableRandom): (Submission.Chapter, List[(Submission.Prompt, Boolean)]) = {
    def createGraderCookie(grader: Grader): Option[Submission.GraderCookie] = grader match {
      case AskGrader(mode, args, _) =>
        Some(Submission.GraderCookie(1, "\\algog", mode + "(" + args.map({ case (k, v) => k + "=\"" + v + "\""}) + ")"))
      case _: OneChoiceGrader => None
      case _: AnyChoiceGrader => None
      case _: AskTFGrader => None
    }

    def artifactString(a: Artifact): String = a match {
      case ExpressionArtifact(expr) => expr.prettyString
      case TexExpressionArtifact(expr) => expr match {
        case fml: Formula =>
          val disjuncts = FormulaTools.disjuncts(fml)
          if (disjuncts.forall({ case Equal(_: Variable, _: Number) => true case _ => false })) {
            // list of values
            disjuncts.map({ case Equal(_, n) => n.prettyString }).mkString("{", ",", "}")
          } else {
            // intervals
            def left(a: Formula) = a match {
              case Less(a, _) => "(" + a.prettyString
              case LessEqual(a, _) => "[" + a.prettyString
              case True => "(\\infty"
            }
            def right(a: Formula) = a match {
              case Less(_, a) => a.prettyString + ")"
              case LessEqual(_, a) => a.prettyString + "]"
              case True => "\\infty)"
            }
            disjuncts.map({ case And(l, r) => left(l) + "," + right(r) }).mkString("\\cup")
          }
        case _ => expr.prettyString
      }
      case ListExpressionArtifact(exprs) => exprs.map(_.prettyString).mkString(",")
      case SequentArtifact(goals) => goals.map(_.toString).mkString(";;")
    }

    def artifactSrcString(a: Artifact): String = a match {
      case _: ExpressionArtifact => """{\kyxline"""" + artifactString(a) + """"}"""
      case _: TexExpressionArtifact => "{$" + artifactString(a) + "$}"
      case _: ListExpressionArtifact => "{" + artifactString(a) + "}"
      case _: SequentArtifact => """{\kyxline"""" + artifactString(a) + """"}"""
    }

    def createAnswer(grader: Grader, a: Artifact): List[Submission.Answer] = {
      val graderCookie = createGraderCookie(grader)
      a match {
        case _: ExpressionArtifact | _: TexExpressionArtifact | _: ListExpressionArtifact | _: SequentArtifact =>
          TextAnswer(1, "", "\\sol", graderCookie, artifactString(a), artifactSrcString(grader.expected)) :: Nil
        case ChoiceArtifact(selected) => selected.map(s => Submission.ChoiceAnswer(1, "",
          grader.expected match { case ChoiceArtifact(es) => if (es.contains(s)) "\\choice*" else "\\choice" },
          graderCookie, s, isSelected=true))
        case BoolArtifact(value) =>
          //@todo assumes askTF is a choice with two options
          Submission.ChoiceAnswer(1, "",
            grader.expected match { case BoolArtifact(b) => if (b.contains(true)) "\\choice*" else "\\choice" },
            graderCookie, "True", isSelected=value.getOrElse(false)) ::
          Submission.ChoiceAnswer(1, "",
            grader.expected match { case BoolArtifact(b) => if (b.contains(false)) "\\choice*" else "\\choice" },
            graderCookie, "False", isSelected=value.exists(!_)) :: Nil
        case TextArtifact(value) => TextAnswer(1, "", "\\sol", graderCookie, value.getOrElse(""), artifactSrcString(grader.expected)) :: Nil
      }
    }

    /** Creates a prompt with its answers. Returns the prompt and correct=true/incorrect=false. */
    def createPrompt(q: Question, i: Int): (Submission.Prompt, Boolean) = {
      val (grader, correct, incorrect) = toGrader(q)
      //@note some questions may not have incorrect test answers annotated, but all have a correct solution
      val answerIncorrectly = !r.rand.nextBoolean() && incorrect.nonEmpty
      val answers = {
        if (answerIncorrectly) createAnswer(grader, incorrect(r.rand.nextInt(incorrect.size)))
        else createAnswer(grader, correct(r.rand.nextInt(correct.size)))
      }
      (Submission.Prompt(i, "", 1.0, answers), !answerIncorrectly)
    }

    def createProblem(p: Problem, i: Int): (Submission.Problem, List[(Submission.Prompt, Boolean)]) = {
      //@note problems have IDs 1000,..., prompts of problem 1000 have IDs 2000,.... etc.
      val answers = p.questions.zipWithIndex.map({ case (q, j) => createPrompt(q, 2000+1000*i+j) })
      (Submission.Problem(1000 + i, p.name.getOrElse(""), "", answers.map(_._1)), answers)
    }
    val submittedProblems = problems.zipWithIndex.map((createProblem _).tupled)
    (Submission.Chapter(1, chapterLabel, submittedProblems.map(_._1)),
      submittedProblems.flatMap(_._2))
  }

  private def run(problems: List[Problem]): Unit = {
    forEvery (table(problems)) { case (problem, grader, testAnswers, noAnswers) =>
      println("Problem section: " + problem)
      testAnswers.foreach(t => {
        println("Testing sol: " + t)
        val tic = System.nanoTime()
        val result = grader.check(t)
        result.left.value shouldBe 'proved withClue (t + ": " + result.right.toOption.getOrElse("<unknown>"))
        println("Successfully verified sol")
        val toc = System.nanoTime()
        (toc - tic) should be <= 5000000000L
      })
      noAnswers.foreach(t => {
        println("Testing no-sol: " + t)
        val tic = System.nanoTime()
        grader.check(t) shouldNot be ('left)
        println("Successfully rejected no-sol")
        val toc = System.nanoTime()
        (toc - tic) should be <= 5000000000L
      })
    }
  }

  /** Extracts quiz problems with their solutions (have, expected) from the resource at `path`. */
  private def extractProblems(path: String): List[Problem] = {
    val r = getClass.getResourceAsStream(path)
    require(r != null, "Unable to find " + path + "; please check that keymaerax-webui/src/test/resources" + path + " exists")
    val content = resource.managed(io.Source.fromInputStream(r, "UTF-8")).apply(_.mkString)
    Problem.fromString(content.linesWithSeparators.filterNot(_.startsWith("%")).mkString)
  }

  private def table(problems: List[Problem]) = {
    Table(("Problem", "Grader", "TestAnswers", "NoAnswers"), problems.flatMap(p => p.questions.map(q => {
      val (grader, correct, incorrect) = toGrader(q)
      (p.name, grader, correct, incorrect)
    })):_*)
  }

  /** Returns a grader, a list of correct solutions, and a list of incorrect solutions for question `q`. */
  private def toGrader(q: Question): (Grader, List[Artifact], List[Artifact]) = q match {
    case q: AskQuestion =>
      (AskGrader(q.grader, q.args, q.expected), q.testSols, q.noSols)
    case MultiAskQuestion(main, earlier) =>
      val (mainGrader, mainSols, mainNosols) = toGrader(main)
      val earlierGraders = earlier.map({ case (k, v) => (k, toGrader(v)) }).toList.sortBy(_._1)
      val grader = MultiAskGrader(mainGrader, earlierGraders.map({ case (k, (grader, _, _)) => (k, grader) }).toMap)
      // take first solution from each question, second from each question, ... (result sorted earliest to main)
      val sols = (earlierGraders.map(_._2._2) :+ mainSols).transpose.map(MultiArtifact)
      // same for nosols, but fill in missing nosols with blank answers
      val earlierNosols = earlierGraders.map(_._2._3)
      val maxNosols = (earlierNosols.map(_.size) :+ mainNosols.size).max
      val nosols = (
        earlierNosols.map(n => n ++ List.fill(maxNosols-n.size)(TextArtifact(None))) :+
        (mainNosols ++ List.fill(maxNosols-mainNosols.size)(TextArtifact(None)))).
        transpose.map(MultiArtifact)
      (grader, sols, nosols)
    case q: OneChoiceQuestion =>
      val (correct, incorrect) = q.choices.partition(_.isCorrect) match {
        case (c, i) =>
          (c.map(c => ChoiceArtifact(c.text :: Nil)), i.map(c => ChoiceArtifact(c.text :: Nil)))
      }
      //@todo do we ever have a \\onechoice with more than 1 correct answer?
      assert(correct.size == 1, "Missing or non-unique correct solution")
      (OneChoiceGrader(Map.empty[String, String], correct.head), correct, incorrect)
    case q: AnyChoiceQuestion =>
      val (correct, incorrect) = q.choices.partition(_.isCorrect) match {
        case (c, _) =>
          //@note any other combination of selected choices
          val incorrectCombinations = q.choices.toSet.subsets.filter(_ != c.toSet)
          (ChoiceArtifact(c.map(_.text)), incorrectCombinations.map(c => ChoiceArtifact(c.map(_.text).toList)))
      }
      (AnyChoiceGrader(Map.empty[String, String], correct), correct :: Nil, incorrect.toList)
    case q: AskTFQuestion =>
      val correct = BoolArtifact(Some(q.isTrue))
      val incorrect = BoolArtifact(Some(!q.isTrue))
      (AskTFGrader(correct), correct :: Nil, incorrect :: Nil)
  }

}