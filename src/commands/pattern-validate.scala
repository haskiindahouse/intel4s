import java.nio.file.{Files, Path}
import scala.collection.mutable

// ── Pattern spec model ────────────────────────────────────────────────────

case class PatternSpec(
  patternName: String,
  severity: String,
  category: String,
  description: String,
  cwe: String,
  bloomKeywords: List[String],
  testPositive: String,
  testNegative: String,
  testSuppressed: Option[String]
)

case class PatternValidationResult(
  patternName: String,
  positivePass: Boolean,
  negativePass: Boolean,
  suppressedPass: Option[Boolean],
  errors: List[String]
)

// ── JSON parsing (hand-built — no new dependencies) ───────────────────────

private def parsePatternSpec(json: String): Either[String, PatternSpec] =
  def extractString(key: String): Option[String] =
    val pattern = s""""$key"\\s*:\\s*"((?:[^"\\\\]|\\\\.)*)"""".r
    pattern.findFirstMatchIn(json).map(_.group(1)
      .replace("\\n", "\n").replace("\\t", "\t").replace("\\\"", "\"").replace("\\\\", "\\"))

  def extractStringArray(key: String): List[String] =
    val pattern = s""""$key"\\s*:\\s*\\[([^\\]]*)\\]""".r
    pattern.findFirstMatchIn(json).map { m =>
      """"([^"]+)"""".r.findAllMatchIn(m.group(1)).map(_.group(1)).toList
    }.getOrElse(Nil)

  def extractNullableString(key: String): Option[String] =
    val nullPattern = s""""$key"\\s*:\\s*null""".r
    if nullPattern.findFirstIn(json).isDefined then None
    else extractString(key)

  val required = List("patternName", "severity", "category", "description", "cwe", "testPositive", "testNegative")
  val missing = required.filter(k => extractString(k).isEmpty)
  if missing.nonEmpty then
    Left(s"Missing required fields: ${missing.mkString(", ")}")
  else
    Right(PatternSpec(
      patternName = extractString("patternName").get,
      severity = extractString("severity").get,
      category = extractString("category").get,
      description = extractString("description").get,
      cwe = extractString("cwe").get,
      bloomKeywords = extractStringArray("bloomKeywords"),
      testPositive = extractString("testPositive").get,
      testNegative = extractString("testNegative").get,
      testSuppressed = extractNullableString("testSuppressed")
    ))

// ── Scan helper — writes temp file, runs scanFile, returns findings ───────

private def scanCodeSnippet(code: String, workspace: Path): List[BugFinding] =
  val tmpFile = Files.createTempFile(workspace, "pspec-validate-", ".scala")
  val wrapped = s"object PatternSpecValidator {\n$code\n}"
  Files.writeString(tmpFile, wrapped)
  try
    val findings = java.util.concurrent.ConcurrentLinkedQueue[BugFinding]()
    scanFile(tmpFile, tmpFile.getFileName.toString, findings, enableTaint = false)
    import scala.jdk.CollectionConverters.*
    findings.asScala.toList
  finally
    Files.deleteIfExists(tmpFile)

// ── Resolve pattern name to BugPattern enum ──────────────────────────────

private def resolvePattern(name: String): Option[BugPattern] =
  BugPattern.values.find(_.toString.equalsIgnoreCase(name))

// ── Command router ───────────────────────────────────────────────────────

def cmdPattern(args: List[String], ctx: CommandContext): CmdResult =
  args match
    case "validate" :: rest => cmdPatternValidate(rest, ctx)
    case _ => CmdResult.UsageError("Usage: agent4s pattern validate <spec-file.json> [spec-file2.json ...]")

// ── Validate subcommand ──────────────────────────────────────────────────

def cmdPatternValidate(args: List[String], ctx: CommandContext): CmdResult =
  if args.isEmpty then
    CmdResult.UsageError("Usage: agent4s pattern validate <spec-file.json> [spec-file2.json ...]")
  else
    val results = mutable.ListBuffer.empty[PatternValidationResult]
    val workspace = ctx.workspace

    for specPath <- args do
      val file = Path.of(specPath)
      val resolved = if file.isAbsolute then file else workspace.resolve(file)

      if !Files.exists(resolved) then
        results += PatternValidationResult(specPath, false, false, None, List(s"File not found: $resolved"))
      else
        val json = Files.readString(resolved)
        parsePatternSpec(json) match
          case Left(err) =>
            results += PatternValidationResult(specPath, false, false, None, List(err))
          case Right(spec) =>
            val errors = mutable.ListBuffer.empty[String]

            // Validate pattern name maps to known BugPattern
            val targetPattern = resolvePattern(spec.patternName)
            if targetPattern.isEmpty then
              errors += s"Unknown pattern: ${spec.patternName} (not in BugPattern enum)"

            // Validate severity
            val validSeverities = Set("Critical", "High", "Medium")
            if !validSeverities.contains(spec.severity) then
              errors += s"Invalid severity: ${spec.severity} (expected: ${validSeverities.mkString(", ")})"

            // Validate category
            val validCategories = BugCategory.values.map(_.toString).toSet
            if !validCategories.contains(spec.category) then
              errors += s"Invalid category: ${spec.category} (expected: ${validCategories.mkString(", ")})"

            // Run positive test
            val positiveFindings = scanCodeSnippet(spec.testPositive, workspace)
            val positiveMatches = targetPattern match
              case Some(p) => positiveFindings.filter(_.pattern == p)
              case None => positiveFindings.filter(_.pattern.toString.equalsIgnoreCase(spec.patternName))
            val positivePass = positiveMatches.nonEmpty
            if !positivePass then
              errors += s"Positive test did not trigger pattern ${spec.patternName}"

            // Run negative test
            val negativeFindings = scanCodeSnippet(spec.testNegative, workspace)
            val negativeMatches = targetPattern match
              case Some(p) => negativeFindings.filter(_.pattern == p)
              case None => negativeFindings.filter(_.pattern.toString.equalsIgnoreCase(spec.patternName))
            val negativePass = negativeMatches.isEmpty
            if !negativePass then
              errors += s"Negative test triggered pattern ${spec.patternName} (false positive)"

            // Run suppressed test (if provided)
            val suppressedPass = spec.testSuppressed.map { code =>
              val suppressedFindings = scanCodeSnippet(code, workspace)
              val suppressedMatches = targetPattern match
                case Some(p) => suppressedFindings.filter(_.pattern == p)
                case None => suppressedFindings.filter(_.pattern.toString.equalsIgnoreCase(spec.patternName))
              val pass = suppressedMatches.isEmpty
              if !pass then errors += s"Suppressed test still triggered pattern ${spec.patternName}"
              pass
            }

            results += PatternValidationResult(spec.patternName, positivePass, negativePass, suppressedPass, errors.toList)

    CmdResult.PatternValidation(results.toList)
