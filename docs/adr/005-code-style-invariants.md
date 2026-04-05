# ADR-005: Code Style Invariants for AI Generation

**Date:** 2025-03-01
**Status:** Accepted
**Author:** Architecture Owner

## Context

AI agents generate Scala code that compiles but violates project conventions. Unnamed tuples, `return` statements, wildcard imports, and mutable state appear in generated code unless explicitly forbidden. Each violation requires manual correction.

## Decision

Enforce a small set of hard code style invariants that AI must never violate. These go beyond formatting — they are semantic constraints that prevent classes of bugs.

## Reasoning

- **Named tuples over unnamed:** `(List[Reference], Boolean)` is unreadable — caller must remember positional semantics. `(results: List[Reference], timedOut: Boolean)` is self-documenting. Scala 3 named tuples have zero runtime overhead.
- **No `return`:** Deprecated in Scala 3 lambdas. Inside `.foreach`, `.map`, `try`/`catch` it causes non-local return (throws exception). `scala.util.boundary` + `boundary.break` is the idiomatic replacement.
- **No mutable state in domain:** `var` and mutable collections make concurrent access unsafe and behavior unpredictable. All data types in `model.scala` must be immutable.
- **Explicit imports:** Wildcard imports pull in entire namespaces, cause ambiguity errors, and make it harder to understand dependencies.

## Consequences

+ Consistent, readable code across all AI-generated files
+ Eliminates entire classes of bugs (non-local return, race conditions, import ambiguity)
+ New AI sessions immediately know the rules from CLAUDE.md
- Slightly more verbose code (named tuple fields, explicit imports)
- AI may initially generate violations that need correction

## Constraints for AI

- ALWAYS use named tuples: `(name: Type, ...)` not `(Type, ...)`
- NEVER use `return` — use `boundary`/`break`, `match`, or `if`/`else`
- NEVER use `var` or mutable collections in `model.scala` types
- NEVER use wildcard imports — always list specific imports
- NEVER use `.par` — use `list.asJava.parallelStream()` in Scala 3.8
- When touching code with existing violations (legacy `return`, unnamed tuples) — fix them
- Use `pkg.stats` not `pkg.children` for Scalameta Pkg contents
- Use manual `traverse`/`visit` not `.collect` for Scalameta Tree
