# ADR-002: Git OID-based Caching

**Date:** 2025-01-15
**Status:** Accepted
**Author:** Architecture Owner

## Context

Scalex must reindex a workspace quickly. On large codebases (17.7k files in scala3), reparsing every file on every invocation is too slow (~30s). We need a way to detect which files changed and skip unchanged ones.

## Decision

Use Git object IDs (OIDs/blob SHAs) from `git ls-files --stage` as the cache key. Store extracted symbols in a binary index at `.scalex/index.bin` keyed by OID. On subsequent runs, compare OIDs — skip files whose OID hasn't changed.

## Reasoning

- **Alternative 1: File modification timestamps** — Unreliable across git operations (checkout, rebase, cherry-pick change mtime). Would cause cache misses after every branch switch.
- **Alternative 2: Content hashing (SHA-256)** — Requires reading every file from disk to compute hash. On 17.7k files, disk I/O alone is significant.
- **Alternative 3: Git OIDs** — Available for free from `git ls-files --stage` (single git process, no file reads). OID changes if and only if file content changes. Perfectly reliable across all git operations.
- **Why OIDs win:** Zero disk reads to detect changes. One `git ls-files` call gives all OIDs. Cache hit rate on warm index approaches 100% for unchanged files.

## Consequences

+ Warm index time is near-instant for unchanged workspaces
+ No false cache hits — OID is a content hash
+ No disk reads for change detection
- Requires git repository (not a limitation — scalex targets git repos)
- Untracked files are invisible to `git ls-files` (by design — only index tracked code)
- Binary index format (`.scalex/index.bin`) must be versioned for schema changes

## Constraints for AI

- NEVER use file modification time for cache invalidation
- NEVER read file contents just to check if it changed — compare OIDs instead
- The `.scalex/` directory is local cache, not committed to git (add to `.gitignore`)
- Index format changes require bumping the index version magic number
- When adding new data to the index, measure index size impact on scala3 benchmark
