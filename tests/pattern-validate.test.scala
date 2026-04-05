import munit.FunSuite
import java.nio.file.{Files, Path}

// ── PatternValidate tests ─────────────────────────────────────────────────

class PatternValidateSuite extends ScalexTestBase:

  // ── JSON parsing ──────────────────────────────────────────────────────────

  test("parsePatternSpec: valid spec") {
    val json = """{
      "patternName": "WeakHash",
      "severity": "Medium",
      "category": "Security",
      "description": "Weak hash algorithm",
      "cwe": "CWE-328",
      "bloomKeywords": ["MessageDigest"],
      "testPositive": "import java.security.MessageDigest\ndef bad = MessageDigest.getInstance(\"MD5\")",
      "testNegative": "import java.security.MessageDigest\ndef ok = MessageDigest.getInstance(\"SHA-256\")",
      "testSuppressed": null
    }"""
    val result = parsePatternSpec(json)
    assert(result.isRight, s"Should parse: ${result.left.getOrElse("")}")
    val spec = result.toOption.get
    assertEquals(spec.patternName, "WeakHash")
    assertEquals(spec.severity, "Medium")
    assertEquals(spec.bloomKeywords, List("MessageDigest"))
    assert(spec.testPositive.contains("MD5"))
    assert(spec.testNegative.contains("SHA-256"))
    assertEquals(spec.testSuppressed, None)
  }

  test("parsePatternSpec: missing required field") {
    val json = """{
      "patternName": "WeakHash",
      "severity": "Medium"
    }"""
    val result = parsePatternSpec(json)
    assert(result.isLeft)
    assert(result.left.getOrElse("").contains("Missing required fields"))
  }

  // ── Pattern validation end-to-end ─────────────────────────────────────────

  test("pattern validate: valid spec passes") {
    val specFile = workspace.resolve("test-spec.pattern-spec.json")
    Files.writeString(specFile, """{
      "patternName": "WeakHash",
      "severity": "Medium",
      "category": "Security",
      "description": "Weak hash algorithm",
      "cwe": "CWE-328",
      "bloomKeywords": ["MessageDigest"],
      "testPositive": "import java.security.MessageDigest\ndef bad = MessageDigest.getInstance(\"MD5\")",
      "testNegative": "import java.security.MessageDigest\ndef ok = MessageDigest.getInstance(\"SHA-256\")",
      "testSuppressed": null
    }""")
    try
      val idx = WorkspaceIndex(workspace, needBlooms = false)
      val ctx = CommandContext(idx = idx, workspace = workspace, limit = 0, noTaint = true)
      val result = cmdPattern(List("validate", specFile.toString), ctx)
      result match
        case CmdResult.PatternValidation(results) =>
          assertEquals(results.size, 1)
          val v = results.head
          assert(v.positivePass, s"Positive should pass: ${v.errors}")
          assert(v.negativePass, s"Negative should pass: ${v.errors}")
        case other => fail(s"Expected PatternValidation, got $other")
    finally
      Files.deleteIfExists(specFile)
  }

  test("pattern validate: positive failure detected") {
    val specFile = workspace.resolve("test-spec-bad.pattern-spec.json")
    // WHY: testPositive uses SHA-256 which should NOT trigger WeakHash
    Files.writeString(specFile, """{
      "patternName": "WeakHash",
      "severity": "Medium",
      "category": "Security",
      "description": "Weak hash algorithm",
      "cwe": "CWE-328",
      "bloomKeywords": ["MessageDigest"],
      "testPositive": "import java.security.MessageDigest\ndef bad = MessageDigest.getInstance(\"SHA-256\")",
      "testNegative": "import java.security.MessageDigest\ndef ok = MessageDigest.getInstance(\"SHA-256\")",
      "testSuppressed": null
    }""")
    try
      val idx = WorkspaceIndex(workspace, needBlooms = false)
      val ctx = CommandContext(idx = idx, workspace = workspace, limit = 0, noTaint = true)
      val result = cmdPattern(List("validate", specFile.toString), ctx)
      result match
        case CmdResult.PatternValidation(results) =>
          assertEquals(results.size, 1)
          val v = results.head
          assert(!v.positivePass, "Positive should fail — SHA-256 is not weak")
          assert(v.errors.exists(_.contains("did not trigger")))
        case other => fail(s"Expected PatternValidation, got $other")
    finally
      Files.deleteIfExists(specFile)
  }

  test("pattern validate: negative failure detected") {
    val specFile = workspace.resolve("test-spec-fp.pattern-spec.json")
    // WHY: testNegative uses MD5 which SHOULD trigger WeakHash — spec is wrong
    Files.writeString(specFile, """{
      "patternName": "WeakHash",
      "severity": "Medium",
      "category": "Security",
      "description": "Weak hash algorithm",
      "cwe": "CWE-328",
      "bloomKeywords": ["MessageDigest"],
      "testPositive": "import java.security.MessageDigest\ndef bad = MessageDigest.getInstance(\"MD5\")",
      "testNegative": "import java.security.MessageDigest\ndef bad2 = MessageDigest.getInstance(\"MD5\")",
      "testSuppressed": null
    }""")
    try
      val idx = WorkspaceIndex(workspace, needBlooms = false)
      val ctx = CommandContext(idx = idx, workspace = workspace, limit = 0, noTaint = true)
      val result = cmdPattern(List("validate", specFile.toString), ctx)
      result match
        case CmdResult.PatternValidation(results) =>
          assertEquals(results.size, 1)
          val v = results.head
          assert(v.positivePass, "Positive should pass — MD5 is weak")
          assert(!v.negativePass, "Negative should fail — MD5 triggers the pattern")
          assert(v.errors.exists(_.contains("false positive")))
        case other => fail(s"Expected PatternValidation, got $other")
    finally
      Files.deleteIfExists(specFile)
  }

  test("pattern validate: file not found") {
    val idx = WorkspaceIndex(workspace, needBlooms = false)
    val ctx = CommandContext(idx = idx, workspace = workspace, limit = 0, noTaint = true)
    val result = cmdPattern(List("validate", "nonexistent.json"), ctx)
    result match
      case CmdResult.PatternValidation(results) =>
        assertEquals(results.size, 1)
        assert(results.head.errors.exists(_.contains("not found")))
      case other => fail(s"Expected PatternValidation, got $other")
  }

  test("pattern validate: unknown pattern name") {
    val specFile = workspace.resolve("test-spec-unknown.pattern-spec.json")
    Files.writeString(specFile, """{
      "patternName": "NonexistentPattern",
      "severity": "High",
      "category": "Security",
      "description": "does not exist",
      "cwe": "CWE-000",
      "bloomKeywords": [],
      "testPositive": "def x = 1",
      "testNegative": "def y = 2",
      "testSuppressed": null
    }""")
    try
      val idx = WorkspaceIndex(workspace, needBlooms = false)
      val ctx = CommandContext(idx = idx, workspace = workspace, limit = 0, noTaint = true)
      val result = cmdPattern(List("validate", specFile.toString), ctx)
      result match
        case CmdResult.PatternValidation(results) =>
          assertEquals(results.size, 1)
          assert(results.head.errors.exists(_.contains("Unknown pattern")))
        case other => fail(s"Expected PatternValidation, got $other")
    finally
      Files.deleteIfExists(specFile)
  }

  test("pattern validate: invalid severity") {
    val specFile = workspace.resolve("test-spec-sev.pattern-spec.json")
    Files.writeString(specFile, """{
      "patternName": "WeakHash",
      "severity": "SuperCritical",
      "category": "Security",
      "description": "test",
      "cwe": "CWE-328",
      "bloomKeywords": [],
      "testPositive": "import java.security.MessageDigest\ndef bad = MessageDigest.getInstance(\"MD5\")",
      "testNegative": "import java.security.MessageDigest\ndef ok = MessageDigest.getInstance(\"SHA-256\")",
      "testSuppressed": null
    }""")
    try
      val idx = WorkspaceIndex(workspace, needBlooms = false)
      val ctx = CommandContext(idx = idx, workspace = workspace, limit = 0, noTaint = true)
      val result = cmdPattern(List("validate", specFile.toString), ctx)
      result match
        case CmdResult.PatternValidation(results) =>
          assertEquals(results.size, 1)
          assert(results.head.errors.exists(_.contains("Invalid severity")))
        case other => fail(s"Expected PatternValidation, got $other")
    finally
      Files.deleteIfExists(specFile)
  }

  test("pattern: no subcommand shows usage") {
    val idx = WorkspaceIndex(workspace, needBlooms = false)
    val ctx = CommandContext(idx = idx, workspace = workspace, limit = 0, noTaint = true)
    val result = cmdPattern(Nil, ctx)
    assert(result.isInstanceOf[CmdResult.UsageError])
  }

  test("pattern validate: multiple specs") {
    val spec1 = workspace.resolve("spec1.pattern-spec.json")
    val spec2 = workspace.resolve("spec2.pattern-spec.json")
    Files.writeString(spec1, """{
      "patternName": "WeakHash",
      "severity": "Medium",
      "category": "Security",
      "description": "weak hash",
      "cwe": "CWE-328",
      "bloomKeywords": ["MessageDigest"],
      "testPositive": "import java.security.MessageDigest\ndef bad = MessageDigest.getInstance(\"MD5\")",
      "testNegative": "import java.security.MessageDigest\ndef ok = MessageDigest.getInstance(\"SHA-256\")",
      "testSuppressed": null
    }""")
    Files.writeString(spec2, """{
      "patternName": "NullLiteral",
      "severity": "Medium",
      "category": "TypeSafety",
      "description": "null usage",
      "cwe": "CWE-476",
      "bloomKeywords": ["null"],
      "testPositive": "val x: String = null",
      "testNegative": "val x: Option[String] = None",
      "testSuppressed": null
    }""")
    try
      val idx = WorkspaceIndex(workspace, needBlooms = false)
      val ctx = CommandContext(idx = idx, workspace = workspace, limit = 0, noTaint = true)
      val result = cmdPattern(List("validate", spec1.toString, spec2.toString), ctx)
      result match
        case CmdResult.PatternValidation(results) =>
          assertEquals(results.size, 2)
          assert(results(0).positivePass, s"WeakHash positive: ${results(0).errors}")
          assert(results(0).negativePass, s"WeakHash negative: ${results(0).errors}")
          assert(results(1).positivePass, s"NullLiteral positive: ${results(1).errors}")
          assert(results(1).negativePass, s"NullLiteral negative: ${results(1).errors}")
        case other => fail(s"Expected PatternValidation, got $other")
    finally
      Files.deleteIfExists(spec1)
      Files.deleteIfExists(spec2)
  }
