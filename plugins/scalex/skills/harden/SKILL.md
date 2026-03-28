---
name: harden
description: "Strengthen Scala code against edge cases, errors, and production failures. Adds proper error handling, timeouts, retries, circuit breakers, validation, and graceful degradation. Triggers: 'harden', 'make production-ready', 'add error handling', 'handle edge cases', 'make robust'."
---

Strengthen this code against everything that can go wrong in production. Designs that only work with perfect data aren't production-ready.

## Step 1: Assess hardening needs

```bash
bash "<scalex-cli>" overview --concise -w "<project>"
bash "<scalex-cli>" bug-hunt --no-tests -w "<project>"
bash "<scalex-cli>" entrypoints -w "<project>"
```

If a specific target was provided:
```bash
bash "<scalex-cli>" explain <Target> --verbose --inherited --related -w "<project>"
bash "<scalex-cli>" call-graph <method> --in <Target> -w "<project>"
```

## Step 2: Harden across 8 dimensions

### 1. Input Validation
For each HTTP endpoint / public API method:
- Are inputs validated before processing?
- Are sizes bounded? (max string length, max collection size)
- Are formats validated? (email, UUID, date)

**Pattern (ZIO)**:
```scala
def createUser(name: String, email: String): ZIO[Any, AppError, User] =
  for
    _ <- ZIO.when(name.isBlank)(ZIO.fail(AppError.ValidationError("name is required")))
    _ <- ZIO.when(!email.contains("@"))(ZIO.fail(AppError.ValidationError("invalid email")))
    user <- userRepo.create(name, email)
  yield user
```

### 2. Error Handling
```bash
bash "<scalex-cli>" grep "throw\\|RuntimeException\\|Exception" --no-tests --count -w "<project>"
```
- Replace `throw` with typed errors
- Replace generic exceptions with specific error types
- Ensure all error paths return meaningful messages
- ZIO: use typed error channel, not defects

### 3. Timeouts
Every external call (HTTP, database, file I/O) needs a timeout:
```bash
bash "<scalex-cli>" grep "timeout\\|Timeout\\|Duration" --no-tests --count -w "<project>"
```
- Database queries: timeout (30s default)
- HTTP calls: connect timeout + read timeout
- File operations: bounded reads
- ZIO: `.timeout(30.seconds)` on external calls

### 4. Retries
Transient failures should be retried:
- HTTP 429, 503 → retry with exponential backoff
- Database connection failures → retry 3x
- ZIO: `.retry(Schedule.exponential(1.second) && Schedule.recurs(3))`

### 5. Resource Safety
```bash
bash "<scalex-cli>" bug-hunt --bug-category resource --no-tests -w "<project>"
```
- Every opened resource has a matching close
- Use `ZIO.acquireRelease` / `Using` / `try-finally`
- Database connections returned to pool
- File handles closed in all paths (including error)

### 6. Null Safety
```bash
bash "<scalex-cli>" bug-hunt --bug-category type-safety --no-tests -w "<project>"
```
- Wrap Java interop returns in `Option()`
- Replace `.get` with `.getOrElse` or pattern match
- Replace `.head` with `.headOption`
- Replace `null` checks with `Option`

### 7. Concurrency Safety
```bash
bash "<scalex-cli>" bug-hunt --bug-category concurrency --no-tests -w "<project>"
```
- No shared mutable state without synchronization
- Use `Ref` instead of `var` in ZIO
- Use `AtomicReference` instead of `var` in non-effect code
- No `Thread.sleep` — use `ZIO.sleep` or `ScheduledExecutorService`

### 8. Graceful Degradation
- What happens when a dependency is down?
- Can the app start with partial functionality?
- Are circuit breakers in place for external services?
- Are there health check endpoints?

## Step 3: Apply hardening

Work by priority:
1. **Critical**: Security issues from bug-hunt
2. **High**: Missing timeouts on external calls, unhandled errors
3. **Medium**: Missing validation, null safety
4. **Low**: Retry logic, circuit breakers

For each fix, read the code first:
```bash
bash "<scalex-cli>" body <method> --in <Type> --imports -w "<project>"
```

Then apply targeted edits.

## Step 4: Verify

```bash
bash "<scalex-cli>" bug-hunt --no-tests -w "<project>"
```

Confirm: fewer findings than before hardening.

NEVER:
- Add error handling that swallows errors silently
- Add retries without backoff (thundering herd)
- Add timeouts without considering the operation's natural duration
- Assume perfect input — always validate at system boundaries
- Harden test code — tests should be simple and direct
- Over-engineer: not every method needs retry logic
