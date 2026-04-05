# Spec: Bug-Hunt v2 — Self-Improving Security Analyzer

**Date:** 2026-04-05
**Priority:** P0
**Modules:** commands/bug-hunt.scala, taint.scala, model.scala, extraction.scala, index.scala
**Estimated complexity:** XL (4 features, each M-L)

## Goal

Turn bug-hunt from a static pattern matcher into a self-improving analyzer: auto-suppresses false positives via memories, validates patterns via meta-fuzzing, prioritizes findings via reachability, and grows pattern base via CVE-to-pattern pipeline.

## Context

Current state: 45 AST patterns, cross-file taint analysis (3 hops), 19 tests, bloom filter pre-screening. Key pain points:
- False positives erode trust (OptionGet on Ref.get, CommandInjection on literals)
- No way to learn from LLM triage verdicts
- Findings lack reachability context ("is this reachable from a public API?")
- Adding patterns is manual (6 file changes per pattern)
- Pattern quality untested beyond happy-path fixtures

Inspired by: Semgrep AI Memories (96% triage accuracy, 2.8x FP reduction with 5 memories), hybrid fuzzing research, CVE-Genie (51% automated CVE reproduction).

## Scope

### In scope:
- Feature 1: Suppression memories (`.scalex/memories.json`)
- Feature 2: Meta-fuzzing framework (property-based pattern testing)
- Feature 3: Reachability analysis (`--reachable` flag on bug-hunt)
- Feature 4: CVE-to-pattern spec format + validation pipeline

### Out of scope:
- Silent telemetry / opt-in analytics (separate spec)
- Semantic call-graph improvements (depends on SemanticDB availability)
- UI/dashboard for memory management
- Automatic LLM-based pattern generation without human review

---

## Feature 1: Suppression Memories

### Data Model

```scala
// In model.scala
case class SuppressionMemory(
  patternName: String,              // "OptionGet", "CommandInjection"
  scope: MemoryScope,               // where this applies
  reason: String,                   // "ZIO Ref.get in for-comprehension"
  source: MemorySource,             // who created it
  suppressionType: SuppressionType, // Ignore | Lower | Review
  contextLine: String,              // original finding context
  createdAt: Long                   // epoch millis
)

enum MemoryScope:
  case Global(patternName: String)
  case FileScoped(patternName: String, filePattern: String)
  case MethodScoped(patternName: String, filePattern: String, methodName: String)

enum MemorySource:
  case LlmTriage(skillRun: String)
  case UserManual
  case CommunitySubmit(issueNumber: Int)

enum SuppressionType:
  case Ignore   // don't report
  case Lower    // report with reduced severity
  case Review   // flag for manual review
```

### Storage

File: `.scalex/memories.json` (JSONL, one memory per line). Human-readable, version-controllable, shareable across team.

### Commands

```
agent4s memory list                          # show all memories
agent4s memory add <pattern> --reason "..."  # manual add
agent4s memory remove <id>                   # remove by index
agent4s memory export > memories.jsonl       # export
agent4s memory import < memories.jsonl       # import (merge)
agent4s memory clear                         # remove all
```

### Integration with bug-hunt

Load memories at scan start. For each finding, check if any memory matches (pattern + scope). Apply suppression type.

### Requirement: Memory matching

#### Scenario: Global memory suppresses pattern everywhere
- **WHEN** a memory exists with `scope = Global("OptionGet")`
- **AND** bug-hunt finds an OptionGet finding in any file
- **THEN** the system SHALL apply the memory's suppressionType
- **AND** the system SHALL NOT report Ignore-type findings in output

#### Scenario: Method-scoped memory only suppresses in that method
- **WHEN** a memory exists with `scope = MethodScoped("PathTraversal", ".*Service.scala", "loadConfig")`
- **AND** bug-hunt finds PathTraversal in `loadConfig` method of a Service.scala file
- **THEN** the system SHALL suppress that finding
- **AND** the system SHALL NOT suppress PathTraversal in other methods of the same file

#### Scenario: LLM triage auto-records memory
- **WHEN** the `/agent4s:bug-hunt` skill classifies a finding as "False positive"
- **AND** the LLM provides a reason
- **THEN** the skill SHALL call `agent4s memory add <pattern> --reason "<reason>" --source llm-triage`
- **AND** the system SHALL persist the memory in `.scalex/memories.json`

#### Scenario: Memory does not suppress real bugs
- **WHEN** a memory exists for pattern X in method Y
- **AND** the code in method Y changes (different contextLine)
- **THEN** the system SHALL NOT apply the old memory
- **AND** the system SHALL flag the stale memory for review

### SHALL NOT:
- SHALL NOT store source code in memory files (only pattern name, context line, reason)
- SHALL NOT auto-create memories without LLM triage or explicit user action
- SHALL NOT break existing bug-hunt output format (memories are transparent filter)

---

## Feature 2: Meta-Fuzzing Framework

### Architecture

```
PatternSpec (per pattern)
  ├── positive: List[String]     # code that MUST trigger
  ├── negative: List[String]     # code that MUST NOT trigger
  └── suppressed: List[String]   # code with known suppression context

PatternValidator
  ├── parseAndScan(code) → List[BugFinding]
  ├── validatePositive(spec) → Boolean
  ├── validateNegative(spec) → Boolean
  └── validateSuppressed(spec) → Boolean
```

### Test Infrastructure

New file: `tests/pattern-specs.test.scala`

Uses Scalameta's `Input.VirtualFile` to parse synthetic code strings. No disk I/O needed — pure in-memory validation.

```scala
def scanCode(code: String, pattern: BugPattern): List[BugFinding] =
  // Parse code string → AST → run scanFile logic → return findings
  // Filter to only the target pattern
```

### Pattern Specs

One spec per pattern. Minimum coverage:
- 1 positive case (triggers detection)
- 1 negative case (similar but safe)
- 1 suppression case (per suppression rule that exists for this pattern)

Priority patterns (highest false-positive risk from research):
1. OptionGet (6 suppression rules)
2. CommandInjection (taint-dependent)
3. PathTraversal (taint-dependent)
4. ResourceLeak (context-dependent)
5. HardcodedSecret (placeholder detection)

### Requirement: Pattern validation

#### Scenario: Positive spec catches real bug
- **WHEN** a PatternSpec.positive code is scanned
- **THEN** the system SHALL find exactly the expected number of findings for that pattern
- **AND** the system SHALL NOT find zero findings (test must fail if pattern is broken)

#### Scenario: Negative spec has no false positives
- **WHEN** a PatternSpec.negative code is scanned
- **THEN** the system SHALL find zero findings for that pattern
- **AND** the system SHALL NOT produce any findings (test must fail on false positive)

#### Scenario: Suppression spec validates safety filter
- **WHEN** a PatternSpec.suppressed code is scanned
- **THEN** the system SHALL find zero findings (suppression works)
- **AND** if the suppression logic is removed, the test SHALL fail (mutation testing)

#### Scenario: All 45 patterns have specs
- **WHEN** the test suite runs
- **THEN** every BugPattern enum value SHALL have at least one PatternSpec
- **AND** the system SHALL fail compilation/test if a new pattern is added without a spec

### SHALL NOT:
- SHALL NOT use disk I/O for pattern validation (in-memory parsing only)
- SHALL NOT depend on external test fixtures (self-contained code strings)
- SHALL NOT add ScalaCheck as a dependency (use hand-crafted specs, not random generation)

---

## Feature 3: Reachability Analysis

### Approach

```
1. Find entrypoints: cmdEntrypoints() → @main, extends App, HTTP routes, test suites
2. For each entrypoint: extract body → find callees (text-based or semantic)
3. Recursively expand callees up to depth N (default: 5)
4. Build reachable method set: Set[String] of method names reachable from any entrypoint
5. For each bug-hunt finding: extractScopes(file, line) → find enclosing method
6. Check: is enclosing method in reachable set?
7. Tag finding: reachable=true/false, distance from nearest entrypoint
```

### New flag

```
agent4s bug-hunt --reachable              # only show findings reachable from entrypoints
agent4s bug-hunt --reachable --depth 3    # limit call-graph depth
```

### Output enrichment

Findings get two new optional fields:

```scala
case class BugFinding(
  // ... existing fields ...
  reachableFrom: Option[List[String]] = None,  // entrypoint names
  callDepth: Option[Int] = None                // hops from nearest entrypoint
)
```

### Requirement: Reachability filtering

#### Scenario: Finding reachable from HTTP entrypoint
- **WHEN** bug-hunt runs with `--reachable`
- **AND** a finding is in method `processInput` which is called by `handleRequest` which is an HTTP route
- **THEN** the system SHALL include this finding in output
- **AND** the system SHALL set `reachableFrom = Some(List("handleRequest"))` and `callDepth = Some(2)`

#### Scenario: Finding in dead code
- **WHEN** bug-hunt runs with `--reachable`
- **AND** a finding is in method `legacyHelper` which is not called by any entrypoint within depth limit
- **THEN** the system SHALL exclude this finding from output
- **AND** the system SHALL NOT silently lose the finding — print summary "N findings filtered (not reachable)"

#### Scenario: Without --reachable flag, behavior unchanged
- **WHEN** bug-hunt runs without `--reachable`
- **THEN** the system SHALL report all findings as before
- **AND** the system SHALL NOT perform reachability analysis (no performance cost)

#### Scenario: Call-graph depth limit
- **WHEN** `--depth 3` is set
- **AND** a method is reachable only at depth 5
- **THEN** the system SHALL treat it as unreachable
- **AND** the system SHALL NOT hang on circular call chains (visited set)

### SHALL NOT:
- SHALL NOT require SemanticDB (text-based call-graph is the default)
- SHALL NOT add more than 2 seconds to bug-hunt on scala3 compiler (18.7K files)
- SHALL NOT change output format when `--reachable` is not specified
- SHALL NOT count test suites as entrypoints by default (use `--include-tests` to include)

---

## Feature 4: CVE-to-Pattern Pipeline

### Pattern Spec Format

New file type: `.pattern-spec.json` in `patterns/` directory.

```json
{
  "patternName": "InsecureSSLContext",
  "severity": "High",
  "category": "Security",
  "description": "SSLContext with permissive TrustManager — MITM vulnerability",
  "cwe": "CWE-295",
  "astPattern": {
    "nodeType": "Term.Apply",
    "methodName": "init",
    "qualifierPattern": "SSLContext|sslContext",
    "argCheck": "contains TrustManager with permissive accept"
  },
  "isTaintSink": false,
  "bloomKeywords": ["SSLContext", "TrustManager", "init"],
  "testPositive": "val ctx = SSLContext.getInstance(\"TLS\")\nctx.init(null, Array(new X509TrustManager { ... }), null)",
  "testNegative": "val ctx = SSLContext.getDefault",
  "testSuppressed": null
}
```

### Validation command

```
agent4s pattern validate patterns/InsecureSSLContext.pattern-spec.json
```

This command:
1. Parses the spec
2. Generates Scala wrapper code from testPositive/testNegative
3. Scans with bug-hunt logic
4. Reports: PASS (positive triggers, negative clean) or FAIL (with details)

### Thin categories to expand (from research)

| Category | Current | Candidates from CVE databases |
|---|---|---|
| Resource (3) | Source, Connection, Stream | FileChannel, Socket, Lock, MappedByteBuffer, Cursor |
| Concurrency (6) | Await, Thread.sleep, synchronized, var+Future | Double-checked locking, ConcurrentHashMap iteration during modification, Lock ordering violation |
| Effect (6) | ZIO.succeed throw, ZIO.die, unsafeRun | Unhandled error channel, fiber leak, incorrect error recovery |
| Security (21) | SQL, XSS, SSRF, crypto, etc. | Insecure SSL/TLS, timing attack, unsafe reflection, JWT none algorithm, XML signature wrapping |

### Requirement: Pattern spec validation

#### Scenario: Valid pattern spec passes
- **WHEN** `pattern validate` runs on a spec with correct positive/negative tests
- **THEN** the system SHALL report PASS
- **AND** the system SHALL verify positive code triggers the pattern
- **AND** the system SHALL verify negative code does NOT trigger the pattern

#### Scenario: Invalid spec fails clearly
- **WHEN** positive test does not trigger the pattern
- **THEN** the system SHALL report FAIL with "positive case did not trigger"
- **AND** the system SHALL NOT silently pass

#### Scenario: Spec format validation
- **WHEN** a required field is missing from the JSON spec
- **THEN** the system SHALL report a clear error naming the missing field
- **AND** the system SHALL NOT attempt validation

### SHALL NOT:
- SHALL NOT auto-merge patterns without human review
- SHALL NOT add patterns that don't have both positive and negative tests
- SHALL NOT generate Scala code at runtime (specs contain pre-written test snippets)
- SHALL NOT increase index size (patterns are code-only, not indexed data)

---

## Technical Approach

### Implementation order

1. **Meta-fuzzing** (no code changes to production — tests only)
2. **Memories** (new model types + integration in bug-hunt scan loop)
3. **Reachability** (new flag + entrypoint/call-graph composition)
4. **CVE pipeline** (new command + spec format + validator)

### Files to modify

| File | Feature | Changes |
|---|---|---|
| `src/model.scala` | 1,2,3 | SuppressionMemory, MemoryScope, MemorySource, SuppressionType enums. BugFinding +reachableFrom +callDepth fields |
| `src/commands/bug-hunt.scala` | 1,3 | Load memories at scan start, filter findings. Add --reachable flag, call reachability analysis |
| `src/commands/memory.scala` | 1 | New file: memory list/add/remove/export/import/clear commands |
| `src/commands/pattern-validate.scala` | 4 | New file: pattern spec validator |
| `src/reachability.scala` | 3 | New file: forward call-graph expansion from entrypoints, reachable set computation |
| `src/taint.scala` | 1 | No changes (taint suppression is orthogonal to memories) |
| `src/cli.scala` | 1,3,4 | New commands: memory, pattern. New flag: --reachable, --depth |
| `src/dispatch.scala` | 1,3,4 | Route memory and pattern commands |
| `tests/pattern-specs.test.scala` | 2 | New file: all 45 pattern specs with positive/negative/suppressed |
| `tests/memory.test.scala` | 1 | New file: memory CRUD, matching, persistence |
| `tests/reachability.test.scala` | 3 | New file: reachability from entrypoints, depth limit, cycle handling |

### Performance budget

| Operation | Budget | Rationale |
|---|---|---|
| Memory load | <10ms | JSON parse of ~100 rules |
| Memory match per finding | <0.1ms | HashMap lookup by pattern name |
| Reachability build (cold) | <2s on scala3 | Entrypoints + 5-level call-graph expansion |
| Pattern validation | <500ms per spec | Parse + scan of small code snippet |

---

## Acceptance Criteria

- [ ] All 45 patterns have PatternSpecs (positive + negative)
- [ ] Top 5 false-positive-prone patterns have suppression specs
- [ ] `memory add/list/remove/export/import/clear` all work
- [ ] LLM triage in `/agent4s:bug-hunt` auto-records memories for false positives
- [ ] `--reachable` filters findings by entrypoint reachability
- [ ] `--reachable` adds <2s overhead on scala3 compiler benchmark
- [ ] `pattern validate` validates spec format and runs positive/negative tests
- [ ] Compile: `scala-cli compile src/` — zero warnings
- [ ] Deprecations: `scala-cli compile --scalac-option "-deprecation" src/` — zero warnings
- [ ] Tests: `scala-cli test src/ tests/` — all pass
- [ ] New files <400 lines each
- [ ] No new dependencies added

## Constraints

- Invariant #1: Scalameta only — no compiler dependency
- Invariant #2: No new dependencies without approval
- Invariant #3: Named tuples only
- Invariant #6: Benchmark before/after on scala3
- Invariant #7: Must be better than grep
- Invariant #10: Immutable domain types
