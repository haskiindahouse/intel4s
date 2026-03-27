import java.nio.file.{Files, Path}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

// ── Unused command ──────────────────────────────────────────────────────────

def cmdUnused(args: List[String], ctx: CommandContext): CmdResult =
  val pkg = args.headOption
  // Get candidate symbols — types by default, all with --kind
  var candidates = filterSymbols(ctx.idx.symbols, ctx)
  // Scope to package if specified
  pkg.foreach { p =>
    val resolved = resolvePackage(p, ctx)
    resolved match
      case Some(rp) =>
        val prefix = rp + "."
        candidates = candidates.filter(s => s.packageName == rp || s.packageName.startsWith(prefix))
      case None =>
        // Fall through with empty candidates to trigger not-found
        candidates = Nil
  }
  // Default to types only (class/trait/object/enum) unless --kind is specified
  if ctx.kindFilter.isEmpty then
    candidates = candidates.filter(s => typeKinds.contains(s.kind))
  // Exclude test files by default
  candidates = candidates.filter(s => !isTestFile(s.file, ctx.workspace))

  if candidates.isEmpty && pkg.isDefined then
    CmdResult.NotFound(
      s"""No symbols found in package "${pkg.get}"""",
      mkPackageNotFound(pkg.get, ctx, "unused"))
  else
    // For each symbol, check if it has external references via bloom filters
    val unused = mutable.ListBuffer.empty[SymbolInfo]
    val indexedFiles = ctx.idx.indexedFiles

    candidates.foreach { sym =>
      val defRelPaths = ctx.idx.findDefinition(sym.name)
        .filter(d => d.packageName == sym.packageName)
        .map(d => ctx.workspace.relativize(d.file).toString)
        .toSet

      // Phase 1: bloom filter pre-screen — check if ANY other file might contain this name
      val hasBloomHit = indexedFiles.exists { f =>
        !defRelPaths.contains(f.relativePath) &&
        f.identifierBloom.forall(_.mightContain(sym.name))
      }

      if !hasBloomHit then {
        // No bloom hit → definitely unused
        unused += sym
      } else {
        // Phase 2: text verification — confirm with actual file read
        val hasRealRef = indexedFiles.exists { f =>
          !defRelPaths.contains(f.relativePath) &&
          f.identifierBloom.forall(_.mightContain(sym.name)) && {
            val path = ctx.workspace.resolve(f.relativePath)
            try
              val lines = Files.readAllLines(path).asScala
              lines.exists(line => ctx.idx.containsWord(line, sym.name))
            catch
              case _: java.io.IOException => false
          }
        }
        if !hasRealRef then unused += sym
      }
    }

    val sorted = rankSymbols(unused.toList, ctx.workspace)
    val total = sorted.size
    val header = pkg match
      case Some(p) => s"""Potentially unused symbols in "$p" — $total found:"""
      case None => s"Potentially unused symbols — $total found:"
    CmdResult.SymbolList(header, sorted, total, emptyMessage = "No unused symbols found", truncate = true)
