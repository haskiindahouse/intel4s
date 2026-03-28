<p align="center">
  <img src="docs/vulture.png" alt="intel4s" width="200">
  <br>
  <strong>intel4s</strong>
  <br><br>
  <em>Scalameta-based code index for AI agents. Text-based by default, type-aware with SemanticDB.</em>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="MIT License"></a>
  <a href="#commands"><img src="https://img.shields.io/badge/commands-37-brightgreen.svg" alt="37 Commands"></a>
</p>

---

## What this is

A CLI that parses Scala source files via [Scalameta](https://scalameta.org/), builds an in-memory symbol index, and answers structural queries. No build server, no compilation, no daemon. Designed for AI coding agents that need structured code navigation instead of grep.

**What it does well** (without types):
- Symbol search, definition lookup, hierarchy walking — from parsed ASTs
- `refs` / `imports` — bloom filter pre-screening + word-boundary text matching. Not type-aware, but finds references that grep misses (wildcard imports, alias imports)
- `unused` — if no file's bloom filter contains the symbol name, it has zero external references
- `call-graph` — parses method body, finds method calls by identifier name in the AST. Can't resolve overloads or virtual dispatch
- `bug-hunt` — AST pattern matching for 45 known-bad patterns (`.get` on Option, `null`, `asInstanceOf`, SQL injection patterns, weak crypto, etc)

**What it does better** (with `-Xsemanticdb`):
- `rename --semantic` reads `.semanticdb` files from the compiler. Two `Config` in different packages? Only renames the right one
- `call-graph --semantic` uses compiler-produced occurrence data for precise callees
- `refs --semantic` resolves same-named symbols by package

**What it can't do:**
- Resolve implicits, type aliases, or macro-generated code
- Infer types (we parse source, we don't compile it)
- Distinguish overloaded methods without SemanticDB
- Full data flow analysis in the compiler sense — our taint tracking is heuristic-based (variable name patterns + backward tracing through assignments)

---

## Quick Start

### Claude Code

```bash
claude plugins install intel4s
```

Then in your project:
```
/intel4s:setup
```

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

### CLI

```bash
git clone https://github.com/haskiindahouse/intel4s.git
cd intel4s && ./build-native.sh

# Or run without building
scala-cli run src/ -- search /path/to/project MyClass
```

---

## Commands

### Search & Navigate

```bash
intel4s search Service --kind trait         # fuzzy camelCase search
intel4s def UserService --verbose           # find definition with signature
intel4s explain UserService --related       # definition + doc + members + impls in one call
intel4s hierarchy Compiler --depth 3        # inheritance tree from parsed extends clauses
intel4s members Signal --inherited          # members including parents
```

### Refactor

```bash
intel4s rename OldName NewName              # text-based, word-boundary safe
intel4s rename OldName NewName --semantic   # type-aware via SemanticDB (requires compilation)
intel4s scaffold impl MyServiceLive         # generate override stubs with type param substitution
intel4s scaffold test MyService             # test skeleton (munit/scalatest/zio-test)
```

### Analyze

```bash
intel4s refs UserService --count            # how many files reference this symbol
intel4s call-graph processPayment --in Svc  # what it calls + who calls it (by name matching)
intel4s unused com.legacy                   # symbols with zero external refs (bloom pre-screen + text verify)
intel4s bug-hunt --hotspots                 # 45 AST patterns + taint heuristics + git churn ranking
intel4s bug-hunt --severity critical        # only critical patterns (SQL injection, deserialization)
intel4s coverage UserService                # references in test files only
intel4s deps Phase --depth 2               # what this symbol depends on (imports + body refs)
```

### Explore

```bash
intel4s overview --concise                  # project summary: packages, key types, stats
intel4s api com.example --used-by com.web   # which symbols are imported by another package
intel4s diff HEAD~5                         # which symbols changed vs a git ref
intel4s ast-pattern --extends Phase --has-method run  # structural search by shape
intel4s grep "pattern" --in ClassName --each-method   # regex scoped to a type's methods
```

<details>
<summary><strong>All 37 commands</strong></summary>

```
search          Fuzzy camelCase symbol search
def             Find where a symbol is defined
impl            Find classes/objects extending a trait
refs            Find references (text matching + bloom filters)
imports         Find import statements for a symbol
members         List members of a class/trait/object
doc             Extract scaladoc comment
explain         Definition + doc + members + impls in one call
body            Extract method/class source text
hierarchy       Inheritance tree from extends clauses
overrides       Find override implementations across types
deps            Import + body dependencies of a symbol
context         Enclosing scopes at a file:line
diff            Symbol-level diff vs a git ref
coverage        References in test files only
tests           List test cases structurally
ast-pattern     Search by structural shape (extends + has-method + body-contains)
overview        Project summary (symbols by kind, top packages)
api             Externally-imported symbols of a package
summary         Sub-packages with symbol counts
packages        List all packages
package         All symbols in a package
file            Find files by name (fuzzy)
symbols         What's defined in a file
annotated       Find symbols with a specific annotation
entrypoints     Find @main, def main, extends App, test suites
grep            Regex search scoped to Scala/Java files
index           Force reindex
batch           Multiple queries, one index load (stdin)
rename          Word-boundary rename (text or semantic)
unused          Symbols with zero external references
call-graph      Callees (from body) + callers (from refs) of a method
bug-hunt        45 AST patterns + taint analysis + hotspot ranking
scaffold impl   Generate override stubs for unimplemented members
scaffold test   Generate test suite skeleton
graph           ASCII/Unicode directed graph rendering
mcp             Start MCP server (JSON-RPC over stdio)
```

</details>

All commands support `--json`, `--path`, `--no-tests`, `--in-package`, `--limit`.

---

## bug-hunt

AST pattern scanner with heuristic taint analysis. Finds common bug patterns without compilation.

**45 patterns** across 7 categories: security (SQL injection, XSS, SSRF, XXE, command injection, path traversal, weak crypto, hardcoded secrets), type safety (.get, .head, asInstanceOf, null, return in lambda), concurrency (Await.result with Duration.Inf, Thread.sleep, nested synchronized, sender() in Future), effects (throw in ZIO.succeed, ZIO.die, unsafeRun), resources (unclosed streams/connections).

**Taint analysis** (on by default): traces variable assignments backward from sinks to sources within a single method body. If all sink arguments are derived from literals → suppressed. If an argument traces back to an HTTP parameter → enriched with flow chain. Cross-file tracing is limited (checks if callee body directly contains a source, max 3 hops). Not compiler-grade data flow — it's variable name heuristics + AST backward tracing, not type-based.

```bash
intel4s bug-hunt -w /path/to/project              # all patterns, taint on
intel4s bug-hunt --severity critical --no-tests    # critical only, production code
intel4s bug-hunt --hotspots                        # rank files by findings × git churn
intel4s bug-hunt --no-taint                        # pattern-only mode (faster)
intel4s bug-hunt --json                            # structured output for tooling
```

---

## Plugin Skills (Claude Code)

13 skills + 1 agent for automated workflows:

| Skill | What it does |
|---|---|
| `/intel4s:setup` | Detect build tool, run overview, write CLAUDE.md section |
| `/intel4s:semanticdb` | Add `-Xsemanticdb` to build config, compile, verify |
| `/intel4s:doctor` | Check binary, index, SemanticDB, CLAUDE.md status |
| `/intel4s:upgrade` | Upgrade binary to latest release |
| `/intel4s:bug-hunt` | Scan → LLM triage → GitHub issues cross-ref → reproduction |
| `/intel4s:audit` | Full codebase audit across 8 dimensions |
| `/intel4s:critique` | Architecture evaluation (coupling, cohesion, hierarchy) |
| `/intel4s:harden` | Add validation, timeouts, retries, error handling |
| `/intel4s:simplify` | Remove dead code, flatten complexity, inline indirection |
| `/intel4s:normalize` | Discover project conventions, align deviating code |
| `/intel4s:extract` | Find duplication, extract reusable traits/utilities |
| `/intel4s:polish` | Final pass: naming, imports, docs, consistency |
| `scala-expert` | Agent auto-invoked for multi-step tasks (3+ commands) |

---

## Benchmarks

| Project | Files | Symbols | Cold Index | Warm Index |
|---|---|---|---|---|
| Production monorepo | 14,219 | 170,094 | 5.3s | 445ms |
| Scala 3 compiler | 18,485 | 144,211 | 2.7s | 349ms |

### vs grep

| Task | intel4s | grep |
|---|---|---|
| Who imports Compiler? | 1,206 files (resolves wildcard imports) | 17 files (literal match only) |
| Inheritance tree | Complete from parsed extends clauses | Not possible |
| Rename Config (2 packages) | `--semantic` renames only the right one | Renames both (no disambiguation) |

Use grep for: string literals, config values, non-Scala files.

---

## How It Works

```
1. git ls-files --stage       → tracked .scala/.java files + content hashes
2. Compare OIDs vs cache      → skip unchanged files
3. Scalameta parse (parallel) → AST → symbols, bloom filters, imports
4. .intel4s/index.bin          → binary cache with string interning
5. Answer the query            → lazy indexes
6. [Optional] SemanticDB       → .semanticdb files from compiler for type-aware mode
```

No build server. No daemon. Run, answer, exit.

---

## Limitations

- **No type inference.** We parse source text into ASTs. We don't know what type `x` has unless it's annotated.
- **No implicit resolution.** Can't find given instances that the compiler would select.
- **No macro expansion.** Macro-generated code is invisible.
- **refs is text-based.** `refs Config` finds all things named Config across all packages. Use `--semantic` for type-aware disambiguation.
- **Taint analysis is heuristic.** We trace variable names, not types. False positives exist. The LLM triage step in `/intel4s:bug-hunt` skill helps filter them.
- **call-graph without --semantic** matches method names, not resolved dispatch targets.

For full semantic precision, compile with `-Xsemanticdb` and use `--semantic` flag.

---

## Credits

Built on [scalex](https://github.com/nguyenyou/scalex) by Tu Nguyen. MIT licensed.

- [Scalameta](https://scalameta.org/) — AST parsing and SemanticDB format
- [Metals](https://scalameta.org/metals/) — inspiration for git OID caching, bloom filter search
- [ascii-graphs](https://github.com/scalameta/ascii-graphs) — Sugiyama-style graph layout (ported to Scala 3.8)

---

## License

MIT
