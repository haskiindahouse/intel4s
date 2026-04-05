---
name: audit
description: "Comprehensive Scala codebase audit — code smells, dead code, complexity hotspots, test coverage gaps, security issues. Generates prioritized report with severity ratings. Does NOT fix issues — documents them for other skills to address. Triggers: 'audit this code', 'code review', 'assess code quality', 'health check'."
---

Run a systematic quality audit of this Scala project and generate a comprehensive report with prioritized findings.

**CRITICAL: This is an audit, not a fix. Document issues thoroughly — do NOT implement changes.**

## Step 1: Locate scalex-cli

The scalex-cli bootstrap script is at the path relative to this skill:
`../agent4s/scripts/scalex-cli`

## Step 2: Gather project context

Run these commands to build a complete picture:

```bash
bash "<scalex-cli>" overview --concise -w "<project>"
bash "<scalex-cli>" bug-hunt --hotspots --json -w "<project>"
bash "<scalex-cli>" entrypoints -w "<project>"
bash "<scalex-cli>" tests --count -w "<project>"
```

If a specific target was provided (e.g., a package or type), scope subsequent commands with `--path` or focus on that area.

## Step 3: Audit across 8 dimensions

Work through each dimension systematically:

### 1. Security (CRITICAL — start here)
Use the bug-hunt results from Step 2. Categorize:
- **Critical**: SQL injection, command injection, deserialization, hardcoded production secrets
- **High**: Weak crypto, XXE, path traversal, SSRF
- **Medium**: .head on collections, asInstanceOf, null usage

For each critical/high finding, read the surrounding code with `body --in <Owner> -C 5` to assess real risk.

### 2. Dead Code
```bash
bash "<scalex-cli>" unused -w "<project>"
bash "<scalex-cli>" unused --kind def -w "<project>"
```
Identify: unused types, unused methods, orphaned test helpers. Cross-reference with `coverage` to find test-only code.

### 3. Complexity Hotspots
Use bug-hunt `--hotspots` data. For top hotspot files:
```bash
bash "<scalex-cli>" members <Type> --verbose -w "<project>"
```
Flag: types with 20+ members, methods with 50+ lines, deeply nested inheritance.

### 4. Test Coverage
```bash
bash "<scalex-cli>" tests --count -w "<project>"
```
For each key type (from overview hub types):
```bash
bash "<scalex-cli>" coverage <Type> -w "<project>"
```
Flag: untested public APIs, types with zero test references.

### 5. Architecture
```bash
bash "<scalex-cli>" overview --architecture -w "<project>"
bash "<scalex-cli>" api <main-package> -w "<project>"
```
Flag: circular dependencies, god objects (hub types with 50+ refs), package coupling.

### 6. Code Style
Look for: inconsistent naming, mixed patterns (some files use `for` comprehensions, others use `flatMap`), unnecessary var usage, deprecated APIs.

### 7. Error Handling
For ZIO projects: search for `ZIO.die`, `throw` in effects, unhandled defects.
For Future-based: search for `Future` without `.recover`, `Await.result`.
```bash
bash "<scalex-cli>" grep "ZIO.die\\|throw" --no-tests -w "<project>"
bash "<scalex-cli>" grep "Await.result" --no-tests -w "<project>"
```

### 8. Dependencies
Check for: types that import from too many packages (high coupling), packages with only 1-2 types (fragmentation).
```bash
bash "<scalex-cli>" deps <HubType> --depth 2 -w "<project>"
```

## Step 4: Generate audit report

```markdown
## Audit Report — <project name>

### Summary
- Total issues: N (X critical, Y high, Z medium, W low)
- Files scanned: N
- Test coverage: M types tested out of N public types

### Security Findings (from bug-hunt)
[List critical/high with file:line and context]

### Dead Code
[List unused types and methods with locations]

### Complexity Hotspots
[Top 5 complex types/files with member counts]

### Test Coverage Gaps
[Untested public types/methods]

### Architecture Concerns
[Coupling issues, god objects, circular deps]

### Code Style Issues
[Inconsistencies, naming, patterns]

### Recommended Actions (prioritized)
1. [Critical] Fix security issue X — use `/agent4s:bug-hunt` for details
2. [High] Remove dead code — use `/agent4s:simplify`
3. [Medium] Improve test coverage — use `scaffold test`
4. [Low] Normalize naming — use `/agent4s:normalize`
```

NEVER:
- Fix issues during an audit — only document them
- Skip the security dimension — always check first
- Report without severity — every finding needs a rating
- Ignore test coverage — untested code is a liability
