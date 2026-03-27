import java.nio.file.{Files, Path}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

// ── Rename command ──────────────────────────────────────────────────────────

def cmdRename(args: List[String], ctx: CommandContext): CmdResult =
  args match
    case oldName :: newName :: _ =>
      if ctx.semantic then semanticRename(oldName, newName, ctx)
      else textRename(oldName, newName, ctx)
    case _ =>
      CmdResult.UsageError("Usage: scalex rename <OldName> <NewName> [--semantic]")

/** Text-based rename: word-boundary replacement across all files. Fast, but may hit wrong symbol if name is ambiguous. */
private def textRename(oldName: String, newName: String, ctx: CommandContext): CmdResult =
  val defs = filterSymbols(ctx.idx.findDefinition(oldName), ctx)
  if defs.isEmpty then
    CmdResult.NotFound(
      s"""No definition of "$oldName" found""",
      mkNotFoundWithSuggestions(oldName, ctx, "rename"))
  else
    val defFiles = defs.map(_.file).toSet
    val allRefs = ctx.idx.findReferences(oldName, strict = true)
    val filteredRefs = filterRefs(allRefs, ctx)
    val edits = buildTextEdits(filteredRefs, oldName, newName, defFiles)
    CmdResult.RenameResult(oldName, newName, defs.head, edits)

/** Semantic rename: uses SemanticDB for type-aware, package-precise renaming.
  * Two symbols named "Config" in different packages? Only renames the right one. */
private def semanticRename(oldName: String, newName: String, ctx: CommandContext): CmdResult =
  val defs = filterSymbols(ctx.idx.findDefinition(oldName), ctx)
  if defs.isEmpty then
    CmdResult.NotFound(
      s"""No definition of "$oldName" found""",
      mkNotFoundWithSuggestions(oldName, ctx, "rename"))
  else
    ctx.idx.semanticIndex match
      case None =>
        System.err.println("No SemanticDB data found. Compile with -Xsemanticdb (Scala 3) or semanticdbEnabled := true (sbt).")
        System.err.println("Falling back to text-based rename.")
        textRename(oldName, newName, ctx)
      case Some(semIdx) =>
        val targetDef = defs.head
        // Map scalex definition to SemanticDB symbol
        semIdx.matchSymbol(targetDef.name, targetDef.packageName, targetDef.kind) match
          case None =>
            System.err.println(s"Could not resolve '$oldName' in SemanticDB — falling back to text-based rename.")
            textRename(oldName, newName, ctx)
          case Some(sdbSymbol) =>
            // Find ALL occurrences of this exact symbol — type-aware, no ambiguity
            val semRefs = semIdx.findRefsBySymbol(sdbSymbol)
            if semRefs.isEmpty then
              System.err.println(s"No semantic refs for symbol $sdbSymbol — falling back to text-based rename.")
              textRename(oldName, newName, ctx)
            else
              // Show disambiguation info
              val allSymbols = semIdx.resolveSymbols(oldName)
              if allSymbols.size > 1 then
                System.err.println(s"${allSymbols.size} symbols named '$oldName' found. Renaming only:")
                System.err.println(s"  $sdbSymbol")
                System.err.println(s"Untouched:")
                allSymbols.filter(_.symbol != sdbSymbol).foreach { s =>
                  System.err.println(s"  ${s.symbol} (${s.pkg})")
                }
              System.err.println(s"Using semantic rename (${semIdx.fileCount} compiled files) — type-aware, precise")

              // Build edits from semantic refs
              val edits = mutable.ListBuffer.empty[RenameEdit]
              val seenKeys = mutable.HashSet.empty[String]
              val defFiles = defs.map(_.file).toSet

              semRefs.foreach { ref =>
                val key = s"${ref.file}:${ref.line}"
                if seenKeys.add(key) then {
                  // Read the actual line from the file
                  val lineText = try
                    val lines = Files.readAllLines(ref.file)
                    if ref.line > 0 && ref.line <= lines.size() then lines.get(ref.line - 1)
                    else null
                  catch
                    case _: Exception => null

                  if lineText != null then {
                    val trimmed = lineText.trim
                    val newLine = replaceWord(trimmed, oldName, newName)
                    if newLine != trimmed then {
                      val category = ref.role match
                        case SemanticRole.Definition =>
                          if isDefinitionLine(trimmed, oldName) then "definition" else "usage"
                        case SemanticRole.Reference =>
                          if trimmed.startsWith("import ") then "import"
                          else if trimmed.matches("""^\s*(//|/\*|\*).*""") then "comment"
                          else "usage"
                      edits += RenameEdit(ref.file, ref.line, trimmed, newLine, category)
                    }
                  }
                }
              }

              CmdResult.RenameResult(oldName, newName, targetDef,
                edits.toList.sortBy(e => (ctx.workspace.relativize(e.file).toString, e.line)))

private def buildTextEdits(refs: List[Reference], oldName: String, newName: String, defFiles: Set[Path]): List[RenameEdit] =
  val edits = mutable.ListBuffer.empty[RenameEdit]
  val seenKeys = mutable.HashSet.empty[String]
  refs.foreach { ref =>
    val key = s"${ref.file}:${ref.line}"
    if seenKeys.add(key) then {
      val newLine = replaceWord(ref.contextLine, oldName, newName)
      if newLine != ref.contextLine then {
        val category =
          if defFiles.contains(ref.file) && isDefinitionLine(ref.contextLine, oldName) then "definition"
          else if ref.contextLine.trim.startsWith("import ") then "import"
          else if ref.contextLine.matches("""^\s*(//|/\*|\*).*""") then "comment"
          else "usage"
        edits += RenameEdit(ref.file, ref.line, ref.contextLine, newLine, category)
      }
    }
  }
  edits.toList.sortBy(e => (e.file.toString, e.line))

private def isDefinitionLine(line: String, name: String): Boolean =
  val trimmed = line.trim
  trimmed.matches(s"""^(trait|class|object|enum|def|val|var|type|given)\\s+.*""") &&
    (trimmed.contains(s"trait $name") || trimmed.contains(s"class $name") || trimmed.contains(s"object $name") ||
     trimmed.contains(s"enum $name") || trimmed.contains(s"def $name") || trimmed.contains(s"val $name") ||
     trimmed.contains(s"var $name") || trimmed.contains(s"type $name"))

private def replaceWord(text: String, oldWord: String, newWord: String): String =
  val sb = StringBuilder()
  var i = 0
  while i < text.length do
    val idx = text.indexOf(oldWord, i)
    if idx < 0 then {
      sb.append(text.substring(i))
      i = text.length
    } else {
      val before = idx == 0 || !text(idx - 1).isLetterOrDigit
      val afterPos = idx + oldWord.length
      val after = afterPos >= text.length || !text(afterPos).isLetterOrDigit
      sb.append(text.substring(i, idx))
      if before && after then sb.append(newWord)
      else sb.append(oldWord)
      i = afterPos
    }
  sb.toString
