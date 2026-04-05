---
name: bug-hunt
description: "Scan Scala codebase for 45 AST-based bug patterns with taint analysis. Orchestrates: scan → LLM triage → memory recording → GitHub issue cross-reference → reproduction → structured report. Auto-records false positives as suppression memories for future runs. Triggers: 'find bugs', 'security scan', 'find vulnerabilities', 'code audit', 'bug hunt', 'check for unsafe code'."
---

You have access to the `agent4s bug-hunt` command — a deterministic AST-based scanner with cross-file taint analysis that detects 45 bug patterns across 7 categories (security, type safety, concurrency, effects, resources, crypto, XML). Your job is to orchestrate the full pipeline: scan → triage → record memories → cross-reference → report.

## Locate the CLI

The bootstrap script is at a sibling path relative to this skill:

```
../agent4s/scripts/scalex-cli
```

Resolve it from this skill's directory. All commands below use `<cli>` as shorthand for `bash "<resolved-path>"`.

## Step 1: Run the scan

```bash
<cli> bug-hunt --json --hotspots --reachable -w "<project-root>"
```

Flags to consider:
- `--reachable` — only show findings reachable from entrypoints (recommended: reduces noise)
- `--reachable-depth N` — limit call-graph depth (default: 5)
- `--no-tests` — exclude test files
- `--severity critical` or `--severity high` — focus on worst issues first
- `--bug-category security` — security patterns only
- `--path src/main/` — scope to specific directory

Parse the JSON output:
- `findings[]` — each with `file`, `line`, `pattern`, `severity`, `category`, `message`, `contextLine`, `enclosingSymbol`, `reachableFrom`, `callDepth`
- `hotspots[]` — files ranked by `findingCount × gitChurn`
- `filesScanned`, `timedOut`

For large codebases (100+ findings), prioritize:
1. Critical severity first
2. Hotspot files (high churn × many findings)
3. Reachable findings over dead code

## Step 2: Load existing memories

Check what the team already triaged:

```bash
<cli> memory list -w "<project-root>"
```

Findings matching existing Ignore memories were already filtered by the scanner. Review existing memories to understand project conventions before triaging new findings.

## Step 3: Triage each finding

For each finding, read surrounding code:

```bash
<cli> body <enclosingSymbol> --in <Owner> -C 5 -w "<project-root>"
```

If `enclosingSymbol` is `<top-level>`, use the Read tool on the file instead.

Classify each finding as:

| Verdict | Meaning | Action |
|---------|---------|--------|
| **Confirmed** | Clearly a bug based on code context | Include in report, generate repro |
| **Likely** | Suspicious, needs human review | Include in report with context |
| **False positive** | Safe in context | **Record as suppression memory** |

### Known false positive patterns

- `Ref.get` in ZIO — this is `zio.Ref.get`, not `Option.get`
- `base.get` in tapir endpoints — endpoint builder, not Option.get
- `.get` after `<-` in for-comprehension with ZIO — likely `Ref.get`
- `Map.get(key)` — returns `Option`, safe
- Hardcoded secrets in test files — acceptable for test fixtures
- `asInstanceOf` inside `match` case — type already checked by pattern match
- `null` in Java interop code — sometimes unavoidable
- `.head` on known-non-empty collection (after `.nonEmpty` check or in test assertion)

## Step 4: Record false positive memories

**This is critical.** For EVERY false positive finding, record a suppression memory so future scans skip it:

```bash
<cli> memory add <PatternName> \
  --reason "<why this is safe — be specific>" \
  --scope global \
  --source llm-triage \
  -w "<project-root>"
```

Scope options:
- `--scope global` — suppress this pattern everywhere (e.g., "OptionGet on ZIO Ref.get")
- `--scope file --file ".*Service.scala"` — suppress in matching files
- `--scope method --file ".*Service.scala" --method "loadConfig"` — suppress in specific method

Suppression type:
- `--type ignore` (default) — don't report at all
- `--type lower` — report with reduced severity
- `--type review` — flag for manual review

**Examples:**

```bash
# ZIO Ref.get is not Option.get
<cli> memory add OptionGet --reason "ZIO Ref.get in for-comprehension — safe monadic accessor" --scope global --source llm-triage -w .

# Test fixtures with hardcoded secrets
<cli> memory add HardcodedSecret --reason "Test fixture credentials — not production secrets" --scope file --file ".*Test.scala" --source llm-triage -w .

# asInstanceOf after exhaustive match
<cli> memory add AsInstanceOf --reason "Safe cast inside pattern match guard in codec" --scope method --file ".*Codec.scala" --method "decode" --source llm-triage -w .
```

## Step 5: Cross-reference GitHub issues (optional)

Skip if the project has no GitHub remote. Check: `gh repo view --json url -q .url 2>/dev/null`

For each **Confirmed** or **Likely** finding, map pattern to search terms:

| Pattern | Search terms |
|---------|-------------|
| AsInstanceOf | ClassCastException |
| OptionGet / CollectionHead | NoSuchElementException, "head of empty" |
| NullLiteral | NullPointerException |
| AwaitInfinite | timeout, hang, deadlock |
| ResourceLeak / ConnectionLeak | "resource leak", "connection leak" |
| SQLInjection | "SQL injection", "security" |

```bash
gh issue list --repo <owner/repo> --search "<terms>" --limit 5 --json title,url,state
```

If a matching issue is found → upgrade finding to **Confirmed (issue-linked)**.

## Step 6: Generate reproductions

For each **Confirmed** finding, write a minimal scala-cli script:

```scala
//> using scala 3.3

// Reproduction for: <pattern> in <file>:<line>
// Expected: <what should happen>
// Actual: <what goes wrong>

@main def repro() =
  val result = <extracted-problematic-code>
  println(s"Result: $result")
```

Run: `scala-cli run repro.scala` and record: CONFIRMED / PASSED / COMPILE ERROR.

## Step 7: Report

Present the final structured report:

```markdown
## Bug Hunt Report

**Scan**: <N> files scanned, <M> findings, <K> filtered by reachability

### Critical
**<Pattern>** in `<File>:<Line>` — `<Owner.method>`
- Risk: <what can go wrong>
- Reachable from: <entrypoint> (depth: <N>)
- Reproduction: <result>
- Fix: <suggested fix>
- Issue: <GitHub URL> (if found)

### High
...

### Medium (needs review)
...

### Summary
| Metric | Count |
|--------|-------|
| Confirmed | X |
| Likely (needs review) | Y |
| False positives recorded | Z |
| GitHub issues matched | N |
| Memories created | M |

### Suppression memories recorded
- `OptionGet` (global): ZIO Ref.get — N occurrences
- `HardcodedSecret` (file:*Test.scala): test fixtures — N occurrences
- ...

> Next scan will auto-suppress these. Run `agent4s memory list` to review.
```
