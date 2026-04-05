# /project:postmortem $ARGUMENTS

Create a structured post-mortem when a task failed or needed significant rework.

`$ARGUMENTS` is the spec name.

## Steps

1. Read the spec: `docs/specs/$ARGUMENTS.md`
2. Read git log for the branch:
   ```bash
   git log --oneline main..$ARGUMENTS 2>/dev/null || git log --oneline -10
   ```
3. Read diff stats:
   ```bash
   git diff main...$ARGUMENTS --stat 2>/dev/null
   ```
4. Create `docs/pipeline-log/pm-$ARGUMENTS.md` using `docs/pipeline-log/POSTMORTEM-TEMPLATE.md`
5. Fill in:
   - **What happened** — what was expected vs what actually happened
   - **Failure taxonomy** — check all that apply
   - **Agent context** — which files were read, where the agent got stuck
   - **Action items** — concrete changes to prevent recurrence
6. Leave "Root cause" section for human or Oracle to fill
7. Report to user: post-mortem created, review recommended
