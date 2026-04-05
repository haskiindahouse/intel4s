# /project:plan

Create a task specification with BDD scenarios.

## Steps

1. Read `CLAUDE.md` — internalize invariants and anti-patterns
2. Read `docs/ARCHITECTURE.md` — understand module structure
3. Read `MEMORY.md` — current project state
4. Ask the user: "Describe the task — what and why"
5. Identify affected modules by mapping to `src/` file structure:
   - `src/extraction.scala` — AST parsing, symbol extraction
   - `src/index.scala` — git integration, persistence, WorkspaceIndex
   - `src/analysis.scala` — cross-index analysis (hierarchy, overrides, deps)
   - `src/cli.scala` — arg parsing, flags, entry point
   - `src/dispatch.scala` — command routing
   - `src/commands/*.scala` — individual command handlers
   - `src/model.scala` — data types, enums
   - `src/format.scala` — output formatters
6. Check if any invariants constrain the approach (especially #1 Scalameta-only, #2 no new deps, #7 better-than-grep)
7. Create file `docs/specs/[task-name].md` using `docs/specs/TEMPLATE.md`
8. Fill in all sections — especially BDD scenarios with SHALL / SHALL NOT
9. Show the spec to the user for approval
10. Do NOT begin implementation until the user approves
