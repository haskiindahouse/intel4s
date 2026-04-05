# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What is Scalex

Scalex is a Scala code intelligence CLI for coding agents. It provides fast symbol search, find definitions, and find references — without requiring an IDE, build server, or compilation. Designed as a Claude Code plugin.

## Read before any work

1. This file (`CLAUDE.md`) — invariants, rules, anti-patterns
2. `docs/ARCHITECTURE.md` — system design, query flow, component diagrams
3. `MEMORY.md` (auto-loaded) — current project state, recent decisions
4. Relevant ADRs in `docs/adr/` — reasoning behind architectural choices
5. Active spec in `docs/specs/` — if working on a planned task

## IMPORTANT: No company references

NEVER mention any company names, internal project names, proprietary codebases, or organization-specific details in any output — including commit messages, PR descriptions, changelogs, roadmaps, documentation, code comments, or conversations. Always use generic examples (e.g. `HttpMessageService`, `UserServiceLive`) instead.

## Search scope

Source code lives in `src/` (production) and `tests/` (test suite). When searching for Scala code, scope searches to these directories. Avoid searching repo-wide — `benchmark/` contains ~17.7k Scala files from the scala3 compiler clone that will pollute results.

## Workflow

- **Pipeline-first**: All non-trivial tasks go through the agentic pipeline: `/project:plan` → human approve → `/project:start-task` → implement → `/project:complete-task` → `/project:review`
- Before planning or implementing any feature, first add it to `docs/ROADMAP.md` under the appropriate section
- The roadmap is the source of truth for what's planned and what's done
- **Spec-driven development**: Write a BDD spec (`docs/specs/`) before code. Every feature needs SHALL/SHALL NOT scenarios. See `docs/specs/TEMPLATE.md`
- **Bug fix workflow**: When receiving a bug report, always write a failing test that reproduces the bug *before* writing the fix. This validates the bug is real and ensures the fix is verifiable. Only then apply the code fix and confirm the test passes.
- **Post-mortem on failure**: When a task fails or needs significant rework, create a post-mortem via `/project:postmortem`. See `docs/pipeline-log/POSTMORTEM-TEMPLATE.md`

## Architectural Invariants (NEVER violate)

| # | Invariant | Why |
|---|-----------|-----|
| 1 | Scalameta only — no presentation compiler, no build server dependency | PC requires `.class`/`.tasty` on classpath, reintroduces compilation. See ADR-001 |
| 2 | No new dependencies without explicit approval | AI adds packages without evaluating impact — 3 deps is the sweet spot |
| 3 | Named tuples only — never `(A, B)`, always `(name: A, value: B)` | Unnamed tuples are unreadable in Scala 3; named tuples are self-documenting |
| 4 | No `return` statements anywhere | Deprecated in Scala 3 lambdas, footgun everywhere. Use `boundary`/`break` |
| 5 | Zero warnings, zero deprecations in compiled code | CI blocks merge on any warning. Run `scala-cli compile src/` and verify clean |
| 6 | Every new feature benchmarked before/after on scala3 (17.7k files) | Performance budget: <5% regression on index times, 0% index size growth for non-index features |
| 7 | Must be better than grep or don't ship it | Agent can always fall back to grep — scalex must never be the worse option |
| 8 | No backwards compatibility preservation | Tool, not library. Fix inconsistencies, don't preserve them |
| 9 | Bug fix = failing test first, then code fix | Validates bug is real, ensures fix is verifiable |
| 10 | Immutable domain — no `var`, no mutable collections in model types | Purity enables safe concurrent access and predictable behavior |

## Code Generation Rules

### Size limits

| Metric | Recommended | Hard limit | Action if exceeded |
|--------|-------------|------------|-------------------|
| Lines per function | 30–40 | 50 | Extract helper methods |
| Lines per file | 200–300 | 400 | Extract to separate file |
| Function parameters | 3–4 | 5 | Use parameter object / named tuple |
| Nesting depth | 2–3 | 4 | Extract sub-functions |

### Tests (mandatory for every change)

For each public method / command:
- Happy path — основной сценарий работает
- Primary error — основная ошибка обрабатывается
- Edge case — граничное условие (empty input, not found, max size)

### After generating code (MANDATORY)

1. `scala-cli compile src/` — zero warnings
2. `scala-cli compile --scalac-option "-deprecation" src/` — zero deprecations
3. `scala-cli test src/ tests/` — all tests pass
4. If fails → fix immediately, do not proceed

## Comment Styleguide

Write **WHY**, not **WHAT**. Comments are context for the next AI session that has no project history.

| Type | Write? | Contains | Example |
|------|--------|----------|---------|
| **WHY** | Yes | Reasoning, tradeoff, ADR reference | `// WHY: bloom filter false-positive rate ~1% — acceptable for candidate shortlisting` |
| **HACK** | Yes | Workaround + removal condition | `// HACK: Scalameta Pkg.children wraps in PkgBody — use pkg.stats instead. Track scalameta#NNNN` |
| **TODO** | Yes | Scope + issue link | `// TODO(perf): parallel file parsing — #42` |
| **WHAT** | No | Code paraphrase | `// Get the symbol by name` ← obvious from code, delete |

Magic numbers — always extract to named constants with WHY-comment:
```scala
// WHY: 20s timeout — refs does text search across all candidate files,
// worst case on scala3 (17.7k files) takes ~15s
val RefsTimeoutSeconds = 20
```

## Anti-Patterns (NEVER do)

| Wrong | Correct | Why |
|-------|---------|-----|
| `(List[Reference], Boolean)` | `(results: List[Reference], timedOut: Boolean)` | Named tuples are self-documenting |
| `return` inside lambda/foreach | `boundary` + `boundary.break` | `return` is deprecated in Scala 3 lambdas |
| `pkg.children` to get Pkg contents | `pkg.stats` | `.children` wraps stats in `PkgBody` wrapper |
| `tree.collect { case ... }` | Manual `traverse` + `visit` | `.collect` doesn't work on Scalameta Tree in Scala 3 |
| `list.par.map(...)` | `list.asJava.parallelStream()` | `.par` doesn't exist in Scala 3.8 |
| `import scalameta._` (wildcard) | `import scala.meta.{Defn, Term, ...}` | Explicit imports prevent namespace pollution |
| `com.google.common:guava` | `com.google.guava:guava` | Wrong Maven group ID — will not resolve |
| Add a new dependency | Ask first, justify need | 3 deps is the sweet spot, each new one adds maintenance burden |
| `throw new RuntimeException(...)` | Return typed error / `Option` / `boundary.break` | Exceptions bypass type system, unrecoverable in CLI |
| File >400 lines | Extract to separate file | AI loses context in long files |

## Build & Run

```bash
# Run via scala-cli (development)
scala-cli run src/ -- <command> [args...]

# Run tests
scala-cli test src/ tests/

# Run a single test suite
scala-cli test src/ tests/ --test-only 'ExtractionSuite'

# Run a single test by name substring
scala-cli test src/ tests/ -- '*keyword*'

# Build GraalVM native image (requires GraalVM + scala-cli)
./build-native.sh
# Output: ./scalex (26MB standalone binary)

# Validate Claude Code plugin structure
claude plugin validate plugins/agent4s/
```

## Architecture

Source code is in `src/`, tests in `tests/` (Scala 3.8.2, JDK 21+). When searching the codebase, scope to these directories to avoid hitting benchmark data or build artifacts.

```
src/                           # Production source code
├── project.scala              # scala-cli directives only
├── model.scala                # Data types, enums, version constant
├── extraction.scala           # AST parsing & single-file extraction functions
├── index.scala                # Git integration, persistence, WorkspaceIndex, filtering
├── analysis.scala             # Cross-index analysis (hierarchy, overrides, deps, diff, ast-pattern)
├── format.scala               # Text formatters for symbols and references
├── cli.scala                  # Arg parsing, workspace resolution, @main entry point
├── command-helpers.scala      # Shared filters: filterSymbols, filterRefs, mkNotFoundWithSuggestions
├── dispatch.scala             # Command map + runCommand
└── commands/                  # One file per command (ls to discover all 25 commands)
    ├── definition.scala       # cmdDef
    ├── search.scala           # cmdSearch
    ├── refs.scala             # cmdRefs
    ├── hierarchy.scala        # cmdHierarchy
    ├── overview.scala         # cmdOverview
    └── ...                    # 25 commands total

tests/                         # Test suite
├── test-base.test.scala       # Shared test fixture (workspace setup)
├── extraction.test.scala      # Extraction tests
├── index.test.scala           # Index/search/persistence tests
├── analysis.test.scala        # Analysis tests (hierarchy, overrides, deps, etc.)
└── cli.test.scala             # CLI/formatting/command output tests

benchmark/                     # Benchmark data (gitignored)
├── scala3/                    # Shallow clone of scala/scala3 for benchmarks
└── results/                   # Hyperfine JSON exports
```

### Pipeline

```
git ls-files --stage → Scalameta parse → in-memory index → query
                              ↓
                    .scalex/index.bin (binary cache, OID-keyed, bloom filters)
```

1. **Git discovery**: `git ls-files --stage` returns all tracked `.scala` files with their OIDs
2. **Symbol extraction**: Scalameta parses source ASTs (Scala 3 first, falls back to Scala 2.13), extracts top-level symbols (class/trait/object/def/val/type/enum/given/extension)
3. **OID caching**: On subsequent runs, compares OIDs — skips unchanged files entirely
4. **Persistence**: Binary format with string interning at `.scalex/index.bin`
5. **Bloom filters**: Per-file bloom filter of identifiers — `refs` and `imports` only read candidate files

### Code style

- **Named tuples**: Never use unnamed tuples. Whenever a tuple is needed — return types, local variables, collection elements — always use named tuples. E.g. `(results: List[Reference], timedOut: Boolean)` not `(List[Reference], Boolean)`.
- **No `return` statements**: Never use `return` anywhere — not in methods, not in lambdas, not in `for`/`foreach`. Use `scala.util.boundary` + `boundary.break` for early exit, or restructure with `match`/`if-else`. The `return` keyword is deprecated in Scala 3 inside lambdas and is a footgun everywhere else. Existing `return` statements in the codebase are legacy — do not add new ones, and remove them when touching nearby code.

### Key design choices

- **Scalameta, not presentation compiler**: Scala 3's PC requires compiled `.class`/`.tasty` on classpath, which reintroduces build server dependency. Scalameta parses source directly.
- **Git OIDs for caching**: Available free from `git ls-files --stage`, no disk reads needed to detect changes.
- **No build server**: Coding agents can run `./mill __.compile` directly for error checking.
- **No backwards compatibility**: This is a tool, not a library. Do the right thing and do it consistently. If the current way of printing data, formatting JSON, or structuring output is inconsistent, fix it to match the up-to-date pattern — don't preserve old behavior for backwards compatibility. Consistency across commands matters more than not breaking hypothetical consumers.
- **Feature gate question**: "Is this better than grep, or does it introduce a worst case that grep never has?" If a feature risks being slower or less reliable than grep in any scenario, don't add it. The agent can always fall back to grep — scalex must never be the worse option.
- **Performance budget**: Every new feature must be benchmarked before/after on a large codebase (e.g. scala3 compiler, 17.7k files). Measure: index size (`.scalex/index.bin`), cold index time, warm index time, and query latency. Accept <5% regression on index times, 0% index size growth for non-index features, <10% if index schema changes. Prefer on-the-fly source reads over index bloat for infrequent queries (e.g. `members`, `doc`).

### Dependencies

- `org.scalameta::scalameta:4.15.2` — AST parsing
- `com.google.guava:guava:33.5.0-jre` — bloom filters
- `org.scalameta::munit:1.2.4` — test framework (test only)

## Plugin structure

```
plugins/
└── scalex/                        # scalex plugin
    ├── .claude-plugin/plugin.json
    └── skills/agent4s/
        ├── SKILL.md
        ├── references/
        └── scripts/scalex-cli     # Bootstrap: downloads + caches binary, forwards args
```

The bootstrap script `scalex-cli` contains `EXPECTED_VERSION` that must be bumped alongside `ScalexVersion` in `src/model.scala` when releasing.

## Release workflow

### Step 1: Release PR (merge first)
1. Move `[Unreleased]` section in `CHANGELOG.md` to the new version with date
2. Bump `ScalexVersion` in `src/model.scala`
3. Create PR, get it merged to main

### Step 2: Tag + release
4. Tag as `vX.Y.Z` and push — GitHub Actions builds native binaries + creates release

### Step 3: Plugin version bump
5. Bump `EXPECTED_VERSION` in `plugins/agent4s/skills/agent4s/scripts/scalex-cli`
6. Update `CHECKSUM_scalex_*` values in `scalex-cli` — get hashes from individual `.sha256` release assets (iterate: `gh release view vX.Y.Z --json assets --jq '.assets[] | select(.name | endswith(".sha256")) | .name'` then download each with `gh release download vX.Y.Z -p <name> -O -`)
7. Bump `version` in `.claude-plugin/marketplace.json` (plugin version is only managed here, not in `plugins/agent4s/.claude-plugin/plugin.json`)
8. Commit, create PR, merge to main (main is protected — cannot push directly)

Note: `marketplace.json` is at the repo root (`.claude-plugin/marketplace.json`), NOT inside `plugins/`.

## Feature checklist

When adding or changing commands/flags in `src/cli.scala`:
- Update help text in the `main` function
- Update `plugins/agent4s/skills/agent4s/SKILL.md` (commands, options table, common workflows, description frontmatter) and `plugins/agent4s/skills/agent4s/references/commands.md` (command signature, description, examples, options table). Description must be double-quoted YAML and under 1024 chars (for GitHub Copilot CLI compatibility). **Always run `./scripts/check-skill-frontmatter.sh` after editing SKILL.md** to validate
- Update `docs/ROADMAP.md`
- Update `CHANGELOG.md`
- Update `README.md` (commands block, Coding-Agent-Friendly Features, "Use it" examples). README does NOT duplicate the options table — it links to SKILL.md
- Update `site/index.html` (command grid, command count heading)

## Gotchas

- **Protected main branch**: Cannot push directly to main — all changes require a PR
- **Zero warnings policy**: Before creating a PR, run `scala-cli compile src/ 2>&1 | grep -i warn` and verify no output. Do not ship compiler warnings.
- **Zero deprecations policy**: Also verify with `scala-cli compile --scalac-option "-deprecation" src/ 2>&1 | grep -i warn`. Do not use deprecated APIs — find and use the replacement immediately.

- **Guava group ID**: `com.google.guava:guava`, NOT `com.google.common:guava`
- **GraalVM native image**: Guava needs `--initialize-at-run-time=com.google.common.hash.Striped64,com.google.common.hash.LongAdder,com.google.common.hash.BloomFilter,com.google.common.hash.BloomFilterStrategies` (see `build-native.sh`)
- **No `.par` in Scala 3.8**: Use `list.asJava.parallelStream()` instead of `list.par`
- **No non-local `return` in Scala 3.8**: `return` inside lambdas/closures is deprecated. Use `scala.util.boundary` + `boundary.break` instead. This includes `return` inside `.foreach`, `.map`, `try`/`catch` blocks inside lambdas, etc.
- **Scalameta `Pkg.children` wraps stats in `PkgBody`**: Use `pkg.stats` to access direct children (Import, Defn.Class, etc.), not `pkg.children` which nests them inside a `PkgBody` wrapper node.
- **Scalameta Tree**: `.collect` doesn't work on Tree in Scala 3 — use manual `traverse` + `visit` pattern
- **Anonymous givens**: Only named givens are indexed; anonymous givens are skipped
- **`refs`/`imports` use text search**: They use bloom filters to shortlist candidate files, then do word-boundary text matching. They are NOT index-based and have a 20-second timeout.
- **Scala 3 indentation in `WorkspaceIndex`**: Deeply nested code can break method boundaries — use brace syntax for nested blocks
- **Test fixture file counts**: Tests hardcode file counts — adding/removing fixtures requires updating all count assertions
- **GitHub Actions SHA pinning**: All actions in `release.yml` are pinned to commit SHAs. To verify/update: `git ls-remote --refs https://github.com/<owner>/<repo>.git refs/tags/<tag>`. Never trust SHAs from memory or LLM output without verifying.

## Pipeline Protocol

Every task follows the agentic pipeline. Use slash commands for each phase.

### Phase 1: Plan (`/project:plan`)
- Read CLAUDE.md, ARCHITECTURE.md, relevant ADRs
- Understand WHAT and WHY before writing code
- Create BDD spec in `docs/specs/[task-name].md` with SHALL / SHALL NOT
- **Wait for human approval** — do NOT start implementation without it

### Phase 2: Implement (`/project:start-task [name]`)
- Work in isolated worktree (one module per agent, no conflicts)
- BDD flow: tests first (RED) → minimal code (GREEN) → mutation check → refactor
- WHY-comments on non-obvious decisions
- One task per worktree — don't mix unrelated changes
- SHALL NOT from spec = hard PROHIBITIONS, not suggestions

### Phase 3: Verify (`/project:verify`)
- Compile: `scala-cli compile src/` — zero warnings
- Deprecations: `scala-cli compile --scalac-option "-deprecation" src/` — zero warnings
- Tests: `scala-cli test src/ tests/` — all green
- If fails → fix immediately (up to 3 iterations). If still fails → task is poorly scoped

### Phase 4: Complete (`/project:complete-task [name]`)
- Full verification pass
- Check all acceptance criteria from spec
- Feature checklist (if command/flag changed)
- Commit, push, create PR

### Phase 5: Review (`/project:review`)
- Architectural review against spec and invariants
- APPROVE / REQUEST_CHANGES / REJECT
- Every issue references a specific invariant or rule

### On Failure (`/project:postmortem [name]`)
- Create structured post-mortem in `docs/pipeline-log/`
- Classify failure taxonomy
- Document action items to prevent recurrence

### Parallelization Rules
- One module — one agent (no two agents editing the same module)
- Dependency order: if task B depends on task A, finish A first
- Max 3-5 parallel worktrees
- Each worktree is expendable: if stuck, delete and restart with better spec

### Agents (`.claude/agents/`)
- **spec-writer** — creates specs, does NOT write code
- **implementer** — implements from spec in isolated worktree, does NOT change scope
- **reviewer** — evaluates against invariants/ADRs, does NOT write code
- **test-writer** — writes MUnit tests from BDD scenarios, does NOT write production code

### Scoped Rules (`.claude/rules/`)
- `scala.md` — Scala-specific patterns, anti-patterns, size limits
- `tests.md` — MUnit conventions, BDD mapping, fixture rules
