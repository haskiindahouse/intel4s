# Contributing to intel4s

## Development Setup

```bash
# Prerequisites: JDK 21+, scala-cli
git clone https://github.com/YOUR_USERNAME/intel4s.git
cd intel4s

# Run from source
scala-cli run src/ -- search . MyClass

# Run tests
scala-cli test src/ tests/

# Run a single test
scala-cli test src/ tests/ -- '*rename*'

# Build native image (requires GraalVM)
./build-native.sh
```

## Project Structure

```
src/                          # Production code
├── project.scala             # Dependencies (scala-cli directives)
├── model.scala               # Data types, enums
├── extraction.scala          # AST parsing (Scalameta + JavaParser)
├── index.scala               # WorkspaceIndex, bloom filters, persistence
├── semanticdb.scala          # SemanticDB integration (type-aware refs)
├── analysis.scala            # Cross-index analysis
├── cli.scala                 # CLI entry point, flag parsing
├── dispatch.scala            # Command registry
├── format.scala              # Output rendering (text + JSON)
├── command-helpers.scala     # Shared utilities
└── commands/                 # One file per command (36 total)

tests/                        # Test suite (528 tests)
├── test-base.test.scala      # Shared fixture
├── cli.test.scala            # Command + rendering tests
├── extraction.test.scala     # AST extraction tests
├── index.test.scala          # Index + search tests
└── analysis.test.scala       # Analysis tests
```

## Adding a New Command

1. Create `src/commands/mycommand.scala` with `def cmdMyCommand(args: List[String], ctx: CommandContext): CmdResult`
2. Add `CmdResult` variant in `model.scala`
3. Register in `dispatch.scala`: `"mycommand" -> cmdMyCommand`
4. Add renderer in `format.scala` (text + JSON)
5. Add help text in `cli.scala`
6. If command needs 2+ positional args (not workspace), add dispatch case in `cli.scala` before the default case
7. Write tests in `tests/cli.test.scala`

## Code Style

- **Named tuples only** — never `(String, Int)`, always `(name: String, count: Int)`
- **No `return` statements** — use `scala.util.boundary` + `break` for early exit
- **No backwards compatibility** — consistency > not breaking hypothetical consumers
- **Zero warnings policy** — `scala-cli compile src/ 2>&1 | grep -i warn` must be empty
- **Zero deprecations** — `scala-cli compile --scalac-option "-deprecation" src/ 2>&1 | grep -i warn` must be empty

## Before Submitting

```bash
# Must all pass:
scala-cli compile src/ 2>&1 | grep -i warn          # zero warnings
scala-cli compile --scalac-option "-deprecation" src/ 2>&1 | grep -i warn  # zero deprecations
scala-cli test src/ tests/                           # 528+ tests passing
```
