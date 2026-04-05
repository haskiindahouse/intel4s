---
name: bug-hunt
description: "Scan Scala codebase for common bug patterns, vulnerabilities, and code smells. Runs deterministic AST scan, then validates findings with LLM analysis, optionally cross-references GitHub issues, and generates minimal reproduction programs. Triggers: 'find bugs', 'security scan', 'find vulnerabilities', 'code audit', 'bug hunt', 'find dead code patterns', 'check for unsafe code'."
---

You have access to the `scalex bug-hunt` command — a deterministic AST-based scanner that detects 15 common bug patterns in Scala code. Your job is to orchestrate the full pipeline: scan → triage → validate → (optionally) cross-reference issues → generate reproductions → report.

## Step 1: Run the scan

Locate the scalex-cli script (sibling path: `../scalex/scripts/scalex-cli`) and run:

```bash
bash "<scalex-cli-path>" bug-hunt --json --hotspots -w "<project-root>"
```

Parse the JSON output. It contains:
- `findings[]` — each with `file`, `line`, `pattern`, `severity`, `category`, `message`, `contextLine`, `enclosingSymbol`
- `hotspots[]` — files ranked by `findingCount × gitChurn`
- `filesScanned`, `timedOut`

If there are too many findings, focus on:
1. Critical severity first
2. Hotspot files (high churn × many findings)
3. Non-test files (`--no-tests` flag or filter post-hoc)

## Step 2: Triage findings

Group findings by severity and category. For each finding:

1. Read the surrounding code:
   ```bash
   bash "<scalex-cli-path>" body <enclosingSymbol> --in <Owner> -C 5 -w "<project-root>"
   ```
   If enclosingSymbol is `<top-level>`, use the file path + line number with the Read tool instead.

2. Classify as:
   - **Confirmed** — clearly a bug based on code context (e.g., `.head` on potentially empty collection with no guard)
   - **Likely** — suspicious but needs more context (e.g., `asInstanceOf` that might be safe after pattern match)
   - **False positive** — safe in context (e.g., `Ref.get` in ZIO is not `Option.get`, `Map.get(key)` is safe, test-only secrets)

### Known false positive patterns
- `Ref.get` in ZIO — this is `zio.Ref.get`, not `Option.get`. Safe.
- `base.get` in tapir endpoints — this is endpoint builder method, not Option.get
- `.get` after `<-` in for-comprehension with ZIO — likely `Ref.get`
- `Map.get(key)` — returns `Option`, safe
- Hardcoded secrets in test files — acceptable for test fixtures
- `asInstanceOf` inside a `match` case body — often safe (pattern match already checked type)
- `null` in Java interop code — sometimes unavoidable

## Step 3: Cross-reference GitHub issues (optional)

**When to use**: For open-source projects, or any project with a GitHub issue tracker. Skip this step for private projects without `gh` CLI access.

**How to check**: Run `gh repo view --json url -q .url 2>/dev/null` in the project directory. If it returns a URL, the project has a GitHub remote.

For each **Likely** or **Confirmed** finding that involves a runtime exception pattern:

1. Map the finding to search terms:
   - `AsInstanceOf` → search "ClassCastException"
   - `OptionGet` / `CollectionHead` → search "NoSuchElementException" or "head of empty"
   - `NullLiteral` → search "NullPointerException"
   - `AwaitInfinite` → search "timeout" or "hang" or "deadlock"
   - `ResourceLeak` → search "resource leak" or "file not closed"
   - `ThreadSleepAsync` → search "blocked" or "thread starvation"

2. Search issues:
   ```bash
   gh search issues "ClassCastException" --repo <owner/repo> --limit 5 --json title,url,body,state
   ```
   Or if `gh search` is not available:
   ```bash
   gh issue list --repo <owner/repo> --search "ClassCastException" --limit 5 --json title,url,body,state
   ```

3. For each matching issue, check if:
   - The stack trace mentions the same file/class as the finding
   - The issue description matches the pattern (e.g., "crash when X is empty" + `.head` finding)
   - The issue is open (higher priority) or recently closed (confirms the pattern was a real problem)

4. If a match is found:
   - Upgrade the finding from **Likely** to **Confirmed (issue-linked)**
   - Include the issue URL and relevant excerpt in the report
   - Use the issue's reproduction steps to inform your own repro (Step 4)

## Step 4: Generate reproductions

For each **Confirmed** finding, write a minimal scala-cli script that demonstrates the bug:

```scala
//> using scala 3.3
//> using dep <relevant-deps-if-needed>

// Reproduction for: <pattern> in <file>:<line>
// Expected: <what should happen>
// Actual: <what goes wrong>

@main def repro() =
  // Minimal code that triggers the issue
  val result = <extracted-problematic-code>
  println(s"Result: $result") // or expect exception
```

Run it:
```bash
scala-cli run repro.scala
```

Record the result:
- **CONFIRMED** — crashed/threw exception as expected
- **PASSED** — no crash (may need different input to trigger)
- **COMPILE ERROR** — repro needs fixing (missing deps, wrong types)

## Step 5: Report

Present the final report grouped by severity:

```markdown
## Bug Hunt Report

### Critical (confirmed)
**<Pattern> in <File>:<Line>** — <Owner.method>
- Pattern: <description>
- Risk: <what can go wrong>
- Reproduction: <repro result>
- Fix: <suggested fix>
- Issue: <GitHub issue URL if found> (optional)

### High (confirmed)
...

### Medium (likely — needs review)
...

### Summary
- X confirmed, Y likely, Z false positives filtered
- N findings cross-referenced with M GitHub issues
- Environment: <OS>, <Scala version>, <JDK version>

### False positives suppressed
- N ZIO Ref.get calls (safe)
- N test-only secrets (acceptable)
- ...
```

## Tips

- For large codebases (1000+ findings), use `--severity critical` or `--severity high` first
- Use `--no-tests` to focus on production code
- Use `--path src/main/` to exclude test/benchmark directories
- Use `--bug-category security` to focus on security issues only
- Hotspots with high churn + many findings are highest priority for review
