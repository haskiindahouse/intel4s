# ADR-004: Bloom Filters for Reference Search

**Date:** 2025-02-10
**Status:** Accepted
**Author:** Architecture Owner

## Context

The `refs` and `imports` commands find all files referencing a given identifier. Naive approach: read every `.scala` file and text-search for the identifier. On scala3 (17.7k files), reading all files takes ~10-15s.

## Decision

Store a per-file Bloom filter of identifiers in the index. When searching for references, first check the Bloom filter — only read files where the filter says "maybe contains." False positive rate ~1%, so we read ~1% extra files but skip 99% of disk reads.

## Reasoning

- **Alternative 1: Full text index** — Store all identifiers with file positions. Accurate but massive index size increase. Would bloat `.scalex/index.bin` from ~2MB to potentially 50MB+ on large codebases.
- **Alternative 2: No index, brute force** — Read all files every time. ~10-15s on scala3. Unacceptable for interactive use.
- **Alternative 3: Bloom filters** — Compact probabilistic data structure. ~10 bits per identifier, negligible index size increase. False positives (~1%) mean we read a few extra files. False negatives are impossible — we never miss a reference.
- **Why Bloom filters win:** Best tradeoff between index size and query speed. Index grows by kilobytes, not megabytes. Query goes from reading 17.7k files to ~200 candidates.

## Consequences

+ Reference search is fast: filter 17.7k files down to ~200 candidates in microseconds
+ Index size impact minimal: Bloom filters add ~100KB for 17.7k files
+ No false negatives: every actual reference is found
- ~1% false positives: we read a few extra files (acceptable)
- Bloom filter must be rebuilt when file content changes (handled by OID caching, ADR-002)
- Uses Guava's BloomFilter implementation — Guava is our only non-Scalameta dependency

## Constraints for AI

- `refs` and `imports` are NOT index-based — they use Bloom filter shortlisting + text search
- Text search uses word-boundary matching, not exact substring — respect this in tests
- Bloom filter false positive rate is tuned for ~1% — don't change without benchmarking
- Both commands have a 20-second timeout for worst-case scenarios
- Guava group ID is `com.google.guava:guava`, NOT `com.google.common:guava`
- GraalVM native image needs `--initialize-at-run-time` for Guava hash classes (see `build-native.sh`)
