# /project:review

Architectural review of current changes against the spec.

## Steps

1. Identify the spec for current branch:
   ```bash
   BRANCH=$(git branch --show-current)
   ```
   Read `docs/specs/$BRANCH.md` (or ask user which spec).

2. Get the diff:
   ```bash
   git diff main --stat
   git diff main
   ```

3. Run the reviewer checklist:

   **Scope compliance:**
   - Changes match spec scope? No additions beyond what was specified?
   - SHALL NOT prohibitions respected?

   **Invariants (CLAUDE.md):**
   - #1: Scalameta only?
   - #2: No new dependencies?
   - #3: Named tuples only?
   - #4: No `return`?
   - #5: Zero warnings?
   - #7: Better than grep?
   - #10: Immutable domain?

   **Architecture:**
   - Module boundaries respected?
   - ADR constraints not violated?
   - No Scalameta anti-patterns? (`.collect`, `pkg.children`, `.par`)

   **Code quality:**
   - Size limits respected?
   - WHY-comments where needed?
   - No WHAT-comments?

   **Tests:**
   - Happy path + error + edge case for each public method?
   - Tests cover all acceptance criteria?

   **Documentation:**
   - CHANGELOG, ROADMAP, SKILL.md, README updated as needed?

4. Output verdict: APPROVE / REQUEST_CHANGES / REJECT
   - Every issue must reference a specific invariant or rule
