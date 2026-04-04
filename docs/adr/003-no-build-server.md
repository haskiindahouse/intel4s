# ADR-003: No Build Server Dependency

**Date:** 2025-01-15
**Status:** Accepted
**Author:** Architecture Owner

## Context

Coding agents operate in diverse environments. Some repos use sbt, some mill, some gradle, some scala-cli. Build tools may be misconfigured, dependencies unresolved, or compilation broken. Scalex must provide code intelligence regardless of build state.

## Decision

Scalex has zero build server dependencies. It works with raw git repositories containing `.scala` files. No sbt, mill, gradle, BSP, or Metals required.

## Reasoning

- **Alternative 1: Integrate with BSP** — Would provide richer information (types, implicits) but requires a configured and working build. When the build is broken — exactly when developers need help most — BSP-based tools are useless.
- **Alternative 2: Integrate with specific build tools** — Would lock scalex to particular ecosystems. Supporting sbt + mill + gradle + scala-cli multiplies maintenance burden.
- **Chosen approach:** Parse sources directly via Scalameta (ADR-001), use git for file discovery (ADR-002). Works on any `.scala` repo, any build tool, any state of compilation.

## Consequences

+ Works on broken builds, partial checkouts, mid-refactoring states
+ Zero configuration — no `build.sbt`, no `build.sc`, no project setup
+ Agent can always run scalex even if compilation fails
- Cannot resolve dependencies or classpath-dependent information
- Cannot provide type information beyond what's syntactically visible
- Agent must run the actual build tool (`sbt compile`, `scala-cli compile`) for error checking

## Constraints for AI

- NEVER add a build tool dependency (sbt, mill, gradle, BSP, Metals)
- NEVER assume classpath, resolved dependencies, or compiled artifacts exist
- If a feature needs compilation — it's out of scope for scalex. The agent runs the compiler directly
- All file discovery goes through `git ls-files`, not build tool source sets
