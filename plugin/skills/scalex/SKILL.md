---
name: scalex
description: Scala code intelligence CLI for navigating Scala 3 codebases. Use this skill whenever you're working in a project with .scala files and need to understand code structure — finding where a class/trait/object is defined, who extends a trait, who uses or imports a symbol, what's in a file, or exploring packages. Trigger on any Scala navigation task like "where is X defined", "who implements Y", "find usages of Z", "what traits exist", or when you need to understand impact before renaming/refactoring. Also use proactively when exploring an unfamiliar Scala codebase — scalex is much faster and more structured than grep for Scala-specific queries. Always prefer scalex over grep/glob for Scala symbol lookups.
---

You have access to `scalex`, a Scala code intelligence CLI that understands Scala 3 syntax (classes, traits, objects, enums, givens, extensions, type aliases). It parses source files via Scalameta and builds a persistent index — no compiler or build server needed.

First run on a project takes ~3s to index. After that, queries run in ~300ms because unchanged files are skipped via git OID caching.

## Setup

Before first use, check if scalex is available and install if needed. Install to `~/.local/bin` (no sudo required):

```bash
which scalex || {
  mkdir -p ~/.local/bin
  OS="$(uname -s)"; ARCH="$(uname -m)"
  case "$OS-$ARCH" in
    Darwin-arm64)  ARTIFACT="scalex-macos-arm64" ;;
    Darwin-x86_64) ARTIFACT="scalex-macos-x64" ;;
    Linux-x86_64)  ARTIFACT="scalex-linux-x64" ;;
  esac
  curl -fsSL "https://github.com/nguyenyou/scalex/releases/latest/download/$ARTIFACT" -o ~/.local/bin/scalex
  chmod +x ~/.local/bin/scalex
}
```

If `scalex` is still not found after install (not on PATH), use the full path: `~/.local/bin/scalex` instead of `scalex` for all commands below.

## Commands

All commands default to current directory. You can pass an explicit workspace path as the first argument after the command (e.g., `scalex def /path/to/project MyTrait`). Every command auto-indexes on first run.

### `scalex def <symbol> [--verbose]` — find definition

Returns where a symbol is defined, including given instances that grep would miss. Use `--verbose` to see the full signature inline — saves a follow-up Read call.

```bash
scalex def PaymentService --verbose
```
```
  trait     PaymentService (com.example.payment) — .../PaymentService.scala:16
             trait PaymentService
  given     paymentService (com.example.module) — .../ServiceModule.scala:185
             given paymentService: PaymentService
```

### `scalex impl <trait> [--verbose] [--limit N]` — find implementations

Finds all classes/objects/enums that extend or mix in a trait. Much more targeted than `refs` when you need to find concrete implementations.

```bash
scalex impl PaymentService --verbose
```
```
  class     PaymentServiceLive — .../PaymentServiceLive.scala:43
             class PaymentServiceLive extends PaymentService
```

### `scalex refs <symbol> [--categorize] [--limit N]` — find references

Finds all usages of a symbol with word-boundary matching. Use `--categorize` before refactoring — it groups results into Definition, ExtendedBy, ImportedBy, UsedAsType, Usage, and Comment so you can understand impact at a glance.

```bash
scalex refs PaymentService --categorize
```
```
  Definition:
    .../PaymentService.scala:16 — trait PaymentService {
  ExtendedBy:
    .../PaymentServiceLive.scala:54 — ) extends PaymentService {
  ImportedBy:
    .../ServiceModule.scala:8 — import com.example.payment.{PaymentService, ...}
  UsedAsType:
    .../AppModule.scala:68 — def paymentService: PaymentService
  Comment:
    .../PaymentServiceLive.scala:38 — /** Live implementation of PaymentService ...
```

Without `--categorize`, returns a flat list (faster for simple lookups).

### `scalex imports <symbol> [--limit N]` — import graph

Returns only import statements for a symbol. Use when you need to know which files depend on something — cleaner than `refs` for dependency analysis.

```bash
scalex imports PaymentService
```

### `scalex search <query> [--kind K] [--verbose] [--limit N]` — search symbols

Fuzzy search by name, ranked: exact → prefix → contains. Use `--kind` to filter by symbol type (class, trait, object, def, val, type, enum, given).

```bash
scalex search Service --kind trait --limit 10
```

### `scalex symbols <file> [--verbose]` — file symbols

Lists everything defined in a file. Use `--verbose` to see signatures.

```bash
scalex symbols src/main/scala/com/example/Service.scala --verbose
```

### `scalex packages` — list packages

```bash
scalex packages
```

### `scalex batch` — multiple queries, one index load

Reads queries from stdin, loads index once. Use when you need several lookups — runs 5 queries in ~1s instead of ~5s.

```bash
echo -e "def UserService\nimpl UserService\nimports UserService" | scalex batch
```

### `scalex index` — force reindex

Normally not needed — every command auto-reindexes. Use after major branch switches or large merges.

## Options

| Flag | Effect |
|---|---|
| `--verbose` | Show signatures, extends clauses, param types |
| `--categorize` | Group refs into Definition/ExtendedBy/ImportedBy/UsedAsType/Comment/Usage |
| `--limit N` | Max results (default: 20) |
| `--kind K` | Filter search: class, trait, object, def, val, type, enum, given |

## Why scalex over grep

scalex understands Scala 3 syntax. It finds `given` definitions, `enum` declarations, and `extension` groups that grep patterns miss. It returns structured output with symbol kind, package name, and line numbers. For any Scala-specific navigation, prefer scalex — it's purpose-built for exactly this.
