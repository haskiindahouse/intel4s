# /project:start-task $ARGUMENTS

Begin implementing a task in an isolated worktree.

`$ARGUMENTS` is the spec name (e.g., `rate-limiting`, `fix-timeout`).

## Steps

1. Update main: `git fetch origin main`
2. Create worktree: `git worktree add ../worktrees/$ARGUMENTS origin/main -b $ARGUMENTS`
3. `cd ../worktrees/$ARGUMENTS`
4. Read the spec: `docs/specs/$ARGUMENTS.md`
5. Read `CLAUDE.md` — refresh invariants
6. Read `MEMORY.md` — project context
7. Read last 5 commits: `git log --oneline -5`
8. Begin implementation following BDD flow:
   a. Write tests per Scenarios (RED — tests must FAIL)
   b. Implement minimal code (GREEN)
   c. Mutation check: break key logic, verify test fails, revert
9. Run verification after each significant change:
   - `scala-cli compile src/` — zero warnings
   - `scala-cli test src/ tests/` — all green
