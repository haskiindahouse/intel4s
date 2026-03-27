<p align="center">
  <img src="site/vulture.png" alt="intel4s" width="256">
  <br>
  <strong style="font-size: 2.5em;">intel4s</strong>
  <br><br>
  <em>Code intelligence for Scala. Works without a build. Gets smarter when you compile.</em>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="MIT License"></a>
  <a href="#commands"><img src="https://img.shields.io/badge/commands-36-brightgreen.svg" alt="36 Commands"></a>
  <a href="#benchmarks"><img src="https://img.shields.io/badge/tests-528_passing-brightgreen.svg" alt="528 Tests"></a>
  <a href="#semantic-mode"><img src="https://img.shields.io/badge/SemanticDB-hybrid-blueviolet.svg" alt="SemanticDB Hybrid"></a>
</p>

<p align="center">
  <a href="#quick-start">Quick Start</a> &middot;
  <a href="#semantic-mode">Semantic Mode</a> &middot;
  <a href="#commands">Commands</a> &middot;
  <a href="#benchmarks">Benchmarks</a>
</p>

---

Like [Metals](https://scalameta.org/metals/), but without the build server. Like `grep`, but it understands Scala. Like `ctags`, but built for AI agents.

- **Hybrid intelligence.** Text-based by default — no build needed. Add `-Xsemanticdb` to your compiler and get type-aware refs, rename, and call-graph. Works in both modes, automatically.
- **Semantic rename.** Two classes named `Config` in different packages? `rename --semantic` only renames the right one. grep can't. Even IntelliJ sometimes gets this wrong.
- **36 commands, one tool call each.** `explain` returns definition + docs + members + implementations in one shot. Saves 4-5 round trips for AI agents.
- **MCP native.** Works with Cursor, Windsurf, Cline, Claude Code out of the box. One config line.
- **Dead code detection.** `unused` finds symbols with zero external references. Bloom filter pre-screen + text verification. No compiler needed.
- **Call graph.** `call-graph` shows what a method calls and who calls it. Bidirectional, in one command.
- **Scaffold.** Generate implementation stubs with type parameter substitution. `scaffold impl MyService` resolves `T` to `User` automatically.

---

## Semantic Mode

The killer feature. Most Scala tools are either fast-but-dumb (grep) or smart-but-heavy (Metals LSP). intel4s is both.

**Without compilation** (always works):
```bash
intel4s refs Config          # text-based, word-boundary matching
intel4s rename Config Cfg    # renames ALL symbols named Config
```

**With compilation** (`-Xsemanticdb`):
```bash
intel4s refs Config --semantic       # type-aware, package-precise
intel4s rename Config Cfg --semantic  # only renames app.Config, not db.Config
```

```
$ intel4s rename Config ServerConfig --semantic

2 symbols named 'Config' found. Renaming only:
  app/Config#
Untouched:
  db/Config# (db)
Using semantic rename (42 compiled files) — type-aware, precise

Rename "Config" → "ServerConfig" — 4 edits in 2 files:

  src/App.scala
    L3 [definition]  case class Config(host: String, port: Int)
       →  case class ServerConfig(host: String, port: Int)
    L5 [usage]  class Server(config: Config) {
       →  class Server(config: ServerConfig) {
```

`db.Config` is untouched. grep would have broken it.

Enable SemanticDB in your build:
```scala
// Scala 3 (scalacOptions)
"-Xsemanticdb"

// sbt
semanticdbEnabled := true

// Metals: automatic — already enabled
```

---

## Quick Start

### MCP Server (Cursor, Windsurf, Cline)

```json
{
  "mcpServers": {
    "intel4s": {
      "type": "stdio",
      "command": "/path/to/intel4s",
      "args": ["mcp"]
    }
  }
}
```

Index stays in memory between tool calls. Every command becomes an MCP tool.

### Claude Code

```bash
/plugin install intel4s
```

> *"use intel4s to explore how authentication works in this codebase"*

### CLI

```bash
# Build from source (scala-cli + GraalVM)
./build-native.sh

# Or run directly
scala-cli run src/ -- search /path/to/project MyClass
```

---

## Commands

### Search & Navigate

```bash
intel4s search Service --kind trait         # fuzzy camelCase search
intel4s def UserService --verbose           # find definition
intel4s explain UserService --related       # one-shot: def + doc + members + impls
intel4s hierarchy Compiler --depth 3        # full inheritance tree
intel4s members Signal --inherited          # API surface including parents
```

### Refactor

```bash
intel4s rename OldName NewName              # text-based rename (fast)
intel4s rename OldName NewName --semantic   # type-aware rename (precise)
intel4s scaffold impl MyServiceLive         # generate stubs with type param substitution
intel4s scaffold test MyService             # test skeleton (munit/scalatest/zio-test)
```

### Analyze

```bash
intel4s refs UserService --count            # impact: "12 importers, 4 extensions"
intel4s refs UserService --semantic         # type-aware references
intel4s call-graph processPayment --in Svc  # what it calls + who calls it
intel4s call-graph processPayment --semantic --in Svc  # compiler-precise callees
intel4s unused com.legacy                   # dead code detection
intel4s coverage UserService                # is this tested?
intel4s deps Phase --depth 2                # transitive dependencies
```

### Explore

```bash
intel4s overview --concise                  # ~60-line codebase summary
intel4s api com.example --used-by com.web   # coupling analysis
intel4s diff HEAD~5                         # symbol-level diff vs git ref
intel4s ast-pattern --extends Phase --has-method run  # structural search
intel4s grep "pattern" --in ClassName --each-method   # scoped grep
```

### Generate

```bash
intel4s scaffold impl UserServiceLive       # override stubs with T → User
intel4s scaffold test UserService --framework zio-test
intel4s graph --render "A->B, B->C"         # ASCII graph art
```

<details>
<summary><strong>All 36 commands</strong></summary>

```
search          Search symbols by name (fuzzy camelCase)
def             Find definition
impl            Find implementations
refs            Find references (text or semantic)
imports         Import graph (wildcard-aware)
members         List members (with --inherited)
doc             Show scaladoc
explain         One-shot composite summary
body            Extract source body
hierarchy       Full inheritance tree
overrides       Find override implementations
deps            Symbol dependencies
context         Enclosing scopes at a line
diff            Symbol-level diff vs git ref
coverage        Is this symbol tested?
tests           List test cases structurally
ast-pattern     Structural AST search
overview        Codebase summary
api             Public API surface
summary         Package breakdown
packages        List all packages
package         Explore a package
file            Find files by name
symbols         Symbols in a file
annotated       Find annotated symbols
entrypoints     Find @main, def main, extends App
grep            Regex content search
index           Rebuild the index
batch           Multiple queries, one index load
rename          Safe rename across codebase
unused          Dead code detection
call-graph      Bidirectional method call graph
scaffold impl   Generate implementation stubs
scaffold test   Generate test skeleton
graph           ASCII/Unicode graph rendering
mcp             Start MCP server
```

</details>

All commands support `--json`, `--path`, `--no-tests`, `--in-package`, `--max-output`, `--limit`.

---

## Benchmarks

| Project | Files | Symbols | Cold Index | Warm Index |
|---|---|---|---|---|
| Production monorepo | 14,219 | 170,094 | 5.3s | 445ms |
| Scala 3 compiler | 18,485 | 144,211 | 2.7s | 349ms |

### intel4s vs grep

| Task | intel4s | grep |
|---|---|---|
| Who imports Compiler? | **1,206 files** (wildcard-aware) | 17 files (misses 98.6%) |
| Full inheritance tree | Complete transitive tree | Impossible |
| Impact of changing X? | 283 refs, categorized + ranked | 1,135 lines, flat |
| Rename Config (2 packages) | `--semantic`: only the right one | Breaks both |

### When to use grep instead

| Task | Tool |
|---|---|
| String literals, error messages | grep |
| Config values, flag names | grep |
| Non-Scala files | grep |
| Everything else | **intel4s** |

---

## How It Works

```
  1. git ls-files --stage       — every tracked .scala/.java file + content hash
     │                            ~40ms for 18k files
     v
  2. Compare OIDs vs cache      — unchanged files skipped (0 changes = 0 parses)
     │
     v
  3. Scalameta parse (parallel) — source → AST → symbols, bloom filters, imports
     │
     v
  4. .intel4s/index.bin         — binary cache with string interning
     │                            loads in ~225ms for 144k+ symbols
     v
  5. Answer the query           — lazy indexes, pay only for what you need
     │
     v
  6. [Optional] SemanticDB      — if .semanticdb files exist, enhance with
                                  type-aware refs, rename, call-graph
```

No build server. No daemon. No background process. Run, answer, exit.

---

## Agent Workflows

intel4s includes a Claude Code plugin with autonomous multi-step workflows. The agent orchestrates commands without asking at each step.

**Explore unfamiliar codebase:**
`overview --concise` → `entrypoints` → `summary <pkg>` → `explain <Type> --related`

**Safe rename:**
`rename Old New --semantic` → review edits → apply → `refs Old --count` (verify: 0)

**Impact analysis:**
`refs X --count` → `call-graph method --semantic` → `hierarchy X` → `imports X`

**Implement a trait:**
`scaffold impl MyClass` → `explain ParentTrait` → `overrides method --body` → write code → `scaffold test`

**Find dead code:**
`unused com.legacy` → `refs Candidate --count` → `coverage Candidate` → remove

---

## Credits

Built on the [scalex](https://github.com/nguyenyou/scalex) codebase by Tu Nguyen. Licensed under MIT.

intel4s extends scalex with semantic rename, SemanticDB integration, call-graph analysis, dead code detection, and code scaffolding.

- [Metals](https://scalameta.org/metals/) — ideas: git OID caching, bloom filter search, parallel indexing
- [Scalameta](https://scalameta.org/) — AST parsing and SemanticDB format
- [ascii-graphs](https://github.com/scalameta/ascii-graphs) — Sugiyama-style graph layout (ported to Scala 3.8)

---

## License

MIT
