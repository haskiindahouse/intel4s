import scala.meta.*
import scala.collection.mutable
import java.nio.file.{Files, Path}
import scala.util.boundary, boundary.break

// ── Taint analysis engine ──────────────────────────────────────────────────
//
// Full pipeline:
//   1. Constant propagation — suppress findings where sink args are literal-derived
//   2. Intraprocedural backward slice — trace taint from sources to sinks
//      - Sanitizer detection: breaks taint chain
//      - Conditional guard detection: reduces confidence
//      - String/collection method propagation: tracks taint through transformations
//      - Parameter taint inference: method params named request/input are tainted
//   3. Cross-file — follow taint through method calls via scalex index
//   4. Confidence scoring — multi-factor scoring for precise prioritization
//
// Taint is ON by default in bug-hunt. --no-taint disables it.

// ── Sources: where untrusted data enters ───────────────────────────────────

private val taintSourcePatterns: List[(methodPattern: String, description: String)] = List(
  "params"         -> "HTTP parameter",
  "param"          -> "HTTP parameter",
  "getParameter"   -> "Servlet parameter",
  "queryString"    -> "HTTP query string",
  "formField"      -> "HTTP form field",
  "header"         -> "HTTP header",
  "cookie"         -> "HTTP cookie",
  "getenv"         -> "Environment variable",
  "readLine"       -> "Standard input",
  "bodyAsString"   -> "HTTP request body",
  "bodyAsJson"     -> "HTTP request body",
  "contentAsString"-> "HTTP response body",
  "getInputStream" -> "Stream input",
  "getReader"      -> "Reader input",
)

// Qualifier patterns — if the receiver name matches, it's likely user input
private val taintQualifierPatterns = Set(
  "request", "req", "params", "headers", "cookies", "env",
  "input", "body", "form", "query", "payload"
)

// Parameter names that indicate untrusted input (for param taint inference)
private val taintedParamNames = Set(
  "request", "req", "input", "body", "payload", "form",
  "query", "params", "data", "userInput", "rawInput"
)

// Parameter type names that indicate untrusted input
private val taintedParamTypes = Set(
  "Request", "HttpRequest", "HttpServletRequest", "ServerRequest",
  "WebSocketFrame", "RawHeader"
)

private def isTaintSource(t: Tree): Option[String] =
  t match
    case Term.Apply.After_4_6_0(Term.Select(qual, Term.Name(method)), _) =>
      val qualName = extractQualName(qual).toLowerCase
      taintSourcePatterns.find(p => method.toLowerCase.contains(p.methodPattern.toLowerCase)).map(_.description)
        .orElse(if taintQualifierPatterns.exists(qualName.contains) then Some("HTTP input") else None)
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
  "htmlencode", "urlencode", "sqlescape", "parameterize",
  "validate", "verify", "check", "normalize",
  "filter", "whitelist", "allowlist",
  "preparedstatement", "setstring", "setint", "setlong"
)

private def isSanitizer(t: Tree): Boolean = t match
  case Term.Apply.After_4_6_0(Term.Name(name), _) =>
    sanitizerNames.exists(name.toLowerCase.contains)
  case Term.Apply.After_4_6_0(Term.Select(_, Term.Name(name)), _) =>
    sanitizerNames.exists(name.toLowerCase.contains)
  case _ => false

// ── String methods: taint propagation rules ────────────────────────────────

// These methods preserve taint (output is still attacker-controlled)
private val taintPreservingMethods = Set(
  "toUpperCase", "toLowerCase", "trim", "strip", "stripLeading", "stripTrailing",
  "replace", "replaceAll", "replaceFirst", "substring", "take", "drop",
  "reverse", "capitalize", "padTo", "format", "formatted",
  "concat", "append", "prepend", "mkString",
  "+", "++", ":+", "+:", "::", ":::", "appended", "prepended",
  "map", "flatMap", "collect", "filter", "filterNot", "withFilter",
  "sorted", "sortBy", "sortWith", "distinct", "reverse",
  "updated", "patch",
  "toString", "asInstanceOf",
)

// These methods kill taint (output is not attacker-controlled)
private val taintKillingMethods = Set(
  "length", "size", "isEmpty", "nonEmpty", "isBlank",
  "toInt", "toLong", "toDouble", "toFloat", "toByte", "toShort", "toBoolean",
  "hashCode", "equals", "eq", "ne",
  "contains", "startsWith", "endsWith", "matches", "indexOf", "lastIndexOf",
  "count", "exists", "forall",
  "getClass", "isInstanceOf",
)

private def methodPreservesTaint(methodName: String): Boolean =
  taintPreservingMethods.contains(methodName)

private def methodKillsTaint(methodName: String): Boolean =
  taintKillingMethods.contains(methodName)

// ── Phase 1: Constant propagation ──────────────────────────────────────────

def collectBindings(body: Tree): Map[String, Tree] =
  val bindings = mutable.LinkedHashMap.empty[String, Tree]
  def walk(t: Tree): Unit =
    t match
      case Defn.Val(_, pats, _, rhs) =>
        pats.foreach {
          case Pat.Var(Term.Name(name)) => bindings(name) = rhs
          case _ => ()
        }
      case Defn.Var.After_4_7_2(_, pats, _, bodyTree) =>
        pats.foreach {
          case Pat.Var(Term.Name(name)) => bindings(name) = bodyTree
          case null => ()
        }
      // For-comprehension bindings: x <- expr
      case Enumerator.Generator(Pat.Var(Term.Name(name)), rhs) =>
        bindings(name) = rhs
      // For-comprehension val bindings: x = expr
      case Enumerator.Val(Pat.Var(Term.Name(name)), rhs) =>
        bindings(name) = rhs
      case _ => ()
    t.children.foreach(walk)
  walk(body)
  bindings.toMap

def isLiteralDerived(expr: Tree, bindings: Map[String, Tree], depth: Int = 0): Boolean =
  if depth > 20 then false
  else expr match
    case _: Lit => true
    case Term.Name(name) =>
      bindings.get(name).exists(isLiteralDerived(_, bindings, depth + 1))
    case Term.Interpolate(_, _, args) =>
      args.forall(isLiteralDerived(_, bindings, depth + 1))
    case Term.ApplyInfix.After_4_6_0(lhs, Term.Name("+"), _, argClause) =>
      isLiteralDerived(lhs, bindings, depth + 1) &&
      argClause.values.forall(isLiteralDerived(_, bindings, depth + 1))
    case Term.Apply.After_4_6_0(Term.Name("s"), argClause) =>
      argClause.values.forall(isLiteralDerived(_, bindings, depth + 1))
    // String method calls on literal-derived strings preserve literal-derived status
    case Term.Apply.After_4_6_0(Term.Select(qual, Term.Name(method)), _)
      if taintKillingMethods.contains(method) || taintPreservingMethods.contains(method) =>
      isLiteralDerived(qual, bindings, depth + 1)
    case Term.Select(qual, Term.Name(method))
      if taintKillingMethods.contains(method) || taintPreservingMethods.contains(method) =>
      isLiteralDerived(qual, bindings, depth + 1)
    case _ => false

// ── Phase 2: Intraprocedural backward slice ────────────────────────────────

def backwardTrace(
  expr: Tree, bindings: Map[String, Tree], filePath: Path,
  methodParams: Set[String] = Set.empty,
  steps: List[TaintStep] = Nil, depth: Int = 0
): Option[TaintFlow] =
  if depth > 15 then None
  else expr match
    // Variable reference — look up binding and recurse
    case Term.Name(name) =>
      bindings.get(name) match
        case Some(rhs) =>
          val step = TaintStep(name, filePath, posLine(expr), "assignment")
          if isSanitizer(rhs) then None // sanitizer breaks chain
          else isTaintSource(rhs) match
            case Some(desc) =>
              val source = TaintSource(rhs.toString.take(80), desc, filePath, posLine(rhs))
              Some(TaintFlow(source, (step :: steps).reverse, confidence = 0.0))
            case None =>
              backwardTrace(rhs, bindings, filePath, methodParams, step :: steps, depth + 1)
        case None =>
          // Check if this is a tainted method parameter
          if methodParams.contains(name) then
            val source = TaintSource(name, "method parameter (untrusted)", filePath, posLine(expr))
            Some(TaintFlow(source, steps.reverse, confidence = 0.0))
          else if taintedParamNames.exists(name.toLowerCase.contains) then
            val source = TaintSource(name, "method parameter (potentially untrusted)", filePath, posLine(expr))
            Some(TaintFlow(source, steps.reverse, confidence = 0.0))
          else None

    // String interpolation — any tainted argument taints the whole string
    case Term.Interpolate(_, _, args) =>
      val step = TaintStep("<interpolation>", filePath, posLine(expr), "string interpolation")
      args.iterator
        .map(arg => backwardTrace(arg, bindings, filePath, methodParams, step :: steps, depth + 1))
        .collectFirst { case Some(flow) => flow }

    // Method call on receiver — handle propagation rules
    case Term.Apply.After_4_6_0(Term.Select(qual, Term.Name(method)), argClause) =>
      isTaintSource(expr) match
        case Some(desc) =>
          val source = TaintSource(expr.toString.take(80), desc, filePath, posLine(expr))
          Some(TaintFlow(source, steps.reverse, confidence = 0.0))
        case None =>
          if isSanitizer(expr) then None // sanitizer kills taint
          else if methodKillsTaint(method) then None // .length, .toInt etc. kill taint
          else
            val step = TaintStep(method, filePath, posLine(expr),
              if methodPreservesTaint(method) then "propagation" else "method call")
            // For taint-preserving methods, trace through qualifier
            if methodPreservesTaint(method) then
              backwardTrace(qual, bindings, filePath, methodParams, step :: steps, depth + 1)
            else
              // Check arguments first, then qualifier
              argClause.values.iterator
                .map(arg => backwardTrace(arg, bindings, filePath, methodParams, step :: steps, depth + 1))
                .collectFirst { case Some(flow) => flow }
                .orElse(backwardTrace(qual, bindings, filePath, methodParams, step :: steps, depth + 1))

    // Simple method call (no receiver) / collection construction
    case Term.Apply.After_4_6_0(Term.Name(fun), argClause) =>
      if isSanitizer(expr) then None
      else
        val opName = if Set("List", "Seq", "Vector", "Array", "Set").contains(fun)
          then "collection construction" else "method call"
        val step = TaintStep(fun, filePath, posLine(expr), opName)
        argClause.values.iterator
          .map(arg => backwardTrace(arg, bindings, filePath, methodParams, step :: steps, depth + 1))
          .collectFirst { case Some(flow) => flow }

    // Select (a.b) — trace qualifier
    case Term.Select(qual, Term.Name(method)) =>
      isTaintSource(expr) match
        case Some(desc) =>
          val source = TaintSource(expr.toString.take(80), desc, filePath, posLine(expr))
          Some(TaintFlow(source, steps.reverse, confidence = 0.0))
        case None =>
          if methodKillsTaint(method) then None
          else backwardTrace(qual, bindings, filePath, methodParams, steps, depth + 1)

    // String concatenation (a + b)
    case Term.ApplyInfix.After_4_6_0(lhs, Term.Name("+"), _, argClause) =>
      val step = TaintStep("<concat>", filePath, posLine(expr), "string concatenation")
      backwardTrace(lhs, bindings, filePath, methodParams, step :: steps, depth + 1)
        .orElse(argClause.values.iterator
          .map(arg => backwardTrace(arg, bindings, filePath, methodParams, step :: steps, depth + 1))
          .collectFirst { case Some(flow) => flow })

    case _ => None

// ── Phase 3: Cross-file taint ──────────────────────────────────────────────

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

// ── Conditional guard detection ────────────────────────────────────────────

/** Check if a tainted variable is guarded by a condition before the sink line.
  * e.g. `if (id.matches("[0-9]+")) then db.query(id)` reduces confidence. */
private def hasConditionalGuard(varName: String, tree: Tree, sinkLine: Int): Boolean =
  var foundGuard = false
  def search(t: Tree): Unit =
    if !foundGuard then
      t match
        case Term.If.After_4_4_0(cond, _, _, _) =>
          // Check if the condition references the tainted variable
          val condStr = cond.toString
          if condStr.contains(varName) && posLine(cond) < sinkLine then
            // Check if condition is a validation pattern
            val validationPatterns = Set("matches", "startsWith", "endsWith", "contains",
              "forall", "isDigit", "isLetter", "nonEmpty", "isEmpty", "isDefined",
              "length", "size", ">", "<", ">=", "<=", "==", "!=")
            if validationPatterns.exists(condStr.contains) then
              foundGuard = true
          t.children.foreach(search)
        case _ =>
          t.children.foreach(search)
  search(tree)
  foundGuard

// ── Parameter taint inference ──────────────────────────────────────────────

/** Extract method parameter names that are likely tainted (from untrusted sources). */
private def inferTaintedParams(tree: Tree): Set[String] =
  val tainted = mutable.Set.empty[String]
  def walk(t: Tree): Unit =
    t match
      case d: Defn.Def =>
        // Extract params from all param clauses
        d.paramClauses.foreach { clause =>
          clause.values.foreach {
            case Term.Param(_, Term.Name(name), Some(declType), _) =>
              val typeName = declType.toString
              if taintedParamTypes.exists(typeName.contains) then
                tainted += name
              else if taintedParamNames.exists(name.toLowerCase.contains) then
                tainted += name
            case Term.Param(_, Term.Name(name), None, _) =>
              if taintedParamNames.exists(name.toLowerCase.contains) then
                tainted += name
            case _ => ()
          }
        }
      case _ => ()
    t.children.foreach(walk)
  walk(tree)
  tainted.toSet

// ── Confidence scoring ─────────────────────────────────────────────────────

def computeConfidence(flow: TaintFlow, pattern: BugPattern, tree: Tree, sinkLine: Int): Double =
  var score = 0.0

  // Source type weight
  val desc = flow.source.description.toLowerCase
  if desc.contains("http") || desc.contains("servlet") then score += 3.0
  else if desc.contains("environment") then score += 2.5
  else if desc.contains("standard input") || desc.contains("stream") || desc.contains("reader") then score += 2.0
  else if desc.contains("untrusted") then score += 1.5
  else score += 1.0

  // Sink severity weight
  pattern.severity match
    case BugSeverity.Critical => score += 2.5
    case BugSeverity.High => score += 1.5
    case BugSeverity.Medium => score += 0.5

  // Flow directness — fewer hops = higher confidence
  val hops = flow.steps.size
  score -= hops * 0.3

  // Bonus: string interpolation in the chain (very likely real injection)
  if flow.steps.exists(_.operation == "string interpolation") then score += 1.5
  if flow.steps.exists(_.operation == "string concatenation") then score += 1.0

  // Penalty: conditional guard detected
  val sourceVarNames = flow.steps.filter(_.operation == "assignment").map(_.varName)
  val hasGuard = sourceVarNames.exists(hasConditionalGuard(_, tree, sinkLine))
  if hasGuard then score -= 2.0

  // Penalty: propagation through many method calls (may have been sanitized in ways we can't see)
  val methodCallCount = flow.steps.count(_.operation == "method call")
  if methodCallCount > 3 then score -= 1.0

  // Bonus: direct parameter → sink (short, clear flow)
  if flow.steps.size <= 2 && desc.contains("parameter") then score += 1.0

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
  val sinkPatterns = Set(
    BugPattern.FragmentConst, BugPattern.SpliceInterpolation,
    BugPattern.ObjectInputStream, BugPattern.JacksonDefaultTyping, BugPattern.HardcodedSecret,
    BugPattern.CommandInjection, BugPattern.PathTraversal, BugPattern.XSS,
    BugPattern.OpenRedirect, BugPattern.SSRF)
  if !sinkPatterns.contains(finding.pattern) then Some(finding)
  else
    // Find the AST node at the finding's line
    val targetLine = finding.line - 1 // scalameta is 0-indexed
    val sinkNode = findNodeAtLine(tree, targetLine)

    sinkNode match
      case None => Some(finding) // can't find node, keep finding as-is
      case Some(node) =>
        val bindings = collectBindings(tree)
        val taintedParams = inferTaintedParams(tree)

        // Phase 1: Check if constant-derived → suppress
        val args = extractSinkArgs(node)
        if args.nonEmpty && args.forall(isLiteralDerived(_, bindings)) then
          None // suppress: all arguments are constant-derived
        else
          // Phase 2: Backward slice to find taint source
          val flow = args.iterator
            .map(arg => backwardTrace(arg, bindings, filePath, taintedParams))
            .collectFirst { case Some(f) => f }

          flow match
            case Some(f) =>
              val confidence = computeConfidence(f, finding.pattern, tree, finding.line)
              val enriched = finding.copy(
                taintFlow = Some(f.copy(confidence = confidence)),
                message = s"${finding.message} (taint: ${f.source.description})"
              )
              Some(enriched)
            case None =>
              Some(finding)

// ── Helpers ─────────────────────────────────────────────────────────────────

private def findNodeAtLine(tree: Tree, targetLine: Int): Option[Tree] =
  var found: Option[Tree] = None
  def search(t: Tree): Unit =
    if found.isEmpty then
      if t.pos.startLine == targetLine then
        found = Some(t)
      t.children.foreach(search)
  search(tree)
  found

private def extractSinkArgs(node: Tree): List[Tree] =
  node match
    case Term.Apply.After_4_6_0(_, argClause) => argClause.values.toList
    case Term.Interpolate(_, _, args) => args.toList
    case Init.After_4_6_0(_, _, argClauses) => argClauses.flatMap(_.values).toList
    case _ =>
      var found: List[Tree] = Nil
      def search(t: Tree): Unit =
        if found.isEmpty then
          t match
            case Term.Apply.After_4_6_0(_, argClause) => found = argClause.values.toList
            case Term.Interpolate(_, _, args) => found = args.toList
            case Init.After_4_6_0(_, _, argClauses) => found = argClauses.flatMap(_.values).toList
            case _ => t.children.foreach(search)
      search(node)
      found

private def posLine(t: Tree): Int =
  try t.pos.startLine + 1
  catch case _: Exception => 0
