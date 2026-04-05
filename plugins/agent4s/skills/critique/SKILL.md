---
name: critique
description: "Evaluate Scala architecture and design quality. Assesses SOLID principles, coupling, cohesion, type hierarchy design, effect system usage, and package structure. Provides actionable feedback, not fixes. Triggers: 'review architecture', 'critique this design', 'is this well designed', 'assess code structure'."
---

Conduct a holistic architecture critique of this Scala codebase or specific area. Evaluate whether the design actually works — not just whether it compiles.

**CRITICAL: This is a review, not a refactoring. Provide direct, specific feedback with concrete examples.**

## Step 1: Locate scalex-cli

`../scalex/scripts/scalex-cli`

## Step 2: Understand the architecture

```bash
bash "<scalex-cli>" overview --architecture -w "<project>"
bash "<scalex-cli>" entrypoints -w "<project>"
bash "<scalex-cli>" summary <top-package> -w "<project>"
```

For the 3-5 most important types (hub types from overview):
```bash
bash "<scalex-cli>" explain <Type> --related --inherited -w "<project>"
```

## Step 3: Evaluate across 7 dimensions

### 1. Type Hierarchy Design
```bash
bash "<scalex-cli>" hierarchy <CoreTrait> --depth 3 -w "<project>"
```
- Is the trait/class hierarchy reasonable? (< 4 levels deep)
- Are traits focused (single responsibility) or god-traits?
- Is there diamond inheritance?
- Are sealed hierarchies used where appropriate?

### 2. Coupling Analysis
```bash
bash "<scalex-cli>" api <package> --used-by <other-package> -w "<project>"
bash "<scalex-cli>" deps <Type> --depth 2 -w "<project>"
```
- How many cross-package dependencies exist?
- Are there circular dependencies?
- Is the dependency direction clean? (domain → infra, not reverse)

### 3. Cohesion
```bash
bash "<scalex-cli>" package <package> --explain -w "<project>"
```
- Does each package have a clear purpose?
- Are related types in the same package?
- Are there packages with unrelated responsibilities?

### 4. Effect System Usage (ZIO/Cats Effect/Future)
```bash
bash "<scalex-cli>" grep "ZIO\\[" --no-tests --count -w "<project>"
bash "<scalex-cli>" grep "Future\\[" --no-tests --count -w "<project>"
```
- Is there a consistent effect type? (mixing ZIO + Future = red flag)
- Are effects properly layered? (ZLayer composition)
- Is error handling explicit? (typed errors vs Throwable)

### 5. API Surface
```bash
bash "<scalex-cli>" api <package> -w "<project>"
bash "<scalex-cli>" members <CoreTrait> --verbose -w "<project>"
```
- Is the public API minimal? (only export what's needed)
- Are method signatures clear? (named parameters, proper types)
- Are there leaky abstractions? (implementation details in traits)

### 6. Pattern Consistency
- Is there one way to do things? (all services follow same pattern)
- Are there anti-patterns? (service locator, static singletons, god objects)
- ZIO: are layers composed consistently?
- Is error handling consistent across modules?

### 7. Testability
```bash
bash "<scalex-cli>" impl <CoreTrait> -w "<project>"
bash "<scalex-cli>" tests --count -w "<project>"
```
- Can core logic be tested without infrastructure?
- Are there test implementations (Fake/Mock) for key traits?
- Is the test structure mirroring the source structure?

## Step 4: Generate critique

```markdown
## Architecture Critique — <project or area>

### Overall Impression
One paragraph gut reaction. What works, what doesn't, single biggest opportunity.

### What's Working Well
2-3 design decisions that are genuinely good. Be specific about why.

### Priority Issues (ordered by impact)
For each:
- **What**: Name the issue clearly
- **Where**: Specific types/packages affected
- **Why it matters**: How this hurts maintainability/correctness
- **Suggestion**: Concrete next step
- **Command**: Which agent4s skill/command to use

### Design Smells
Patterns that aren't broken but could become problems:
- Growing types (approaching god-object territory)
- Inconsistent patterns across modules
- Unnecessary abstractions

### Questions to Consider
Provocative questions that unlock better design decisions.
```

NEVER:
- Be vague ("the code could be better") — always specific with file:line
- Critique without understanding context — read the code first
- Suggest rewriting everything — incremental improvement is always better
- Ignore what's working — acknowledge good decisions
