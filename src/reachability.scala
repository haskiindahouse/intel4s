import java.nio.file.Path
import scala.collection.mutable

// ── Reachability analysis ───────────────────────────────────────────────────
//
// WHY: Text-based call-graph expansion from entrypoints.
// We re-use the same findCallees word-extraction approach from call-graph.scala
// to avoid adding a dependency on the call-graph command internals.
// No semantic analysis needed — the goal is fast triage, not 100% precision.

// Default BFS depth limit — configurable via --depth flag.
// WHY: depth 5 catches typical HTTP handler → service → repo → helper chains.
val DefaultReachabilityDepth = 5

/** Build the reachable method set by BFS from all entrypoints up to maxDepth.
  *
  * Returns:
  *   reachableSet  — all method names reachable from any entrypoint
  *   entrypointMap — method name → list of entrypoint names that reach it
  */
def buildReachableSet(
  idx: WorkspaceIndex,
  workspace: Path,
  maxDepth: Int = DefaultReachabilityDepth,
  includeTests: Boolean = false
): (reachableSet: Set[String], entrypointMap: Map[String, List[String]]) =
  // Collect entrypoints via the same logic as cmdEntrypoints but without ctx overhead.
  val rawEntries = collectEntrypoints(idx, includeTests)
  if rawEntries.isEmpty then
    (reachableSet = Set.empty, entrypointMap = Map.empty)
  else
    val reachableSet    = mutable.HashSet.empty[String]
    val entrypointMap   = mutable.HashMap.empty[String, mutable.ListBuffer[String]]

    rawEntries.foreach { sym =>
      bfsFromEntrypoint(sym.name, sym, idx, maxDepth, reachableSet, entrypointMap)
    }

    (
      reachableSet  = reachableSet.toSet,
      entrypointMap = entrypointMap.view.mapValues(_.toList).toMap
    )

/** Find the innermost enclosing def for a given file+line, using extractScopes. */
def findEnclosingMethod(file: Path, line: Int): Option[String] =
  extractScopes(file, line)
    .filter(_.kind == "def")
    .lastOption
    .map(_.name)

// ── Private helpers ────────────────────────────────────────────────────────

/** Collect entrypoint SymbolInfos matching the same rules as cmdEntrypoints. */
private def collectEntrypoints(idx: WorkspaceIndex, includeTests: Boolean): List[SymbolInfo] =
  val seen    = mutable.HashSet.empty[(name: String, file: String, line: Int)]
  val results = mutable.ListBuffer.empty[SymbolInfo]

  def addIfNew(sym: SymbolInfo): Unit = {
    val key = (name = sym.name, file = sym.file.toString, line = sym.line)
    if !seen.contains(key) then {
      seen += key
      results += sym
    }
  }

  // 1. @main annotated
  idx.findAnnotated("main").foreach(addIfNew)

  // 2. extends App
  idx.findImplementations("App").foreach(addIfNew)

  // 3. def main(...) inside objects
  idx.symbols.filter(_.kind == SymbolKind.Object).foreach { obj =>
    val members = extractMembers(obj.file, obj.name)
    if members.exists(m => m.name == "main" && m.kind == SymbolKind.Def) then
      addIfNew(obj)
  }

  // 4. Test suites — only if includeTests
  if includeTests then
    val testParents = Set("FunSuite", "AnyFunSuite", "FlatSpec", "AnyFlatSpec",
      "WordSpec", "AnyWordSpec", "FreeSpec", "AnyFreeSpec",
      "PropSpec", "FeatureSpec", "Suite", "Specification", "FunSpec")
    testParents.foreach { parent =>
      idx.findImplementations(parent).foreach(addIfNew)
    }

  results.toList

/** BFS expansion from one entrypoint.
  *
  * We extract the symbol's body text, extract word identifiers from it,
  * look them up in the index as def/val symbols, and enqueue them.
  * Cycle-safe via the visited set shared across the whole BFS.
  */
private def bfsFromEntrypoint(
  entrypointName: String,
  sym: SymbolInfo,
  idx: WorkspaceIndex,
  maxDepth: Int,
  reachableSet: mutable.HashSet[String],
  entrypointMap: mutable.HashMap[String, mutable.ListBuffer[String]]
): Unit =
  // Each queue entry: (symbol name, ownerName hint, file, current depth)
  val queue = mutable.Queue.empty[(name: String, file: Path, depth: Int)]

  def markReachable(name: String, depth: Int): Unit = {
    reachableSet += name
    entrypointMap.getOrElseUpdate(name, mutable.ListBuffer.empty) += entrypointName
  }

  // Seed from the entrypoint itself
  markReachable(sym.name, 0)
  queue.enqueue((name = sym.name, file = sym.file, depth = 0))

  while queue.nonEmpty do
    val (methodName, file, depth) = queue.dequeue()
    if depth < maxDepth then
      // Extract callees from method body text
      val callees = calleesFromBody(methodName, file, idx)
      callees.foreach { callee =>
        if !reachableSet.contains(callee.name) then
          markReachable(callee.name, depth + 1)
          // Enqueue the callee for further expansion using its definition file
          val calleeFile = callee.file.getOrElse(file)
          queue.enqueue((name = callee.name, file = calleeFile, depth = depth + 1))
      }

/** Extract callee symbols from a method body using text-based word matching.
  * Mirrors the approach in call-graph.scala findCallees, but returns only defs/vals. */
private def calleesFromBody(methodName: String, file: Path, idx: WorkspaceIndex): List[CalleeInfo] =
  val bodies = extractBody(file, methodName, None)
  if bodies.isEmpty then Nil
  else
    val bodyText = bodies.head.sourceText
    val words    = extractBodyWords(bodyText)
    words -= methodName  // avoid self-reference
    scalaKeywordsForReachability.foreach(words -= _)

    val result = mutable.ListBuffer.empty[CalleeInfo]
    val seen   = mutable.HashSet.empty[String]

    words.foreach { word =>
      if !seen.contains(word.toLowerCase) then
        idx.symbolsByName.get(word.toLowerCase).foreach { syms =>
          syms.filter(s => s.kind == SymbolKind.Def || s.kind == SymbolKind.Val)
            .foreach { s =>
              if seen.add(s.name.toLowerCase) then
                result += CalleeInfo(s.name, s.kind, Some(s.file), Some(s.line), s.packageName, s.signature)
            }
        }
    }
    result.toList

/** Extract unique identifier words from a source text snippet.
  * WHY: Pattern matches any Java identifier — including single-char names like e, f, x.
  * This is intentionally broad; false positives are filtered by keyword list + index lookup. */
private def extractBodyWords(text: String): mutable.HashSet[String] =
  val identPattern = java.util.regex.Pattern.compile("""[a-zA-Z_]\w*""")
  val matcher      = identPattern.matcher(text)
  val words        = mutable.HashSet.empty[String]
  while matcher.find() do words += matcher.group()
  words

// WHY: Same keyword list as call-graph.scala to avoid false callee matches on language keywords.
private val scalaKeywordsForReachability: Set[String] = Set(
  "def", "val", "var", "class", "trait", "object", "enum", "type", "extends", "with",
  "if", "else", "match", "case", "for", "while", "do", "try", "catch", "finally",
  "throw", "return", "import", "package", "new", "this", "super", "override", "abstract",
  "final", "sealed", "private", "protected", "lazy", "given", "using", "then", "end",
  "true", "false", "null", "yield", "forSome", "macro", "inline", "opaque", "transparent",
  "toString", "hashCode", "equals", "apply", "unapply", "copy", "map", "flatMap",
  "filter", "foreach", "fold", "foldLeft", "foldRight", "reduce", "collect", "exists",
  "forall", "find", "head", "tail", "last", "take", "drop", "mkString", "toList",
  "toSet", "toMap", "toSeq", "toArray", "size", "length", "isEmpty", "nonEmpty",
  "contains", "get", "getOrElse", "orElse", "some", "none", "option", "either",
  "left", "right", "println", "print", "require", "assert",
)
