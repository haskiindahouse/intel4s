---
name: submit
description: "Submit feedback to improve agent4s. Report false positives from bug-hunt, commands that returned empty results, missing patterns, or feature requests. Builds a community feedback loop. Triggers: \"this is a false positive\", \"agent4s missed this\", \"wrong result\", \"submit feedback\", \"report issue\", \"this pattern is wrong\"."
---

You are helping the user submit feedback to improve agent4s. This feedback goes to the agent4s GitHub repository as a structured issue, helping maintainers improve bug patterns, reduce false positives, and fix empty-result queries.

**Privacy:** NO source code, file paths, or symbol names from the user's project are included. Only anonymized metadata.

## Step 1: Determine feedback category

Ask the user what happened. Classify into one of:

| Category | When | Label |
|---|---|---|
| `false-positive` | bug-hunt flagged something that isn't a bug | `community:false-positive` |
| `missing-pattern` | A real bug pattern agent4s doesn't detect | `community:missing-pattern` |
| `empty-result` | A command returned 0 results when it shouldn't have | `community:empty-result` |
| `wrong-result` | A command returned incorrect or misleading output | `community:wrong-result` |
| `perf-issue` | A command was unreasonably slow | `community:perf` |
| `feature-request` | User wants a new capability | `community:feature-request` |

## Step 2: Collect context (anonymized)

Gather this metadata — do NOT include actual source code or file paths from the user's project:

```bash
# Agent4s version
bash "<path-to-scalex-cli>" --version 2>/dev/null || echo "unknown"

# Workspace stats (anonymized)
bash "<path-to-scalex-cli>" overview -w "<workspace>" --json 2>/dev/null | head -5
```

Build a signal summary:

```
agent4s version: <version>
workspace: <file_count> files, <symbol_count> symbols
category: <category>
command: <the command that had the issue, e.g. "bug-hunt --severity high">
pattern: <if false-positive, which pattern ID, e.g. "UnsafeGet">
description: <user's 1-2 sentence description>
```

## Step 3: Confirm with user

Show the signal summary to the user. Ask:
- "Does this look right? I will NOT include any source code or file paths."
- "Anything to add?"

Wait for confirmation before proceeding.

## Step 4: Submit via GitHub issue

Check if `gh` CLI is available and authenticated:

```bash
gh auth status 2>&1
```

If authenticated, create the issue:

```bash
gh issue create \
  --repo scala-digest/agent4s \
  --title "[community:<category>] <short description>" \
  --label "community-signal,<category-label>" \
  --body "$(cat <<'ISSUE_EOF'
## Community Signal

**Category:** <category>
**agent4s version:** <version>
**Workspace size:** <file_count> files, <symbol_count> symbols

## What happened

<user description>

## Command

```
<the command that had the issue>
```

## Expected behavior

<what the user expected>

## Additional context

<anything the user added>

---
*Submitted via `/agent4s:submit`. No source code included.*
ISSUE_EOF
)"
```

If `gh` is not available, format the issue as markdown and tell the user:
- "I couldn't submit automatically (gh CLI not authenticated)."
- "You can paste this into a new issue at: https://github.com/scala-digest/agent4s/issues/new"
- Show the formatted issue body.

## Step 5: Confirm

Report the issue URL to the user and thank them. Mention that their feedback directly improves bug-hunt patterns and command quality for all users.
