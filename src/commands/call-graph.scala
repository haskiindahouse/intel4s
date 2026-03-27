import java.nio.file.Path
import scala.collection.mutable
import scala.meta.internal.{semanticdb => sdb}

// ── Call-graph command ──────────────────────────────────────────────────────

def cmdCallGraph(args: List[String], ctx: CommandContext): CmdResult =
  args.headOption match
    case None => CmdResult.UsageError("Usage: scalex call-graph <method> [--in Owner] [--semantic]")
    case Some(methodName) =>
      // Find the method definition
      val ownerFilter = ctx.inOwner
      val allDefs = ctx.idx.findDefinition(methodName).filter(_.kind == SymbolKind.Def)
      val defs = ownerFilter match
        case Some(owner) => allDefs.filter(d => {
          val ownerDefs = ctx.idx.findDefinition(owner).filter(s => typeKinds.contains(s.kind))
          ownerDefs.exists(od => od.file == d.file)
        })
        case None => allDefs

      val filteredDefs = filterSymbols(defs, ctx)

      if filteredDefs.isEmpty then
        CmdResult.NotFound(
          s"""No def "$methodName" found""",
          mkNotFoundWithSuggestions(methodName, ctx, "call-graph"))
      else
        val sym = filteredDefs.head
        val ownerName = ownerFilter.getOrElse {
          ctx.idx.symbols.find(s =>
            typeKinds.contains(s.kind) && s.file == sym.file &&
            extractMembers(s.file, s.name, Some(s.kind)).exists(_.name == methodName)
          ).map(_.name).getOrElse("")
        }

        // Extract method body for line range
        val bodies = extractBody(sym.file, methodName, if ownerName.nonEmpty then Some(ownerName) else None)

        if bodies.isEmpty then
          CmdResult.CallGraph(methodName, sym.file, sym.line, ownerName, Nil, Nil)
        else
          val body = bodies.head
          // Choose semantic or text-based callee detection
          val callees = if ctx.semantic then
            semanticCallees(body, sym, ctx).getOrElse(findCallees(body.sourceText, methodName, ctx))
          else
            findCallees(body.sourceText, methodName, ctx)

          // Find callers
          val callers = if ctx.brief then Nil
          else
            val refs = ctx.idx.findReferences(methodName, timeoutMs = 5000, strict = true)
            val externalRefs = refs.filter(r => r.file != sym.file || r.line != sym.line)
            filterRefs(externalRefs, ctx).take(ctx.limit)

          CmdResult.CallGraph(methodName, sym.file, sym.line, ownerName, callees, callers)

/** Semantic callee detection: uses SemanticDB occurrences in the method body range.
  * Each occurrence has a fully-qualified symbol — no ambiguity, no keyword filtering. */
private def semanticCallees(body: BodyInfo, sym: SymbolInfo, ctx: CommandContext): Option[List[CalleeInfo]] =
  ctx.idx.semanticIndex match
    case None =>
      System.err.println("No SemanticDB data — falling back to text-based call-graph.")
      None
    case Some(semIdx) =>
      // Find the file URI relative to workspace
      val relPath = ctx.workspace.relativize(sym.file).toString
      val occurrences = semIdx.findOccurrencesInRange(relPath, body.startLine, body.endLine)

      if occurrences.isEmpty then
        System.err.println(s"No semantic occurrences in body range — falling back to text-based.")
        None
      else
        System.err.println(s"Using semantic call-graph (${semIdx.fileCount} compiled files) — type-aware, precise")
        val result = mutable.ListBuffer.empty[CalleeInfo]
        val seen = mutable.HashSet.empty[String]

        occurrences.foreach { ref =>
          if !seen.contains(ref.symbol) && ref.symbol != sym.signature then {
            seen += ref.symbol
            // Look up symbol info from SemanticDB
            semIdx.symbolInfoMap.get(ref.symbol).foreach { info =>
              val kind = info.kind match
                case sdb.SymbolInformation.Kind.METHOD => SymbolKind.Def
                case sdb.SymbolInformation.Kind.FIELD => SymbolKind.Val
                case sdb.SymbolInformation.Kind.CLASS => SymbolKind.Class
                case sdb.SymbolInformation.Kind.TRAIT => SymbolKind.Trait
                case sdb.SymbolInformation.Kind.OBJECT => SymbolKind.Object
                case _ => SymbolKind.Def
              // Only include methods, vals, and fields (not types, packages, params)
              val isCallable = info.kind == sdb.SymbolInformation.Kind.METHOD ||
                               info.kind == sdb.SymbolInformation.Kind.FIELD
              if isCallable then
                val displayName = ref.displayName
                // Try to find the file from scalex index
                val scalexDef = ctx.idx.findDefinition(ref.displayName).headOption
                result += CalleeInfo(
                  ref.displayName, kind,
                  scalexDef.map(_.file), scalexDef.map(_.line),
                  scalexDef.map(_.packageName).getOrElse(""),
                  scalexDef.map(_.signature).getOrElse(s"${kind.toString.toLowerCase} ${ref.displayName}")
                )
            }
          }
        }
        Some(result.toList.sortBy(_.name))

private def findCallees(sourceText: String, selfName: String, ctx: CommandContext): List[CalleeInfo] =
  val identPattern = java.util.regex.Pattern.compile("""[a-zA-Z_]\w+""")
  val matcher = identPattern.matcher(sourceText)
  val bodyWords = mutable.HashSet.empty[String]
  while matcher.find() do bodyWords += matcher.group()

  bodyWords -= selfName
  scalaKeywords.foreach(bodyWords -= _)

  val result = mutable.ListBuffer.empty[CalleeInfo]
  val seen = mutable.HashSet.empty[String]

  bodyWords.foreach { word =>
    if !seen.contains(word.toLowerCase) then {
      ctx.idx.symbolsByName.get(word.toLowerCase).foreach { syms =>
        syms.filter(s => s.kind == SymbolKind.Def || s.kind == SymbolKind.Val)
          .foreach { s =>
            if seen.add(s.name.toLowerCase) then
              result += CalleeInfo(s.name, s.kind, Some(s.file), Some(s.line), s.packageName, s.signature)
          }
      }
    }
  }

  result.toList.sortBy(_.name)

private val scalaKeywords = Set(
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

