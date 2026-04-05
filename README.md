<p align="center">
  <img src="docs/vulture.png" alt="agent4s" width="200">
  <br>
  <strong>agent4s</strong>
  <br><br>
  <em>Scala superpowers for AI coding agents.</em>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="MIT License"></a>
  <a href="#all-37-commands"><img src="https://img.shields.io/badge/commands-37-brightgreen.svg" alt="37 Commands"></a>
</p>

---

Your AI agent treats Scala like plain text. `grep` finds 50 things named `Config`. agent4s finds the one you mean.

**37 commands** for code navigation, refactoring, dead code detection, and bug hunting. No build server. No compilation. From `git clone` to first answer in 349ms.

```bash
# Claude Code
claude plugins install agent4s

# Homebrew (macOS/Linux)
brew install scala-digest/tap/agent4s
```

---

## Why

AI coding agents have three bad options for Scala:

| Option | Problem |
|---|---|
| **grep** | Returns raw text. `class Config` matches `ConfigStore`, `ConfigParser`. Two packages with `Config`? Hits both. |
| **Metals LSP** | Requires build server, full compilation, minutes of startup. Designed for humans in IDEs, not agents making 50 tool calls per task. |
| **The AI model itself** | Reads source files well, but can't trace inheritance trees, find dead code, or scan 18K files for bug patterns in 3 seconds. |

agent4s is the fourth option: **instant structured code intelligence from parsed ASTs**. Works without a build. Gets smarter when you compile (`--semantic`).

---

## 5 things only agent4s can do

**Find bugs without compiling.** 45 AST patterns with cross-file taint analysis. SQL injection, XSS, `.get` on Option, `null`, weak crypto, ZIO anti-patterns. No other tool does this for Scala without a build server.
```bash
agent4s bug-hunt --severity critical --no-tests
agent4s bug-hunt --hotspots                        # rank by findings x git churn
```

**Rename the right `Config`.** Two classes named `Config` in different packages? `--semantic` renames only the one you mean.
```bash
agent4s rename Config AppConfig --semantic
```

**Find dead code in seconds.** Bloom filter pre-screening across every file. If no file's bloom filter contains the symbol name — it has zero external references.
```bash
agent4s unused com.legacy --kind class
```

**Trace call chains.** What does `processPayment` call? Who calls it? Bidirectional call graph from parsed method bodies.
```bash
agent4s call-graph processPayment --in PaymentService
```

**Understand a codebase in one command.** Packages, hub types, dependency graph, architecture — all in 60 lines.
```bash
agent4s overview --concise
```

---

## Quick Start

### Claude Code

```bash
claude plugins install agent4s
```

Then in your project:
```
/agent4s:setup
```

14 skills available: `/agent4s:bug-hunt`, `/agent4s:audit`, `/agent4s:critique`, `/agent4s:harden`, `/agent4s:simplify`, `/agent4s:normalize`, `/agent4s:extract`, `/agent4s:polish`, `/agent4s:setup`, `/agent4s:semanticdb`, `/agent4s:doctor`, `/agent4s:upgrade`, `/agent4s:submit`. Plus `scala-expert` agent for multi-step tasks.

### MCP Server

37 commands exposed as MCP tools. In-memory index caching between calls. Copy-paste the config for your editor:

<details>
<summary><strong>Cursor</strong> — <code>.cursor/mcp.json</code></summary>

```json
{
  "mcpServers": {
    "agent4s": {
      "command": "agent4s",
      "args": ["mcp"]
    }
  }
}
```
</details>

<details>
<summary><strong>Windsurf</strong> — <code>~/.codeium/windsurf/mcp_config.json</code></summary>

```json
{
  "mcpServers": {
    "agent4s": {
      "command": "agent4s",
      "args": ["mcp"]
    }
  }
}
```
</details>

<details>
<summary><strong>Cline</strong> — <code>~/.cline/mcp_settings.json</code></summary>

```json
{
  "mcpServers": {
    "agent4s": {
      "command": "agent4s",
      "args": ["mcp"],
      "disabled": false
    }
  }
}
```
</details>

<details>
<summary><strong>Generic MCP client</strong></summary>

```json
{
  "mcpServers": {
    "agent4s": {
      "type": "stdio",
      "command": "agent4s",
      "args": ["mcp"]
    }
  }
}
```
</details>

If you installed via Homebrew, `agent4s` is already on your PATH. Otherwise replace `agent4s` with the full binary path.

### GitHub Action (CI)

```yaml
- uses: scala-digest/agent4s-action@v1
  with:
    command: bug-hunt
    args: '--severity high --no-tests'
```

See [action/README.md](action/README.md) for full options (unused code checks, fail gates, multi-command setups).

### CLI

```bash
git clone https://github.com/scala-digest/agent4s.git
cd agent4s && ./build-native.sh

# Or run without building
scala-cli run src/ -- search /path/to/project MyClass
```

---

## bug-hunt

Static analysis for Scala that doesn't need your build to compile.

**45 patterns** across 7 categories:

| Category | Patterns |
|---|---|
| **Security** | SQL injection, XSS, SSRF, XXE, command injection, path traversal, weak crypto, hardcoded secrets, open redirect, regex DoS, LDAP injection, insecure deserialization, log injection |
| **Type safety** | `.get` on Option, `.head`/`.last` on collection, `asInstanceOf`, `null`, `return` in lambda |
| **Concurrency** | `Await.result(Duration.Inf)`, `Thread.sleep`, nested `synchronized`, `sender()` in Future, `var` + Future |
| **Effects** | `throw` in `ZIO.succeed`, `ZIO.die`, `unsafeRun`, blocking in effect |
| **Resources** | Unclosed `Source`, `Stream`, `Connection` |
| **Crypto** | Weak hash (MD5/SHA1), weak cipher (DES/RC4), weak random, hardcoded IV, ECB mode |
| **Credentials** | 16 regex patterns: AWS, GitHub, Anthropic, OpenAI, Slack, Stripe, private keys |

**Taint analysis** (on by default): traces variable assignments backward from sinks to sources. HTTP parameter flows to SQL query? Flagged with the full flow chain. Literal-derived sinks? Suppressed. Cross-file tracing up to 3 hops.

```bash
agent4s bug-hunt -w /path/to/project              # all patterns
agent4s bug-hunt --severity critical --no-tests    # critical only, production code
agent4s bug-hunt --hotspots                        # files ranked by findings x git churn
agent4s bug-hunt --json                            # structured output for CI
```

---

## Benchmarks

Native GraalVM binary, Apple Silicon M3 Max. Full methodology: [docs/BENCHMARK.md](docs/BENCHMARK.md).

| Project | Files | Symbols | Cold Index | Warm Index |
|---|---|---|---|---|
| Scala 3 compiler | 18,703 | 148,179 | 2.7s | 349ms |

### agent4s vs grep — real numbers on scala3 compiler

| Task | agent4s | grep |
|---|---|---|
| Who imports `Compiler`? | **1,213 files** (resolves `import dotty.tools.*`) | 86 files (literal match only) |
| Inheritance tree | 7 subclasses, 3 levels deep | Not possible |
| Dead code in `dotty.tools.dotc` | **113 classes** with zero external refs | Not possible |
| Bug patterns | **30 findings** in 16 files (MD5 hash, deadlocks, ReDoS) | Regex hacks, high false positives |
| Project overview | Packages, hub types, dep graph in 60 lines | Not possible |
| Rename `Config` (2 packages) | `--semantic` renames only the right one | Renames both |

Use grep for: string literals, config values, non-Scala files.

---

## Commands

### Search & Navigate

```bash
agent4s search Service --kind trait         # fuzzy camelCase search
agent4s def UserService --verbose           # find definition with signature
agent4s explain UserService --related       # definition + doc + members + impls in one call
agent4s hierarchy Compiler --depth 3        # inheritance tree from parsed extends clauses
agent4s members Signal --inherited          # members including parents
```

### Refactor

```bash
agent4s rename OldName NewName              # text-based, word-boundary safe
agent4s rename OldName NewName --semantic   # type-aware via SemanticDB
agent4s scaffold impl MyServiceLive         # generate override stubs with type param substitution
agent4s scaffold test MyService             # test skeleton (munit/scalatest/zio-test)
```

### Analyze

```bash
agent4s refs UserService --count            # how many files reference this symbol
agent4s call-graph processPayment --in Svc  # what it calls + who calls it
agent4s unused com.legacy                   # symbols with zero external refs
agent4s coverage UserService                # references in test files only
agent4s deps Phase --depth 2               # what this symbol depends on
```

### Explore

```bash
agent4s overview --concise                  # project summary: packages, key types, stats
agent4s api com.example --used-by com.web   # coupling between packages
agent4s diff HEAD~5                         # which symbols changed vs a git ref
agent4s ast-pattern --extends Phase --has-method run  # structural search by shape
agent4s grep "pattern" --in ClassName --each-method   # regex scoped to a type's methods
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

## How It Works

```
1. git ls-files --stage       → tracked .scala/.java files + content hashes
2. Compare OIDs vs cache      → skip unchanged files
3. Scalameta parse (parallel) → AST → symbols, bloom filters, imports
4. .scalex/index.bin          → binary cache with string interning
5. Answer the query            → lazy indexes built on demand
6. [Optional] SemanticDB       → .semanticdb files from compiler for type-aware mode
```

No build server. No daemon. Run, answer, exit. Works on any Scala project from `git clone`.

---

## Limitations

agent4s parses source text into ASTs — it does not compile. This makes it fast and dependency-free, but:

- **No type inference.** We don't know what type `x` has unless it's annotated.
- **No implicit resolution.** Can't find which given instance the compiler would select.
- **No macro expansion.** Macro-generated code is invisible.
- **refs is text-based.** `refs Config` finds all things named Config. Use `--semantic` for disambiguation.
- **Taint analysis is heuristic.** Traces variable names, not types. The LLM triage in `/agent4s:bug-hunt` helps filter false positives.

For full semantic precision: compile with `-Xsemanticdb` and use `--semantic` flag.

---

## Credits

Built on [scalex](https://github.com/nguyenyou/scalex) by Tu Nguyen. MIT licensed.

- [Scalameta](https://scalameta.org/) — AST parsing and SemanticDB format
- [Metals](https://scalameta.org/metals/) — inspiration for git OID caching, bloom filter search
- [ascii-graphs](https://github.com/scalameta/ascii-graphs) — Sugiyama-style graph layout (ported to Scala 3.8)

---

## License

MIT
