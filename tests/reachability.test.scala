import munit.FunSuite
import java.nio.file.{Files, Path}

// ── Reachability test suite ───────────────────────────────────────────────
//
// Uses an isolated workspace (not the shared ScalexTestBase) to avoid fixture
// count breakage.  Each test that needs a workspace creates one locally.

class ReachabilitySuite extends FunSuite:

  // ── Workspace / index helpers ─────────────────────────────────────────

  private def makeWorkspace(): Path = Files.createTempDirectory("scalex-reach-test")

  private def deleteRecursive(p: Path): Unit =
    if Files.isDirectory(p) then
      import scala.jdk.CollectionConverters.*
      Files.list(p).iterator().asScala.foreach(deleteRecursive)
    Files.deleteIfExists(p)

  private def write(ws: Path, rel: String, content: String): Unit =
    val file = ws.resolve(rel)
    Files.createDirectories(file.getParent)
    Files.writeString(file, content)

  private def gitInit(ws: Path): Unit =
    def run(cmd: String*): Unit =
      val pb = ProcessBuilder(cmd*)
      pb.directory(ws.toFile)
      pb.redirectErrorStream(true)
      val proc = pb.start()
      proc.getInputStream.readAllBytes()
      proc.waitFor()
    run("git", "init")
    run("git", "add", ".")
    run("git", "commit", "-m", "init")

  private def buildIndex(ws: Path): WorkspaceIndex =
    val idx = WorkspaceIndex(ws)
    idx.index()
    idx

  // ── Fixture: entrypoint → service → helper chain ──────────────────────
  //
  // Chain:  @main run → handleRequest (HTTP handler) → processInput
  // Dead:   legacyHelper (not called by anything)
  // BugFinding in processInput → reachable
  // BugFinding in legacyHelper → not reachable

  private def writeChainFixtures(ws: Path): Unit =
    write(ws, "src/main/scala/App.scala",
      """|@main def run(): Unit = handleRequest("test")
         |
         |def handleRequest(req: String): Unit = processInput(req)
         |""".stripMargin)
    write(ws, "src/main/scala/Service.scala",
      """|def processInput(s: String): Unit =
         |  val x: String = null
         |  println(x)
         |
         |def legacyHelper(): Unit =
         |  val y: String = null
         |  println(y)
         |""".stripMargin)

  // ── Tests ─────────────────────────────────────────────────────────────

  test("buildReachableSet includes method reachable from @main entrypoint") {
    val ws = makeWorkspace()
    try
      writeChainFixtures(ws)
      gitInit(ws)
      val idx = buildIndex(ws)
      val (reachableSet, _) = buildReachableSet(idx, ws, maxDepth = 5, includeTests = false)
      // run is the @main, handleRequest and processInput are callees
      assert(reachableSet.contains("run"), s"run should be reachable: $reachableSet")
      assert(reachableSet.contains("handleRequest"), s"handleRequest should be reachable: $reachableSet")
      assert(reachableSet.contains("processInput"), s"processInput should be reachable: $reachableSet")
    finally deleteRecursive(ws)
  }

  test("buildReachableSet does not include dead-code method not called by anything") {
    val ws = makeWorkspace()
    try
      writeChainFixtures(ws)
      gitInit(ws)
      val idx = buildIndex(ws)
      val (reachableSet, _) = buildReachableSet(idx, ws, maxDepth = 5, includeTests = false)
      // legacyHelper is not called from run chain
      assert(!reachableSet.contains("legacyHelper"), s"legacyHelper should NOT be reachable: $reachableSet")
    finally deleteRecursive(ws)
  }

  test("entrypointMap maps reachable method to its entrypoint name") {
    val ws = makeWorkspace()
    try
      writeChainFixtures(ws)
      gitInit(ws)
      val idx = buildIndex(ws)
      val (_, entrypointMap) = buildReachableSet(idx, ws, maxDepth = 5, includeTests = false)
      val processInputEntrypoints = entrypointMap.get("processInput")
      assert(processInputEntrypoints.isDefined, "processInput should have an entrypoint mapping")
      assert(processInputEntrypoints.exists(_.contains("run")),
        s"processInput should be reachable from run, got: $processInputEntrypoints")
    finally deleteRecursive(ws)
  }

  test("depth limit: method beyond depth is treated as unreachable") {
    val ws = makeWorkspace()
    try
      // Build a chain: main → a → b → c → d → e (depth 5 required)
      write(ws, "src/main/scala/Deep.scala",
        """|@main def main(): Unit = a()
           |def a(): Unit = b()
           |def b(): Unit = c()
           |def c(): Unit = d()
           |def d(): Unit = e()
           |def e(): Unit = println("deep")
           |""".stripMargin)
      gitInit(ws)
      val idx = buildIndex(ws)

      // With depth=3, e() is at depth 5 — should not appear
      val (reachableAt3, _) = buildReachableSet(idx, ws, maxDepth = 3, includeTests = false)
      assert(!reachableAt3.contains("e"), s"e should NOT be reachable at depth 3: $reachableAt3")

      // With depth=5, e() should be reachable
      val (reachableAt5, _) = buildReachableSet(idx, ws, maxDepth = 5, includeTests = false)
      assert(reachableAt5.contains("e"), s"e should be reachable at depth 5: $reachableAt5")
    finally deleteRecursive(ws)
  }

  test("cycle handling: A calls B, B calls A — no infinite loop") {
    val ws = makeWorkspace()
    try
      write(ws, "src/main/scala/Cycle.scala",
        """|@main def entry(): Unit = cycleA()
           |def cycleA(): Unit = cycleB()
           |def cycleB(): Unit = cycleA()
           |""".stripMargin)
      gitInit(ws)
      val idx = buildIndex(ws)
      // Should terminate and both should be reachable
      val (reachableSet, _) = buildReachableSet(idx, ws, maxDepth = 5, includeTests = false)
      assert(reachableSet.contains("cycleA"), s"cycleA should be reachable: $reachableSet")
      assert(reachableSet.contains("cycleB"), s"cycleB should be reachable: $reachableSet")
    finally deleteRecursive(ws)
  }

  test("test suites excluded from entrypoints by default") {
    val ws = makeWorkspace()
    try
      write(ws, "src/test/scala/MySuite.scala",
        """|class MySuite extends munit.FunSuite:
           |  test("foo") { assert(true) }
           |""".stripMargin)
      write(ws, "src/main/scala/App.scala",
        """|@main def mainEntry(): Unit = println("hello")
           |""".stripMargin)
      gitInit(ws)
      val idx = buildIndex(ws)

      // Without includeTests, MySuite should not be an entrypoint
      val entriesNoTests = collectEntrypointNamesForTest(idx, includeTests = false)
      assert(!entriesNoTests.contains("MySuite"),
        s"MySuite should NOT be an entrypoint without --include-tests: $entriesNoTests")

      // With includeTests, MySuite should appear
      val entriesWithTests = collectEntrypointNamesForTest(idx, includeTests = true)
      assert(entriesWithTests.contains("MySuite"),
        s"MySuite SHOULD be an entrypoint with --include-tests: $entriesWithTests")
    finally deleteRecursive(ws)
  }

  test("findEnclosingMethod returns innermost def for a given line") {
    val ws = makeWorkspace()
    try
      write(ws, "src/main/scala/Methods.scala",
        """|object Methods:
           |  def outer(): Unit =
           |    val x = 1
           |    println(x)
           |
           |  def inner(): String =
           |    "hello"
           |""".stripMargin)
      gitInit(ws)
      val filePath = ws.resolve("src/main/scala/Methods.scala")
      // Line 3 is inside outer()
      val enclosing = findEnclosingMethod(filePath, 3)
      assert(enclosing.isDefined, "Should find enclosing method at line 3")
      assertEquals(enclosing.get, "outer")
      // Line 7 is inside inner()
      val enclosingInner = findEnclosingMethod(filePath, 7)
      assert(enclosingInner.isDefined, "Should find enclosing method at line 7")
      assertEquals(enclosingInner.get, "inner")
    finally deleteRecursive(ws)
  }

  test("without --reachable flag, all findings are shown (no reachability overhead)") {
    val ws = makeWorkspace()
    try
      writeChainFixtures(ws)
      gitInit(ws)
      val idx = buildIndex(ws)
      val ctx = CommandContext(idx = idx, workspace = ws, reachable = false, limit = 0, noTaint = true)
      val result = cmdBugHunt(Nil, ctx)
      result match
        case CmdResult.BugHuntReport(findings, _, _, _) =>
          // Both processInput and legacyHelper have NullLiteral bugs — both should appear
          val nullFindings = findings.filter(_.pattern == BugPattern.NullLiteral)
          assert(nullFindings.size >= 2, s"Should find null findings in both methods without --reachable, got ${nullFindings.size}")
        case other => fail(s"Expected BugHuntReport, got: $other")
    finally deleteRecursive(ws)
  }

  test("with --reachable flag, findings in dead code are excluded") {
    val ws = makeWorkspace()
    try
      writeChainFixtures(ws)
      gitInit(ws)
      val idx = buildIndex(ws)
      val ctx = CommandContext(idx = idx, workspace = ws, reachable = true, reachableDepth = 5, limit = 0, noTaint = true)
      val result = cmdBugHunt(Nil, ctx)
      result match
        case CmdResult.BugHuntReport(findings, _, _, _) =>
          val enclosingSymbols = findings.map(_.enclosingSymbol).toSet
          // processInput is reachable, legacyHelper is not
          assert(!findings.exists(f => findEnclosingMethod(f.file, f.line).contains("legacyHelper")),
            s"legacyHelper findings should be filtered when --reachable is set. Found: ${findings.map(f => s"${f.file}:${f.line} enclosing=${findEnclosingMethod(f.file, f.line)}")}")
        case other => fail(s"Expected BugHuntReport, got: $other")
    finally deleteRecursive(ws)
  }

  test("with --reachable, reachable findings are enriched with reachableFrom") {
    val ws = makeWorkspace()
    try
      writeChainFixtures(ws)
      gitInit(ws)
      val idx = buildIndex(ws)
      val ctx = CommandContext(idx = idx, workspace = ws, reachable = true, reachableDepth = 5, limit = 0, noTaint = true)
      val result = cmdBugHunt(Nil, ctx)
      result match
        case CmdResult.BugHuntReport(findings, _, _, _) =>
          findings.foreach { f =>
            assert(f.reachableFrom.isDefined, s"Reachable finding should have reachableFrom set: ${f.file}:${f.line}")
            assert(f.callDepth.isDefined, s"Reachable finding should have callDepth set: ${f.file}:${f.line}")
          }
        case other => fail(s"Expected BugHuntReport, got: $other")
    finally deleteRecursive(ws)
  }

// Helper to test entrypoint collection logic directly without full CommandContext
private def collectEntrypointNamesForTest(idx: WorkspaceIndex, includeTests: Boolean): Set[String] =
  val entries: List[SymbolInfo] = {
    val seen    = scala.collection.mutable.HashSet.empty[(String, String, Int)]
    val results = scala.collection.mutable.ListBuffer.empty[SymbolInfo]
    def addIfNew(sym: SymbolInfo): Unit = {
      val key = (sym.name, sym.file.toString, sym.line)
      if !seen.contains(key) then { seen += key; results += sym }
    }
    idx.findAnnotated("main").foreach(addIfNew)
    idx.findImplementations("App").foreach(addIfNew)
    idx.symbols.filter(_.kind == SymbolKind.Object).foreach { obj =>
      val members = extractMembers(obj.file, obj.name)
      if members.exists(m => m.name == "main" && m.kind == SymbolKind.Def) then addIfNew(obj)
    }
    if includeTests then
      val testParents = Set("FunSuite", "AnyFunSuite", "FlatSpec", "AnyFlatSpec",
        "WordSpec", "AnyWordSpec", "FreeSpec", "AnyFreeSpec",
        "PropSpec", "FeatureSpec", "Suite", "Specification", "FunSpec")
      testParents.foreach { parent => idx.findImplementations(parent).foreach(addIfNew) }
    results.toList
  }
  entries.map(_.name).toSet
