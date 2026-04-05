---
name: doctor
description: "Diagnostic check for agent4s. Verifies scalex binary works, index builds correctly, SemanticDB availability, and CLAUDE.md configuration. Run when something seems broken."
disable-model-invocation: true
---

You are running diagnostics for agent4s in this project. Run each check and report a status summary.

## Locate scalex-cli

The scalex-cli bootstrap script is at the path relative to this skill:
`../agent4s/scripts/scalex-cli`

Resolve the absolute path. If it doesn't exist, report FAIL immediately and suggest reinstalling the plugin.

## Check 1: Binary

```bash
bash "<scalex-cli-path>" --version
```

- **OK**: prints version number (e.g. `1.38.0`)
- **FAIL**: error or no output

If FAIL, check:
- Permission denied → suggest `chmod +x <scalex-cli-path>`
- macOS quarantine → check with `xattr -l ~/.cache/agent4s/* 2>/dev/null | grep quarantine` and suggest `xattr -d com.apple.quarantine ~/.cache/agent4s/*`
- Binary not found → the bootstrap script should auto-download; run `bash "<scalex-cli-path>" --version` again and check stderr for download errors

## Check 2: Index

```bash
bash "<scalex-cli-path>" overview -w "<project-root>" 2>&1 | head -5
```

- **OK**: shows file count, symbol count (e.g. "228 files, 1791 symbols")
- **FAIL**: error message or no Scala files found

If FAIL, check:
- Is this a git repository? (`git rev-parse --git-dir`)
- Are there .scala files tracked by git? (`git ls-files '*.scala' | head -5`)
- Parse errors in Scala files may cause partial indexing — this is OK, scalex skips unparseable files

## Check 3: SemanticDB

```bash
find "<project-root>" -name "*.semanticdb" -path "*/META-INF/*" 2>/dev/null | wc -l
```

- **OK**: N files found → SemanticDB available, `--semantic` flag works
- **NOT CONFIGURED**: 0 files → suggest running `/agent4s:semanticdb`

If OK, also verify scalex can load them:
```bash
bash "<scalex-cli-path>" rename TestSymbol TestSymbol --semantic -w "<project-root>" 2>&1 | head -3
```
Check if it says "Using semantic rename (N compiled files)" or "No SemanticDB data found".

## Check 4: CLAUDE.md

Read the project's CLAUDE.md and check for `<!-- agent4s:start -->` marker.

- **OK**: marker found → agent4s section is configured
- **NOT CONFIGURED**: no marker or no CLAUDE.md → suggest running `/agent4s:setup`

If OK, also check if the section content is stale:
- Does the build tool still match?
- Does the SemanticDB status match the actual state from Check 3?

## Check 5: macOS quarantine (macOS only)

Only run this check on macOS (check `uname -s`):

```bash
xattr -l ~/.cache/agent4s/* 2>/dev/null | grep -c quarantine
```

- **OK**: 0 matches → no quarantine issues
- **WARNING**: quarantine attribute found → suggest `xattr -d com.apple.quarantine ~/.cache/agent4s/*`

## Summary

Print a status table:

```
agent4s doctor:
  Binary:     {OK vX.Y.Z | FAIL: reason}
  Index:      {OK (N files, M symbols) | FAIL: reason}
  SemanticDB: {OK (N files) | Not configured — run /agent4s:semanticdb}
  CLAUDE.md:  {OK | Not configured — run /agent4s:setup}
  Quarantine: {OK | WARNING: run xattr -d ...}
```

If everything is OK, print: "All checks passed. agent4s is ready."

If any check failed, print the specific fix for each failure. Prioritize: Binary > Index > SemanticDB > CLAUDE.md > Quarantine.
