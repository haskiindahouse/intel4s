# Agent Workflows

These are multi-step recipes. When the user asks for a complex task, orchestrate these steps autonomously — don't ask for permission at each step.

## Explore unfamiliar codebase

When dropped into a new Scala project, build a mental model before doing anything else:

1. `overview --concise` → fixed-size summary: packages, key types, dependency stats
2. `entrypoints` → where does execution start?
3. `summary <top-package>` → sub-package structure
4. For the 2-3 most important types: `explain <Type> --related` → understand the core domain

## Safe rename

When asked to rename a symbol:

1. `rename OldName NewName` → get the full edit plan with categories
2. Review the edits — check for false positives (comments, string literals)
3. Apply each edit using the Edit tool, file by file
4. `refs OldName --count` → verify zero remaining references (should be empty)
5. If not empty, investigate remaining references

## Impact analysis before changes

Before modifying a type or method:

1. `refs Symbol --count` → quick triage: how many files are affected?
2. `call-graph method --in Owner` → what does this method touch? who calls it?
3. `hierarchy Symbol --depth 2` → inheritance impact
4. `imports Symbol` → who imports this? (including wildcards)
5. Decide scope: is this a safe local change or a cross-cutting refactor?

## Implement a trait

When asked to implement a trait or fill in abstract members:

1. `scaffold impl MyClass` → get the stubs with resolved type parameters
2. `explain ParentTrait --verbose` → understand what each method should do
3. For complex methods: `overrides methodName --of ParentTrait --body` → see how others implement it
4. Write the implementations
5. `scaffold test MyClass` → generate test skeleton, then fill in real tests

## Find and remove dead code

1. `unused com.example` → find candidates
2. For each candidate: `refs CandidateName --count` → double-check (unused only checks external refs)
3. `coverage CandidateName` → is it tested? (tests referencing unused code = test-only code)
4. Remove confirmed dead code, update tests

## Bug investigation

When investigating a bug in a Scala codebase:

1. `search <keyword>` → find relevant types
2. `explain <Type> --related` → understand the type and its connections
3. `call-graph <suspectMethod> --in Owner` → trace the execution flow
4. `body <method> --in Owner --imports` → read the actual code with imports
5. `refs <method> --count` → how is this called? from where?
6. Narrow down: `grep "pattern" --in ClassName --each-method` → which methods have the pattern?

## Refactor workflow

When asked to refactor (extract, rename, restructure):

1. `explain <Target> --inherited --related` → full picture of the type
2. `refs <Target> --count` → measure impact
3. `unused <package>` → find dead code to clean up while refactoring
4. Make the changes (rename, scaffold, restructure)
5. `refs OldName --count` → verify clean rename (should be 0)
6. `coverage <NewName>` → verify test coverage still applies

## Package audit

When asked to assess a package's health:

1. `summary <package>` → structure overview
2. `api <package>` → what's exported? what's truly public?
3. `unused <package>` → dead code
4. `ast-pattern --extends <BaseTrait> --has-method <required>` → structural compliance check
5. `api <package> --used-by <consumer-package>` → coupling analysis
