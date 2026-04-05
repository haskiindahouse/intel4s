# /project:complete-task $ARGUMENTS

Finish a task: full verification, commit, PR, cleanup.

`$ARGUMENTS` is the spec name.

## Steps

1. Run full verification:
   - Compile: `scala-cli compile src/` — zero warnings
   - Deprecations: `scala-cli compile --scalac-option "-deprecation" src/` — zero warnings
   - Tests: `scala-cli test src/ tests/` — all green

2. If anything fails — fix immediately (up to 3 iterations)

3. Verify all acceptance criteria from `docs/specs/$ARGUMENTS.md` are met:
   - Every Scenario has a test
   - Tests pass
   - Existing tests not broken

4. Check feature checklist if command/flag was added or changed:
   - Help text in `src/cli.scala`
   - `plugins/agent4s/skills/agent4s/SKILL.md` updated
   - `plugins/agent4s/skills/agent4s/references/commands.md` updated
   - `docs/ROADMAP.md` updated
   - `CHANGELOG.md` updated
   - `README.md` updated
   - `docs/site/index.html` updated (if applicable)

5. Commit with descriptive message

6. Push branch: `git push -u origin $ARGUMENTS`

7. Create PR via `gh pr create` if gh CLI is available

8. Notify user: task ready for review
