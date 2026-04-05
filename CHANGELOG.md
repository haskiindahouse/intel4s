# Changelog

All notable changes to agent4s are documented here.

## [0.5.0] — 2026-03-31

### Added

- **`/agent4s:submit` feedback skill** — structured community feedback loop. Users report false positives, missing patterns, empty results, wrong results, performance issues, or feature requests. Anonymized metadata only (version, file count, symbol count — no source code or file paths). Submits via `gh issue create` to the agent4s repo with structured labels, or falls back to formatted markdown for manual submission
- **Homebrew formula** — `brew install scala-digest/tap/agent4s` for macOS (arm64/x64) and Linux (x64). Includes `scripts/update-homebrew.sh` for release automation
- **MCP quick-start templates** — copy-paste configs for Cursor, Windsurf, Cline, and generic MCP clients in README
- **Feedback triage workflow** — GitHub Actions bot runs weekly, aggregates `/submit` reports by pattern. When 3+ users report the same issue → auto-creates `[pattern-review]` meta-issue. When meta-issue is closed → bot comments on all linked reports and closes them ("your feedback improved agent4s")
- **GitHub Action** — `scala-digest/agent4s-action@v1` composite action for CI. Runs bug-hunt, unused, or any agent4s command on PRs. Auto-downloads binary, posts results to Job Summary, supports fail-on-findings gate
- **Windows x64 native image** — GraalVM native image for Windows added to release workflow. 4 platforms: macOS arm64, macOS x64, Linux x64, Windows x64
- **Regex-based credential detection** in `bug-hunt` — 16 high-confidence patterns (AWS access tokens, GitHub PATs, Anthropic/OpenAI API keys, private keys, Slack tokens, Stripe keys, GitLab PATs, npm/PyPI tokens, etc.) scanned against string literal values. Complements existing variable-name detection (`HardcodedSecret`). Ported from gitleaks rules via Claude Code's secretScanner
- **Structured MCP error responses** — `_meta.errorCategory` field in tool results for programmatic error handling. Categories: `not_found`, `usage_error`, `timeout`, `parse_error`, `internal_error`. MCP clients can now distinguish error types without parsing message strings
- **Rename dry-run mode** — `rename` now shows a preview by default. Pass `--apply` to write changes to disk. Includes file staleness protection: compares expected line content against current disk state before writing, skips files modified since indexing (TOCTOU protection)
- Extended `isPlaceholder()` — additional placeholder patterns reduce false positives in secret detection (`"your-key-here"`, `"example"`, `"^[x*]+$"`, etc.)

## [0.4.0] — 2026-03-28

### Added

- **7 refactoring skills** inspired by the impeccable plugin's dimensional approach:
  - `/agent4s:audit` — full codebase audit across 8 dimensions (security, dead code, complexity, test coverage, architecture, style, error handling, dependencies)
  - `/agent4s:critique` — architecture evaluation (type hierarchy, coupling, cohesion, effect system, API surface, pattern consistency, testability)
  - `/agent4s:polish` — final quality pass (naming, imports, docs, method signatures, redundancy, type safety, consistency)
  - `/agent4s:normalize` — discover project conventions, find deviations, align code to majority patterns
  - `/agent4s:harden` — production hardening (input validation, timeouts, retries, resource safety, null safety, concurrency, graceful degradation)
  - `/agent4s:simplify` — reduce complexity (dead code removal, flatten inheritance, extract methods, remove abstractions, inline delegation)
  - `/agent4s:extract` — identify duplication, extract reusable components (traits, type classes, utilities, modules). Rule of three enforced
- **End-to-end pipeline**: setup → explore → audit → bug-hunt → critique → harden → simplify → normalize → extract → polish → verify
- Total plugin skills: 13. Total with agent: 14 components

## [0.3.0] — 2026-03-28

### Added

- **`bug-hunt` command** — AST-based vulnerability scanner with 45 patterns across 7 categories (security, type safety, concurrency, effects, resources, crypto, XML). Bloom filter pre-screening, parallel scan, hotspot ranking (complexity × git churn). Supports `--severity`, `--bug-category`, `--hotspots`, `--no-taint`, `--json`
- **Full taint analysis** — on by default. Constant propagation (suppresses literal-derived sinks), intraprocedural backward slice (traces taint from HTTP params to SQL/exec/file sinks), cross-file analysis via scalex index (max 3 hops), sanitizer detection, conditional guard detection, string/collection propagation rules, parameter taint inference, multi-factor confidence scoring
- **45 bug patterns**: SQL injection, XSS, SSRF, XXE, command injection, path traversal, open redirect, regex DoS, LDAP injection, insecure deserialization, Jackson enableDefaultTyping, hardcoded secrets, weak hash (MD5/SHA1), weak cipher (DES/RC4), weak random, hardcoded IV, ECB mode, NoPadding, log injection, ZIO.die, unsafeRun, blocking in effect, ignored Future, Future.onComplete, .get on Option, .head/.last/.tail on collection, asInstanceOf, null literal, return in lambda, throw in ZIO.succeed, Await.result(Duration.Inf), Thread.sleep, sender() in Future, nested synchronized, var+Future, mutable shared, Try.get, partial match, resource leaks (Source/Stream/Connection)
- **Smart false positive reduction**: ZIO Ref.get vs Option.get, tapir endpoint builders, assertion context suppression, effect chains (.get.map), for-comprehension bindings
- **`/agent4s:bug-hunt` skill** — orchestrates: scan → LLM triage → GitHub issues cross-reference (optional) → reproduction generation → structured report

## [0.2.0] — 2026-03-27

### Added

- **`/agent4s:setup` skill** — one-time project onboarding; detects build tool, runs scalex overview, writes Scala Code Intelligence section to CLAUDE.md with available skills, SemanticDB status, and usage guidance
- **`/agent4s:semanticdb` skill** — enables SemanticDB in build config (sbt/mill/scala-cli/gradle); detects Scala version, applies the right setting, compiles, and verifies `.semanticdb` files were generated
- **`/agent4s:upgrade` skill** — upgrades scalex binary to latest GitHub release; clears cache and re-downloads
- **`/agent4s:doctor` skill** — diagnostic check: binary version, index health, SemanticDB availability, CLAUDE.md configuration, macOS quarantine
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
