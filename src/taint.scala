import scala.meta.*
import scala.collection.mutable
import java.nio.file.{Files, Path}

// ── Taint analysis engine ──────────────────────────────────────────────────
//
// Three phases:
//   Phase 1: Constant propagation — suppress findings where sink args are literal-derived
//   Phase 2: Intraprocedural backward slice — trace taint from sources to sinks within a method
//   Phase 3: Cross-file — follow taint through method calls via scalex index
//
// Taint is ON by default in bug-hunt. --no-taint disables it.

// ── Sources: where untrusted data enters ───────────────────────────────────

private val taintSourcePatterns: List[(methodPattern: String, description: String)] = List(
  "params"        -> "HTTP parameter",
  "param"         -> "HTTP parameter",
  "getParameter"  -> "Servlet parameter",
  "queryString"   -> "HTTP query string",
  "formField"     -> "HTTP form field",
  "header"        -> "HTTP header",
  "cookie"        -> "HTTP cookie",
  "getenv"        -> "Environment variable",
  "readLine"      -> "Standard input",
)

// Qualifier patterns — if the receiver matches, it's likely user input
private val taintQualifierPatterns = Set("request", "req", "params", "headers", "cookies", "env")

private def isTaintSource(t: Tree): Option[String] =
  t match
    // request.params("id"), req.param("x"), etc.
    case Term.Apply.After_4_6_0(Term.Select(qual, Term.Name(method)), _) =>
      val qualName = extractQualName(qual).toLowerCase
      taintSourcePatterns.find(p => method.toLowerCase.contains(p.methodPattern.toLowerCase)).map(_.description)
        .orElse(if taintQualifierPatterns.exists(qualName.contains) then Some("HTTP input") else None)
    // request.body, request.queryString (property access, no args)
    case Term.Select(qual, Term.Name(method)) =>
      val qualName = extractQualName(qual).toLowerCase
      if taintQualifierPatterns.exists(qualName.contains) &&
         taintSourcePatterns.exists(p => method.toLowerCase.contains(p.methodPattern.toLowerCase)) then
        Some("HTTP input")
      else None
    case _ => None

private def extractQualName(t: Tree): String = t match
  case Term.Name(n) => n
  case Term.Select(_, Term.Name(n)) => n
  case _ => ""

// ── Sanitizers: where taint is removed ─────────────────────────────────────

private val sanitizerNames = Set(
  "escape", "sanitize", "encode", "quote", "clean", "purify",
  "htmlencode", "urlencode", "sqlescape", "parameterize", "validate",
  "filter", "whitelist", "allowlist"
)

private def isSanitizer(t: Tree): Boolean = t match
  case Term.Apply.After_4_6_0(Term.Name(name), _) =>
    sanitizerNames.exists(name.toLowerCase.contains)
  case Term.Apply.After_4_6_0(Term.Select(_, Term.Name(name)), _) =>
    sanitizerNames.exists(name.toLowerCase.contains)
  case _ => false

// ── Phase 1: Constant propagation ──────────────────────────────────────────

/** Collect variable bindings (val/var assignments) in a tree. */
def collectBindings(body: Tree): Map[String, Tree] =
  val bindings = mutable.LinkedHashMap.empty[String, Tree]
  def walk(t: Tree): Unit =
    t match
      case Defn.Val(_, pats, _, rhs) =>
        pats.foreach {
          case Pat.Var(Term.Name(name)) => bindings(name) = rhs
          case _ => ()
        }
      case Defn.Var.After_4_7_2(_, pats, _, body) =>
        pats.foreach {
          case Pat.Var(Term.Name(name)) => bindings(name) = body
          case null => ()
        }
      case _ => ()
    t.children.foreach(walk)
  walk(body)
  bindings.toMap

/** Check if an expression is derived entirely from compile-time constants. */
def isLiteralDerived(expr: Tree, bindings: Map[String, Tree], depth: Int = 0): Boolean =
  if depth > 20 then false // prevent infinite recursion
  else expr match
    case _: Lit => true // any literal (String, Int, Boolean, Double, etc.)
    case Term.Name(name) =>
      bindings.get(name).exists(isLiteralDerived(_, bindings, depth + 1))
    case Term.Interpolate(_, _, args) =>
      args.forall(isLiteralDerived(_, bindings, depth + 1))
    case Term.ApplyInfix.After_4_6_0(lhs, Term.Name("+"), _, argClause) =>
      isLiteralDerived(lhs, bindings, depth + 1) &&
      argClause.values.forall(isLiteralDerived(_, bindings, depth + 1))
    case Term.Apply.After_4_6_0(Term.Name("s"), argClause) =>
      argClause.values.forall(isLiteralDerived(_, bindings, depth + 1))
    case _ => false

// ── Phase 2: Intraprocedural backward slice ────────────────────────────────

/** Trace an expression backward through bindings to find taint sources.
  * Returns (isTainted, flow steps, source if found). */
def backwardTrace(
  expr: Tree, bindings: Map[String, Tree], filePath: Path,
  steps: List[TaintStep] = Nil, depth: Int = 0
): Option[TaintFlow] =
  if depth > 15 then None // prevent deep recursion
  else expr match
    // Variable reference — look up binding and recurse
    case Term.Name(name) =>
      bindings.get(name) match
        case Some(rhs) =>
          val step = TaintStep(name, filePath, posLine(expr), "assignment")
          // Check if RHS is a sanitizer
          if isSanitizer(rhs) then None
          // Check if RHS is a taint source
          else isTaintSource(rhs) match
            case Some(desc) =>
              val source = TaintSource(rhs.toString.take(80), desc, filePath, posLine(rhs))
              Some(TaintFlow(source, (step :: steps).reverse, confidence = 0.0))
            case None =>
              // Recurse into RHS
              backwardTrace(rhs, bindings, filePath, step :: steps, depth + 1)
        case None =>
          // Parameter or unknown — check if name suggests user input
          if taintQualifierPatterns.exists(name.toLowerCase.contains) then
            val source = TaintSource(name, "method parameter (potentially untrusted)", filePath, posLine(expr))
            Some(TaintFlow(source, steps.reverse, confidence = 0.0))
          else None

    // String interpolation — check each argument
    case Term.Interpolate(_, _, args) =>
      val step = TaintStep("<interpolation>", filePath, posLine(expr), "string interpolation")
      args.iterator
        .map(arg => backwardTrace(arg, bindings, filePath, step :: steps, depth + 1))
        .collectFirst { case Some(flow) => flow }

    // Method call — check arguments and qualifier
    case Term.Apply.After_4_6_0(fun, argClause) =>
      // First check if this is a direct source
      isTaintSource(expr) match
        case Some(desc) =>
          val source = TaintSource(expr.toString.take(80), desc, filePath, posLine(expr))
          Some(TaintFlow(source, steps.reverse, confidence = 0.0))
        case None =>
          if isSanitizer(expr) then None
          else
            // Check arguments
            val step = TaintStep(fun.toString.take(40), filePath, posLine(expr), "method call")
            argClause.values.iterator
              .map(arg => backwardTrace(arg, bindings, filePath, step :: steps, depth + 1))
              .collectFirst { case Some(flow) => flow }
              .orElse {
                // Check qualifier
                fun match
                  case Term.Select(qual, _) =>
                    backwardTrace(qual, bindings, filePath, step :: steps, depth + 1)
                  case _ => None
              }

    // Select (a.b) — trace qualifier
    case Term.Select(qual, _) =>
      isTaintSource(expr) match
        case Some(desc) =>
          val source = TaintSource(expr.toString.take(80), desc, filePath, posLine(expr))
          Some(TaintFlow(source, steps.reverse, confidence = 0.0))
        case None =>
          backwardTrace(qual, bindings, filePath, steps, depth + 1)

    // String concatenation (a + b)
    case Term.ApplyInfix.After_4_6_0(lhs, Term.Name("+"), _, argClause) =>
      val step = TaintStep("<concat>", filePath, posLine(expr), "string concatenation")
      backwardTrace(lhs, bindings, filePath, step :: steps, depth + 1)
        .orElse(argClause.values.iterator
          .map(arg => backwardTrace(arg, bindings, filePath, step :: steps, depth + 1))
          .collectFirst { case Some(flow) => flow })

    case _ => None

// ── Phase 3: Cross-file taint ──────────────────────────────────────────────

/** Analyze whether a method returns tainted data by examining its body.
  * Uses the scalex index to resolve cross-file definitions. */
def analyzeCrossFile(
  methodName: String, idx: WorkspaceIndex, workspace: Path,
  visited: Set[String] = Set.empty, maxDepth: Int = 3
): Option[TaintSource] =
  if maxDepth <= 0 || visited.contains(methodName) then None
  else
    val defs = idx.findDefinition(methodName)
      .filter(s => s.kind == SymbolKind.Def || s.kind == SymbolKind.Val)
    defs.iterator.map { sym =>
      val bodies = extractBody(sym.file, sym.name, None)
      bodies.iterator.flatMap { body =>
        val source = body.sourceText
        val input = Input.VirtualFile(sym.file.toString, source)
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
        treeParsed.flatMap { tree =>
          val bindings = collectBindings(tree)
          var found: Option[TaintSource] = None
          def search(t: Tree): Unit =
            if found.isEmpty then
              isTaintSource(t) match
                case Some(desc) =>
                  found = Some(TaintSource(s"$methodName → ${t.toString.take(60)}", desc, sym.file, posLine(t)))
                case None =>
                  t.children.foreach(search)
          search(tree)
          found
        }
      }.nextOption()
    }.collectFirst { case Some(s) => s }

// ── Confidence scoring ─────────────────────────────────────────────────────

def computeConfidence(flow: TaintFlow, pattern: BugPattern): Double =
  var score = 0.0
  // Source is known dangerous pattern
  if flow.source.description.contains("HTTP") || flow.source.description.contains("Servlet") then
    score += 3.0
  else if flow.source.description.contains("Environment") || flow.source.description.contains("input") then
    score += 2.0
  else
    score += 1.0 // unknown source type

  // Sink severity
  if pattern.severity == BugSeverity.Critical then score += 2.0
  else if pattern.severity == BugSeverity.High then score += 1.0

  // Direct flow (fewer hops = higher confidence)
  val hops = flow.steps.size
  score -= hops * 0.5

  // String interpolation in the chain = higher confidence
  if flow.steps.exists(_.operation == "string interpolation") then score += 1.0

  math.max(0.0, score)

// ── Main entry point ───────────────────────────────────────────────────────

/** Enrich a bug finding with taint analysis.
  * Returns the finding with taintFlow populated if taint was detected,
  * or None if the finding should be suppressed (constant-derived). */
def analyzeTaint(
  finding: BugFinding, tree: Tree, filePath: Path,
  idx: Option[WorkspaceIndex] = None, workspace: Option[Path] = None
): Option[BugFinding] =
  // Only analyze security-related patterns (sinks)
  val sinkPatterns = Set(BugPattern.FragmentConst, BugPattern.SpliceInterpolation,
    BugPattern.ObjectInputStream, BugPattern.JacksonDefaultTyping, BugPattern.HardcodedSecret,
    BugPattern.CommandInjection, BugPattern.PathTraversal, BugPattern.XSS,
    BugPattern.OpenRedirect, BugPattern.SSRF)
  if !sinkPatterns.contains(finding.pattern) then return Some(finding)

  // Find the AST node at the finding's line
  val targetLine = finding.line - 1 // scalameta is 0-indexed
  var sinkNode: Option[Tree] = None
  def findNode(t: Tree): Unit =
    if sinkNode.isEmpty then
      if t.pos.startLine == targetLine then
        sinkNode = Some(t)
      t.children.foreach(findNode)
  findNode(tree)

  sinkNode match
    case None => Some(finding) // can't find node, keep finding as-is
    case Some(node) =>
      val bindings = collectBindings(tree)

      // Phase 1: Check if constant-derived → suppress
      val args = extractSinkArgs(node)
      if args.nonEmpty && args.forall(isLiteralDerived(_, bindings)) then
        return None // suppress: all arguments are constant-derived

      // Phase 2: Backward slice to find taint source
      val flow = args.iterator
        .map(arg => backwardTrace(arg, bindings, filePath))
        .collectFirst { case Some(f) => f }

      flow match
        case Some(f) =>
          val confidence = computeConfidence(f, finding.pattern)
          val enriched = finding.copy(
            taintFlow = Some(f.copy(confidence = confidence)),
            message = s"${finding.message} (taint: ${f.source.description})"
          )
          Some(enriched)
        case None =>
          // No taint flow found — keep finding but don't enrich
          Some(finding)

/** Extract argument expressions from a sink node. */
private def extractSinkArgs(node: Tree): List[Tree] =
  node match
    case Term.Apply.After_4_6_0(_, argClause) => argClause.values.toList
    case Term.Interpolate(_, _, args) => args.toList
    case _ =>
      // Walk down to find the first Apply or Interpolate
      var found: List[Tree] = Nil
      def search(t: Tree): Unit =
        if found.isEmpty then
          t match
            case Term.Apply.After_4_6_0(_, argClause) => found = argClause.values.toList
            case Term.Interpolate(_, _, args) => found = args.toList
            case _ => t.children.foreach(search)
      search(node)
      found

private def posLine(t: Tree): Int =
  try t.pos.startLine + 1
  catch case _: Exception => 0
