import munit.FunSuite
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

class MemorySuite extends ScalexTestBase:

  // Helpers

  private def makeStore(): MemoryStore = MemoryStore(workspace)

  private def globalMem(pattern: String, suppType: SuppressionType = SuppressionType.Ignore): SuppressionMemory =
    SuppressionMemory(
      patternName     = pattern,
      scope           = MemoryScope.Global(pattern),
      reason          = "test reason",
      source          = MemorySource.UserManual,
      suppressionType = suppType,
      contextLine     = "val x = foo.get",
      createdAt       = 1000L
    )

  private def fileScopedMem(pattern: String, filePattern: String): SuppressionMemory =
    SuppressionMemory(
      patternName     = pattern,
      scope           = MemoryScope.FileScoped(pattern, filePattern),
      reason          = "file-scoped test",
      source          = MemorySource.UserManual,
      suppressionType = SuppressionType.Ignore,
      contextLine     = "",
      createdAt       = 2000L
    )

  private def methodScopedMem(pattern: String, filePattern: String, method: String): SuppressionMemory =
    SuppressionMemory(
      patternName     = pattern,
      scope           = MemoryScope.MethodScoped(pattern, filePattern, method),
      reason          = "method-scoped test",
      source          = MemorySource.UserManual,
      suppressionType = SuppressionType.Ignore,
      contextLine     = "",
      createdAt       = 3000L
    )

  private def bugFinding(pattern: BugPattern, relFile: String, enclosing: String): BugFinding =
    BugFinding(
      file            = workspace.resolve(relFile),
      line            = 5,
      pattern         = pattern,
      contextLine     = "val x = foo.get",
      enclosingSymbol = enclosing,
      message         = pattern.description
    )

  // ── CRUD: add, list, remove, clear ─────────────────────────────────────

  test("memory list is empty on fresh workspace") {
    val store = makeStore()
    assertEquals(store.load(), Nil)
  }

  test("memory add persists a memory") {
    val store = makeStore()
    store.save(List(globalMem("OptionGet")))
    try
      val loaded = store.load()
      assertEquals(loaded.size, 1)
      assertEquals(loaded.head.patternName, "OptionGet")
      assertEquals(loaded.head.suppressionType, SuppressionType.Ignore)
    finally store.save(Nil)
  }

  test("memory remove by index works") {
    val store = makeStore()
    store.save(List(globalMem("OptionGet"), globalMem("NullLiteral")))
    try
      val updated = store.load().patch(0, Nil, 1)
      store.save(updated)
      val loaded = store.load()
      assertEquals(loaded.size, 1)
      assertEquals(loaded.head.patternName, "NullLiteral")
    finally store.save(Nil)
  }

  test("memory clear removes all memories") {
    val store = makeStore()
    store.save(List(globalMem("OptionGet"), globalMem("NullLiteral")))
    store.save(Nil)
    assertEquals(store.load(), Nil)
  }

  // ── Persistence: save and reload ───────────────────────────────────────

  test("save and reload global memory preserves all fields") {
    val store = makeStore()
    val mem = SuppressionMemory(
      patternName     = "CommandInjection",
      scope           = MemoryScope.Global("CommandInjection"),
      reason          = "Only used with literals in this project",
      source          = MemorySource.LlmTriage("run-abc123"),
      suppressionType = SuppressionType.Lower,
      contextLine     = "Runtime.exec(cmd)",
      createdAt       = 42000L
    )
    store.save(List(mem))
    try
      val loaded = store.load()
      assertEquals(loaded.size, 1)
      val r = loaded.head
      assertEquals(r.patternName, "CommandInjection")
      assertEquals(r.reason, "Only used with literals in this project")
      assertEquals(r.suppressionType, SuppressionType.Lower)
      assertEquals(r.contextLine, "Runtime.exec(cmd)")
      assertEquals(r.createdAt, 42000L)
      r.source match
        case MemorySource.LlmTriage(run) => assertEquals(run, "run-abc123")
        case other => fail(s"Expected LlmTriage, got $other")
      r.scope match
        case MemoryScope.Global(p) => assertEquals(p, "CommandInjection")
        case other => fail(s"Expected Global, got $other")
    finally store.save(Nil)
  }

  test("save and reload FileScoped memory preserves scope") {
    val store = makeStore()
    store.save(List(fileScopedMem("PathTraversal", ".*Service\\.scala")))
    try
      val loaded = store.load()
      assertEquals(loaded.size, 1)
      loaded.head.scope match
        case MemoryScope.FileScoped(p, fp) =>
          assertEquals(p, "PathTraversal")
          assertEquals(fp, ".*Service\\.scala")
        case other => fail(s"Expected FileScoped, got $other")
    finally store.save(Nil)
  }

  test("save and reload MethodScoped memory preserves all scope fields") {
    val store = makeStore()
    store.save(List(methodScopedMem("PathTraversal", ".*Service\\.scala", "loadConfig")))
    try
      val loaded = store.load()
      assertEquals(loaded.size, 1)
      loaded.head.scope match
        case MemoryScope.MethodScoped(p, fp, mn) =>
          assertEquals(p, "PathTraversal")
          assertEquals(fp, ".*Service\\.scala")
          assertEquals(mn, "loadConfig")
        case other => fail(s"Expected MethodScoped, got $other")
    finally store.save(Nil)
  }

  test("save and reload CommunitySubmit source") {
    val store = makeStore()
    val mem = SuppressionMemory(
      patternName     = "WeakHash",
      scope           = MemoryScope.Global("WeakHash"),
      reason          = "Intentional legacy MD5",
      source          = MemorySource.CommunitySubmit(42),
      suppressionType = SuppressionType.Review,
      contextLine     = "",
      createdAt       = 1L
    )
    store.save(List(mem))
    try
      val loaded = store.load()
      loaded.head.source match
        case MemorySource.CommunitySubmit(n) => assertEquals(n, 42)
        case other => fail(s"Expected CommunitySubmit, got $other")
      assertEquals(loaded.head.suppressionType, SuppressionType.Review)
    finally store.save(Nil)
  }

  test("special characters in reason survive round-trip") {
    val store = makeStore()
    val mem = globalMem("OptionGet").copy(reason = """he said "don't" and \escaped""")
    store.save(List(mem))
    try
      val loaded = store.load()
      assertEquals(loaded.head.reason, """he said "don't" and \escaped""")
    finally store.save(Nil)
  }

  // ── Matching: Global, FileScoped, MethodScoped ─────────────────────────

  test("Global memory matches finding in any file") {
    val store = makeStore()
    store.save(List(globalMem("OptionGet")))
    try
      val finding = bugFinding(BugPattern.OptionGet, "src/main/scala/com/example/Foo.scala", "doSomething")
      assert(store.matches(finding).isDefined, "Global memory should match any file")
    finally store.save(Nil)
  }

  test("Global memory does NOT match different pattern") {
    val store = makeStore()
    store.save(List(globalMem("OptionGet")))
    try
      val finding = bugFinding(BugPattern.NullLiteral, "src/main/scala/com/example/Foo.scala", "doSomething")
      assert(store.matches(finding).isEmpty, "Global memory should not match different pattern")
    finally store.save(Nil)
  }

  test("FileScoped memory matches only matching files") {
    val store = makeStore()
    store.save(List(fileScopedMem("PathTraversal", ".*Service\\.scala")))
    try
      val matchingFinding    = bugFinding(BugPattern.PathTraversal, "src/main/scala/com/example/UserService.scala", "load")
      val nonMatchingFinding = bugFinding(BugPattern.PathTraversal, "src/main/scala/com/example/Controller.scala", "load")
      assert(store.matches(matchingFinding).isDefined, "FileScoped should match Service.scala")
      assert(store.matches(nonMatchingFinding).isEmpty, "FileScoped should not match Controller.scala")
    finally store.save(Nil)
  }

  test("MethodScoped memory matches only matching file AND method") {
    val store = makeStore()
    store.save(List(methodScopedMem("PathTraversal", ".*Service\\.scala", "loadConfig")))
    try
      val matchBoth   = bugFinding(BugPattern.PathTraversal, "src/main/scala/com/example/UserService.scala", "loadConfig")
      val wrongMethod = bugFinding(BugPattern.PathTraversal, "src/main/scala/com/example/UserService.scala", "otherMethod")
      val wrongFile   = bugFinding(BugPattern.PathTraversal, "src/main/scala/com/example/Controller.scala", "loadConfig")
      assert(store.matches(matchBoth).isDefined,   "Should match correct file+method")
      assert(store.matches(wrongMethod).isEmpty,   "Should not match wrong method in correct file")
      assert(store.matches(wrongFile).isEmpty,     "Should not match wrong file")
    finally store.save(Nil)
  }

  test("matches returns the correct memory") {
    val store = makeStore()
    store.save(List(globalMem("CollectionHead", SuppressionType.Lower)))
    try
      val finding = bugFinding(BugPattern.CollectionHead, "src/main/scala/Foo.scala", "process")
      val result = store.matches(finding)
      assert(result.isDefined)
      assertEquals(result.get.suppressionType, SuppressionType.Lower)
    finally store.save(Nil)
  }

  // ── Integration: bug-hunt with memories filters findings ───────────────

  test("bug-hunt filters Ignore-type memory findings") {
    val store = makeStore()
    // OptionGet is detected in com/bugs/OptionGetBug.scala (which calls .get)
    store.save(List(globalMem("OptionGet", SuppressionType.Ignore)))
    try
      val idx = WorkspaceIndex(workspace, needBlooms = true)
      idx.index()
      val ctx = CommandContext(idx = idx, workspace = workspace, noTaint = true, limit = 100)
      val result = cmdBugHunt(List.empty, ctx)
      result match
        case CmdResult.BugHuntReport(findings, _, _, _) =>
          val optionGetFindings = findings.filter(_.pattern == BugPattern.OptionGet)
          assertEquals(optionGetFindings.size, 0, s"OptionGet should be suppressed by Ignore memory, found: $optionGetFindings")
        case other => fail(s"Expected BugHuntReport, got $other")
    finally
      store.save(Nil)
  }

  test("bug-hunt does NOT filter Lower-type memory findings") {
    val store = makeStore()
    // Lower type should still appear in results
    store.save(List(globalMem("NullLiteral", SuppressionType.Lower)))
    try
      val idx = WorkspaceIndex(workspace, needBlooms = true)
      idx.index()
      val ctx = CommandContext(idx = idx, workspace = workspace, noTaint = true, limit = 100)
      val result = cmdBugHunt(List.empty, ctx)
      result match
        case CmdResult.BugHuntReport(findings, _, _, _) =>
          // NullLiteral is in NullBug.scala — should still appear (Lower = don't suppress)
          val nullFindings = findings.filter(_.pattern == BugPattern.NullLiteral)
          assert(nullFindings.nonEmpty, "NullLiteral should NOT be suppressed by Lower memory")
        case other => fail(s"Expected BugHuntReport, got $other")
    finally
      store.save(Nil)
  }

  test("bug-hunt without memories returns findings unfiltered") {
    val store = makeStore()
    store.save(Nil)
    val idx = WorkspaceIndex(workspace, needBlooms = true)
    idx.index()
    val ctx = CommandContext(idx = idx, workspace = workspace, noTaint = true, limit = 100)
    val result = cmdBugHunt(List.empty, ctx)
    result match
      case CmdResult.BugHuntReport(findings, _, _, _) =>
        assert(findings.nonEmpty, "Should have findings in test fixtures without memories")
      case other => fail(s"Expected BugHuntReport, got $other")
  }

  // ── cmdMemory command: list/add/remove/clear ───────────────────────────

  test("cmdMemory list shows empty when no memories") {
    val store = makeStore()
    store.save(Nil)
    val idx = WorkspaceIndex(workspace, needBlooms = false)
    val ctx = CommandContext(idx = idx, workspace = workspace)
    val result = cmdMemory(List("list"), ctx)
    result match
      case CmdResult.MemoryResult(mems, _) => assertEquals(mems, Nil)
      case other => fail(s"Expected MemoryResult, got $other")
  }

  test("cmdMemory add creates a memory") {
    val store = makeStore()
    store.save(Nil)
    try
      val idx = WorkspaceIndex(workspace, needBlooms = false)
      val ctx = CommandContext(idx = idx, workspace = workspace)
      val result = cmdMemory(List("add", "OptionGet", "--reason", "false positive", "--scope", "global"), ctx)
      result match
        case CmdResult.MemoryResult(mems, msg) =>
          assertEquals(mems.size, 1)
          assertEquals(mems.head.patternName, "OptionGet")
          assert(msg.contains("Added"), s"Expected confirmation message, got: $msg")
        case other => fail(s"Expected MemoryResult, got $other")
    finally
      store.save(Nil)
  }

  test("cmdMemory remove returns UsageError for out-of-range index") {
    val store = makeStore()
    store.save(Nil)
    val idx = WorkspaceIndex(workspace, needBlooms = false)
    val ctx = CommandContext(idx = idx, workspace = workspace)
    val result = cmdMemory(List("remove", "99"), ctx)
    assert(result.isInstanceOf[CmdResult.UsageError], s"Expected UsageError, got $result")
  }

  test("cmdMemory unknown subcommand returns UsageError") {
    val store = makeStore()
    store.save(Nil)
    val idx = WorkspaceIndex(workspace, needBlooms = false)
    val ctx = CommandContext(idx = idx, workspace = workspace)
    val result = cmdMemory(List("bogus"), ctx)
    assert(result.isInstanceOf[CmdResult.UsageError], s"Expected UsageError, got $result")
  }
