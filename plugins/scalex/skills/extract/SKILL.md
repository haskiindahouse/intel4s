---
name: extract
description: "Extract reusable components from Scala code — traits, type classes, shared modules, common patterns. Identifies duplication and consolidates into well-designed abstractions. Triggers: 'extract component', 'find duplication', 'create reusable', 'DRY this up', 'extract trait', 'share across modules'."
---

Identify duplication and repeated patterns, then extract them into well-designed reusable components.

**CRITICAL: Only extract when there are 3+ instances of the pattern. Two similar things are coincidence, not duplication.**

## Step 1: Find duplication candidates

### Structural duplication (types with similar shapes)
```bash
bash "<scalex-cli>" ast-pattern --has-method <commonMethod> -w "<project>"
```
Look for: types that implement the same set of methods but don't share a trait.

### Code duplication (similar implementations)
```bash
bash "<scalex-cli>" grep "<repeated-pattern>" --no-tests --count -w "<project>"
```
Look for: copy-pasted logic across files.

### API pattern duplication
```bash
bash "<scalex-cli>" package <package> --explain -w "<project>"
```
Look for: every service has `create`, `update`, `delete`, `findById` but no shared CRUD trait.

### Overrides with identical bodies
```bash
bash "<scalex-cli>" overrides <method> --body -w "<project>"
```
Look for: 3+ implementations with nearly identical logic — extract to parent.

## Step 2: Design the extraction

For each extraction candidate, decide:

### Extract trait
When: 3+ classes share the same method signatures.
```bash
bash "<scalex-cli>" members <ClassA> --brief -w "<project>"
bash "<scalex-cli>" members <ClassB> --brief -w "<project>"
bash "<scalex-cli>" members <ClassC> --brief -w "<project>"
```
Compare: what methods overlap?

### Extract utility object
When: multiple classes have the same private helper logic.
Use `body` to compare implementations:
```bash
bash "<scalex-cli>" body <helperMethod> --in <ClassA> -w "<project>"
bash "<scalex-cli>" body <helperMethod> --in <ClassB> -w "<project>"
```

### Extract type class
When: behavior needs to be polymorphic without inheritance.
Pattern: `trait Encoder[A]` with `given` instances.

### Extract module / package
When: a group of related types in a large package should be a sub-package.
```bash
bash "<scalex-cli>" summary <large-package> -w "<project>"
```
If a sub-group has 5+ types → extract to sub-package.

## Step 3: Implement extraction

### Recipe: Extract shared trait
1. Identify common methods across classes
2. Create trait with those method signatures
3. Add `extends NewTrait` to each class
4. Verify: `bash "<scalex-cli>" impl NewTrait -w "<project>"` — shows all implementations

### Recipe: Extract utility
1. Identify duplicated logic
2. Create object with the shared method
3. Replace all occurrences with calls to the new utility
4. Verify: `bash "<scalex-cli>" refs NewUtility --count -w "<project>"` — all former duplicates now reference it

### Recipe: Extract type class
1. Define the type class trait: `trait JsonCodec[A]`
2. Provide given instances for each type
3. Replace ad-hoc implementations with type class usage
4. Verify: `bash "<scalex-cli>" search JsonCodec -w "<project>"` — trait + instances visible

### Recipe: Generate missing implementations
```bash
bash "<scalex-cli>" scaffold impl <NewClass> -w "<project>"
```
Generates stubs for unimplemented abstract members with type parameter substitution.

## Step 4: Verify

After extraction:
```bash
# All implementations found?
bash "<scalex-cli>" impl <ExtractedTrait> -w "<project>"

# Old duplicated code removed?
bash "<scalex-cli>" grep "<old-duplicated-pattern>" --count -w "<project>"

# References clean?
bash "<scalex-cli>" refs <ExtractedComponent> --count -w "<project>"

# Dead code?
bash "<scalex-cli>" unused -w "<project>"
```

NEVER:
- Extract with fewer than 3 instances — that's premature abstraction
- Extract for "future reuse" — extract when you HAVE duplication, not when you MIGHT
- Create deep type class hierarchies — keep it flat
- Break existing APIs without checking callers
- Extract test utilities into production code
