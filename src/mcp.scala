import java.io.{BufferedReader, ByteArrayOutputStream, InputStreamReader, PrintStream}
import java.nio.file.Path

// ── Minimal JSON (just enough for MCP JSON-RPC) ────────────────────────────

enum McpJson:
  case Str(value: String)
  case Num(value: Double)
  case Bool(value: Boolean)
  case Null
  case Arr(values: List[McpJson])
  case Obj(fields: List[(key: String, value: McpJson)])

  def apply(k: String): McpJson = this match
    case Obj(fields) => fields.find(_.key == k).map(_.value).getOrElse(McpJson.Null)
    case _ => McpJson.Null

  def asStr: Option[String] = this match
    case Str(v) => Some(v)
    case _ => None

  def asNum: Option[Double] = this match
    case Num(v) => Some(v)
    case _ => None

  def asArr: Option[List[McpJson]] = this match
    case Arr(vs) => Some(vs)
    case _ => None

  def asBool: Option[Boolean] = this match
    case Bool(v) => Some(v)
    case _ => None

object McpJson:
  def parse(input: String): McpJson =
    val r = Reader(input)
    parseValue(r)

  private class Reader(val s: String, var pos: Int = 0):
    def peek: Char = if pos < s.length then s.charAt(pos) else '\u0000'
    def next(): Char = { val c = peek; pos += 1; c }
    def skip(): Unit = while pos < s.length && s.charAt(pos).isWhitespace do pos += 1

  private def parseValue(r: Reader): McpJson =
    r.skip()
    r.peek match
      case '"' => parseStr(r)
      case '{' => parseObj(r)
      case '[' => parseArr(r)
      case 't' => r.pos += 4; Bool(true)
      case 'f' => r.pos += 5; Bool(false)
      case 'n' => r.pos += 4; Null
      case _   => parseNum(r)

  private def parseStr(r: Reader): Str =
    r.next() // skip opening "
    val sb = StringBuilder()
    while r.peek != '"' && r.peek != '\u0000' do
      if r.peek == '\\' then
        r.next()
        r.next() match
          case '"'  => sb.append('"')
          case '\\' => sb.append('\\')
          case '/'  => sb.append('/')
          case 'n'  => sb.append('\n')
          case 'r'  => sb.append('\r')
          case 't'  => sb.append('\t')
          case 'u'  =>
            val hex = new String(Array(r.next(), r.next(), r.next(), r.next()))
            sb.append(Integer.parseInt(hex, 16).toChar)
          case c => sb.append(c)
      else sb.append(r.next())
    r.next() // skip closing "
    Str(sb.toString)

  private def parseNum(r: Reader): Num =
    val start = r.pos
    if r.peek == '-' then r.next()
    while r.peek.isDigit do r.next()
    if r.peek == '.' then { r.next(); while r.peek.isDigit do r.next() }
    if r.peek == 'e' || r.peek == 'E' then
      r.next()
      if r.peek == '+' || r.peek == '-' then r.next()
      while r.peek.isDigit do r.next()
    Num(r.s.substring(start, r.pos).toDouble)

  private def parseObj(r: Reader): Obj =
    r.next() // skip {
    r.skip()
    if r.peek == '}' then
      r.next()
      Obj(Nil)
    else
      val fields = scala.collection.mutable.ListBuffer[(key: String, value: McpJson)]()
      var more = true
      while more do
        r.skip()
        val k = parseStr(r).value
        r.skip(); r.next() // skip :
        fields += ((key = k, value = parseValue(r)))
        r.skip()
        if r.peek == ',' then r.next() else more = false
      r.skip(); r.next() // skip }
      Obj(fields.toList)

  private def parseArr(r: Reader): Arr =
    r.next() // skip [
    r.skip()
    if r.peek == ']' then
      r.next()
      Arr(Nil)
    else
      val values = scala.collection.mutable.ListBuffer[McpJson]()
      var more = true
      while more do
        values += parseValue(r)
        r.skip()
        if r.peek == ',' then r.next() else more = false
      r.skip(); r.next() // skip ]
      Arr(values.toList)

// ── MCP Server ────────────────────────────────────────────────────────────

object McpServer:
  private val indexCache = scala.collection.mutable.Map[Path, WorkspaceIndex]()

  private val validCmds: Set[String] = commands.keySet + "graph"

  private case class ToolDef(name: String, desc: String, queryDesc: Option[String])

  private val toolDefs: List[ToolDef] = List(
    ToolDef("search", "Search symbols by name (fuzzy camelCase matching). Returns matching classes, traits, objects, methods, types.", Some("Symbol name to search for")),
    ToolDef("def", "Find where a symbol is defined — returns file path and line number.", Some("Symbol name")),
    ToolDef("impl", "Find all implementations/extensions of a trait or class.", Some("Trait or class name")),
    ToolDef("refs", "Find all references to a symbol, categorized by type (definition, extension, import, type usage, call site).", Some("Symbol name")),
    ToolDef("imports", "Find all files that import a symbol.", Some("Symbol name")),
    ToolDef("members", "List members of a class, trait, or object. Supports --inherited, --body, --brief.", Some("Class, trait, or object name")),
    ToolDef("doc", "Show Scaladoc documentation for a symbol.", Some("Symbol name")),
    ToolDef("symbols", "List all symbols defined in a file.", Some("File path relative to workspace")),
    ToolDef("file", "Search files by name (fuzzy camelCase matching).", Some("File name or pattern")),
    ToolDef("annotated", "Find symbols with a specific annotation (e.g. main, deprecated).", Some("Annotation name")),
    ToolDef("grep", "Regex search in file contents. Supports --in <Type>, --each-method, --count.", Some("Regex pattern")),
    ToolDef("package", "List symbols in a package. Use --explain for brief explanation of each type.", Some("Package name")),
    ToolDef("body", "Extract source code of a method, val, or class. Supports -C N context lines, --imports.", Some("Symbol name")),
    ToolDef("hierarchy", "Show full inheritance tree — parents and children. Use --up/--down/--depth N.", Some("Class or trait name")),
    ToolDef("overrides", "Find override implementations of a method across the codebase.", Some("Method name")),
    ToolDef("explain", "Composite one-shot summary: definition + scaladoc + members + implementations + imports.", Some("Symbol name")),
    ToolDef("deps", "Show symbol dependencies — what this symbol depends on.", Some("Symbol name")),
    ToolDef("context", "Show enclosing scopes at a specific file location.", Some("file:line (e.g. src/Main.scala:42)")),
    ToolDef("diff", "Symbol-level diff: what symbols were added/removed/changed vs a git ref.", Some("Git ref (branch, tag, or commit)")),
    ToolDef("coverage", "Check if a symbol is tested — finds test files that reference it.", Some("Symbol name")),
    ToolDef("api", "Show public API surface of a package — symbols imported by other packages.", Some("Package name")),
    ToolDef("summary", "Show sub-packages with symbol counts.", Some("Package name")),
    ToolDef("overview", "Codebase summary: packages, hub types, dependency graph. Use --architecture, --concise.", None),
    ToolDef("packages", "List all packages in the workspace.", None),
    ToolDef("index", "Rebuild the workspace index from scratch.", None),
    ToolDef("ast-pattern", "Structural AST search. Use --has-method NAME, --extends TRAIT, --body-contains PAT.", None),
    ToolDef("tests", "List test cases structurally (MUnit, ScalaTest, specs2). Use --count for summary.", None),
    ToolDef("entrypoints", "Find @main, def main, extends App, and test suites.", None),
    ToolDef("graph", "Render a directed graph as ASCII/Unicode art.", Some("Graph expression (e.g. 'A->B, B->C')")),
  )

  def run(): Unit =
    val reader = BufferedReader(InputStreamReader(System.in))
    // Save the real stdout for JSON-RPC responses; redirect System.out so command
    // output doesn't leak into the protocol stream
    val jsonOut = System.out
    val nullStream = PrintStream(java.io.OutputStream.nullOutputStream())
    System.setOut(nullStream)
    Console.withOut(nullStream) {
      var running = true
      while running do
        val line = reader.readLine()
        if line == null then running = false
        else if line.trim.nonEmpty then
          try
            val msg = McpJson.parse(line)
            try
              handleMessage(msg).foreach { response =>
                jsonOut.println(response)
                jsonOut.flush()
              }
            catch
              case e: Exception =>
                System.err.println(s"scalex MCP: internal error: ${e.getMessage}")
                val id = msg("id")
                val errResp = jsonRpcError(id, -32603, s"Internal error: ${Option(e.getMessage).getOrElse(e.getClass.getName)}")
                jsonOut.println(errResp)
                jsonOut.flush()
          catch
            case e: Exception =>
              System.err.println(s"scalex MCP: parse error: ${e.getMessage}")
              val errResp = jsonRpcError(McpJson.Null, -32700, s"Parse error: ${Option(e.getMessage).getOrElse("invalid JSON")}")
              jsonOut.println(errResp)
              jsonOut.flush()
    }

  private def handleMessage(msg: McpJson): Option[String] =
    val id = msg("id")
    val method = msg("method").asStr.getOrElse("")
    method match
      case "initialize"                => Some(respondInitialize(id))
      case "notifications/initialized" => None
      case "tools/list"                => Some(respondToolsList(id))
      case "tools/call"                => Some(respondToolsCall(id, msg("params")))
      case "ping"                      => Some(jsonRpcResult(id, "{}"))
      case _ =>
        if id == McpJson.Null then None // unknown notification — ignore
        else Some(jsonRpcError(id, -32601, s"Method not found: $method"))

  // ── Protocol responses ──────────────────────────────────────────────────

  private def respondInitialize(id: McpJson): String =
    jsonRpcResult(id,
      s"""{"protocolVersion":"2024-11-05","capabilities":{"tools":{}},"serverInfo":{"name":"scalex","version":"${jsonEscape(ScalexVersion)}"}}""")

  private def respondToolsList(id: McpJson): String =
    val tools = toolDefs.map { td =>
      val props = StringBuilder()
      props.append(""""workspace":{"type":"string","description":"Absolute path to the project root"}""")
      td.queryDesc.foreach { qd =>
        props.append(s""","query":{"type":"string","description":"${jsonEscape(qd)}"}""")
      }
      props.append(""","args":{"type":"array","items":{"type":"string"},"description":"Additional CLI flags (e.g. [\"--verbose\", \"--json\", \"--kind\", \"class\"])"}""")
      val required = if td.queryDesc.isDefined then """"workspace","query"""" else """"workspace""""
      s"""{"name":"scalex_${td.name}","description":"${jsonEscape(td.desc)}","inputSchema":{"type":"object","properties":{${props.toString}},"required":[$required]}}"""
    }.mkString("[", ",", "]")
    jsonRpcResult(id, s"""{"tools":$tools}""")

  private def respondToolsCall(id: McpJson, params: McpJson): String =
    val toolName = params("name").asStr.getOrElse("")
    val arguments = params("arguments")
    val cmd = toolName.stripPrefix("scalex_")

    if !validCmds.contains(cmd) then
      toolResult(id, s"Unknown tool: $toolName", isError = true)
    else
      val workspace = arguments("workspace").asStr.getOrElse(".")
      val query = arguments("query").asStr
      val extraArgs = arguments("args").asArr.getOrElse(Nil).flatMap(_.asStr)

      try
        val wsPath = resolveWorkspace(workspace)
        val allArgs = query.toList ++ extraArgs
        val flags = parseFlags(allArgs)
        Timings.enabled = flags.timingsEnabled

        val (result, output) =
          if cmd == "graph" then
            val graphArgs = query.map(q => List("--render", q)).getOrElse(Nil) ++
              extraArgs.filterNot(_ == "--parse") // --parse reads stdin, not available in MCP
            val dummyIdx = WorkspaceIndex(wsPath, needBlooms = false)
            val ctx = flagsToContext(flags, dummyIdx, wsPath)
            val r = cmdGraph(graphArgs, ctx)
            (r, captureOutput { renderWithBudget(r, ctx) })
          else if cmd == "index" then
            indexCache.remove(wsPath)
            val idx = WorkspaceIndex(wsPath, needBlooms = true)
            idx.index()
            indexCache(wsPath) = idx
            val ctx = flagsToContext(flags, idx, wsPath)
            val r = cmdIndex(Nil, ctx)
            (r, captureOutput { renderWithBudget(r, ctx) })
          else
            val idx = indexCache.getOrElseUpdate(wsPath, {
              val i = WorkspaceIndex(wsPath, needBlooms = true)
              i.index()
              i
            })
            idx.index() // incremental update — checks OIDs, re-parses only changed files
            val effectiveNoTests = if cmd == "overview" && !flags.includeTests then true else flags.noTests
            val ctx = flagsToContext(flags, idx, wsPath, effectiveNoTests = Some(effectiveNoTests))
            val handler = commands.getOrElse(cmd, (_: List[String], _: CommandContext) => CmdResult.UsageError(s"Unknown command: $cmd"))
            val r = handler(flags.cleanArgs, ctx)
            (r, captureOutput { renderWithBudget(r, ctx) })

        Timings.report()
        Timings.enabled = false
        val category = classifyResult(result)
        toolResult(id, output, isError = category.isDefined, errorCategory = category)
      catch
        case e: Exception =>
          Timings.enabled = false
          val msg = Option(e.getMessage).getOrElse(e.getClass.getName)
          val category = if msg.toLowerCase.contains("parse") then "parse_error" else "internal_error"
          toolResult(id, s"Error: $msg", isError = true, errorCategory = Some(category))

  // ── Helpers ─────────────────────────────────────────────────────────────

  private def classifyResult(result: CmdResult): Option[String] = result match
    case _: CmdResult.NotFound => Some("not_found")
    case _: CmdResult.UsageError => Some("usage_error")
    case r: CmdResult.BugHuntReport if r.timedOut => Some("timeout")
    case r: CmdResult.RefList if r.timedOut => Some("timeout")
    case r: CmdResult.CategorizedRefs if r.timedOut => Some("timeout")
    case r: CmdResult.FlatRefs if r.timedOut => Some("timeout")
    case r: CmdResult.GrepCount if r.timedOut => Some("timeout")
    case r: CmdResult.GrepByMethod if r.timedOut => Some("timeout")
    case _ => None

  private def captureOutput(block: => Unit): String =
    val baos = ByteArrayOutputStream()
    val ps = PrintStream(baos, true, "UTF-8")
    val savedOut = System.out
    System.setOut(ps)
    try Console.withOut(ps) { block }
    finally System.setOut(savedOut)
    baos.toString("UTF-8").stripTrailing()

  private def toolResult(id: McpJson, text: String, isError: Boolean = false, errorCategory: Option[String] = None): String =
    val errField = if isError then ""","isError":true""" else ""
    val metaField = errorCategory.map(cat => s""","_meta":{"errorCategory":"$cat"}""").getOrElse("")
    jsonRpcResult(id, s"""{"content":[{"type":"text","text":"${jsonEscape(text)}"}]$errField$metaField}""")

  private def jsonRpcResult(id: McpJson, result: String): String =
    s"""{"jsonrpc":"2.0","id":${formatId(id)},"result":$result}"""

  private def jsonRpcError(id: McpJson, code: Int, message: String): String =
    s"""{"jsonrpc":"2.0","id":${formatId(id)},"error":{"code":$code,"message":"${jsonEscape(message)}"}}"""

  // Test-only entry points
  def testHandleMessage(msg: McpJson): Option[String] = handleMessage(msg)
  def testClearCache(): Unit = indexCache.clear()

  private def formatId(id: McpJson): String = id match
    case McpJson.Num(n) => if n == n.toLong then n.toLong.toString else n.toString
    case McpJson.Str(s) => s""""${jsonEscape(s)}""""
    case _ => "null"
