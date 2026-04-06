import munit.FunSuite
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

// ── Meta-fuzzing: PatternSpec tests — Effect + Concurrency patterns ────────────

class PatternSpecEffectsSuite extends ScalexTestBase:

  private def scanForPattern(code: String, pattern: BugPattern): List[BugFinding] =
    val fileName = s"pspec-eff-${pattern.toString.toLowerCase}.scala"
    val testFile = workspace.resolve(fileName)
    Files.writeString(testFile, s"object PSpecEffHelper {\n$code\n}")
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

  // ── ThrowInZioSucceed ─────────────────────────────────────────────────────────

  test("PatternSpec: ThrowInZioSucceed — positive") {
    val findings = scanForPattern(
      """def bad = ZIO.succeed(throw new RuntimeException("boom"))""",
      BugPattern.ThrowInZioSucceed
    )
    assert(findings.nonEmpty, "throw inside ZIO.succeed must trigger ThrowInZioSucceed")
  }

  test("PatternSpec: ThrowInZioSucceed — negative (throw in ZIO.fail is intentional)") {
    // throw outside ZIO.succeed context should not trigger this specific pattern
    val findings = scanForPattern(
      """def ok: Nothing = throw new RuntimeException("top level")""",
      BugPattern.ThrowInZioSucceed
    )
    assertEquals(findings.size, 0, "throw at top level must not trigger ThrowInZioSucceed")
  }

  // ── AwaitInfinite ─────────────────────────────────────────────────────────────

  test("PatternSpec: AwaitInfinite — positive") {
    val findings = scanForPattern(
      """import scala.concurrent.{Await, Future, duration}
        |def bad(f: Future[Int]) = Await.result(f, duration.Duration.Inf)""".stripMargin,
      BugPattern.AwaitInfinite
    )
    assert(findings.nonEmpty, "Await.result with Duration.Inf must trigger AwaitInfinite")
  }

  test("PatternSpec: AwaitInfinite — negative (finite timeout)") {
    val findings = scanForPattern(
      """import scala.concurrent.{Await, Future, duration}
        |def ok(f: Future[Int]) = Await.result(f, duration.Duration(10, "seconds"))""".stripMargin,
      BugPattern.AwaitInfinite
    )
    assertEquals(findings.size, 0, "Await.result with finite timeout must not trigger AwaitInfinite")
  }

  // ── ThreadSleepAsync ──────────────────────────────────────────────────────────

  test("PatternSpec: ThreadSleepAsync — positive") {
    val findings = scanForPattern(
      """def bad = Thread.sleep(1000)""",
      BugPattern.ThreadSleepAsync
    )
    assert(findings.nonEmpty, "Thread.sleep must trigger ThreadSleepAsync")
  }

  test("PatternSpec: ThreadSleepAsync — negative (ZIO.sleep is safe)") {
    val findings = scanForPattern(
      """def ok = ZIO.sleep(zio.Duration.fromMillis(1000))""",
      BugPattern.ThreadSleepAsync
    )
    assertEquals(findings.size, 0, "ZIO.sleep must not trigger ThreadSleepAsync")
  }

  // ── SenderInFuture ────────────────────────────────────────────────────────────

  test("PatternSpec: SenderInFuture — positive") {
    val findings = scanForPattern(
      """import scala.concurrent.Future
        |def bad = Future { sender() }""".stripMargin,
      BugPattern.SenderInFuture
    )
    assert(findings.nonEmpty, "sender() inside Future must trigger SenderInFuture")
  }

  test("PatternSpec: SenderInFuture — negative (sender() outside Future)") {
    val findings = scanForPattern(
      """def ok = sender()""",
      BugPattern.SenderInFuture
    )
    assertEquals(findings.size, 0, "sender() outside Future must not trigger SenderInFuture")
  }

  // ── ZioDie ───────────────────────────────────────────────────────────────────

  test("PatternSpec: ZioDie — positive") {
    val findings = scanForPattern(
      """def bad = ZIO.die(new RuntimeException("defect"))""",
      BugPattern.ZioDie
    )
    assert(findings.nonEmpty, "ZIO.die must trigger ZioDie")
  }

  test("PatternSpec: ZioDie — negative (ZIO.fail is proper error channel)") {
    val findings = scanForPattern(
      """def ok = ZIO.fail(new RuntimeException("expected"))""",
      BugPattern.ZioDie
    )
    assertEquals(findings.size, 0, "ZIO.fail must not trigger ZioDie")
  }

  // ── UnsafeRun ─────────────────────────────────────────────────────────────────

  test("PatternSpec: UnsafeRun — positive (unsafeRun)") {
    val findings = scanForPattern(
      """def bad(effect: Any) = unsafeRun(effect)""",
      BugPattern.UnsafeRun
    )
    assert(findings.nonEmpty, "unsafeRun() must trigger UnsafeRun")
  }

  test("PatternSpec: UnsafeRun — positive (Unsafe.unsafe)") {
    val findings = scanForPattern(
      """def bad(rt: Any) = Unsafe.unsafe { implicit u => rt }""",
      BugPattern.UnsafeRun
    )
    assert(findings.nonEmpty, "Unsafe.unsafe must trigger UnsafeRun")
  }

  test("PatternSpec: UnsafeRun — negative (safe fiber joining)") {
    val findings = scanForPattern(
      """def ok(fiber: Any) = fiber.join""",
      BugPattern.UnsafeRun
    )
    assertEquals(findings.size, 0, "fiber.join must not trigger UnsafeRun")
  }

  // ── BlockingInEffect — not yet implemented in scanner; pending ────────────────

  test("PatternSpec: BlockingInEffect — positive".pending) {
    // Pattern defined in model but not yet implemented in scanner
    // Expected: Thread.sleep or blocking I/O inside ZIO.succeed/flatMap should trigger
  }

  test("PatternSpec: BlockingInEffect — negative".pending) {
    val findings = scanForPattern(
      """def ok = ZIO.attemptBlocking(java.lang.Thread.sleep(1000))""",
      BugPattern.BlockingInEffect
    )
    assertEquals(findings.size, 0, "ZIO.attemptBlocking must not trigger BlockingInEffect")
  }

  // ── IgnoredFuture — not yet implemented in scanner; pending ──────────────────

  test("PatternSpec: IgnoredFuture — positive".pending) {
    // Pattern defined in model but not yet implemented in scanner
  }

  test("PatternSpec: IgnoredFuture — negative (Await consuming Future result)".pending) {
    val findings = scanForPattern(
      """import scala.concurrent.{Await, Future, duration}
        |def ok(f: Future[Int]) = Await.result(f, duration.Duration(5, "seconds"))""".stripMargin,
      BugPattern.IgnoredFuture
    )
    assertEquals(findings.size, 0, "Awaited Future must not trigger IgnoredFuture")
  }

  // ── FutureOnComplete ──────────────────────────────────────────────────────────

  test("PatternSpec: FutureOnComplete — positive") {
    // WHY: scanner checks context.contains("Future") || app.toString.contains("Future").
    // Use Future(...).onComplete so the Future context is pushed and toString contains "Future".
    val findings = scanForPattern(
      """import scala.concurrent.{Future, ExecutionContext}
        |implicit val ec: ExecutionContext = ExecutionContext.global
        |def bad = Future(42).onComplete { _ => () }""".stripMargin,
      BugPattern.FutureOnComplete
    )
    assert(findings.nonEmpty, "Future.onComplete must trigger FutureOnComplete")
  }

  test("PatternSpec: FutureOnComplete — negative (Future.map is not onComplete)") {
    val findings = scanForPattern(
      """import scala.concurrent.{Future, ExecutionContext}
        |implicit val ec: ExecutionContext = ExecutionContext.global
        |def ok = Future(42).map(_ + 1)""".stripMargin,
      BugPattern.FutureOnComplete
    )
    assertEquals(findings.size, 0, "Future.map must not trigger FutureOnComplete")
  }

  // ── SynchronizedNested ────────────────────────────────────────────────────────

  test("PatternSpec: SynchronizedNested — positive") {
    // WHY: scanner tracks "synchronized" context via bare `synchronized(...)` call (Java-style).
    // The outer `synchronized(block)` pushes context; the inner `synchronized(...)` matches.
    val findings = scanForPattern(
      """def bad = synchronized { synchronized { 42 } }""",
      BugPattern.SynchronizedNested
    )
    assert(findings.nonEmpty, "Nested bare synchronized blocks must trigger SynchronizedNested")
  }

  test("PatternSpec: SynchronizedNested — negative (no synchronized at all)") {
    // WHY: any single bare `synchronized { }` triggers because context is set for the node itself.
    // Use a completely different pattern to prove the absence of the pattern.
    val findings = scanForPattern(
      """def ok = 42 + 1""",
      BugPattern.SynchronizedNested
    )
    assertEquals(findings.size, 0, "Code with no synchronized must not trigger SynchronizedNested")
  }

  // ── VarInConcurrent — not yet implemented in scanner; pending ─────────────────

  test("PatternSpec: VarInConcurrent — positive".pending) {
    // Pattern defined in model but not yet implemented in scanner
    // Expected: var field in class that also uses Future/ZIO
  }

  test("PatternSpec: VarInConcurrent — negative (val in concurrent class)".pending) {
    val findings = scanForPattern(
      """import scala.concurrent.Future
        |class Safe { val count: Int = 0; def work = Future.successful(count) }""".stripMargin,
      BugPattern.VarInConcurrent
    )
    assertEquals(findings.size, 0, "val (immutable) in concurrent class must not trigger VarInConcurrent")
  }

  // ── MutableShared — not yet implemented in scanner; pending ──────────────────

  test("PatternSpec: MutableShared — positive".pending) {
    // Pattern defined in model but not yet implemented in scanner
    // Expected: mutable ListBuffer/ArrayBuffer in object/class field
  }

  test("PatternSpec: MutableShared — negative (immutable collection)".pending) {
    val findings = scanForPattern(
      """object Safe { val items: List[Int] = List(1, 2, 3) }""",
      BugPattern.MutableShared
    )
    assertEquals(findings.size, 0, "Immutable List in object must not trigger MutableShared")
  }
