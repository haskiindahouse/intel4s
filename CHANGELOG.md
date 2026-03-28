# Changelog

All notable changes to intel4s are documented here.

## [Unreleased]

### Added

- **`bug-hunt` command** — deterministic AST-based scanner for 15 common bug patterns (SQL injection, insecure deserialization, hardcoded secrets, unsafe casts, null usage, resource leaks, ZIO/Akka concurrency issues). Bloom filter pre-screening, parallel scan, hotspot ranking (complexity × git churn). Supports `--severity`, `--bug-category`, `--hotspots`, `--json`
- **`/intel4s:bug-hunt` skill** — orchestrates full pipeline: scan → LLM triage → GitHub issues cross-reference (optional) → reproduction generation → structured report

## [0.2.0] — 2026-03-27

### Added

- **`/intel4s:setup` skill** — one-time project onboarding; detects build tool, runs scalex overview, writes Scala Code Intelligence section to CLAUDE.md with available skills, SemanticDB status, and usage guidance
- **`/intel4s:semanticdb` skill** — enables SemanticDB in build config (sbt/mill/scala-cli/gradle); detects Scala version, applies the right setting, compiles, and verifies `.semanticdb` files were generated
- **`/intel4s:upgrade` skill** — upgrades scalex binary to latest GitHub release; clears cache and re-downloads
- **`/intel4s:doctor` skill** — diagnostic check: binary version, index health, SemanticDB availability, CLAUDE.md configuration, macOS quarantine
- **`scala-expert` agent** — auto-invoked by Claude for complex multi-step Scala tasks (refactoring, impact analysis, codebase exploration); chains 3+ scalex commands using built-in workflow recipes
- Extracted shared workflow recipes to `references/workflows.md` — single source of truth for SKILL.md and scala-expert agent

## [0.1.0] — 2026-03-27

First release. Built on [scalex](https://github.com/nguyenyou/scalex) v1.38.0 with six new features.

### Added

- **SemanticDB integration** — optional type-aware mode using compiled `.semanticdb` files. When the project is compiled with `-Xsemanticdb` (Scala 3) or `semanticdbEnabled := true` (sbt), `--semantic` flag enables compiler-precise refs, rename, and call-graph. Falls back to text-based when SemanticDB is unavailable. Auto-discovers `.semanticdb` files in `target/`, `out/`, `.bloop/`, `.scala-build/`
- **`rename <Old> <New>`** — safe symbol rename across the codebase. Word-boundary aware (renaming `Repository` does not affect `UserRepository`). With `--semantic`: type-aware rename that disambiguates same-named symbols in different packages (two `Config` classes → renames only the right one)
- **`unused [package]`** — dead code detection. Finds symbols with zero external references using bloom filter pre-screening + text verification. Defaults to types (class/trait/object/enum); supports `--kind` filter
- **`call-graph <method>`** — bidirectional method call graph. Shows callees (what the method calls) and callers (who calls it). With `--semantic`: uses SemanticDB occurrences for compiler-precise callee detection instead of text matching
- **`scaffold impl <class>`** — generate `override ... = ???` stubs for unimplemented abstract members. Walks the full parent chain. Type parameter substitution: `Processor[T]` + `extends Processor[User]` → stubs use `User`, not `T`
- **`scaffold test <class>`** — generate test suite skeleton from public methods. Supports `--framework munit` (default), `scalatest`, `zio-test`
- **MCP server** — JSON-RPC over STDIO exposing all 36 commands as MCP tools. In-memory index caching between tool calls. Works with Cursor, Windsurf, Cline, and any MCP-compatible tool
- **Agent workflows in SKILL.md** — seven autonomous multi-step workflows: explore codebase, safe rename, impact analysis, implement trait, find dead code, bug investigation, refactor workflow

### Based on scalex v1.38.0

All 29 original scalex commands are included: `search`, `def`, `impl`, `refs`, `imports`, `members`, `doc`, `explain`, `body`, `hierarchy`, `overrides`, `deps`, `context`, `diff`, `coverage`, `tests`, `ast-pattern`, `overview`, `api`, `summary`, `packages`, `package`, `file`, `symbols`, `annotated`, `entrypoints`, `grep`, `index`, `batch`, `graph`.
