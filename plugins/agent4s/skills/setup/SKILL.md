---
name: setup
description: "One-time project setup for agent4s Scala code intelligence. Detects build tool, analyzes project structure, and writes a Scala Code Intelligence section to CLAUDE.md so the agent knows when and how to use scalex. Run once per project, re-run to refresh."
disable-model-invocation: true
---

You are setting up agent4s for this Scala project. Follow these steps exactly.

## Step 1: Locate scalex-cli

The scalex-cli bootstrap script is at the path relative to this skill:
`../agent4s/scripts/scalex-cli`

Resolve the absolute path and verify it exists. If it doesn't exist, tell the user to reinstall the plugin.

## Step 2: Detect build tool

Run this command in the project root:

```bash
ls -d build.sbt project/ build.sc project.scala build.gradle build.gradle.kts 2>/dev/null
```

Map results:
- `build.sbt` or `project/` → **sbt**
- `build.sc` → **Mill**
- `project.scala` → **scala-cli**
- `build.gradle` or `build.gradle.kts` → **Gradle**
- None found → **unknown**

Also try to detect the Scala version:
- sbt: `grep -r "scalaVersion" build.sbt project/ 2>/dev/null | head -3`
- mill: `grep "def scalaVersion" build.sc 2>/dev/null`
- scala-cli: `grep "using scala" project.scala *.scala 2>/dev/null | head -3`

## Step 3: Run scalex overview

```bash
bash "<scalex-cli-path>" overview --concise -w "<project-root>"
```

Extract from the output:
- Total file count and symbol count
- Top 3-5 packages
- Key hub types (if shown)

## Step 4: Check SemanticDB availability

```bash
find "<project-root>" -name "*.semanticdb" -path "*/META-INF/*" 2>/dev/null | head -1
```

If any files found → SemanticDB is **available**.
If none found → SemanticDB is **not configured**.

## Step 5: Generate CLAUDE.md section

Read the existing `CLAUDE.md` in the project root (if it exists).

Generate the following section, filling in the detected values:

```markdown
<!-- agent4s:start -->
## Scala Code Intelligence (agent4s)

This project uses the [agent4s](https://github.com/scala-digest/agent4s) plugin for Scala code intelligence.

### Build tool
{detected build tool} {detected Scala version if found}

### Project structure
{2-4 lines from overview: file count, symbol count, top packages, key types}

### When to use scalex vs grep
- **Use scalex** for: finding definitions (`def`), implementations (`impl`), references (`refs`), type hierarchy (`hierarchy`), understanding types (`explain`), safe rename (`rename`), dead code (`unused`), call graphs (`call-graph`), method bodies (`body`), test structure (`tests`)
- **Use grep/Grep tool** for: non-Scala files, string literals, regex patterns in comments, files not yet git-tracked

### Available skills
- `/agent4s:scalex` — 35 commands for Scala code intelligence (always available)
- `/agent4s:setup` — re-run this setup to refresh project info
- `/agent4s:semanticdb` — enable SemanticDB for type-aware rename and call-graph
- `/agent4s:upgrade` — upgrade scalex binary to latest release
- `/agent4s:doctor` — diagnostic check: binary, SemanticDB, CLAUDE.md

### SemanticDB status
{If available: "Available. Use `--semantic` flag with `rename`, `call-graph`, and `refs` for type-aware precision."}
{If not configured: "Not configured. Run `/agent4s:semanticdb` to enable type-aware rename and call-graph."}

### Quick tips
- For complex tasks (refactoring, impact analysis, codebase exploration), the `scala-expert` agent is invoked automatically
- Use `explain <Type> --related` to understand a type before modifying it
- Use `refs <Symbol> --count` before any change to gauge impact
- Use `batch` mode when you need 3+ lookups: `echo -e "def Foo\nimpl Foo\nrefs Foo" | scalex batch`
<!-- agent4s:end -->
```

## Step 6: Write to CLAUDE.md

- If CLAUDE.md exists and contains `<!-- agent4s:start -->` through `<!-- agent4s:end -->` markers: **replace** the entire block between markers (inclusive)
- If CLAUDE.md exists but has no markers: **append** the section at the end of the file
- If CLAUDE.md does not exist: **create** it with just this section

Use the Edit tool for replacement or the Write tool for creation.

## Step 7: Confirm

Print a summary:
```
agent4s setup complete:
  Build tool: {tool} ({scala version})
  Project: {N} files, {M} symbols
  SemanticDB: {available/not configured}
  CLAUDE.md: {created/updated}
```

If SemanticDB is not configured, suggest: "Run `/agent4s:semanticdb` to enable type-aware features."
