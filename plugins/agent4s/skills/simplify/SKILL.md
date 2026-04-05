---
name: simplify
description: "Reduce Scala code complexity ruthlessly. Extract methods, flatten nesting, remove unnecessary abstractions, eliminate dead code, simplify type hierarchies. Less code = fewer bugs. Triggers: 'simplify', 'reduce complexity', 'refactor to simpler', 'this code is too complex', 'too many abstractions'."
---

Strip code to its essence. Every line must earn its place. Complexity is the enemy.

**CRITICAL: Understand the code deeply before simplifying. Read it, trace its call graph, understand WHY it exists.**

## Step 1: Understand what you're simplifying

```bash
bash "<scalex-cli>" overview --concise -w "<project>"
```

If a specific target:
```bash
bash "<scalex-cli>" explain <Target> --verbose --inherited --related -w "<project>"
bash "<scalex-cli>" call-graph <method> --in <Target> -w "<project>"
bash "<scalex-cli>" refs <Target> --count -w "<project>"
```

**Ask yourself**: How many callers does this have? If you simplify it, what breaks?

## Step 2: Identify complexity

### Dead code
```bash
bash "<scalex-cli>" unused -w "<project>"
bash "<scalex-cli>" unused --kind def -w "<project>"
```
Remove it. Dead code is noise.

### Over-abstraction
```bash
bash "<scalex-cli>" hierarchy <Type> --depth 4 -w "<project>"
```
Signs:
- Trait with only one implementation → inline the trait
- Abstract class with no shared logic → use trait
- 4+ levels of inheritance → flatten
- Type class with one instance → just use the instance

### Unnecessary indirection
```bash
bash "<scalex-cli>" call-graph <method> --in <Type> -w "<project>"
```
Signs:
- Method that just delegates to another method → inline
- Service that wraps another service with no added logic → remove
- DTO that mirrors another DTO → use one

### Large types
```bash
bash "<scalex-cli>" members <Type> --verbose -w "<project>"
```
Signs:
- 20+ members → split by responsibility
- Methods that don't use `this` → extract to companion or utility
- Mixed concerns (HTTP handling + business logic) → separate layers

### Deep nesting
Read method bodies and look for:
- 3+ levels of `if`/`match` nesting → extract to methods
- Long `for` comprehensions (10+ generators) → split into steps
- Callback pyramids → use for-comprehension

## Step 3: Simplification recipes

### Recipe: Flatten inheritance
```
Before: trait A → trait B → trait C → class D
After: trait A → class D (merge B and C into D if they have no other impls)
```
Verify with: `bash "<scalex-cli>" impl <TraitToRemove> -w "<project>"` — if only 1 impl, safe to inline.

### Recipe: Extract method
```
Before: 50-line method with 3 logical sections
After: 3 methods of 15 lines each, called from original
```
Use `body --in <Owner>` to read the method, identify sections, extract.

### Recipe: Remove dead abstraction
```
Before: trait UserRepo + class UserRepoLive (only implementation)
After: class UserRepo (concrete, no trait)
```
Verify with: `bash "<scalex-cli>" impl UserRepo -w "<project>"` — if 1 impl + no test fake, inline.
**EXCEPTION**: If there's a Fake/Mock impl for testing, keep the trait.

### Recipe: Simplify error hierarchy
```
Before: 15 specific error case classes
After: 5 meaningful error groups + message field
```
Verify with: `bash "<scalex-cli>" hierarchy <ErrorType> -w "<project>"` — are all cases used?

### Recipe: Inline delegation
```
Before: def process(x: Int) = helper.doProcess(x)
After: directly use helper.doProcess(x) at call sites
```
Verify with: `bash "<scalex-cli>" refs process --count -w "<project>"` — how many callers?

## Step 4: Apply changes

For each simplification:
1. **Impact check**: `refs <Target> --count` — who uses this?
2. **Read the code**: `body <Target> --in <Owner> --imports`
3. **Apply the change**: Edit tool
4. **Verify**: no callers broken, tests still reference correct names

## Step 5: Verify

```bash
bash "<scalex-cli>" overview --concise -w "<project>"
```

Compare: fewer types? Fewer symbols? Simpler hierarchy?

NEVER:
- Simplify code you don't understand — read it first
- Remove abstractions that enable testability (trait + fake pattern)
- Inline widely-used utility methods — that creates duplication
- Simplify during a bug fix — do it as a separate step
- Remove error handling in the name of simplicity
- Break public APIs without checking all callers
