import munit.FunSuite
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

// ── Meta-fuzzing: PatternSpec tests — Security injection + access patterns ────
// (continues in pattern-specs-crypto.test.scala for crypto/XML/logging patterns)

class PatternSpecSecuritySuite extends ScalexTestBase:

  private def scanForPattern(code: String, pattern: BugPattern): List[BugFinding] =
    val fileName = s"pspec-sec-${pattern.toString.toLowerCase}.scala"
    val testFile = workspace.resolve(fileName)
    Files.writeString(testFile, s"object PSpecSecHelper {\n$code\n}")
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

  // ── FragmentConst ─────────────────────────────────────────────────────────────

  test("PatternSpec: FragmentConst — positive") {
    val findings = scanForPattern(
      """def bad(id: String) = Fragment.const(s"SELECT * FROM t WHERE id = $id")""",
      BugPattern.FragmentConst
    )
    assert(findings.nonEmpty, "FragmentConst must trigger on interpolated Fragment.const")
  }

  test("PatternSpec: FragmentConst — negative (literal string)") {
    val findings = scanForPattern(
      """def ok = Fragment.const("SELECT * FROM users")""",
      BugPattern.FragmentConst
    )
    assertEquals(findings.size, 0, "Fragment.const with literal must not trigger")
  }

  // ── SpliceInterpolation ───────────────────────────────────────────────────────

  test("PatternSpec: SpliceInterpolation — positive") {
    val findings = scanForPattern(
      """def bad(id: String) = sql"SELECT * FROM users WHERE id = #$id" """,
      BugPattern.SpliceInterpolation
    )
    assert(findings.nonEmpty, "SpliceInterpolation must trigger on sql with #$")
  }

  test("PatternSpec: SpliceInterpolation — negative (safe dollar-interpolation)") {
    val findings = scanForPattern(
      """def ok(id: String) = sql"SELECT * FROM users WHERE id = $id" """,
      BugPattern.SpliceInterpolation
    )
    assertEquals(findings.size, 0, "sql with safe $ must not trigger SpliceInterpolation")
  }

  // ── ObjectInputStream ─────────────────────────────────────────────────────────

  test("PatternSpec: ObjectInputStream — positive (new ObjectInputStream)") {
    val findings = scanForPattern(
      """import java.io.{ObjectInputStream, FileInputStream}
        |def bad(path: String) = new ObjectInputStream(new FileInputStream(path))""".stripMargin,
      BugPattern.ObjectInputStream
    )
    assert(findings.nonEmpty, "ObjectInputStream must trigger on new ObjectInputStream")
  }

  test("PatternSpec: ObjectInputStream — negative (ObjectOutputStream is different)") {
    val findings = scanForPattern(
      """import java.io.{ObjectOutputStream, FileOutputStream}
        |def ok(path: String) = new ObjectOutputStream(new FileOutputStream(path))""".stripMargin,
      BugPattern.ObjectInputStream
    )
    assertEquals(findings.size, 0, "ObjectOutputStream must not trigger ObjectInputStream")
  }

  // ── JacksonDefaultTyping ──────────────────────────────────────────────────────

  test("PatternSpec: JacksonDefaultTyping — positive") {
    val findings = scanForPattern(
      """def bad(mapper: Any) = mapper.enableDefaultTyping()""",
      BugPattern.JacksonDefaultTyping
    )
    assert(findings.nonEmpty, "enableDefaultTyping() must trigger JacksonDefaultTyping")
  }

  test("PatternSpec: JacksonDefaultTyping — negative (other Jackson calls)") {
    val findings = scanForPattern(
      """def ok(mapper: Any) = mapper.writeValueAsString("hello")""",
      BugPattern.JacksonDefaultTyping
    )
    assertEquals(findings.size, 0, "writeValueAsString must not trigger JacksonDefaultTyping")
  }

  // ── HardcodedSecret ───────────────────────────────────────────────────────────

  test("PatternSpec: HardcodedSecret — positive (password)") {
    val findings = scanForPattern(
      """val password = "hunter2secret" """,
      BugPattern.HardcodedSecret
    )
    assert(findings.nonEmpty, "Hardcoded password must trigger HardcodedSecret")
  }

  test("PatternSpec: HardcodedSecret — negative (safe name)") {
    val findings = scanForPattern(
      """val userName = "alice" """,
      BugPattern.HardcodedSecret
    )
    assertEquals(findings.size, 0, "Non-secret name must not trigger HardcodedSecret")
  }

  test("PatternSpec: HardcodedSecret — suppressed: placeholder value") {
    val findings = scanForPattern(
      """val password = "changeme" """,
      BugPattern.HardcodedSecret
    )
    assertEquals(findings.size, 0, "Placeholder value must not trigger HardcodedSecret")
  }

  // ── CommandInjection ──────────────────────────────────────────────────────────

  test("PatternSpec: CommandInjection — positive (ProcessBuilder with var)") {
    val findings = scanForPattern(
      """def bad(cmd: String) = ProcessBuilder(cmd)""",
      BugPattern.CommandInjection
    )
    assert(findings.nonEmpty, "ProcessBuilder with non-literal must trigger CommandInjection")
  }

  test("PatternSpec: CommandInjection — suppressed: literal command string") {
    val findings = scanForPattern(
      """def ok = ProcessBuilder("ls", "-la")""",
      BugPattern.CommandInjection
    )
    assertEquals(findings.size, 0, "ProcessBuilder with literal args must not trigger CommandInjection")
  }

  // ── PathTraversal ─────────────────────────────────────────────────────────────

  test("PatternSpec: PathTraversal — positive (Paths.get with var)") {
    // WHY: scanner matches Term.Name("Paths"), not fully-qualified — use imported form
    val findings = scanForPattern(
      """import java.nio.file.Paths
        |def bad(p: String) = Paths.get(p)""".stripMargin,
      BugPattern.PathTraversal
    )
    assert(findings.nonEmpty, "Paths.get with non-literal must trigger PathTraversal")
  }

  test("PatternSpec: PathTraversal — suppressed: literal path") {
    val findings = scanForPattern(
      """import java.nio.file.Paths
        |def ok = Paths.get("/etc/hosts")""".stripMargin,
      BugPattern.PathTraversal
    )
    assertEquals(findings.size, 0, "Paths.get with literal must not trigger PathTraversal")
  }

  // ── XSS ──────────────────────────────────────────────────────────────────────

  test("PatternSpec: XSS — positive") {
    val findings = scanForPattern(
      """def bad(input: String) = Html(input)""",
      BugPattern.XSS
    )
    assert(findings.nonEmpty, "Html() with non-literal must trigger XSS")
  }

  test("PatternSpec: XSS — negative (literal HTML)") {
    val findings = scanForPattern(
      """def ok = Html("<b>safe</b>")""",
      BugPattern.XSS
    )
    assertEquals(findings.size, 0, "Html() with literal must not trigger XSS")
  }

  // ── OpenRedirect ──────────────────────────────────────────────────────────────

  test("PatternSpec: OpenRedirect — positive") {
    val findings = scanForPattern(
      """def bad(url: String) = Redirect(url)""",
      BugPattern.OpenRedirect
    )
    assert(findings.nonEmpty, "Redirect with non-literal must trigger OpenRedirect")
  }

  test("PatternSpec: OpenRedirect — negative (literal URL)") {
    val findings = scanForPattern(
      """def ok = Redirect("https://example.com/home")""",
      BugPattern.OpenRedirect
    )
    assertEquals(findings.size, 0, "Redirect with literal URL must not trigger OpenRedirect")
  }

  // ── SSRF ──────────────────────────────────────────────────────────────────────

  test("PatternSpec: SSRF — positive (Source.fromURL with var)") {
    val findings = scanForPattern(
      """import scala.io.Source
        |def bad(url: String) = Source.fromURL(url).mkString""".stripMargin,
      BugPattern.SSRF
    )
    assert(findings.nonEmpty, "Source.fromURL with non-literal must trigger SSRF")
  }

  test("PatternSpec: SSRF — negative (literal URL)") {
    val findings = scanForPattern(
      """import scala.io.Source
        |def ok = Source.fromURL("https://api.example.com/data").mkString""".stripMargin,
      BugPattern.SSRF
    )
    assertEquals(findings.size, 0, "Source.fromURL with literal must not trigger SSRF")
  }

  // ── HardcodedCredential ───────────────────────────────────────────────────────

  test("PatternSpec: HardcodedCredential — positive (GitHub PAT pattern)") {
    // WHY: 36 alphanum chars after "ghp_" match the github-pat credential rule
    val findings = scanForPattern(
      """val token = "ghp_aBcDeFgHiJkLmNoPqRsTuVwXyZ1234567890" """,
      BugPattern.HardcodedCredential
    )
    assert(findings.nonEmpty, "GitHub PAT string must trigger HardcodedCredential")
  }

  test("PatternSpec: HardcodedCredential — negative (short string)") {
    val findings = scanForPattern(
      """val x = "hello" """,
      BugPattern.HardcodedCredential
    )
    assertEquals(findings.size, 0, "Short string must not trigger HardcodedCredential")
  }
