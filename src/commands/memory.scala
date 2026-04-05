import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

// ── Memory store: persistence for suppression memories ─────────────────────
// WHY: JSONL (one JSON object per line) — human-readable, git-diffable, easy
//      to merge across branches. No JSON library needed: hand-built serialiser.

class MemoryStore(workspace: Path):
  private val memoriesFile = workspace.resolve(".scalex/memories.json")

  def load(): List[SuppressionMemory] =
    if !Files.exists(memoriesFile) then Nil
    else
      val lines = try Files.readAllLines(memoriesFile).asScala.toList
                  catch case _: java.io.IOException => Nil
      lines.flatMap(l => parseMemory(l.trim)).filterNot(_.patternName.isEmpty)

  def save(memories: List[SuppressionMemory]): Unit =
    val parent = memoriesFile.getParent
    if !Files.exists(parent) then Files.createDirectories(parent)
    val lines = memories.map(serializeMemory)
    Files.writeString(memoriesFile, lines.mkString("\n") + (if lines.nonEmpty then "\n" else ""))

  def matches(finding: BugFinding): Option[SuppressionMemory] =
    matchesLoaded(finding, load())

  // WHY: Pre-loaded variant avoids re-reading the file for each finding in bulk scans.
  def matchesLoaded(finding: BugFinding, mems: List[SuppressionMemory]): Option[SuppressionMemory] =
    val patName = finding.pattern.toString
    val relFile = workspace.relativize(finding.file).toString
    mems.find { mem =>
      mem.scope match
        case MemoryScope.Global(p) =>
          p == patName
        case MemoryScope.FileScoped(p, fp) =>
          p == patName && relFile.matches(fp)
        case MemoryScope.MethodScoped(p, fp, mn) =>
          p == patName && relFile.matches(fp) && finding.enclosingSymbol == mn
    }

// ── JSON serialisation (hand-built, no dependency) ─────────────────────────

private def serializeMemory(m: SuppressionMemory): String =
  val scopeJson = m.scope match
    case MemoryScope.Global(p) =>
      s"""{"type":"global","patternName":"${jsonEscape(p)}"}"""
    case MemoryScope.FileScoped(p, fp) =>
      s"""{"type":"file","patternName":"${jsonEscape(p)}","filePattern":"${jsonEscape(fp)}"}"""
    case MemoryScope.MethodScoped(p, fp, mn) =>
      s"""{"type":"method","patternName":"${jsonEscape(p)}","filePattern":"${jsonEscape(fp)}","methodName":"${jsonEscape(mn)}"}"""
  val sourceJson = m.source match
    case MemorySource.LlmTriage(run) =>
      s"""{"type":"llm-triage","skillRun":"${jsonEscape(run)}"}"""
    case MemorySource.UserManual =>
      """{"type":"user-manual"}"""
    case MemorySource.CommunitySubmit(issue) =>
      s"""{"type":"community","issueNumber":$issue}"""
  s"""{"patternName":"${jsonEscape(m.patternName)}","scope":$scopeJson,"reason":"${jsonEscape(m.reason)}","source":$sourceJson,"suppressionType":"${m.suppressionType.toString.toLowerCase}","contextLine":"${jsonEscape(m.contextLine)}","createdAt":${m.createdAt}}"""

private def parseMemory(line: String): Option[SuppressionMemory] =
  if line.isEmpty || line.startsWith("//") then None
  else
    try
      val patternName = extractJsonString(line, "patternName").getOrElse("")
      val reason      = extractJsonString(line, "reason").getOrElse("")
      val contextLine = extractJsonString(line, "contextLine").getOrElse("")
      val createdAt   = extractJsonLong(line, "createdAt").getOrElse(0L)
      val suppType = extractJsonString(line, "suppressionType").getOrElse("ignore") match
        case "lower"  => SuppressionType.Lower
        case "review" => SuppressionType.Review
        case _        => SuppressionType.Ignore

      val scopeRaw = extractJsonObject(line, "scope").getOrElse("{}")
      val scope = extractJsonString(scopeRaw, "type").getOrElse("global") match
        case "file" =>
          val fp = extractJsonString(scopeRaw, "filePattern").getOrElse(".*")
          val pn = extractJsonString(scopeRaw, "patternName").getOrElse(patternName)
          MemoryScope.FileScoped(pn, fp)
        case "method" =>
          val fp = extractJsonString(scopeRaw, "filePattern").getOrElse(".*")
          val mn = extractJsonString(scopeRaw, "methodName").getOrElse("")
          val pn = extractJsonString(scopeRaw, "patternName").getOrElse(patternName)
          MemoryScope.MethodScoped(pn, fp, mn)
        case _ =>
          val pn = extractJsonString(scopeRaw, "patternName").getOrElse(patternName)
          MemoryScope.Global(pn)

      val sourceRaw = extractJsonObject(line, "source").getOrElse("{}")
      val source = extractJsonString(sourceRaw, "type").getOrElse("user-manual") match
        case "llm-triage" =>
          val run = extractJsonString(sourceRaw, "skillRun").getOrElse("")
          MemorySource.LlmTriage(run)
        case "community" =>
          val n = extractJsonInt(sourceRaw, "issueNumber").getOrElse(0)
          MemorySource.CommunitySubmit(n)
        case _ => MemorySource.UserManual

      Some(SuppressionMemory(patternName, scope, reason, source, suppType, contextLine, createdAt))
    catch
      case _: Exception => None

// ── Minimal JSON extraction helpers (no library) ──────────────────────────
// WHY: These operate on the flat JSON produced by serializeMemory.
//      They are not a general JSON parser — just enough for our own format.

private def extractJsonString(json: String, key: String): Option[String] =
  val pattern = s""""$key":"((?:[^"\\\\]|\\\\.)*)"""".r
  pattern.findFirstMatchIn(json).map { m =>
    jsonUnescape(m.group(1))
  }

private def extractJsonLong(json: String, key: String): Option[Long] =
  val pattern = s""""$key":(\\d+)""".r
  pattern.findFirstMatchIn(json).flatMap(m => m.group(1).toLongOption)

private def extractJsonInt(json: String, key: String): Option[Int] =
  val pattern = s""""$key":(\\d+)""".r
  pattern.findFirstMatchIn(json).flatMap(m => m.group(1).toIntOption)

// Extract the first nested JSON object for the given key.
// WHY: We only need one level of nesting (scope/source objects), so a
//      simple brace-counting scan is sufficient and avoids a JSON library dep.
private def extractJsonObject(json: String, key: String): Option[String] =
  val needle = s""""$key":{"""
  val start = json.indexOf(needle)
  if start < 0 then None
  else
    val objStart = start + needle.length - 1
    // WHY: mutable vars for brace-counting scan — imperative is clearest here
    var depth = 0
    var i = objStart
    var end = -1
    while i < json.length && end < 0 do
      json.charAt(i) match
        case '{' => depth += 1
        case '}' =>
          depth -= 1
          if depth == 0 then end = i
        case _ => ()
      i += 1
    if end < 0 then None
    else Some(json.substring(objStart, end + 1))

private def jsonUnescape(s: String): String =
  val sb = new StringBuilder
  var i = 0
  while i < s.length do
    if s.charAt(i) == '\\' && i + 1 < s.length then
      s.charAt(i + 1) match
        case '"'  => sb.append('"');  i += 2
        case '\\' => sb.append('\\'); i += 2
        case 'n'  => sb.append('\n'); i += 2
        case 'r'  => sb.append('\r'); i += 2
        case 't'  => sb.append('\t'); i += 2
        case _    => sb.append(s.charAt(i)); i += 1
    else
      sb.append(s.charAt(i))
      i += 1
  sb.toString

// ── memory command ─────────────────────────────────────────────────────────

def cmdMemory(args: List[String], ctx: CommandContext): CmdResult =
  val store = MemoryStore(ctx.workspace)
  args match
    case "list" :: _ | Nil =>
      val mems = store.load()
      CmdResult.MemoryResult(mems)

    case "add" :: patternName :: rest =>
      val reason     = extractFlag(rest, "--reason").getOrElse("")
      val scopeType  = extractFlag(rest, "--scope").getOrElse("global")
      val filePattern = extractFlag(rest, "--file").getOrElse(".*")
      val methodName  = extractFlag(rest, "--method").getOrElse("")
      val suppStr     = extractFlag(rest, "--type").getOrElse("ignore")
      val sourceStr   = extractFlag(rest, "--source").getOrElse("user-manual")

      val suppType = suppStr.toLowerCase match
        case "lower"  => SuppressionType.Lower
        case "review" => SuppressionType.Review
        case _        => SuppressionType.Ignore

      val source = sourceStr.toLowerCase match
        case "llm-triage" => MemorySource.LlmTriage("manual")
        case _            => MemorySource.UserManual

      val scope = scopeType.toLowerCase match
        case "file"   => MemoryScope.FileScoped(patternName, filePattern)
        case "method" => MemoryScope.MethodScoped(patternName, filePattern, methodName)
        case _        => MemoryScope.Global(patternName)

      val mem = SuppressionMemory(
        patternName    = patternName,
        scope          = scope,
        reason         = reason,
        source         = source,
        suppressionType = suppType,
        contextLine    = "",
        createdAt      = System.currentTimeMillis()
      )
      val updated = store.load() :+ mem
      store.save(updated)
      CmdResult.MemoryResult(List(mem), message = s"Added memory for $patternName")

    case "remove" :: indexStr :: _ =>
      val idx = indexStr.toIntOption.getOrElse(-1)
      val mems = store.load()
      if idx < 0 || idx >= mems.size then
        CmdResult.UsageError(s"Index $idx out of range (0..${mems.size - 1})")
      else
        val updated = mems.patch(idx, Nil, 1)
        store.save(updated)
        CmdResult.MemoryResult(updated, message = s"Removed memory at index $idx")

    case "export" :: _ =>
      val mems = store.load()
      mems.foreach(m => println(serializeMemory(m)))
      CmdResult.MemoryResult(Nil, message = "")

    case "import" :: rest =>
      val fileArg = extractFlag(rest, "--file")
      val lines = fileArg match
        case Some(path) =>
          try Files.readAllLines(java.nio.file.Paths.get(path)).asScala.toList
          catch case _: java.io.IOException => Nil
        case None =>
          val reader = new java.io.BufferedReader(new java.io.InputStreamReader(System.in))
          Iterator.continually(reader.readLine()).takeWhile(_ != null).toList
      val imported = lines.flatMap(l => parseMemory(l.trim))
      val existing = store.load()
      val merged = (existing ++ imported).distinctBy(m => (m.patternName, m.scope.toString))
      store.save(merged)
      CmdResult.MemoryResult(merged, message = s"Imported ${imported.size} memories")

    case "clear" :: _ =>
      store.save(Nil)
      CmdResult.MemoryResult(Nil, message = "Cleared all memories")

    case sub :: _ =>
      CmdResult.UsageError(s"Unknown memory subcommand: $sub. Use: list|add|remove|export|import|clear")

private def extractFlag(args: List[String], flag: String): Option[String] =
  val i = args.indexOf(flag)
  if i >= 0 then args.lift(i + 1) else None
