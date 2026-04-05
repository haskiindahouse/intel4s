# Rules for Scala files (src/**/*.scala)

## Named tuples

Never use unnamed tuples. Always use named tuples:
```scala
// Wrong
def foo(): (List[Symbol], Boolean) = ...

// Correct
def foo(): (results: List[Symbol], timedOut: Boolean) = ...
```

## No return statements

Never use `return` — not in methods, not in lambdas, not in `for`/`foreach`.
Use `scala.util.boundary` + `boundary.break` for early exit.

## Scalameta patterns

- Use `pkg.stats` not `pkg.children` (children wraps in PkgBody)
- Use manual `traverse` + `visit` not `.collect` on Tree (doesn't work in Scala 3)
- Use explicit imports: `import scala.meta.{Defn, Term}` not `import scalameta._`

## Parallelism

Use `list.asJava.parallelStream()` not `list.par` (doesn't exist in Scala 3.8).

## Error handling

- No `throw new RuntimeException(...)` — return typed error, `Option`, or `boundary.break`
- Narrow `Exception` catches to `IOException` for file I/O
- No `.get` on Option in production code — use `match`, `fold`, `getOrElse`

## Immutability

No `var`, no mutable collections in model types (`src/model.scala`).
Mutable state allowed only in performance-critical internal loops with WHY-comment.

## Size limits

- Functions: 30-40 lines recommended, 50 hard limit
- Files: 200-300 lines recommended, 400 hard limit
- Parameters: 3-4 recommended, 5 hard limit
- Nesting: 2-3 recommended, 4 hard limit

## Comments

Write WHY, not WHAT. Magic numbers must be named constants with WHY-comment.
