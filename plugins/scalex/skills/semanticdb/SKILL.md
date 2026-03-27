---
name: semanticdb
description: "Enable SemanticDB for type-aware Scala code intelligence. Detects build tool, adds -Xsemanticdb to build configuration, compiles to generate .semanticdb files, and verifies. Enables --semantic flag for rename, call-graph, and refs."
disable-model-invocation: true
---

You are enabling SemanticDB for this Scala project so that intel4s can use type-aware features (`--semantic` flag for `rename`, `call-graph`, `refs`).

## Step 1: Check current state

First, check if SemanticDB is already available:

```bash
find "<project-root>" -name "*.semanticdb" -path "*/META-INF/*" 2>/dev/null | head -3
```

If files are found, tell the user SemanticDB is already available and they can use `--semantic` flag. Ask if they want to recompile anyway (in case .semanticdb files are stale).

## Step 2: Detect build tool

```bash
ls -d build.sbt project/ build.sc project.scala build.gradle build.gradle.kts 2>/dev/null
```

Map: `build.sbt`/`project/` → sbt, `build.sc` → Mill, `project.scala` → scala-cli, `build.gradle(.kts)` → Gradle.

If no build tool found, tell the user you cannot auto-configure and print manual instructions for each build tool.

## Step 3: Detect Scala version

This determines which configuration to apply:

**For sbt:**
```bash
grep -r "scalaVersion" build.sbt project/ 2>/dev/null | head -5
```
- Scala 3.x → use `scalacOptions += "-Xsemanticdb"`
- Scala 2.x → use `semanticdbEnabled := true`
- If unclear, default to `scalacOptions += "-Xsemanticdb"` (works for Scala 3, which is the common case)

**For Mill:**
```bash
grep "def scalaVersion" build.sc 2>/dev/null
```

**For scala-cli:**
```bash
grep "using scala" project.scala *.scala 2>/dev/null | head -3
```

## Step 4: Present the planned change and ask for confirmation

Show the user exactly what will be added and to which file. Use AskUserQuestion or just describe the change and wait for their OK.

### sbt (Scala 3)
File: `build.sbt`
```scala
// Enable SemanticDB for type-aware code intelligence
ThisBuild / scalacOptions += "-Xsemanticdb"
```

### sbt (Scala 2)
File: `build.sbt`
```scala
// Enable SemanticDB for type-aware code intelligence
ThisBuild / semanticdbEnabled := true
```

### Mill
File: `build.sc`
Add to the module definition:
```scala
override def scalacOptions = super.scalacOptions() ++ Seq("-Xsemanticdb")
```

### scala-cli
File: `project.scala` (or create it if it doesn't exist)
```scala
//> using options -Xsemanticdb
```

### Gradle (Kotlin DSL)
File: `build.gradle.kts`
```kotlin
tasks.withType<ScalaCompile> {
    scalaCompileOptions.additionalParameters = listOf("-Xsemanticdb")
}
```

### Gradle (Groovy DSL)
File: `build.gradle`
```groovy
tasks.withType(ScalaCompile) {
    scalaCompileOptions.additionalParameters = ['-Xsemanticdb']
}
```

## Step 5: Apply the edit

After the user confirms, use the Edit tool to add the configuration to the appropriate build file.

**Important**: Read the build file first to understand its structure. Place the new line in a sensible location:
- sbt: near other `scalacOptions` or `ThisBuild` settings
- Mill: inside the module's `def scalacOptions` override (create one if needed)
- scala-cli: with other `//> using` directives in project.scala
- Gradle: in the appropriate configuration block

## Step 6: Compile

Run the appropriate compile command:

| Build tool | Command |
|---|---|
| sbt | `sbt compile` |
| Mill | `mill _.compile` or `mill <module>.compile` |
| scala-cli | `scala-cli compile .` |
| Gradle | `gradle compileScala` |

This may take a while for large projects. Run with a generous timeout.

## Step 7: Verify

Check that .semanticdb files were generated:

```bash
find "<project-root>" -name "*.semanticdb" -path "*/META-INF/*" 2>/dev/null | wc -l
```

If files found, SemanticDB is working. If not, check:
- Did compilation succeed? Look at the compile output for errors
- Is the Scala version compatible? (`-Xsemanticdb` requires Scala 3.x)
- For Scala 2, is sbt version >= 1.3.0?

## Step 8: Update CLAUDE.md

If the project has a CLAUDE.md with `<!-- intel4s:start -->` markers (from `/intel4s:setup`), update the SemanticDB status line:

Find the line that says "Not configured" and replace it with:
```
Available. Use `--semantic` flag with `rename`, `call-graph`, and `refs` for type-aware precision.
```

## Step 9: Confirm

Print a summary:
```
SemanticDB enabled:
  Build file: {file} (added {setting})
  Compiled: {N} .semanticdb files generated
  Ready: use --semantic with rename, call-graph, refs
```
