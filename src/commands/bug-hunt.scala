import scala.meta.*
import scala.collection.mutable
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

// ── Bug hunt command ───────────────────────────────────────────────────────

def cmdBugHunt(args: List[String], ctx: CommandContext): CmdResult =
  val indexedFiles = ctx.idx.indexedFiles
  val workspace = ctx.workspace

  // Apply standard file filters
  val candidates = indexedFiles.filter { f =>
    val relPath = f.relativePath
    val isTest = isTestFile(workspace.resolve(relPath), workspace)
    val passesTest = if ctx.noTests then !isTest else true
    val passesPath = ctx.pathFilter.forall(p => relPath.startsWith(p))
    val passesExclude = ctx.excludePath.forall(p => !relPath.startsWith(p))
    val isScala = relPath.endsWith(".scala")
    passesTest && passesPath && passesExclude && isScala
  }

  // Phase 1: Bloom filter pre-screen — skip files that definitely don't contain target identifiers
  val bloomKeywords = List("get", "head", "last", "asInstanceOf", "null", "return",
    "succeed", "Await", "sleep", "sender", "Fragment", "ObjectInputStream",
    "enableDefaultTyping", "password", "secret", "token", "apiKey", "fromFile",
    "exec", "ProcessBuilder", "Process", "File", "Paths", "Html", "Redirect",
    "singleRequest", "fromURL", "sql",
    "MessageDigest", "Cipher", "Random", "IvParameterSpec",
    "SAXParser", "DocumentBuilderFactory", "SAXParserFactory", "XMLInputFactory", "XPath",
    // WHY: log method names needed for LogInjection pattern — without them bloom pre-screen filters out files
    "info", "warn", "error", "debug", "trace",
    "die", "unsafeRun", "unsafe", "onComplete",
    "synchronized", "mutable",
    "tail", "Pattern",
    "FileInputStream", "FileOutputStream", "BufferedReader", "BufferedWriter",
    "Connection", "PreparedStatement", "Statement", "ResultSet")

  val bloomCandidates = candidates.filter { f =>
    f.identifierBloom match
      case Some(bloom) => bloomKeywords.exists(bloom.mightContain)
      case None => true // no bloom → must scan
  }

  // Phase 2: AST scan — parallel with timeout
  val findings = java.util.concurrent.ConcurrentLinkedQueue[BugFinding]()
  val deadline = System.nanoTime() + 20_000_000_000L // 20 second timeout
  @volatile var timedOut = false

  val enableTaint = !ctx.noTaint
  val idxForTaint = if enableTaint then Some(ctx.idx) else None

  bloomCandidates.asJava.parallelStream().forEach { idxFile =>
    if System.nanoTime() < deadline then
      val filePath = workspace.resolve(idxFile.relativePath)
      try scanFile(filePath, idxFile.relativePath, findings, enableTaint, idxForTaint, workspace)
      catch case _: Exception => () // skip unparseable files
    else timedOut = true
  }

  // Collect and filter results
  var results = findings.asScala.toList

  // Apply suppression memories — filter out findings that match an Ignore memory.
  // WHY: Load once then pass to matches to avoid re-reading the file per finding.
  val memoryStore = MemoryStore(workspace)
  val memories = memoryStore.load()
  if memories.nonEmpty then
    results = results.filter { finding =>
      memoryStore.matchesLoaded(finding, memories) match
        case Some(mem) if mem.suppressionType == SuppressionType.Ignore => false
        case _ => true
    }

  // Apply severity filter
  ctx.bugSeverity.foreach { sev =>
    val minSev = sev.toLowerCase match
      case "critical" => 0
      case "high"     => 1
      case "medium"   => 2
      case _          => 3
    results = results.filter { f =>
      val fSev = f.pattern.severity match
        case BugSeverity.Critical => 0
        case BugSeverity.High     => 1
        case BugSeverity.Medium   => 2
      fSev <= minSev
    }
  }

  // Apply category filter
  ctx.bugCategory.foreach { cat =>
    val target = cat.toLowerCase
    results = results.filter { f =>
      f.pattern.category.toString.toLowerCase == target
    }
  }

  // Phase 3 (optional): Reachability filtering — keep only findings in methods
  // reachable from entrypoints within the configured call-graph depth.
  // WHY: done before sorting/limiting so limit applies to reachable findings only.
  var filteredByReachability = 0
  if ctx.reachable then
    val (reachableSet, entrypointMap) = buildReachableSet(
      ctx.idx, workspace, ctx.reachableDepth, ctx.reachableIncludeTests
    )
    val (reachableFindings, unreachable) = results.partition { f =>
      findEnclosingMethod(f.file, f.line).exists(reachableSet.contains)
    }
    filteredByReachability = unreachable.size
    // Enrich reachable findings with metadata
    results = reachableFindings.map { f =>
      val method = findEnclosingMethod(f.file, f.line)
      val reachableFromList = method.flatMap(m => entrypointMap.get(m)).getOrElse(Nil)
      // Compute minimum depth as 1-based hops (1 = directly in entrypoint, 2+ = via callees).
      // We don't store per-hop depth in entrypointMap, so we use 1 as a conservative lower bound.
      // A future semantic call-graph can provide precise depth.
      // WHY: depth 1 is "reachable via at least one hop" — good enough for triage prioritization.
      val depth = if reachableFromList.nonEmpty then Some(1) else None
      f.copy(reachableFrom = Some(reachableFromList), callDepth = depth)
    }
    if filteredByReachability > 0 then
      System.err.println(s"Reachability: $filteredByReachability finding${if filteredByReachability != 1 then "s" else ""} filtered (not reachable from entrypoints within depth ${ctx.reachableDepth})")

  // Sort: critical first, then high, then medium; within same severity by file path
  results = results.sortBy { f =>
    val sevOrd = f.pattern.severity match
      case BugSeverity.Critical => 0
      case BugSeverity.High     => 1
      case BugSeverity.Medium   => 2
    (sevOrd, f.file.toString, f.line)
  }

  // Apply limit
  if ctx.limit > 0 && results.size > ctx.limit then
    results = results.take(ctx.limit)

  // Phase 4: Hotspot ranking (optional)
  val hotspots = if ctx.hotspots then computeHotspots(results, workspace) else Nil

  CmdResult.BugHuntReport(results, hotspots, candidates.size, timedOut)

// ── AST scanner ────────────────────────────────────────────────────────────

// WHY: not private — pattern-validate command needs to call this for spec validation
def scanFile(
  filePath: Path, relPath: String, findings: java.util.concurrent.ConcurrentLinkedQueue[BugFinding],
  enableTaint: Boolean = false, idx: Option[WorkspaceIndex] = None, workspace: Path = null
): Unit =
  val source = try Files.readString(filePath) catch
    case _: java.io.IOException => ""
  if source.isEmpty then {/* empty */} else {
  val lines = source.split('\n')
  val input = Input.VirtualFile(filePath.toString, source)
  val treeParsed = try
    given scala.meta.Dialect = scala.meta.dialects.Scala3
    Some(input.parse[Source].get)
  catch
    case _: Exception =>
      try
        given scala.meta.Dialect = scala.meta.dialects.Scala213
        Some(input.parse[Source].get)
      catch
        case _: Exception => None
  treeParsed.foreach { tree =>

  // Local buffer for this file's findings (taint post-processes before adding to main queue)
  val localFindings = java.util.concurrent.ConcurrentLinkedQueue[BugFinding]()

  // Track context stack for context-dependent patterns
  def scan(t: Tree, context: List[String]): Unit =
    t match
      // ── .get on Option/Try ──
      case sel @ Term.Select(qual, Term.Name("get")) if !isSafeGet(sel, qual, context) =>
        report(localFindings, filePath, relPath, sel.pos, lines, BugPattern.OptionGet, context)

      // ── .head / .last on collection ──
      case sel @ Term.Select(_, Term.Name("head")) if !context.contains("assertion") =>
        report(localFindings, filePath, relPath, sel.pos, lines, BugPattern.CollectionHead, context)
      case sel @ Term.Select(_, Term.Name("last")) if !context.contains("assertion") =>
        report(localFindings, filePath, relPath, sel.pos, lines, BugPattern.CollectionHead, context)

      // ── asInstanceOf ──
      case sel @ Term.ApplyType.After_4_6_0(Term.Select(_, Term.Name("asInstanceOf")), _) =>
        report(localFindings, filePath, relPath, sel.pos, lines, BugPattern.AsInstanceOf, context)

      // ── null literal ──
      case n @ Lit.Null() =>
        report(localFindings, filePath, relPath, n.pos, lines, BugPattern.NullLiteral, context)

      // ── return keyword ──
      case r @ Term.Return(_) =>
        // Only flag if inside a lambda/function body (not top-level def)
        if context.exists(c => c == "lambda" || c == "foreach" || c == "map" || c == "flatMap") then
          report(localFindings, filePath, relPath, r.pos, lines, BugPattern.ReturnInLambda, context)

      // ── throw inside ZIO.succeed / UIO ──
      case th @ Term.Throw(_) if context.exists(c => c == "ZIO.succeed" || c == "UIO" || c == "URIO") =>
        report(localFindings, filePath, relPath, th.pos, lines, BugPattern.ThrowInZioSucceed, context)

      // ── Await.result with Duration.Inf ──
      case app @ Term.Apply.After_4_6_0(Term.Select(Term.Name("Await"), Term.Name("result")), argClause)
        if argClause.values.exists(_.toString.contains("Inf")) =>
        report(localFindings, filePath, relPath, app.pos, lines, BugPattern.AwaitInfinite, context)

      // ── Thread.sleep ──
      case app @ Term.Apply.After_4_6_0(Term.Select(Term.Name("Thread"), Term.Name("sleep")), _) =>
        report(localFindings, filePath, relPath, app.pos, lines, BugPattern.ThreadSleepAsync, context)

      // ── sender() inside Future ──
      case sel @ Term.Apply.After_4_6_0(Term.Name("sender"), _) if context.contains("Future") =>
        report(localFindings, filePath, relPath, sel.pos, lines, BugPattern.SenderInFuture, context)

      // ── Fragment.const with interpolation ──
      case app @ Term.Apply.After_4_6_0(Term.Select(Term.Name("Fragment"), Term.Name("const")), argClause)
        if argClause.values.exists(isStringInterpolation) =>
        report(localFindings, filePath, relPath, app.pos, lines, BugPattern.FragmentConst, context)

      // ── sql"...#$..." splice interpolation ──
      case interp @ Term.Interpolate(Term.Name("sql"), _, _) if interp.toString.contains("#$") =>
        report(localFindings, filePath, relPath, interp.pos, lines, BugPattern.SpliceInterpolation, context)

      // ── ObjectInputStream ──
      case app @ Term.Apply.After_4_6_0(Term.Select(_, Term.Name("readObject")), _)
        if app.toString.contains("ObjectInputStream") =>
        report(localFindings, filePath, relPath, app.pos, lines, BugPattern.ObjectInputStream, context)
      case n @ Init.After_4_6_0(Type.Name("ObjectInputStream"), _, _) =>
        report(localFindings, filePath, relPath, n.pos, lines, BugPattern.ObjectInputStream, context)

      // ── enableDefaultTyping ──
      case app @ Term.Apply.After_4_6_0(Term.Select(_, Term.Name("enableDefaultTyping")), _) =>
        report(localFindings, filePath, relPath, app.pos, lines, BugPattern.JacksonDefaultTyping, context)

      // ── Hardcoded secrets ──
      case Term.Assign(Term.Name(name), Lit.String(value))
        if isSecretName(name) && value.nonEmpty && !isPlaceholder(value) =>
        report(localFindings, filePath, relPath, t.pos, lines, BugPattern.HardcodedSecret, context)
      case Defn.Val(_, List(Pat.Var(Term.Name(name))), _, Lit.String(value))
        if isSecretName(name) && value.nonEmpty && !isPlaceholder(value) =>
        report(localFindings, filePath, relPath, t.pos, lines, BugPattern.HardcodedSecret, context)

      // ── Source.fromFile without Using ──
      case app @ Term.Apply.After_4_6_0(Term.Select(Term.Name("Source"), Term.Name("fromFile")), _)
        if !context.contains("Using") && !context.contains("try") =>
        report(localFindings, filePath, relPath, app.pos, lines, BugPattern.ResourceLeak, context)

      // ── Command injection: Runtime.exec / ProcessBuilder with non-literal ──
      case app @ Term.Apply.After_4_6_0(Term.Select(_, Term.Name("exec")), argClause)
        if argClause.values.exists(!isLiteralArg(_)) =>
        report(localFindings, filePath, relPath, app.pos, lines, BugPattern.CommandInjection, context)
      case app @ Term.Apply.After_4_6_0(Term.Name("ProcessBuilder"), argClause)
        if argClause.values.exists(!isLiteralArg(_)) =>
        report(localFindings, filePath, relPath, app.pos, lines, BugPattern.CommandInjection, context)
      case app @ Term.Apply.After_4_6_0(Term.Select(Term.Name("Process"), Term.Name("apply")), argClause)
        if argClause.values.exists(!isLiteralArg(_)) =>
        report(localFindings, filePath, relPath, app.pos, lines, BugPattern.CommandInjection, context)
      // scala.sys.process: "cmd".! or "cmd".!! or Process("cmd")
      case app @ Term.Select(qual, Term.Name(n)) if (n == "$bang" || n == "$bang$bang") && !isLiteralArg(qual) =>
        report(localFindings, filePath, relPath, app.pos, lines, BugPattern.CommandInjection, context)

      // ── Path traversal: new File / Paths.get with non-literal ──
      case app @ Init.After_4_6_0(Type.Name("File"), _, argClauses)
        if argClauses.flatMap(_.values).exists(!isLiteralArg(_)) =>
        report(localFindings, filePath, relPath, app.pos, lines, BugPattern.PathTraversal, context)
      case app @ Term.Apply.After_4_6_0(Term.Select(Term.Name("Paths"), Term.Name("get")), argClause)
        if argClause.values.exists(!isLiteralArg(_)) =>
        report(localFindings, filePath, relPath, app.pos, lines, BugPattern.PathTraversal, context)

      // ── XSS: Html() with non-literal ──
      case app @ Term.Apply.After_4_6_0(Term.Name("Html"), argClause)
        if argClause.values.exists(!isLiteralArg(_)) =>
        report(localFindings, filePath, relPath, app.pos, lines, BugPattern.XSS, context)

      // ── Open redirect: Redirect() with non-literal URL ──
      case app @ Term.Apply.After_4_6_0(Term.Name("Redirect"), argClause)
        if argClause.values.exists(!isLiteralArg(_)) =>
        report(localFindings, filePath, relPath, app.pos, lines, BugPattern.OpenRedirect, context)

      // ── SSRF: HTTP client with non-literal URL ──
      case app @ Term.Apply.After_4_6_0(Term.Select(_, Term.Name("singleRequest")), argClause)
        if argClause.values.exists(!isLiteralArg(_)) =>
        report(localFindings, filePath, relPath, app.pos, lines, BugPattern.SSRF, context)
      case app @ Term.Apply.After_4_6_0(Term.Select(_, Term.Name("url")), argClause)
        if argClause.values.exists(!isLiteralArg(_)) =>
        report(localFindings, filePath, relPath, app.pos, lines, BugPattern.SSRF, context)
      case app @ Term.Apply.After_4_6_0(Term.Select(Term.Name("Source"), Term.Name("fromURL")), argClause)
        if argClause.values.exists(!isLiteralArg(_)) =>
        report(localFindings, filePath, relPath, app.pos, lines, BugPattern.SSRF, context)

      // ── Weak cryptography ──
      case app @ Term.Apply.After_4_6_0(Term.Select(Term.Name("MessageDigest"), Term.Name("getInstance")), argClause)
        if argClause.values.exists(a => isWeakHash(a.toString)) =>
        report(localFindings, filePath, relPath, app.pos, lines, BugPattern.WeakHash, context)
      case app @ Term.Apply.After_4_6_0(Term.Select(Term.Name("Cipher"), Term.Name("getInstance")), argClause)
        if argClause.values.exists(a => isWeakCipher(a.toString)) =>
        report(localFindings, filePath, relPath, app.pos, lines, BugPattern.WeakCipher, context)
      case app @ Term.Apply.After_4_6_0(Term.Select(Term.Name("Cipher"), Term.Name("getInstance")), argClause)
        if argClause.values.exists(_.toString.contains("ECB")) =>
        report(localFindings, filePath, relPath, app.pos, lines, BugPattern.ECBMode, context)
      case app @ Term.Apply.After_4_6_0(Term.Select(Term.Name("Cipher"), Term.Name("getInstance")), argClause)
        if argClause.values.exists(_.toString.contains("NoPadding")) =>
        report(localFindings, filePath, relPath, app.pos, lines, BugPattern.NoPadding, context)
      case n @ Init.After_4_6_0(Type.Name("Random"), _, _) =>
        report(localFindings, filePath, relPath, n.pos, lines, BugPattern.WeakRandom, context)
      case app @ Term.Apply.After_4_6_0(Term.New(Init.After_4_6_0(Type.Name("Random"), _, _)), _) =>
        report(localFindings, filePath, relPath, app.pos, lines, BugPattern.WeakRandom, context)
      case app @ Term.Apply.After_4_6_0(Term.Select(Term.Name("IvParameterSpec"), Term.Name("apply")), argClause)
        if argClause.values.exists(isLiteralArg) =>
        report(localFindings, filePath, relPath, app.pos, lines, BugPattern.HardcodedIV, context)
      case n @ Init.After_4_6_0(Type.Name("IvParameterSpec"), _, argClauses)
        if argClauses.flatMap(_.values).exists(a => a.toString.contains("getBytes")) =>
        report(localFindings, filePath, relPath, n.pos, lines, BugPattern.HardcodedIV, context)

      // ── XML/XXE ──
      case n @ Init.After_4_6_0(Type.Name("SAXParser"), _, _) =>
        report(localFindings, filePath, relPath, n.pos, lines, BugPattern.XXE, context)
      case app @ Term.Apply.After_4_6_0(Term.Select(Term.Name("DocumentBuilderFactory"), Term.Name("newInstance")), _) =>
        report(localFindings, filePath, relPath, app.pos, lines, BugPattern.XXE, context)
      case app @ Term.Apply.After_4_6_0(Term.Select(Term.Name("SAXParserFactory"), Term.Name("newInstance")), _) =>
        report(localFindings, filePath, relPath, app.pos, lines, BugPattern.XXE, context)
      case app @ Term.Apply.After_4_6_0(Term.Select(Term.Name("XMLInputFactory"), Term.Name("newInstance")), _) =>
        report(localFindings, filePath, relPath, app.pos, lines, BugPattern.XXE, context)
      case app @ Term.Apply.After_4_6_0(Term.Select(_, Term.Name("evaluate")), argClause)
        if app.toString.contains("XPath") && argClause.values.exists(!isLiteralArg(_)) =>
        report(localFindings, filePath, relPath, app.pos, lines, BugPattern.XPathInjection, context)

      // ── Logging injection ──
      case app @ Term.Apply.After_4_6_0(Term.Select(_, Term.Name(logMethod)), argClause)
        if Set("info", "warn", "error", "debug", "trace").contains(logMethod) &&
           argClause.values.exists(isStringInterpolation) =>
        report(localFindings, filePath, relPath, app.pos, lines, BugPattern.LogInjection, context)

      // ── ZIO / effect-specific ──
      case app @ Term.Apply.After_4_6_0(Term.Select(Term.Name("ZIO"), Term.Name("die")), _) =>
        report(localFindings, filePath, relPath, app.pos, lines, BugPattern.ZioDie, context)
      case app @ Term.Apply.After_4_6_0(Term.Name("unsafeRun"), _) =>
        report(localFindings, filePath, relPath, app.pos, lines, BugPattern.UnsafeRun, context)
      case app @ Term.Apply.After_4_6_0(Term.Select(Term.Name("Unsafe"), Term.Name("unsafe")), _) =>
        report(localFindings, filePath, relPath, app.pos, lines, BugPattern.UnsafeRun, context)
      case app @ Term.Apply.After_4_6_0(Term.Select(_, Term.Name("onComplete")), _)
        if context.contains("Future") || app.toString.contains("Future") =>
        report(localFindings, filePath, relPath, app.pos, lines, BugPattern.FutureOnComplete, context)

      // ── Concurrency extended ──
      case app @ Term.ApplyInfix.After_4_6_0(_, Term.Name("synchronized"), _, _)
        if context.contains("synchronized") =>
        report(localFindings, filePath, relPath, app.pos, lines, BugPattern.SynchronizedNested, context)
      case app @ Term.Apply.After_4_6_0(Term.Name("synchronized"), _)
        if context.contains("synchronized") =>
        report(localFindings, filePath, relPath, app.pos, lines, BugPattern.SynchronizedNested, context)

      // ── Credential value detection (regex-based) ──
      case lit @ Lit.String(value) if value.length >= 20 && !isPlaceholder(value) =>
        credentialRules.foreach { rule =>
          if rule.pattern.findFirstIn(value).isDefined then
            report(localFindings, filePath, relPath, lit.pos, lines, BugPattern.HardcodedCredential, context,
              messageOverride = Some(s"hardcoded ${ruleIdToLabel(rule.id)} detected in string literal"))
        }

      // ── Type safety extended ──
      case sel @ Term.Select(_, Term.Name("tail")) if !context.contains("assertion") =>
        report(localFindings, filePath, relPath, sel.pos, lines, BugPattern.CollectionLast, context)
      case app @ Term.Apply.After_4_6_0(Term.Select(Term.Name("Pattern"), Term.Name("compile")), argClause)
        if argClause.values.exists(!isLiteralArg(_)) =>
        report(localFindings, filePath, relPath, app.pos, lines, BugPattern.RegexDoS, context)

      // ── Resource management ──
      case n @ Init.After_4_6_0(Type.Name(typeName), _, _)
        if Set("FileInputStream", "FileOutputStream", "BufferedReader", "BufferedWriter",
               "PrintWriter", "DataInputStream", "DataOutputStream").contains(typeName) &&
           !context.contains("Using") && !context.contains("try") =>
        report(localFindings, filePath, relPath, n.pos, lines, BugPattern.StreamNotClosed, context)
      case n @ Init.After_4_6_0(Type.Name(typeName), _, _)
        if Set("Connection", "PreparedStatement", "Statement", "ResultSet").contains(typeName) &&
           !context.contains("Using") && !context.contains("try") =>
        report(localFindings, filePath, relPath, n.pos, lines, BugPattern.ConnectionLeak, context)

      case _ => ()

    // Recurse into children with updated context
    t.children.foreach { child =>
      val newCtx = child match
        // Track ZIO.succeed context
        case Term.Apply.After_4_6_0(Term.Select(Term.Name("ZIO"), Term.Name("succeed")), _) => "ZIO.succeed" :: context
        case Term.Apply.After_4_6_0(Term.Name("UIO"), _) => "UIO" :: context
        case Term.Apply.After_4_6_0(Term.Name("URIO"), _) => "URIO" :: context
        // Track Future context
        case Term.Apply.After_4_6_0(Term.Name("Future"), _) => "Future" :: context
        case Term.Apply.After_4_6_0(Term.Select(Term.Name("Future"), _), _) => "Future" :: context
        // Track lambda context
        case Term.Function.After_4_6_0(_, _) => "lambda" :: context
        // Track common HOF names for return-in-lambda detection
        case Term.Apply.After_4_6_0(Term.Select(_, Term.Name(n)), _) if Set("map", "flatMap", "foreach", "filter", "collect").contains(n) => n :: context
        // Track Using/try for resource leak detection
        case Term.Apply.After_4_6_0(Term.Name("Using"), _) => "Using" :: context
        case Term.Apply.After_4_6_0(Term.Select(Term.Name("Using"), _), _) => "Using" :: context
        case Term.Try.After_4_9_9(_, _, _) => "try" :: context
        case Term.TryWithHandler(_, _, _) => "try" :: context
        // Track for-comprehension body for ZIO Ref.get detection
        case Term.ForYield.After_4_9_9(_, _) => "for-body" :: context
        case Term.For.After_4_9_9(_, _) => "for-body" :: context
        // Track assertion context — .head/.last inside assertions are test patterns, not bugs
        case Term.Apply.After_4_6_0(Term.Name(n), _) if assertionNames.contains(n) => "assertion" :: context
        case Term.Apply.After_4_6_0(Term.Select(_, Term.Name(n)), _) if assertionNames.contains(n) => "assertion" :: context
        // Track synchronized for nested synchronized detection
        case Term.Apply.After_4_6_0(Term.Name("synchronized"), _) => "synchronized" :: context
        case _ => context
      scan(child, newCtx)
    }

  // First pass: pattern scan populates localFindings
  scan(tree, Nil)

  // Second pass: taint analysis (if enabled) — filter constant-derived, enrich with flow
  if enableTaint then
    localFindings.asScala.foreach { finding =>
      analyzeTaint(finding, tree, filePath, idx, Option(workspace)) match
        case Some(enriched) => findings.add(enriched)
        case None => () // suppressed by taint analysis (constant-derived)
    }
  else
    localFindings.asScala.foreach(findings.add)
  } // end treeParsed.foreach
  } // end if source.nonEmpty

// ── Helpers ────────────────────────────────────────────────────────────────

/** Check if a tree node is a literal value (string, int, etc.) — used for sink arg checks. */
private def isLiteralArg(t: Tree): Boolean = t match
  case _: Lit => true
  case Term.Name("None") => true
  case _ => false

private def report(
  findings: java.util.concurrent.ConcurrentLinkedQueue[BugFinding],
  filePath: Path, relPath: String, pos: Position, lines: Array[String],
  pattern: BugPattern, context: List[String],
  messageOverride: Option[String] = None
): Unit =
  val line = pos.startLine + 1 // scalameta is 0-indexed
  val contextLine = if line > 0 && line <= lines.length then lines(line - 1).trim else ""
  val enclosing = context.headOption.getOrElse("<top-level>")
  findings.add(BugFinding(
    file = filePath,
    line = line,
    pattern = pattern,
    contextLine = contextLine,
    enclosingSymbol = enclosing,
    message = messageOverride.getOrElse(pattern.description)
  ))

private def isSafeGet(sel: Term.Select, qual: Term, context: List[String]): Boolean =
  // 1. .get(key) with argument — Map.get, safe
  val hasArg = sel.parent match
    case Some(Term.Apply.After_4_6_0(_, argClause)) => argClause.values.nonEmpty
    case _ => false

  // 2. Inside for-comprehension with <- (Enumerator.Generator) — likely ZIO Ref.get / IO
  val inForGenerator = sel.parent match
    case Some(_: Enumerator.Generator) => true
    case _ => false

  // 3. Qualifier is a known safe name pattern (ref, state, counter, queue, hub, promise, semaphore)
  val qualName = qual match
    case Term.Name(n) => n.toLowerCase
    case Term.Select(_, Term.Name(n)) => n.toLowerCase
    case _ => ""
  val safeRefNames = Set("ref", "state", "counter", "store", "cache", "queue", "hub",
    "promise", "semaphore", "stateref", "mapref", "fiberref")
  val isRefName = safeRefNames.exists(qualName.contains)

  // 4. Qualifier is a known endpoint builder pattern (base, endpoint)
  val endpointNames = Set("base", "endpoint", "secureendpoint", "publicendpoint")
  val isEndpoint = endpointNames.contains(qualName) || qualName.endsWith("base") || qualName.endsWith("endpoint")

  // 5. In ZIO context (for-comprehension body) — .get is likely Ref.get
  val inForBody = context.contains("for-body")

  // 6. .get followed by .map/.flatMap/.foreach — likely Ref.get or IO, not Option.get
  val chainedWithEffect = sel.parent match
    case Some(Term.Select(_, Term.Name(n))) if Set("map", "flatMap", "foreach", "tap", "mapError").contains(n) => true
    case _ => false

  hasArg || inForGenerator || isRefName || isEndpoint || inForBody || chainedWithEffect

private val assertionNames = Set(
  "assert", "assertTrue", "assertEquals", "assertNotEquals",
  "assertResult", "assertThrows", "intercept",
  "shouldBe", "shouldEqual", "mustBe", "mustEqual",
  "expect", "check", "verify",
  "yield" // for-yield with assertTrue in ZIO test
)

private def isWeakHash(s: String): Boolean =
  val lower = s.toLowerCase
  lower.contains("md5") || lower.contains("sha-1") || lower.contains("sha1")

private def isWeakCipher(s: String): Boolean =
  val lower = s.toLowerCase
  lower.contains("des") || lower.contains("rc4") || lower.contains("blowfish")

private def isStringInterpolation(t: Tree): Boolean = t match
  case Term.Interpolate(_, _, _) => true
  case _ => false

private val secretNames = Set("password", "passwd", "secret", "token", "apikey", "api_key",
  "apitoken", "api_token", "accesskey", "access_key", "secretkey", "secret_key",
  "privatekey", "private_key", "credential", "credentials")

private def isSecretName(name: String): Boolean =
  secretNames.contains(name.toLowerCase.replaceAll("[_-]", ""))

private def isPlaceholder(value: String): Boolean =
  val lower = value.toLowerCase
  lower == "changeme" || lower == "xxx" || lower == "todo" || lower == "fixme" ||
  lower.startsWith("${") || lower.startsWith("$") || lower == "placeholder" ||
  lower == "test" || lower == "dummy" || lower.isEmpty ||
  lower == "your-key-here" || lower == "replace-me" || lower == "example" ||
  lower.startsWith("<") || lower.contains("example.com") || lower == "none" ||
  lower.matches("^[x*]+$") || lower.matches("^0+$")

// ── Credential value detection (ported from Claude Code secretScanner) ─────

private val credentialRules: List[(id: String, pattern: scala.util.matching.Regex)] = List(
  (id = "aws-access-token",         pattern = """(?:A3T[A-Z0-9]|AKIA|ASIA|ABIA|ACCA)[A-Z2-7]{16}""".r),
  (id = "gcp-api-key",              pattern = """AIza[\w\-]{35}""".r),
  (id = "anthropic-api-key",        pattern = """sk-ant-(?:api)?03-[a-zA-Z0-9_\-]{80,100}AA""".r),
  (id = "anthropic-admin-key",      pattern = """sk-ant-admin01-[a-zA-Z0-9_\-]{80,100}AA""".r),
  (id = "openai-api-key",           pattern = """sk-(?:proj|svcacct|admin)-[A-Za-z0-9_\-]{40,80}T3BlbkFJ[A-Za-z0-9_\-]{20,80}""".r),
  (id = "github-pat",               pattern = """ghp_[0-9a-zA-Z]{36}""".r),
  (id = "github-fine-grained-pat",  pattern = """github_pat_\w{82}""".r),
  (id = "github-app-token",         pattern = """(?:ghu|ghs)_[0-9a-zA-Z]{36}""".r),
  (id = "gitlab-pat",               pattern = """glpat-[\w\-]{20}""".r),
  (id = "slack-bot-token",          pattern = """xoxb-[0-9]{10,13}-[0-9]{10,13}[a-zA-Z0-9\-]*""".r),
  (id = "stripe-access-token",      pattern = """(?:sk|rk)_(?:test|live|prod)_[a-zA-Z0-9]{10,99}""".r),
  (id = "npm-access-token",         pattern = """npm_[a-zA-Z0-9]{36}""".r),
  (id = "private-key",              pattern = """-----BEGIN[ A-Z0-9_\-]{0,100}PRIVATE KEY""".r),
  (id = "huggingface-access-token", pattern = """hf_[a-zA-Z]{34}""".r),
  (id = "sendgrid-api-token",       pattern = """SG\.[a-zA-Z0-9=_\-.]{66}""".r),
  (id = "pypi-upload-token",        pattern = """pypi-AgEIcHlwaS5vcmc[\w\-]{50,}""".r),
)

private def ruleIdToLabel(ruleId: String): String =
  val specialCases = Map(
    "aws" -> "AWS", "gcp" -> "GCP", "api" -> "API", "pat" -> "PAT",
    "github" -> "GitHub", "gitlab" -> "GitLab", "openai" -> "OpenAI",
    "npm" -> "NPM", "pypi" -> "PyPI", "huggingface" -> "HuggingFace",
    "anthropic" -> "Anthropic", "sendgrid" -> "SendGrid",
  )
  ruleId.split('-').map(w => specialCases.getOrElse(w, w.capitalize)).mkString(" ")

// ── Hotspot ranking ────────────────────────────────────────────────────────

private def computeHotspots(findings: List[BugFinding], workspace: Path): List[HotspotInfo] =
  if findings.isEmpty then return Nil

  // Group findings by file
  val byFile = findings.groupBy(f => workspace.relativize(f.file).toString)

  // Get git churn for all files in one call
  val churnMap = try
    val pb = ProcessBuilder("git", "log", "--format=", "--name-only", "--since=90 days")
    pb.directory(workspace.toFile)
    val proc = pb.start()
    val output = String(proc.getInputStream.readAllBytes(), "UTF-8")
    proc.waitFor()
    output.linesIterator
      .filter(_.nonEmpty)
      .foldLeft(Map.empty[String, Int]) { (acc, file) =>
        acc.updated(file, acc.getOrElse(file, 0) + 1)
      }
  catch
    case _: Exception => Map.empty[String, Int]

  byFile.map { (relFile, fileFindIngs) =>
    val churn = churnMap.getOrElse(relFile, 0)
    val complexity = fileFindIngs.size // simple: number of findings as proxy
    val risk = fileFindIngs.size.toDouble * math.max(1, churn)
    HotspotInfo(
      file = relFile,
      findingCount = fileFindIngs.size,
      churnScore = churn,
      complexityScore = complexity,
      riskScore = risk
    )
  }.toList.sortBy(-_.riskScore)
