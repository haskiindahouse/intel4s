# ADR-001: Scalameta over Presentation Compiler

**Date:** 2025-01-15
**Status:** Accepted
**Author:** Architecture Owner

## Context

Scalex needs to parse Scala source files and extract symbol information (classes, traits, objects, methods, types). The two main options for Scala code analysis are:
1. Scala 3 Presentation Compiler (PC) — full semantic analysis
2. Scalameta — syntactic AST parsing from source

## Decision

Use Scalameta for all source parsing. No presentation compiler, no build server, no compilation required.

## Reasoning

- **Alternative 1: Presentation Compiler** — Requires compiled `.class`/`.tasty` files on classpath. This means the project must be compiled first, which reintroduces build server dependency (BSP/metals). A coding agent would need a working build before scalex can provide intelligence — defeating the purpose of a zero-setup tool.
- **Alternative 2: Scalameta** — Parses source directly from `.scala` files. No compilation, no classpath, no build server. Works on any Scala file, even if the project doesn't compile. Tradeoff: no type inference, no implicit resolution, no cross-file semantic analysis.
- **Why Scalameta wins:** The target users are coding agents that need fast symbol lookup. They don't need full semantic analysis — they need "where is class X defined" and "where is symbol Y referenced." Scalameta answers both. Agents can run the compiler directly for type checking.

## Consequences

+ Zero-setup: works on any git repo with `.scala` files, even non-compiling ones
+ No build tool dependency: no sbt, mill, gradle, or BSP required
+ Fast: parsing is orders of magnitude faster than compilation
- No type information: can't resolve inferred types, implicits, or overloads by type signature
- No cross-file semantic analysis: references are found via text search, not semantic resolution
- Scala 3 syntax coverage depends on Scalameta release cycle

## Constraints for AI

- NEVER add presentation compiler, metals, BSP, or any build-server dependency
- NEVER assume compiled artifacts (`.class`, `.tasty`) exist
- All symbol extraction MUST go through Scalameta's `dialects.Scala3` parser (with Scala 2.13 fallback)
- Reference search uses text matching + bloom filters, NOT semantic resolution
- If a Scalameta limitation blocks a feature, prefer degraded accuracy over adding compilation
