import java.nio.file.{Files, Path, Paths}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.meta.internal.{semanticdb => sdb}

// ── SemanticDB integration (optional, type-aware refs) ──────────────────────

/** Semantic reference with resolved symbol and exact position. */
case class SemanticRef(file: Path, line: Int, character: Int, symbol: String, role: SemanticRole, displayName: String)

enum SemanticRole:
  case Definition, Reference

/** Index built from .semanticdb files — provides type-aware references. */
class SemanticIndex(
  val refsByName: Map[String, List[SemanticRef]],
  val refsBySymbol: Map[String, List[SemanticRef]],
  val occurrencesByFile: Map[String, List[SemanticRef]],
  val symbolInfoMap: Map[String, sdb.SymbolInformation],
  val fileCount: Int
):
  /** Find refs by simple name (may include multiple symbols with same name). */
  def findRefs(name: String): List[SemanticRef] =
    refsByName.getOrElse(name.toLowerCase, Nil)

  /** Find refs for a specific fully-qualified SemanticDB symbol. Type-aware — no ambiguity. */
  def findRefsBySymbol(symbol: String): List[SemanticRef] =
    refsBySymbol.getOrElse(symbol, Nil)

  /** Resolve a simple name to SemanticDB symbol(s). Returns (sdbSymbol, package, kind). */
  def resolveSymbols(name: String): List[(symbol: String, pkg: String, kind: String)] =
    refsByName.getOrElse(name.toLowerCase, Nil)
      .filter(_.role == SemanticRole.Definition)
      .map(_.symbol)
      .distinct
      .flatMap { sym =>
        symbolInfoMap.get(sym).map { info =>
          val pkg = extractPackage(sym)
          val kind = info.kind match
            case sdb.SymbolInformation.Kind.CLASS => "class"
            case sdb.SymbolInformation.Kind.TRAIT => "trait"
            case sdb.SymbolInformation.Kind.OBJECT => "object"
            case sdb.SymbolInformation.Kind.METHOD => "def"
            case sdb.SymbolInformation.Kind.FIELD => "val"
            case sdb.SymbolInformation.Kind.TYPE => "type"
            case sdb.SymbolInformation.Kind.INTERFACE => "trait"
            case _ => "symbol"
          (symbol = sym, pkg = pkg, kind = kind)
        }
      }

  /** Find all occurrences within a file's line range. For call-graph: what symbols are referenced in a method body. */
  def findOccurrencesInRange(fileUri: String, startLine: Int, endLine: Int): List[SemanticRef] =
    occurrencesByFile.getOrElse(fileUri, Nil).filter { ref =>
      ref.role == SemanticRole.Reference && ref.line >= startLine && ref.line <= endLine
    }

  /** Match a scalex SymbolInfo to the best SemanticDB symbol. */
  def matchSymbol(name: String, packageName: String, kind: SymbolKind): Option[String] =
    val candidates = resolveSymbols(name)
    if candidates.isEmpty then None
    else if candidates.size == 1 then Some(candidates.head.symbol)
    else
      // Disambiguate by package
      val pkgPrefix = packageName.replace('.', '/')
      candidates.find(c => c.symbol.startsWith(pkgPrefix + "/")).map(_.symbol)
        .orElse(candidates.headOption.map(_.symbol))

  def isEmpty: Boolean = refsByName.isEmpty

  /** Extract package from SemanticDB symbol. "com/example/Foo#" → "com.example" */
  private def extractPackage(symbol: String): String =
    val stripped = symbol.stripSuffix(".").stripSuffix("#").replaceAll("""\([^)]*\)""", "")
    val lastSlash = stripped.lastIndexOf('/')
    if lastSlash <= 0 then ""
    else stripped.substring(0, lastSlash).replace('/', '.')

object SemanticIndex:
  /** Try to load SemanticDB data from a workspace. Returns None if no .semanticdb files found. */
  def load(workspace: Path): Option[SemanticIndex] =
    val sdbFiles = discoverSemanticDBFiles(workspace)
    if sdbFiles.isEmpty then None
    else
      val refsByName = mutable.HashMap.empty[String, mutable.ListBuffer[SemanticRef]]
      val refsBySymbol = mutable.HashMap.empty[String, mutable.ListBuffer[SemanticRef]]
      val occurrencesByFile = mutable.HashMap.empty[String, mutable.ListBuffer[SemanticRef]]
      val symbolInfoMap = mutable.HashMap.empty[String, sdb.SymbolInformation]
      var fileCount = 0

      sdbFiles.foreach { sdbFile =>
        try
          val bytes = Files.readAllBytes(sdbFile)
          val docs = sdb.TextDocuments.parseFrom(bytes)
          docs.documents.foreach { doc =>
            fileCount += 1
            val sourcePath = resolveSourcePath(workspace, doc.uri)

            // Index symbol information
            doc.symbols.foreach { info =>
              symbolInfoMap(info.symbol) = info
            }

            // Index occurrences (references + definitions)
            doc.occurrences.foreach { occ =>
              if occ.symbol.nonEmpty && !occ.symbol.startsWith("local") then
                val name = extractSimpleName(occ.symbol)
                if name.nonEmpty then
                  val role = occ.role match
                    case sdb.SymbolOccurrence.Role.DEFINITION => SemanticRole.Definition
                    case _ => SemanticRole.Reference
                  val line = occ.range.map(_.startLine + 1).getOrElse(0)
                  val char = occ.range.map(_.startCharacter).getOrElse(0)
                  val ref = SemanticRef(sourcePath, line, char, occ.symbol, role, name)

                  refsByName.getOrElseUpdate(name.toLowerCase, mutable.ListBuffer.empty) += ref
                  refsBySymbol.getOrElseUpdate(occ.symbol, mutable.ListBuffer.empty) += ref
                  occurrencesByFile.getOrElseUpdate(doc.uri, mutable.ListBuffer.empty) += ref
            }
          }
        catch
          case _: Exception => () // skip unparseable files
      }

      if fileCount == 0 then None
      else Some(SemanticIndex(
        refsByName.map((k, v) => k -> v.toList).toMap,
        refsBySymbol.map((k, v) => k -> v.toList).toMap,
        occurrencesByFile.map((k, v) => k -> v.toList).toMap,
        symbolInfoMap.toMap,
        fileCount
      ))

  /** Extract simple name from SemanticDB symbol string.
    * e.g., "com/example/UserService#findUser()." → "findUser"
    * e.g., "com/example/UserService#" → "UserService" */
  def extractSimpleName(symbol: String): String =
    if symbol.isEmpty || symbol.startsWith("local") then ""
    else
      // Strip trailing descriptor: # (type), . (term), (). (method)
      val stripped = symbol
        .stripSuffix(".")
        .replaceAll("""\([^)]*\)$""", "") // remove method disambiguator
        .stripSuffix("#")
      // Take the last segment after /
      val lastSlash = stripped.lastIndexOf('/')
      if lastSlash >= 0 then stripped.substring(lastSlash + 1)
      else stripped

  /** Resolve a SemanticDB URI to an absolute path.
    * SemanticDB URIs are relative to the compiler's sourceroot.
    * Try workspace first, then common subdirectories. */
  private def resolveSourcePath(workspace: Path, uri: String): Path =
    val direct = workspace.resolve(uri).normalize
    if Files.exists(direct) then direct
    else
      // Try common source subdirectories
      val candidates = List("src", "src/main/scala", "src/main/java", "app", "core/src/main/scala")
      candidates.iterator
        .map(sub => workspace.resolve(sub).resolve(uri).normalize)
        .find(Files.exists(_))
        .getOrElse(direct) // return direct even if not found — let caller handle

  /** Find all .semanticdb files in common build output locations. */
  private def discoverSemanticDBFiles(workspace: Path): List[Path] =
    val candidates = List(
      "target",
      "out",
      ".bloop",
      ".scala-build",
      "src/.scala-build",
    )
    val result = mutable.ListBuffer.empty[Path]

    candidates.foreach { dir =>
      val dirPath = workspace.resolve(dir)
      if Files.isDirectory(dirPath) then
        findSemanticDBFilesRecursive(dirPath, result, maxDepth = 10)
    }

    // Check subdirectories for .scala-build (scala-cli puts it next to sources)
    if result.isEmpty then
      try
        Files.list(workspace).iterator().asScala.foreach { sub =>
          if Files.isDirectory(sub) then
            val scalaBuild = sub.resolve(".scala-build")
            if Files.isDirectory(scalaBuild) then
              findSemanticDBFilesRecursive(scalaBuild, result, maxDepth = 10)
        }
      catch
        case _: java.io.IOException => ()

    result.toList

  private def findSemanticDBFilesRecursive(dir: Path, result: mutable.ListBuffer[Path], maxDepth: Int): Unit =
    if maxDepth <= 0 then ()
    else
      try
        val entries = Files.list(dir).iterator().asScala.toList
        entries.foreach { entry =>
          if Files.isRegularFile(entry) && entry.toString.endsWith(".semanticdb") then
            result += entry
          else if Files.isDirectory(entry) then
            val name = entry.getFileName.toString
            if name == "META-INF" || name == "semanticdb" || name == "classes" ||
               name.startsWith("scala-") || name.startsWith("bloop-") ||
               name.startsWith("classes") || name == "dest" || name == "compile" ||
               name == "main" || name == "test" ||
               !name.startsWith(".") then
              findSemanticDBFilesRecursive(entry, result, maxDepth - 1)
        }
      catch
        case _: java.io.IOException => ()
