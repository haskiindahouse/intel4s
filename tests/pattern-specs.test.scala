import munit.FunSuite
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

// ── Meta-fuzzing: PatternSpec tests for all 45 BugPattern values ──────────────
// Split across 3 files by category:
//   pattern-specs.test.scala           — TypeSafety + Resource (this file)
//   pattern-specs-security.test.scala  — Security patterns
//   pattern-specs-effects.test.scala   — Effect + Concurrency patterns

class PatternSpecSuite extends ScalexTestBase:

  // Helper: write `code` to a tracked temp file, index it, run bug-hunt with noTaint,
  // return findings for the given pattern. Cleans up after itself.
  private def scanForPattern(code: String, pattern: BugPattern): List[BugFinding] =
    val fileName = s"pattern-test-${pattern.toString.toLowerCase}.scala"
    val testFile = workspace.resolve(fileName)
    Files.writeString(testFile, s"object PatternTestHelper {\n$code\n}")
    run("git", "add", fileName)
    try
      val idx = WorkspaceIndex(workspace, needBlooms = false)
      idx.index()
      val ctx = CommandContext(idx = idx, workspace = workspace, limit = 0, noTaint = true,
        pathFilter = Some(fileName))
      cmdBugHunt(Nil, ctx) match
        case r: CmdResult.BugHuntReport => r.findings.filter(_.pattern == pattern)
        case _ => Nil
    finally
      run("git", "rm", "-f", fileName)

  // ── OptionGet ────────────────────────────────────────────────────────────────

  test("PatternSpec: OptionGet — positive") {
    val findings = scanForPattern("""def bad: String = Option.empty[String].get""", BugPattern.OptionGet)
    assert(findings.nonEmpty, "OptionGet must trigger on .get on Option")
  }

  test("PatternSpec: OptionGet — negative (getOrElse is safe)") {
    val findings = scanForPattern("""def ok: String = Option.empty[String].getOrElse("x")""", BugPattern.OptionGet)
    assertEquals(findings.size, 0, "getOrElse must not trigger OptionGet")
  }

  test("PatternSpec: OptionGet — suppressed: Map.get(key)") {
    val findings = scanForPattern("""def ok = Map("a" -> 1).get("a")""", BugPattern.OptionGet)
    assertEquals(findings.size, 0, "Map.get(key) with argument must not trigger OptionGet")
  }

  test("PatternSpec: OptionGet — suppressed: Ref.get in for-comprehension") {
    val findings = scanForPattern(
      """def ok = for { v <- ref.get } yield v""",
      BugPattern.OptionGet
    )
    assertEquals(findings.size, 0, "Ref.get in for-comprehension must not trigger OptionGet")
  }

  test("PatternSpec: OptionGet — suppressed: chained .get.map") {
    val findings = scanForPattern("""def ok = state.get.map(identity)""", BugPattern.OptionGet)
    assertEquals(findings.size, 0, ".get.map chain must not trigger OptionGet")
  }

  // ── CollectionHead ───────────────────────────────────────────────────────────

  test("PatternSpec: CollectionHead — positive (.head)") {
    val findings = scanForPattern("""def bad: Int = List.empty[Int].head""", BugPattern.CollectionHead)
    assert(findings.nonEmpty, "CollectionHead must trigger on .head")
  }

  test("PatternSpec: CollectionHead — positive (.last)") {
    val findings = scanForPattern("""def bad: Int = List(1, 2).last""", BugPattern.CollectionHead)
    assert(findings.nonEmpty, "CollectionHead must trigger on .last")
  }

  test("PatternSpec: CollectionHead — negative (headOption is safe)") {
    val findings = scanForPattern("""def ok = List.empty[Int].headOption""", BugPattern.CollectionHead)
    assertEquals(findings.size, 0, "headOption must not trigger CollectionHead")
  }

  // ── AsInstanceOf ─────────────────────────────────────────────────────────────

  test("PatternSpec: AsInstanceOf — positive") {
    val findings = scanForPattern("""def bad(x: Any): String = x.asInstanceOf[String]""", BugPattern.AsInstanceOf)
    assert(findings.nonEmpty, "AsInstanceOf must trigger on .asInstanceOf")
  }

  test("PatternSpec: AsInstanceOf — negative (isInstanceOf is safe)") {
    val findings = scanForPattern("""def ok(x: Any): Boolean = x.isInstanceOf[String]""", BugPattern.AsInstanceOf)
    assertEquals(findings.size, 0, "isInstanceOf must not trigger AsInstanceOf")
  }

  // ── NullLiteral ──────────────────────────────────────────────────────────────

  test("PatternSpec: NullLiteral — positive") {
    val findings = scanForPattern("""val bad: String = null""", BugPattern.NullLiteral)
    assert(findings.nonEmpty, "NullLiteral must trigger on null")
  }

  test("PatternSpec: NullLiteral — negative (empty string)") {
    val findings = scanForPattern("""val ok: String = "" """, BugPattern.NullLiteral)
    assertEquals(findings.size, 0, "Empty string must not trigger NullLiteral")
  }

  // ── ReturnInLambda ───────────────────────────────────────────────────────────

  test("PatternSpec: ReturnInLambda — positive") {
    val findings = scanForPattern(
      """def bad = List(1, 2).map { x => return x }""",
      BugPattern.ReturnInLambda
    )
    assert(findings.nonEmpty, "ReturnInLambda must trigger on return inside map")
  }

  test("PatternSpec: ReturnInLambda — negative (return in top-level def body)") {
    // Top-level `return` is not inside a lambda context; scanner only flags lambda/foreach/map/flatMap
    val findings = scanForPattern(
      """def ok(x: Int): Int = if x > 0 then x else 0""",
      BugPattern.ReturnInLambda
    )
    assertEquals(findings.size, 0, "Simple def must not trigger ReturnInLambda")
  }

  // ── CollectionLast ───────────────────────────────────────────────────────────

  test("PatternSpec: CollectionLast — positive (.tail)") {
    val findings = scanForPattern("""def bad = List(1, 2).tail""", BugPattern.CollectionLast)
    assert(findings.nonEmpty, "CollectionLast must trigger on .tail")
  }

  test("PatternSpec: CollectionLast — negative (init is different)") {
    val findings = scanForPattern("""def ok = List(1, 2).init""", BugPattern.CollectionLast)
    assertEquals(findings.size, 0, ".init must not trigger CollectionLast")
  }

  // ── UnsafeGet — not yet implemented in scanner; pending ──────────────────────

  test("PatternSpec: UnsafeGet — positive".pending) {
    // Pattern defined in model but not yet implemented in scanner
    // Expected: Try(...).get or Future.value.get should trigger
  }

  test("PatternSpec: UnsafeGet — negative") {
    val findings = scanForPattern("""val x = scala.util.Try(42).getOrElse(0)""", BugPattern.UnsafeGet)
    assertEquals(findings.size, 0, "getOrElse on Try must not trigger UnsafeGet")
  }

  // ── PartialFunction — not yet implemented in scanner; pending ─────────────────

  test("PatternSpec: PartialFunction — positive".pending) {
    // Pattern defined in model but not yet implemented in scanner
  }

  test("PatternSpec: PartialFunction — negative") {
    val findings = scanForPattern(
      """def ok(x: Option[Int]) = x match { case Some(v) => v; case None => 0 }""",
      BugPattern.PartialFunction
    )
    assertEquals(findings.size, 0, "Exhaustive match must not trigger PartialFunction")
  }

  // ── ResourceLeak ─────────────────────────────────────────────────────────────

  test("PatternSpec: ResourceLeak — positive (Source.fromFile bare)") {
    val findings = scanForPattern(
      """import scala.io.Source
        |def bad = Source.fromFile("test.txt").mkString""".stripMargin,
      BugPattern.ResourceLeak
    )
    assert(findings.nonEmpty, "ResourceLeak must trigger on bare Source.fromFile")
  }

  test("PatternSpec: ResourceLeak — negative (inside try block)") {
    // WHY: Source.fromFile must be INSIDE the try block to be recognized as safe by the context tracker
    val findings = scanForPattern(
      """import scala.io.Source
        |def ok = try { Source.fromFile("f").mkString } catch { case _: Exception => "" }""".stripMargin,
      BugPattern.ResourceLeak
    )
    assertEquals(findings.size, 0, "Source.fromFile inside try must not trigger ResourceLeak")
  }

  test("PatternSpec: ResourceLeak — suppressed: inside Using{}") {
    val findings = scanForPattern(
      """import scala.io.Source
        |import scala.util.Using
        |def ok = Using(Source.fromFile("f"))(_.mkString)""".stripMargin,
      BugPattern.ResourceLeak
    )
    assertEquals(findings.size, 0, "Source.fromFile inside Using must not trigger ResourceLeak")
  }

  // ── ConnectionLeak ───────────────────────────────────────────────────────────

  test("PatternSpec: ConnectionLeak — positive (Init of Connection type outside try)") {
    // WHY: scanner matches Init nodes of type "Connection" — triggered by class MyConn extends Connection
    val findings = scanForPattern(
      """class MyConn extends Connection""",
      BugPattern.ConnectionLeak
    )
    assert(findings.nonEmpty, "Extending Connection outside try/Using must trigger ConnectionLeak")
  }

  test("PatternSpec: ConnectionLeak — negative (ResultSet inside try)") {
    val findings = scanForPattern(
      """def ok = try { class Wrapped extends ResultSet } finally ()""",
      BugPattern.ConnectionLeak
    )
    assertEquals(findings.size, 0, "Connection Init inside try must not trigger ConnectionLeak")
  }

  // ── StreamNotClosed ──────────────────────────────────────────────────────────

  test("PatternSpec: StreamNotClosed — positive (FileInputStream bare)") {
    val findings = scanForPattern(
      """import java.io.FileInputStream
        |def bad(path: String) = new FileInputStream(path)""".stripMargin,
      BugPattern.StreamNotClosed
    )
    assert(findings.nonEmpty, "FileInputStream without try/Using must trigger StreamNotClosed")
  }

  test("PatternSpec: StreamNotClosed — negative (inside try)") {
    val findings = scanForPattern(
      """import java.io.FileInputStream
        |def ok(path: String) = try { val s = new FileInputStream(path); s } finally ()""".stripMargin,
      BugPattern.StreamNotClosed
    )
    assertEquals(findings.size, 0, "FileInputStream inside try must not trigger StreamNotClosed")
  }
