# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What is Scalex

Scalex is a Scala code intelligence CLI for AI agents. It provides fast symbol search, find definitions, and find references — without requiring an IDE, build server, or compilation. Designed as a Claude Code plugin.

## Build & Run

```bash
# Run via scala-cli (development)
scala-cli run scalex.scala -- <command> [args...]

# Build GraalVM native image (requires GraalVM)
./build-native.sh
# Output: plugin/bin/scalex (26MB standalone binary)

# Run native binary
./plugin/bin/scalex <command> [args...]

# Validate Claude Code plugin structure
claude plugin validate plugin/
```

## Architecture

Single-file implementation at `scalex.scala` (~400 lines, Scala 3.8.2, JDK 21+).

### Pipeline

```
git ls-files --stage → Scalameta parse → in-memory index → query
                              ↓
                    .scalex/index.bin (binary cache, OID-keyed, bloom filters)
```

1. **Git discovery**: `git ls-files --stage` returns all tracked `.scala` files with their OIDs
2. **Symbol extraction**: Scalameta parses source ASTs, extracts top-level symbols (class/trait/object/def/val/type/enum/given/extension)
3. **OID caching**: On subsequent runs, compares OIDs — skips unchanged files entirely
4. **Persistence**: Binary format with string interning at `.scalex/index.bin`
5. **Bloom filters**: Per-file bloom filter of identifiers — `refs` only reads candidate files

### Key design choices

- **Scalameta, not presentation compiler**: Scala 3's PC requires compiled `.class`/`.tasty` on classpath, which reintroduces build server dependency. Scalameta parses source directly.
- **Git OIDs for caching**: Available free from `git ls-files --stage`, no disk reads needed to detect changes.
- **No build server**: AI agents can run `./mill __.compile` directly for error checking.

### Dependencies

- `org.scalameta::scalameta:4.15.2` — AST parsing
- `com.google.guava:guava:33.4.0-jre` — bloom filters

## Plugin structure

```
plugin/
├── .claude-plugin/plugin.json    # Manifest
├── skills/scalex/SKILL.md        # Teaches Claude when/how to use scalex
├── scripts/scalex                # Launcher (native binary → scala-cli fallback)
└── bin/scalex                    # Native binary (gitignored, built by build-native.sh)
```

Use `--plugin-dir` for local development: `claude --plugin-dir plugin/`

## Performance benchmarks (native image)

| Project | Files | Symbols | Cold Index | Warm Index |
|---|---|---|---|---|
| circe-sanely-auto | 92 | 259 | ~50ms | ~10ms |
| mill | 1,415 | 12,778 | 214ms | 50ms |
| large monorepo | 13,958 | 100,891 | 3.3s | 364ms |

## Gotchas

- **Guava group ID**: `com.google.guava:guava`, NOT `com.google.common:guava`
- **GraalVM native image**: Guava needs `--initialize-at-run-time=com.google.common.hash.Striped64,com.google.common.hash.LongAdder,com.google.common.hash.BloomFilter,com.google.common.hash.BloomFilterStrategies` (see `build-native.sh`)
- **No `.par` in Scala 3.8**: Use `list.asJava.parallelStream()` instead of `list.par`
- **Scalameta Tree**: `.collect` doesn't work on Tree in Scala 3 — use manual `traverse` + `visit` pattern
