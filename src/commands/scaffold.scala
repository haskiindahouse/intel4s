import java.nio.file.Path
import scala.collection.mutable

// ── Scaffold command ────────────────────────────────────────────────────────

def cmdScaffold(args: List[String], ctx: CommandContext): CmdResult =
  args match
    case "impl" :: rest => scaffoldImpl(rest, ctx)
    case "test" :: rest => scaffoldTest(rest, ctx)
    case _ => CmdResult.UsageError("Usage: scalex scaffold <impl|test> <class> [--framework munit|scalatest|zio-test]")

private def scaffoldImpl(args: List[String], ctx: CommandContext): CmdResult =
  args.headOption match
    case None => CmdResult.UsageError("Usage: scalex scaffold impl <class>")
    case Some(className) =>
      val defs = filterSymbols(ctx.idx.findDefinition(className).filter(s => typeKinds.contains(s.kind)), ctx)
      if defs.isEmpty then
        CmdResult.NotFound(
          s"""No class/trait/object/enum "$className" found""",
          mkNotFoundWithSuggestions(className, ctx, "scaffold"))
      else
        val sym = defs.head
        val ownMemberNames = extractMembers(sym.file, sym.name, Some(sym.kind)).map(_.name).toSet

        val visited = mutable.HashSet.empty[String]
        visited += sym.name.toLowerCase
        val seenMembers = mutable.HashSet.empty[String]
        ownMemberNames.foreach(seenMembers.add)
        val result = mutable.ListBuffer.empty[(parentName: String, parentFile: Path, parentLine: Int, members: List[MemberInfo])]

        def walk(parentNames: List[String], childFile: Path, childName: String): Unit = {
          parentNames.foreach { pName =>
            if !visited.contains(pName.toLowerCase) then {
              visited += pName.toLowerCase
              val parentDefs = ctx.idx.findDefinition(pName).filter(s => typeKinds.contains(s.kind))
              parentDefs.headOption.foreach { pd =>
                // Build type parameter mapping: parent's type params → child's type args
                val typeParams = extractTypeParamNames(pd.file, pd.name)
                val typeArgs = extractParentTypeArgs(childFile, childName, pd.name)
                val typeMapping =
                  if typeParams.nonEmpty && typeParams.size == typeArgs.size then
                    typeParams.zip(typeArgs).toMap
                  else Map.empty[String, String]

                val parentMembers = extractMembers(pd.file, pd.name, Some(pd.kind))
                val abstractMembers = mutable.ListBuffer.empty[MemberInfo]
                parentMembers.foreach { m =>
                  if !seenMembers.contains(m.name) then {
                    val bodies = extractBody(pd.file, m.name, Some(pd.name))
                    val isAbstract = bodies.exists(_.isAbstract)
                    seenMembers += m.name
                    if isAbstract then {
                      val substituted = if typeMapping.nonEmpty then
                        m.copy(signature = applyTypeSubstitution(m.signature, typeMapping))
                      else m
                      abstractMembers += substituted
                    }
                  }
                }
                if abstractMembers.nonEmpty then
                  result += ((parentName = pd.name, parentFile = pd.file, parentLine = pd.line, members = abstractMembers.toList))
                walk(pd.parents, pd.file, pd.name)
              }
            }
          }
        }

        walk(sym.parents, sym.file, sym.name)
        CmdResult.ScaffoldImpl(sym.name, sym.file, sym.line, sym.packageName, result.toList)

/** Replace type parameter names with concrete type arguments in a signature.
  * E.g., mapping {"T" -> "User"} transforms "def process(item: T): Unit"
  * into "def process(item: User): Unit". */
private def applyTypeSubstitution(signature: String, mapping: Map[String, String]): String =
  mapping.foldLeft(signature) { case (sig, (param, arg)) =>
    sig.replaceAll(
      s"\\b${java.util.regex.Pattern.quote(param)}\\b",
      java.util.regex.Matcher.quoteReplacement(arg))
  }

private def scaffoldTest(args: List[String], ctx: CommandContext): CmdResult =
  args.headOption match
    case None => CmdResult.UsageError("Usage: scalex scaffold test <class> [--framework munit|scalatest|zio-test]")
    case Some(className) =>
      val defs = filterSymbols(ctx.idx.findDefinition(className).filter(s => typeKinds.contains(s.kind)), ctx)
      if defs.isEmpty then
        CmdResult.NotFound(
          s"""No class/trait/object/enum "$className" found""",
          mkNotFoundWithSuggestions(className, ctx, "scaffold"))
      else
        val sym = defs.head
        val members = extractMembers(sym.file, sym.name, Some(sym.kind))
          .filter(m => m.kind == SymbolKind.Def)
        CmdResult.ScaffoldTest(sym.name, sym.file, sym.line, sym.packageName, members, ctx.framework)
