# Rules for test files (tests/**/*.scala)

## Framework

MUnit. Tests use `test("name") { ... }` syntax.

## Required coverage

For each public method or command:
1. **Happy path** — main scenario works
2. **Primary error** — main error case handled
3. **Edge case** — boundary condition (empty input, not found, max size)

## Test structure

```scala
test("descriptive name matching the scenario") {
  // Setup
  val index = loadTestIndex()

  // Action
  val result = cmdSearch(List("MyClass"), ctx)

  // Assertion
  assert(result match { case CmdResult.SymbolList(syms, _) => syms.nonEmpty; case _ => false })
}
```

## Fixture rules

- Shared test fixture lives in `tests/test-base.test.scala`
- Test fixture files are in the test workspace — adding/removing fixtures requires updating ALL count assertions
- Do NOT mock — use real test fixture data
- Do NOT create test utilities unless >3 tests need them

## BDD mapping

When implementing tests from a spec:
- Each WHEN-THEN-AND block = one test
- SHALL = positive assertion (`assert`, `assertEquals`)
- SHALL NOT = negative assertion (`assert(!...)`, verify absence)
- Test name must match scenario name from spec

## Anti-patterns in tests

- Do NOT test private implementation details
- Do NOT write tests that pass trivially (mutation test: break the code, test must fail)
- Do NOT use `Thread.sleep` in tests — use deterministic timing
- Do NOT hardcode absolute paths
