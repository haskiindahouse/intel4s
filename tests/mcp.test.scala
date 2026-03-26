import munit.FunSuite

class McpSuite extends FunSuite:

  // ── JSON parser ─────────────────────────────────────────────────────────

  test("McpJson parses strings") {
    val result = McpJson.parse(""""hello world"""")
    assertEquals(result, McpJson.Str("hello world"))
  }

  test("McpJson parses strings with escapes") {
    val result = McpJson.parse(""""line1\\nline2\ttab\""""")
    assertEquals(result, McpJson.Str("line1\\nline2\ttab\""))
  }

  test("McpJson parses unicode escapes") {
    val result = McpJson.parse(""""\\u0048ello"""")
    // \\u0048 is literal backslash + u0048 in JSON, but \u0048 is 'H'
    val result2 = McpJson.parse(""""\u0048ello"""")
    assertEquals(result2, McpJson.Str("Hello"))
  }

  test("McpJson parses integers") {
    val result = McpJson.parse("42")
    assertEquals(result, McpJson.Num(42.0))
  }

  test("McpJson parses negative numbers") {
    val result = McpJson.parse("-3.14")
    assertEquals(result, McpJson.Num(-3.14))
  }

  test("McpJson parses booleans and null") {
    assertEquals(McpJson.parse("true"), McpJson.Bool(true))
    assertEquals(McpJson.parse("false"), McpJson.Bool(false))
    assertEquals(McpJson.parse("null"), McpJson.Null)
  }

  test("McpJson parses arrays") {
    val result = McpJson.parse("""[1, "two", true, null]""")
    assertEquals(result, McpJson.Arr(List(
      McpJson.Num(1.0), McpJson.Str("two"), McpJson.Bool(true), McpJson.Null
    )))
  }

  test("McpJson parses empty array") {
    assertEquals(McpJson.parse("[]"), McpJson.Arr(Nil))
  }

  test("McpJson parses objects") {
    val result = McpJson.parse("""{"name": "scalex", "version": 1}""")
    assertEquals(result, McpJson.Obj(List(
      (key = "name", value = McpJson.Str("scalex")),
      (key = "version", value = McpJson.Num(1.0))
    )))
  }

  test("McpJson parses empty object") {
    assertEquals(McpJson.parse("{}"), McpJson.Obj(Nil))
  }

  test("McpJson parses nested structures") {
    val result = McpJson.parse("""{"a": {"b": [1, 2]}, "c": null}""")
    val a = result("a")
    assertEquals(a("b"), McpJson.Arr(List(McpJson.Num(1.0), McpJson.Num(2.0))))
    assertEquals(result("c"), McpJson.Null)
  }

  test("McpJson.apply returns Null for missing keys") {
    val obj = McpJson.parse("""{"a": 1}""")
    assertEquals(obj("missing"), McpJson.Null)
  }

  test("McpJson.apply on non-object returns Null") {
    assertEquals(McpJson.Str("hello")("key"), McpJson.Null)
  }

  test("McpJson.asStr extracts string values") {
    assertEquals(McpJson.Str("hello").asStr, Some("hello"))
    assertEquals(McpJson.Num(42.0).asStr, None)
  }

  test("McpJson.asNum extracts numeric values") {
    assertEquals(McpJson.Num(42.0).asNum, Some(42.0))
    assertEquals(McpJson.Str("hello").asNum, None)
  }

  test("McpJson.asArr extracts array values") {
    assertEquals(McpJson.Arr(Nil).asArr, Some(Nil))
    assertEquals(McpJson.Str("hello").asArr, None)
  }

  test("McpJson.asBool extracts boolean values") {
    assertEquals(McpJson.Bool(true).asBool, Some(true))
    assertEquals(McpJson.Null.asBool, None)
  }

  test("McpJson parseStr handles unterminated string without infinite loop") {
    // Should not hang — EOF terminates the parse
    val result = McpJson.parse(""""unterminated""")
    assertEquals(result, McpJson.Str("unterminated"))
  }

  test("McpJson parses scientific notation") {
    val result = McpJson.parse("1.5e10")
    assertEquals(result, McpJson.Num(1.5e10))
  }

  test("McpJson parses deeply nested structures") {
    val result = McpJson.parse("""{"a":{"b":{"c":{"d":42}}}}""")
    assertEquals(result("a")("b")("c")("d").asNum, Some(42.0))
  }

  // ── JSON-RPC message parsing ────────────────────────────────────────────

  test("McpJson parses initialize request") {
    val msg = McpJson.parse("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"cursor","version":"1.0"}}}""")
    assertEquals(msg("jsonrpc").asStr, Some("2.0"))
    assertEquals(msg("id").asNum, Some(1.0))
    assertEquals(msg("method").asStr, Some("initialize"))
    assertEquals(msg("params")("protocolVersion").asStr, Some("2024-11-05"))
    assertEquals(msg("params")("clientInfo")("name").asStr, Some("cursor"))
  }

  test("McpJson parses tools/call request") {
    val msg = McpJson.parse("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"scalex_search","arguments":{"workspace":"/tmp/project","query":"UserService","args":["--verbose","--kind","class"]}}}""")
    assertEquals(msg("method").asStr, Some("tools/call"))
    val params = msg("params")
    assertEquals(params("name").asStr, Some("scalex_search"))
    val args = params("arguments")
    assertEquals(args("workspace").asStr, Some("/tmp/project"))
    assertEquals(args("query").asStr, Some("UserService"))
    val extraArgs = args("args").asArr.get.flatMap(_.asStr)
    assertEquals(extraArgs, List("--verbose", "--kind", "class"))
  }

  test("McpJson parses notification (no id)") {
    val msg = McpJson.parse("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
    assertEquals(msg("method").asStr, Some("notifications/initialized"))
    assertEquals(msg("id"), McpJson.Null)
  }

  // ── MCP protocol responses ──────────────────────────────────────────────

  test("McpServer.handleMessage returns initialize response") {
    val msg = McpJson.parse("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""")
    val response = McpServer.testHandleMessage(msg)
    assert(response.isDefined, "initialize should return a response")
    val resp = McpJson.parse(response.get)
    assertEquals(resp("jsonrpc").asStr, Some("2.0"))
    assertEquals(resp("id").asNum, Some(1.0))
    val result = resp("result")
    assertEquals(result("protocolVersion").asStr, Some("2024-11-05"))
    assertEquals(result("serverInfo")("name").asStr, Some("scalex"))
    assertEquals(result("serverInfo")("version").asStr, Some(ScalexVersion))
  }

  test("McpServer.handleMessage returns no response for notifications") {
    val msg = McpJson.parse("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
    val response = McpServer.testHandleMessage(msg)
    assert(response.isEmpty, "notifications should not return a response")
  }

  test("McpServer.handleMessage returns tools list") {
    val msg = McpJson.parse("""{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""")
    val response = McpServer.testHandleMessage(msg)
    assert(response.isDefined, "tools/list should return a response")
    val resp = McpJson.parse(response.get)
    val tools = resp("result")("tools").asArr.get
    assert(tools.nonEmpty, "should have tools")
    // Verify expected tool names exist
    val toolNames = tools.flatMap(t => t("name").asStr).toSet
    assert(toolNames.contains("scalex_search"), s"should have scalex_search: $toolNames")
    assert(toolNames.contains("scalex_def"), s"should have scalex_def: $toolNames")
    assert(toolNames.contains("scalex_refs"), s"should have scalex_refs: $toolNames")
    assert(toolNames.contains("scalex_overview"), s"should have scalex_overview: $toolNames")
    assert(toolNames.contains("scalex_graph"), s"should have scalex_graph: $toolNames")
    assert(toolNames.contains("scalex_mcp") == false, "should not have scalex_mcp (mcp is a mode, not a tool)")
    assert(toolNames.contains("scalex_batch") == false, "should not have scalex_batch (use individual tool calls)")
  }

  test("McpServer.handleMessage returns tool schemas with workspace") {
    val msg = McpJson.parse("""{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""")
    val response = McpServer.testHandleMessage(msg)
    val tools = McpJson.parse(response.get)("result")("tools").asArr.get
    // Check a tool with query
    val searchTool = tools.find(t => t("name").asStr.contains("scalex_search")).get
    val schema = searchTool("inputSchema")
    val required = schema("required").asArr.get.flatMap(_.asStr)
    assert(required.contains("workspace"), "search should require workspace")
    assert(required.contains("query"), "search should require query")
    // Check a tool without query
    val overviewTool = tools.find(t => t("name").asStr.contains("scalex_overview")).get
    val overviewRequired = overviewTool("inputSchema")("required").asArr.get.flatMap(_.asStr)
    assert(overviewRequired.contains("workspace"), "overview should require workspace")
    assert(!overviewRequired.contains("query"), "overview should not require query")
  }

  test("McpServer.handleMessage returns error for unknown method") {
    val msg = McpJson.parse("""{"jsonrpc":"2.0","id":99,"method":"unknown/method"}""")
    val response = McpServer.testHandleMessage(msg)
    assert(response.isDefined, "unknown method should return error")
    val resp = McpJson.parse(response.get)
    assertEquals(resp("error")("code").asNum, Some(-32601.0))
  }

  test("McpServer.handleMessage ignores unknown notifications") {
    val msg = McpJson.parse("""{"jsonrpc":"2.0","method":"unknown/notification"}""")
    val response = McpServer.testHandleMessage(msg)
    assert(response.isEmpty, "unknown notification should be ignored")
  }

  test("McpServer.handleMessage responds to ping") {
    val msg = McpJson.parse("""{"jsonrpc":"2.0","id":5,"method":"ping"}""")
    val response = McpServer.testHandleMessage(msg)
    assert(response.isDefined, "ping should return a response")
    val resp = McpJson.parse(response.get)
    assertEquals(resp("id").asNum, Some(5.0))
  }

  test("McpServer.handleMessage returns error for unknown tool") {
    val msg = McpJson.parse("""{"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"scalex_nonexistent","arguments":{"workspace":"/tmp"}}}""")
    val response = McpServer.testHandleMessage(msg)
    val resp = McpJson.parse(response.get)
    val content = resp("result")("content").asArr.get.head
    assert(content("text").asStr.get.contains("Unknown tool"), "should report unknown tool")
    assertEquals(resp("result")("isError").asBool, Some(true))
  }

  test("McpServer.handleMessage handles string id") {
    val msg = McpJson.parse("""{"jsonrpc":"2.0","id":"abc-123","method":"ping"}""")
    val response = McpServer.testHandleMessage(msg)
    val resp = McpJson.parse(response.get)
    assertEquals(resp("id").asStr, Some("abc-123"))
  }

  test("McpServer tools/call with invalid workspace returns error") {
    val msg = McpJson.parse("""{"jsonrpc":"2.0","id":15,"method":"tools/call","params":{"name":"scalex_search","arguments":{"workspace":"/nonexistent/path/that/does/not/exist","query":"Foo"}}}""")
    val response = McpServer.testHandleMessage(msg)
    val resp = McpJson.parse(response.get)
    val content = resp("result")("content").asArr.get.head
    assert(content("text").asStr.get.startsWith("Error:"), s"should report error: ${content("text").asStr}")
    assertEquals(resp("result")("isError").asBool, Some(true))
  }

  test("McpServer tools/call with missing query for required-query tool returns result") {
    // Commands handle missing args gracefully (usage error or empty results)
    val ws = java.nio.file.Files.createTempDirectory("mcp-missing-query")
    val msg = McpJson.parse(s"""{"jsonrpc":"2.0","id":16,"method":"tools/call","params":{"name":"scalex_search","arguments":{"workspace":"${jsonEscape(ws.toString)}"}}}""")
    val response = McpServer.testHandleMessage(msg)
    assert(response.isDefined, "should return a response even without query")
    deleteRecursive(ws)
  }

  test("McpServer tool count matches expectation") {
    val msg = McpJson.parse("""{"jsonrpc":"2.0","id":17,"method":"tools/list","params":{}}""")
    val response = McpServer.testHandleMessage(msg)
    val tools = McpJson.parse(response.get)("result")("tools").asArr.get
    assertEquals(tools.size, 29, s"expected 29 tools, got ${tools.size}")
  }

  // ── Tool call integration tests ─────────────────────────────────────────

  test("McpServer tools/call scalex_search returns results") {
    val ws = java.nio.file.Files.createTempDirectory("mcp-test")
    val srcDir = ws.resolve("src")
    java.nio.file.Files.createDirectories(srcDir)
    java.nio.file.Files.writeString(srcDir.resolve("Example.scala"),
      """package test
        |class Example {
        |  def hello: String = "world"
        |}
        |""".stripMargin)
    // Initialize git repo
    val pb1 = ProcessBuilder("git", "init").directory(ws.toFile)
    pb1.start().waitFor()
    val pb2 = ProcessBuilder("git", "add", ".").directory(ws.toFile)
    pb2.start().waitFor()
    val pb3 = ProcessBuilder("git", "commit", "-m", "init").directory(ws.toFile)
    pb3.environment().put("GIT_AUTHOR_NAME", "test")
    pb3.environment().put("GIT_AUTHOR_EMAIL", "test@test.com")
    pb3.environment().put("GIT_COMMITTER_NAME", "test")
    pb3.environment().put("GIT_COMMITTER_EMAIL", "test@test.com")
    pb3.start().waitFor()

    val msg = McpJson.parse(s"""{"jsonrpc":"2.0","id":20,"method":"tools/call","params":{"name":"scalex_search","arguments":{"workspace":"${jsonEscape(ws.toString)}","query":"Example"}}}""")
    val response = McpServer.testHandleMessage(msg)
    val resp = McpJson.parse(response.get)
    val text = resp("result")("content").asArr.get.head("text").asStr.get
    assert(text.contains("Example"), s"search result should contain 'Example': $text")

    // Cleanup
    deleteRecursive(ws)
    McpServer.testClearCache()
  }

  test("McpServer tools/call scalex_index rebuilds index") {
    val ws = java.nio.file.Files.createTempDirectory("mcp-index-test")
    val srcDir = ws.resolve("src")
    java.nio.file.Files.createDirectories(srcDir)
    java.nio.file.Files.writeString(srcDir.resolve("Indexed.scala"),
      """package indexed
        |object Indexed
        |""".stripMargin)
    val pb1 = ProcessBuilder("git", "init").directory(ws.toFile)
    pb1.start().waitFor()
    val pb2 = ProcessBuilder("git", "add", ".").directory(ws.toFile)
    pb2.start().waitFor()
    val pb3 = ProcessBuilder("git", "commit", "-m", "init").directory(ws.toFile)
    pb3.environment().put("GIT_AUTHOR_NAME", "test")
    pb3.environment().put("GIT_AUTHOR_EMAIL", "test@test.com")
    pb3.environment().put("GIT_COMMITTER_NAME", "test")
    pb3.environment().put("GIT_COMMITTER_EMAIL", "test@test.com")
    pb3.start().waitFor()

    val msg = McpJson.parse(s"""{"jsonrpc":"2.0","id":25,"method":"tools/call","params":{"name":"scalex_index","arguments":{"workspace":"${jsonEscape(ws.toString)}"}}}""")
    val response = McpServer.testHandleMessage(msg)
    val resp = McpJson.parse(response.get)
    val text = resp("result")("content").asArr.get.head("text").asStr.get
    // Index stats should mention files and symbols
    assert(text.contains("file") || text.contains("symbol") || text.contains("Indexed"), s"index result should show stats: $text")

    deleteRecursive(ws)
    McpServer.testClearCache()
  }

  test("McpServer tools/call scalex_graph renders graph") {
    val ws = java.nio.file.Files.createTempDirectory("mcp-graph-test")
    val msg = McpJson.parse(s"""{"jsonrpc":"2.0","id":30,"method":"tools/call","params":{"name":"scalex_graph","arguments":{"workspace":"${jsonEscape(ws.toString)}","query":"A->B, B->C"}}}""")
    val response = McpServer.testHandleMessage(msg)
    val resp = McpJson.parse(response.get)
    val text = resp("result")("content").asArr.get.head("text").asStr.get
    assert(text.contains("A"), s"graph should contain node A: $text")
    assert(text.contains("B"), s"graph should contain node B: $text")
    assert(text.contains("C"), s"graph should contain node C: $text")

    deleteRecursive(ws)
  }

  private def deleteRecursive(path: java.nio.file.Path): Unit =
    if java.nio.file.Files.isDirectory(path) then
      val stream = java.nio.file.Files.list(path)
      try stream.forEach(deleteRecursive)
      finally stream.close()
    java.nio.file.Files.deleteIfExists(path)
