---
name: normalize
description: "Align Scala code to project conventions and patterns. Ensures consistent style, naming, error handling, effect patterns, and module structure across the codebase. Triggers: 'normalize', 'make consistent', 'align to conventions', 'fix inconsistencies'."
---

Analyze the codebase to discover its established patterns, then systematically align deviant code to match.

## Step 1: Discover project conventions

**CRITICAL: Don't impose external conventions — discover what THIS project already does.**

```bash
bash "<scalex-cli>" overview --concise -w "<project>"
bash "<scalex-cli>" package <main-package> --explain -w "<project>"
```

Identify the dominant patterns by examining the most-used types:

```bash
bash "<scalex-cli>" explain <MostUsedType1> --verbose -w "<project>"
bash "<scalex-cli>" explain <MostUsedType2> --verbose -w "<project>"
```

Document what you find:
- **Effect system**: ZIO? Cats Effect? Future? Mixed?
- **Error handling**: Typed errors? Exception hierarchy? Either?
- **Naming**: camelCase methods? Verb-first? Noun-first?
- **Module pattern**: Trait + Live + Fake? Cake pattern? Constructor injection?
- **Test pattern**: munit? ScalaTest? ZIO Test? Naming convention?
- **Import style**: Specific? Wildcard? Organized?

## Step 2: Find deviations

For each convention, search for violations:

```bash
# Find files that deviate from the dominant pattern
bash "<scalex-cli>" grep "<deviant-pattern>" --no-tests -w "<project>"
```

Common deviations to look for:
- **Mixed effect types**: Some files use Future, most use ZIO
- **Inconsistent error handling**: Some throw, some return typed errors
- **Naming inconsistencies**: `UserService` vs `UserSvc` vs `UserHandler`
- **Module structure**: Some modules have Fake implementations, others don't
- **Import organization**: Some files have organized imports, others don't

## Step 3: Create normalization plan

For each deviation:
1. What's the dominant convention?
2. What files deviate?
3. What's the minimal change to align?

**IMPORTANT: Normalize to the majority pattern. If 80% of files use ZIO and 20% use Future, normalize to ZIO.**

## Step 4: Apply normalizations

Work module by module, not file by file. For each module:

1. Read the current state:
   ```bash
   bash "<scalex-cli>" package <module-package> --explain -w "<project>"
   ```

2. Identify deviations from the plan

3. Apply changes using Edit tool

4. Verify with:
   ```bash
   bash "<scalex-cli>" members <ModifiedType> --verbose -w "<project>"
   ```

### Normalization dimensions

**Effect system alignment**:
- Replace `Future` with `ZIO.fromFuture` where project uses ZIO
- Wrap blocking calls in `ZIO.attemptBlocking`
- Ensure error types are consistent (`AppError` not random `Exception`)

**Error handling**:
- Replace `throw` with typed errors
- Replace `Try` with `ZIO.attempt` (in ZIO projects)
- Ensure error ADTs match project patterns

**Naming**:
- Use `rename` command for safe renames:
  ```bash
  bash "<scalex-cli>" rename OldName NewName -w "<project>"
  ```
- Apply renames using Edit tool

**Module structure**:
- Missing Fake implementations → generate with `scaffold impl`
- Missing test files → generate with `scaffold test`

**Import organization**:
- Group: scala stdlib → java → third-party → project
- Remove unused
- Prefer specific over wildcard

## Step 5: Verify

```bash
bash "<scalex-cli>" overview --concise -w "<project>"
```

Confirm: do all modules now follow the same patterns?

NEVER:
- Impose your preferred style — discover the project's style
- Normalize everything at once — work module by module
- Change working code for aesthetic reasons without clear benefit
- Normalize test code to match production patterns (tests have different needs)
- Create new patterns — only align to existing ones
