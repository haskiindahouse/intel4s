# /project:orient $ARGUMENTS

Restore context after a break (multi-session tasks).

`$ARGUMENTS` is the spec name.

## Steps

1. Find the worktree: `git worktree list`
2. If worktree exists: `cd ../worktrees/$ARGUMENTS`
3. Read the spec: `docs/specs/$ARGUMENTS.md`
4. Read `MEMORY.md` — what was decided, what problems were found
5. Read last 5 commits in this worktree: `git log --oneline -5`
6. Check uncommitted changes: `git diff --stat`
7. Check which acceptance criteria from the spec are done vs remaining
8. Report to user:
   - Where we stopped
   - What remains
   - Any blockers discovered
9. Continue implementation
