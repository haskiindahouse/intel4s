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
    "enableDefaultTyping", "password", "secret", "token", "apiKey", "fromFile")

  val bloomCandidates = candidates.filter { f =>
    f.identifierBloom match
      case Some(bloom) => bloomKeywords.exists(bloom.mightContain)
      case None => true // no bloom → must scan
  }

  // Phase 2: AST scan — parallel with timeout
  val findings = java.util.concurrent.ConcurrentLinkedQueue[BugFinding]()
  val deadline = System.nanoTime() + 20_000_000_000L // 20 second timeout
  @volatile var timedOut = false

  bloomCandidates.asJava.parallelStream().forEach { idxFile =>
    if System.nanoTime() < deadline then
      val filePath = workspace.resolve(idxFile.relativePath)
      try scanFile(filePath, idxFile.relativePath, findings)
      catch case _: Exception => () // skip unparseable files
    else timedOut = true
  }

  // Collect and filter results
  var results = findings.asScala.toList

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

  // Phase 3: Hotspot ranking (optional)
  val hotspots = if ctx.hotspots then computeHotspots(results, workspace) else Nil

  CmdResult.BugHuntReport(results, hotspots, candidates.size, timedOut)

// ── AST scanner ────────────────────────────────────────────────────────────

private def scanFile(filePath: Path, relPath: String, findings: java.util.concurrent.ConcurrentLinkedQueue[BugFinding]): Unit =
  val source = try Files.readString(filePath) catch
    case _: java.io.IOException => return
  val lines = source.split('\n')
  val input = Input.VirtualFile(filePath.toString, source)
  val tree = try
    given scala.meta.Dialect = scala.meta.dialects.Scala3
    input.parse[Source].get
  catch
    case _: Exception =>
      try
        given scala.meta.Dialect = scala.meta.dialects.Scala213
        input.parse[Source].get
      catch
        case _: Exception => return

  // Track context stack for context-dependent patterns
  def scan(t: Tree, context: List[String]): Unit =
    t match
      // ── .get on Option/Try ──
      case sel @ Term.Select(qual, Term.Name("get")) if !isSafeGet(sel, qual, context) =>
        report(findings, filePath, relPath, sel.pos, lines, BugPattern.OptionGet, context)

      // ── .head / .last on collection ──
      case sel @ Term.Select(_, Term.Name("head")) =>
        report(findings, filePath, relPath, sel.pos, lines, BugPattern.CollectionHead, context)
      case sel @ Term.Select(_, Term.Name("last")) =>
        report(findings, filePath, relPath, sel.pos, lines, BugPattern.CollectionHead, context)

      // ── asInstanceOf ──
      case sel @ Term.ApplyType.After_4_6_0(Term.Select(_, Term.Name("asInstanceOf")), _) =>
        report(findings, filePath, relPath, sel.pos, lines, BugPattern.AsInstanceOf, context)

      // ── null literal ──
      case n @ Lit.Null() =>
        report(findings, filePath, relPath, n.pos, lines, BugPattern.NullLiteral, context)

      // ── return keyword ──
      case r @ Term.Return(_) =>
        // Only flag if inside a lambda/function body (not top-level def)
        if context.exists(c => c == "lambda" || c == "foreach" || c == "map" || c == "flatMap") then
          report(findings, filePath, relPath, r.pos, lines, BugPattern.ReturnInLambda, context)

      // ── throw inside ZIO.succeed / UIO ──
      case th @ Term.Throw(_) if context.exists(c => c == "ZIO.succeed" || c == "UIO" || c == "URIO") =>
        report(findings, filePath, relPath, th.pos, lines, BugPattern.ThrowInZioSucceed, context)

      // ── Await.result with Duration.Inf ──
      case app @ Term.Apply.After_4_6_0(Term.Select(Term.Name("Await"), Term.Name("result")), argClause)
        if argClause.values.exists(_.toString.contains("Inf")) =>
        report(findings, filePath, relPath, app.pos, lines, BugPattern.AwaitInfinite, context)

      // ── Thread.sleep ──
      case app @ Term.Apply.After_4_6_0(Term.Select(Term.Name("Thread"), Term.Name("sleep")), _) =>
        report(findings, filePath, relPath, app.pos, lines, BugPattern.ThreadSleepAsync, context)

      // ── sender() inside Future ──
      case sel @ Term.Apply.After_4_6_0(Term.Name("sender"), _) if context.contains("Future") =>
        report(findings, filePath, relPath, sel.pos, lines, BugPattern.SenderInFuture, context)

      // ── Fragment.const with interpolation ──
      case app @ Term.Apply.After_4_6_0(Term.Select(Term.Name("Fragment"), Term.Name("const")), argClause)
        if argClause.values.exists(isStringInterpolation) =>
        report(findings, filePath, relPath, app.pos, lines, BugPattern.FragmentConst, context)

      // ── sql"...#$..." splice interpolation ──
      case interp @ Term.Interpolate(Term.Name("sql"), _, _) if interp.toString.contains("#$") =>
        report(findings, filePath, relPath, interp.pos, lines, BugPattern.SpliceInterpolation, context)

      // ── ObjectInputStream ──
      case app @ Term.Apply.After_4_6_0(Term.Select(_, Term.Name("readObject")), _)
        if app.toString.contains("ObjectInputStream") =>
        report(findings, filePath, relPath, app.pos, lines, BugPattern.ObjectInputStream, context)
      case n @ Init.After_4_6_0(Type.Name("ObjectInputStream"), _, _) =>
        report(findings, filePath, relPath, n.pos, lines, BugPattern.ObjectInputStream, context)

      // ── enableDefaultTyping ──
      case app @ Term.Apply.After_4_6_0(Term.Select(_, Term.Name("enableDefaultTyping")), _) =>
        report(findings, filePath, relPath, app.pos, lines, BugPattern.JacksonDefaultTyping, context)

      // ── Hardcoded secrets ──
      case Term.Assign(Term.Name(name), Lit.String(value))
        if isSecretName(name) && value.nonEmpty && !isPlaceholder(value) =>
        report(findings, filePath, relPath, t.pos, lines, BugPattern.HardcodedSecret, context)
      case Defn.Val(_, List(Pat.Var(Term.Name(name))), _, Lit.String(value))
        if isSecretName(name) && value.nonEmpty && !isPlaceholder(value) =>
        report(findings, filePath, relPath, t.pos, lines, BugPattern.HardcodedSecret, context)

      // ── Source.fromFile without Using ──
      case app @ Term.Apply.After_4_6_0(Term.Select(Term.Name("Source"), Term.Name("fromFile")), _)
        if !context.contains("Using") && !context.contains("try") =>
        report(findings, filePath, relPath, app.pos, lines, BugPattern.ResourceLeak, context)

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
        case _ => context
      scan(child, newCtx)
    }

  scan(tree, Nil)

// ── Helpers ────────────────────────────────────────────────────────────────

private def report(
  findings: java.util.concurrent.ConcurrentLinkedQueue[BugFinding],
  filePath: Path, relPath: String, pos: Position, lines: Array[String],
  pattern: BugPattern, context: List[String]
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
    message = pattern.description
  ))

private def isSafeGet(sel: Term.Select, qual: Term, context: List[String]): Boolean =
  // 1. .get(key) with argument — Map.get, safe
  val hasArg = sel.parent match
    case Some(Term.Apply.After_4_6_0(_, argClause)) => argClause.values.nonEmpty
    case _ => false
  if hasArg then return true

  // 2. Inside for-comprehension with <- (Enumerator.Generator) — likely ZIO Ref.get / IO
  val inForGenerator = sel.parent match
    case Some(_: Enumerator.Generator) => true
    case _ => false
  if inForGenerator then return true

  // 3. Qualifier is a known safe name pattern (ref, state, counter, queue, hub, promise, semaphore)
  val qualName = qual match
    case Term.Name(n) => n.toLowerCase
    case Term.Select(_, Term.Name(n)) => n.toLowerCase
    case _ => ""
  val safeRefNames = Set("ref", "state", "counter", "store", "cache", "queue", "hub",
    "promise", "semaphore", "stateref", "mapref", "fiberref")
  if safeRefNames.exists(qualName.contains) then return true

  // 4. Qualifier is a known endpoint builder pattern (base, endpoint)
  val endpointNames = Set("base", "endpoint", "secureendpoint", "publicendpoint")
  if endpointNames.contains(qualName) || qualName.endsWith("base") || qualName.endsWith("endpoint") then return true

  // 5. In ZIO context (for-comprehension body) — .get is likely Ref.get
  if context.contains("for-body") then return true

  // 6. .get followed by .map/.flatMap/.foreach — likely Ref.get or IO, not Option.get
  val chainedWithEffect = sel.parent match
    case Some(Term.Select(_, Term.Name(n))) if Set("map", "flatMap", "foreach", "tap", "mapError").contains(n) => true
    case _ => false
  if chainedWithEffect then return true

  false

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
  lower == "test" || lower == "dummy" || lower.isEmpty

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
