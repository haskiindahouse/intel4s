import munit.FunSuite
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

// ── Meta-fuzzing: PatternSpec tests — Crypto, XML, Logging, Regex patterns ────

class PatternSpecCryptoSuite extends ScalexTestBase:

  private def scanForPattern(code: String, pattern: BugPattern): List[BugFinding] =
    val fileName = s"pspec-cry-${pattern.toString.toLowerCase}.scala"
    val testFile = workspace.resolve(fileName)
    Files.writeString(testFile, s"object PSpecCryHelper {\n$code\n}")
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

  // ── WeakHash ──────────────────────────────────────────────────────────────────

  test("PatternSpec: WeakHash — positive (MD5)") {
    val findings = scanForPattern(
      """import java.security.MessageDigest
        |def bad = MessageDigest.getInstance("MD5")""".stripMargin,
      BugPattern.WeakHash
    )
    assert(findings.nonEmpty, "MD5 MessageDigest must trigger WeakHash")
  }

  test("PatternSpec: WeakHash — negative (SHA-256)") {
    val findings = scanForPattern(
      """import java.security.MessageDigest
        |def ok = MessageDigest.getInstance("SHA-256")""".stripMargin,
      BugPattern.WeakHash
    )
    assertEquals(findings.size, 0, "SHA-256 must not trigger WeakHash")
  }

  // ── WeakCipher ────────────────────────────────────────────────────────────────

  test("PatternSpec: WeakCipher — positive (DES)") {
    val findings = scanForPattern(
      """import javax.crypto.Cipher
        |def bad = Cipher.getInstance("DES/CBC/PKCS5Padding")""".stripMargin,
      BugPattern.WeakCipher
    )
    assert(findings.nonEmpty, "DES cipher must trigger WeakCipher")
  }

  test("PatternSpec: WeakCipher — negative (AES)") {
    val findings = scanForPattern(
      """import javax.crypto.Cipher
        |def ok = Cipher.getInstance("AES/CBC/PKCS5Padding")""".stripMargin,
      BugPattern.WeakCipher
    )
    assertEquals(findings.size, 0, "AES cipher must not trigger WeakCipher")
  }

  // ── WeakRandom ────────────────────────────────────────────────────────────────

  test("PatternSpec: WeakRandom — positive (new Random)") {
    val findings = scanForPattern(
      """import java.util.Random
        |def bad = new Random()""".stripMargin,
      BugPattern.WeakRandom
    )
    assert(findings.nonEmpty, "new java.util.Random must trigger WeakRandom")
  }

  test("PatternSpec: WeakRandom — negative (SecureRandom)") {
    val findings = scanForPattern(
      """import java.security.SecureRandom
        |def ok = new SecureRandom()""".stripMargin,
      BugPattern.WeakRandom
    )
    assertEquals(findings.size, 0, "SecureRandom must not trigger WeakRandom")
  }

  // ── HardcodedIV ───────────────────────────────────────────────────────────────

  test("PatternSpec: HardcodedIV — positive (IvParameterSpec with literal.getBytes)") {
    val findings = scanForPattern(
      """import javax.crypto.spec.IvParameterSpec
        |def bad = new IvParameterSpec("1234567890123456".getBytes)""".stripMargin,
      BugPattern.HardcodedIV
    )
    assert(findings.nonEmpty, "IvParameterSpec with hardcoded getBytes must trigger HardcodedIV")
  }

  test("PatternSpec: HardcodedIV — negative (dynamic IV array)") {
    // WHY: IvParameterSpec with a variable (not literal.getBytes) should not trigger
    val findings = scanForPattern(
      """import javax.crypto.spec.IvParameterSpec
        |def ok(iv: Array[Byte]) = new IvParameterSpec(iv)""".stripMargin,
      BugPattern.HardcodedIV
    )
    assertEquals(findings.size, 0, "IvParameterSpec with variable bytes must not trigger HardcodedIV")
  }

  // ── ECBMode ───────────────────────────────────────────────────────────────────

  test("PatternSpec: ECBMode — positive") {
    val findings = scanForPattern(
      """import javax.crypto.Cipher
        |def bad = Cipher.getInstance("AES/ECB/PKCS5Padding")""".stripMargin,
      BugPattern.ECBMode
    )
    assert(findings.nonEmpty, "ECB mode must trigger ECBMode")
  }

  test("PatternSpec: ECBMode — negative (GCM mode)") {
    val findings = scanForPattern(
      """import javax.crypto.Cipher
        |def ok = Cipher.getInstance("AES/GCM/NoPadding")""".stripMargin,
      BugPattern.ECBMode
    )
    assertEquals(findings.size, 0, "GCM mode must not trigger ECBMode")
  }

  // ── NoPadding ─────────────────────────────────────────────────────────────────

  test("PatternSpec: NoPadding — positive (CBC/NoPadding)") {
    val findings = scanForPattern(
      """import javax.crypto.Cipher
        |def bad = Cipher.getInstance("AES/CBC/NoPadding")""".stripMargin,
      BugPattern.NoPadding
    )
    assert(findings.nonEmpty, "NoPadding in cipher must trigger NoPadding pattern")
  }

  test("PatternSpec: NoPadding — negative (PKCS5Padding)") {
    val findings = scanForPattern(
      """import javax.crypto.Cipher
        |def ok = Cipher.getInstance("AES/CBC/PKCS5Padding")""".stripMargin,
      BugPattern.NoPadding
    )
    assertEquals(findings.size, 0, "PKCS5Padding must not trigger NoPadding")
  }

  // ── XXE ───────────────────────────────────────────────────────────────────────

  test("PatternSpec: XXE — positive (DocumentBuilderFactory)") {
    val findings = scanForPattern(
      """import javax.xml.parsers.DocumentBuilderFactory
        |def bad = DocumentBuilderFactory.newInstance()""".stripMargin,
      BugPattern.XXE
    )
    assert(findings.nonEmpty, "DocumentBuilderFactory.newInstance must trigger XXE")
  }

  test("PatternSpec: XXE — negative (no XML parsing)") {
    val findings = scanForPattern(
      """def ok = "no xml here".length""",
      BugPattern.XXE
    )
    assertEquals(findings.size, 0, "Non-XML code must not trigger XXE")
  }

  // ── XPathInjection ────────────────────────────────────────────────────────────

  test("PatternSpec: XPathInjection — positive") {
    // WHY: scanner checks app.toString.contains("XPath") — param must be named XPath* for match
    val findings = scanForPattern(
      """def bad(expr: String, XPath: Any, doc: Any) = XPath.evaluate(expr, doc)""",
      BugPattern.XPathInjection
    )
    assert(findings.nonEmpty, "XPath.evaluate with non-literal must trigger XPathInjection")
  }

  test("PatternSpec: XPathInjection — negative (literal expression)") {
    // WHY: scanner checks argClause.values.exists(!isLiteralArg(_)) — all args must be literal
    val findings = scanForPattern(
      """def ok(XPath: Any) = XPath.evaluate("/root/child", "/ctx")""",
      BugPattern.XPathInjection
    )
    assertEquals(findings.size, 0, "XPath.evaluate with all literal args must not trigger XPathInjection")
  }

  // ── LogInjection ──────────────────────────────────────────────────────────────

  test("PatternSpec: LogInjection — positive") {
    val findings = scanForPattern(
      """def bad(logger: Any, input: String) = logger.info(s"User: $input")""",
      BugPattern.LogInjection
    )
    assert(findings.nonEmpty, "Interpolated log message must trigger LogInjection")
  }

  test("PatternSpec: LogInjection — negative (literal log message)") {
    val findings = scanForPattern(
      """def ok(logger: Any) = logger.info("Server started")""",
      BugPattern.LogInjection
    )
    assertEquals(findings.size, 0, "Literal log message must not trigger LogInjection")
  }

  // ── RegexDoS ──────────────────────────────────────────────────────────────────

  test("PatternSpec: RegexDoS — positive (Pattern.compile with var)") {
    val findings = scanForPattern(
      """import java.util.regex.Pattern
        |def bad(userInput: String) = Pattern.compile(userInput)""".stripMargin,
      BugPattern.RegexDoS
    )
    assert(findings.nonEmpty, "Pattern.compile with non-literal must trigger RegexDoS")
  }

  test("PatternSpec: RegexDoS — negative (literal pattern)") {
    val findings = scanForPattern(
      """import java.util.regex.Pattern
        |def ok = Pattern.compile("[a-z]+")""".stripMargin,
      BugPattern.RegexDoS
    )
    assertEquals(findings.size, 0, "Pattern.compile with literal must not trigger RegexDoS")
  }

  // ── LDAPInjection — not yet implemented in scanner; pending ───────────────────

  test("PatternSpec: LDAPInjection — positive".pending) {
    // Pattern defined in model but not yet implemented in scanner
  }

  test("PatternSpec: LDAPInjection — negative".pending) {
    val findings = scanForPattern(
      """def ok = "cn=admin,dc=example,dc=com" """,
      BugPattern.LDAPInjection
    )
    assertEquals(findings.size, 0, "Literal LDAP string must not trigger LDAPInjection")
  }
