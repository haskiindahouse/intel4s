---
name: polish
description: "Final quality pass on Scala code before shipping. Fixes naming inconsistencies, unused imports, formatting, missing docs, redundant code, and small issues that separate good from great. Triggers: 'polish this code', 'clean up', 'final pass', 'tidy up before PR'."
---

Perform a meticulous final pass to catch all the small details that separate good work from great work.

**CRITICAL: Polish is the last step, not the first. Don't polish code that's not functionally complete.**

First: Understand what you're polishing.
```bash
bash "<scalex-cli>" overview --concise -w "<project>"
```

If a specific target was provided:
```bash
bash "<scalex-cli>" explain <Target> --verbose --inherited -w "<project>"
bash "<scalex-cli>" body <Target> --imports -w "<project>"
```

## Polish systematically across these dimensions

### 1. Naming
- Are names descriptive and consistent? (`processPayment` not `doIt`, `userId` not `uid`)
- Do types use standard Scala conventions? (PascalCase types, camelCase methods, UPPER_SNAKE constants)
- Are boolean methods/vals prefixed with `is`/`has`/`can`/`should`?
- Are collections named with plurals? (`users` not `userList`)

### 2. Imports
- Remove unused imports
- Organize: stdlib → third-party → project (separated by blank lines)
- Use specific imports over wildcards where possible
- Remove redundant renames

### 3. Documentation
- Do public APIs have scaladoc? (traits, public methods)
- Are complex algorithms explained with comments?
- Are TODOs and FIXMEs addressed or tracked?
- Remove outdated comments that don't match the code

### 4. Method Signatures
- Are return types explicit on public methods?
- Are default parameters used instead of overloads?
- Are named parameters used for boolean arguments? (`validate(strict = true)` not `validate(true)`)
- Is the parameter order intuitive? (required before optional)

### 5. Error Handling
- Are errors specific? (`UserNotFound(id)` not `RuntimeException("not found")`)
- ZIO: typed errors on the E channel?
- Are error messages helpful? (include context, not just "error occurred")

### 6. Redundancy
- Remove dead code paths (unreachable branches)
- Simplify: `if (x == true)` → `if (x)`, `list.filter(x => x.isActive)` → `list.filter(_.isActive)`
- Replace `Option(x).getOrElse(default)` with `if (x != null) x else default` when appropriate
- Consolidate duplicate logic into shared methods

### 7. Type Safety
- Replace `Any` with specific types where possible
- Use sealed traits for ADTs
- Use named tuples (Scala 3) instead of anonymous tuples
- Use opaque types for domain IDs (`opaque type UserId = String`)

### 8. Consistency
- Same patterns across similar code (all repositories follow same style)
- Same error handling approach across modules
- Same import style across files

## Apply changes

Use the Edit tool to make small, targeted improvements. Work file by file.

After each file:
```bash
bash "<scalex-cli>" symbols <file> --summary -w "<project>"
```

## Polish checklist

- [ ] No unused imports
- [ ] No TODO/FIXME without tracking
- [ ] Public APIs have return types
- [ ] Boolean params use named arguments
- [ ] Error messages are specific and helpful
- [ ] No redundant code (`if (true)`, `list.map(x => x)`)
- [ ] Naming is consistent across files
- [ ] No compiler warnings

NEVER:
- Polish before the feature is functionally complete
- Change behavior while polishing — this is cosmetic only
- Rewrite large sections — small targeted improvements only
- Remove code you don't understand — read it first with `explain` or `body`
