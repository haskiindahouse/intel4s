import munit.FunSuite
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

class BugHuntSuite extends ScalexTestBase:

  // ── Basic detection tests ──────────────────────────────────────────

  test("bug-hunt: detects .get on Option") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val ctx = CommandContext(idx = idx, workspace = workspace, limit = 0)
    val result = cmdBugHunt(Nil, ctx)
    result match
      case r: CmdResult.BugHuntReport =>
        val optGets = r.findings.filter(_.pattern == BugPattern.OptionGet)
        assert(optGets.nonEmpty, s"Should find OptionGet pattern, got: ${r.findings.map(_.pattern)}")
        // Should find the .get in OptionGetBug.bad
        val inBugFile = optGets.filter(_.file.toString.contains("OptionGetBug"))
        assert(inBugFile.nonEmpty, s"Should find OptionGet in OptionGetBug.scala")
      case other => fail(s"Expected BugHuntReport, got: $other")
  }

  test("bug-hunt: does not flag Map.get (has argument)") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val ctx = CommandContext(idx = idx, workspace = workspace, limit = 0)
    val result = cmdBugHunt(Nil, ctx)
    result match
      case r: CmdResult.BugHuntReport =>
        val mapGets = r.findings.filter { f =>
          f.pattern == BugPattern.OptionGet && f.contextLine.contains("Map")
        }
        assertEquals(mapGets.size, 0, s"Map.get should not be flagged: $mapGets")
      case other => fail(s"Expected BugHuntReport, got: $other")
  }

  test("bug-hunt: detects .head and .last on collections") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val ctx = CommandContext(idx = idx, workspace = workspace, limit = 0)
    val result = cmdBugHunt(Nil, ctx)
    result match
      case r: CmdResult.BugHuntReport =>
        val heads = r.findings.filter(f => f.pattern == BugPattern.CollectionHead && f.file.toString.contains("CollectionBug"))
        assert(heads.size >= 2, s"Should find at least 2 CollectionHead in CollectionBug.scala (head + last), got: ${heads.size}")
      case other => fail(s"Expected BugHuntReport, got: $other")
  }

  test("bug-hunt: detects null literal") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val ctx = CommandContext(idx = idx, workspace = workspace, limit = 0)
    val result = cmdBugHunt(Nil, ctx)
    result match
      case r: CmdResult.BugHuntReport =>
        val nulls = r.findings.filter(f => f.pattern == BugPattern.NullLiteral && f.file.toString.contains("NullBug"))
        assert(nulls.nonEmpty, s"Should find NullLiteral in NullBug.scala")
      case other => fail(s"Expected BugHuntReport, got: $other")
  }

  test("bug-hunt: detects asInstanceOf") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val ctx = CommandContext(idx = idx, workspace = workspace, limit = 0)
    val result = cmdBugHunt(Nil, ctx)
    result match
      case r: CmdResult.BugHuntReport =>
        val casts = r.findings.filter(f => f.pattern == BugPattern.AsInstanceOf && f.file.toString.contains("CastBug"))
        assert(casts.nonEmpty, s"Should find AsInstanceOf in CastBug.scala")
        // isInstanceOf should NOT be flagged
        val isInstance = r.findings.filter(f => f.contextLine.contains("isInstanceOf"))
        assertEquals(isInstance.size, 0, s"isInstanceOf should not be flagged: $isInstance")
      case other => fail(s"Expected BugHuntReport, got: $other")
  }

  test("bug-hunt: detects hardcoded secrets") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val ctx = CommandContext(idx = idx, workspace = workspace, limit = 0)
    val result = cmdBugHunt(Nil, ctx)
    result match
      case r: CmdResult.BugHuntReport =>
        val secrets = r.findings.filter(f => f.pattern == BugPattern.HardcodedSecret && f.file.toString.contains("SecretBug"))
        assert(secrets.size >= 2, s"Should find at least 2 secrets (password + apiKey), got: ${secrets.size}")
        // "safeName" and "placeholder" should NOT be flagged
        val safe = r.findings.filter(f => f.pattern == BugPattern.HardcodedSecret && f.contextLine.contains("safeName"))
        assertEquals(safe.size, 0, s"safeName should not be flagged: $safe")
        val placeholder = r.findings.filter(f => f.pattern == BugPattern.HardcodedSecret && f.contextLine.contains("changeme"))
        assertEquals(placeholder.size, 0, s"placeholder should not be flagged: $placeholder")
      case other => fail(s"Expected BugHuntReport, got: $other")
  }

  test("bug-hunt: detects Source.fromFile without Using/try") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val ctx = CommandContext(idx = idx, workspace = workspace, limit = 0)
    val result = cmdBugHunt(Nil, ctx)
    result match
      case r: CmdResult.BugHuntReport =>
        val leaks = r.findings.filter(f => f.pattern == BugPattern.ResourceLeak && f.file.toString.contains("ResourceBug"))
        assert(leaks.nonEmpty, s"Should find ResourceLeak in ResourceBug.scala (the bad one)")
      case other => fail(s"Expected BugHuntReport, got: $other")
  }

  // ── Filtering tests ────────────────────────────────────────────────

  test("bug-hunt: --severity critical filters to critical only") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val ctx = CommandContext(idx = idx, workspace = workspace, limit = 0, bugSeverity = Some("critical"))
    val result = cmdBugHunt(Nil, ctx)
    result match
      case r: CmdResult.BugHuntReport =>
        r.findings.foreach { f =>
          assertEquals(f.pattern.severity, BugSeverity.Critical, s"All findings should be Critical with --severity critical, got: ${f.pattern} (${f.pattern.severity})")
        }
      case other => fail(s"Expected BugHuntReport, got: $other")
  }

  test("bug-hunt: --bug-category security filters to security only") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val ctx = CommandContext(idx = idx, workspace = workspace, limit = 0, bugCategory = Some("security"))
    val result = cmdBugHunt(Nil, ctx)
    result match
      case r: CmdResult.BugHuntReport =>
        r.findings.foreach { f =>
          assertEquals(f.pattern.category, BugCategory.Security, s"All findings should be Security, got: ${f.pattern} (${f.pattern.category})")
        }
      case other => fail(s"Expected BugHuntReport, got: $other")
  }

  test("bug-hunt: --no-tests excludes test files") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val ctx = CommandContext(idx = idx, workspace = workspace, limit = 0, noTests = true)
    val result = cmdBugHunt(Nil, ctx)
    result match
      case r: CmdResult.BugHuntReport =>
        val testFindings = r.findings.filter(f => f.file.toString.contains("/test/"))
        assertEquals(testFindings.size, 0, s"Should exclude test files with --no-tests: $testFindings")
      case other => fail(s"Expected BugHuntReport, got: $other")
  }

  test("bug-hunt: --path restricts to subtree") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val ctx = CommandContext(idx = idx, workspace = workspace, limit = 0, pathFilter = Some("src/main/scala/com/bugs/"))
    val result = cmdBugHunt(Nil, ctx)
    result match
      case r: CmdResult.BugHuntReport =>
        r.findings.foreach { f =>
          val rel = workspace.relativize(f.file).toString
          assert(rel.startsWith("src/main/scala/com/bugs/"), s"All findings should be in bugs/ subtree: $rel")
        }
        assert(r.findings.nonEmpty, "Should still find bugs in the bugs/ subtree")
      case other => fail(s"Expected BugHuntReport, got: $other")
  }

  test("bug-hunt: --limit caps results") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val ctx = CommandContext(idx = idx, workspace = workspace, limit = 3)
    val result = cmdBugHunt(Nil, ctx)
    result match
      case r: CmdResult.BugHuntReport =>
        assert(r.findings.size <= 3, s"Should have at most 3 findings with --limit 3, got: ${r.findings.size}")
      case other => fail(s"Expected BugHuntReport, got: $other")
  }

  // ── Output format tests ────────────────────────────────────────────

  test("bug-hunt: text output format") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val ctx = CommandContext(idx = idx, workspace = workspace, limit = 0)
    val output = captureOut {
      renderWithBudget(cmdBugHunt(Nil, ctx), ctx)
    }
    assert(output.contains("Bug hunt:"), s"Output should start with 'Bug hunt:': ${output.take(100)}")
    assert(output.contains("finding"), s"Output should mention findings: ${output.take(200)}")
    assert(output.contains("scanned"), s"Output should mention scanned files: ${output.take(200)}")
  }

  test("bug-hunt: JSON output format") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val ctx = CommandContext(idx = idx, workspace = workspace, limit = 0, jsonOutput = true)
    val output = captureOut {
      renderWithBudget(cmdBugHunt(Nil, ctx), ctx)
    }
    assert(output.contains(""""findings":["""), s"JSON should have findings array: ${output.take(200)}")
    assert(output.contains(""""filesScanned":"""), s"JSON should have filesScanned: ${output.take(200)}")
    assert(output.contains(""""severity":"""), s"JSON should have severity field: ${output.take(200)}")
    assert(output.contains(""""pattern":"""), s"JSON should have pattern field: ${output.take(200)}")
  }

  // ── Sorting test ───────────────────────────────────────────────────

  test("bug-hunt: results sorted by severity (critical first)") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val ctx = CommandContext(idx = idx, workspace = workspace, limit = 0)
    val result = cmdBugHunt(Nil, ctx)
    result match
      case r: CmdResult.BugHuntReport =>
        if r.findings.size > 1 then
          val severities = r.findings.map(_.pattern.severity)
          val sevOrder = severities.map {
            case BugSeverity.Critical => 0
            case BugSeverity.High => 1
            case BugSeverity.Medium => 2
          }
          // Check that severity order is non-decreasing
          sevOrder.sliding(2).foreach { pair =>
            if pair.size == 2 then
              assert(pair(0) <= pair(1), s"Findings should be sorted by severity: ${severities.take(10)}")
          }
      case other => fail(s"Expected BugHuntReport, got: $other")
  }

  // ── Empty project test ─────────────────────────────────────────────

  test("bug-hunt: returns zero findings for clean code") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    // Filter to a path that has no bug patterns
    val ctx = CommandContext(idx = idx, workspace = workspace, limit = 0, pathFilter = Some("src/main/scala/com/example/Model.scala"))
    val result = cmdBugHunt(Nil, ctx)
    result match
      case r: CmdResult.BugHuntReport =>
        // Model.scala has no bug patterns
        val modelFindings = r.findings.filter(_.file.toString.contains("Model.scala"))
        assertEquals(modelFindings.size, 0, s"Model.scala should have no findings: $modelFindings")
      case other => fail(s"Expected BugHuntReport, got: $other")
  }

  // ── Hotspot test ───────────────────────────────────────────────────

  test("bug-hunt: --hotspots returns hotspot info") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val ctx = CommandContext(idx = idx, workspace = workspace, limit = 0, hotspots = true)
    val result = cmdBugHunt(Nil, ctx)
    result match
      case r: CmdResult.BugHuntReport =>
        if r.findings.nonEmpty then
          assert(r.hotspots.nonEmpty, s"Should have hotspot info when findings exist")
          r.hotspots.foreach { h =>
            assert(h.findingCount > 0, s"Hotspot should have positive finding count: $h")
            assert(h.riskScore >= 0, s"Risk score should be non-negative: $h")
          }
      case other => fail(s"Expected BugHuntReport, got: $other")
  }

  test("bug-hunt: hotspots sorted by risk descending") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val ctx = CommandContext(idx = idx, workspace = workspace, limit = 0, hotspots = true)
    val result = cmdBugHunt(Nil, ctx)
    result match
      case r: CmdResult.BugHuntReport =>
        if r.hotspots.size > 1 then
          r.hotspots.sliding(2).foreach { pair =>
            if pair.size == 2 then
              assert(pair(0).riskScore >= pair(1).riskScore, s"Hotspots should be sorted by risk descending: ${r.hotspots.map(_.riskScore)}")
          }
      case other => fail(s"Expected BugHuntReport, got: $other")
  }
