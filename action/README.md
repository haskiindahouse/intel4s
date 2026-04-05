# agent4s GitHub Action

Run agent4s in CI — bug-hunt on PRs, unused code detection, and more.

## Usage

### Bug-hunt on every PR

```yaml
name: agent4s
on: [pull_request]

jobs:
  bug-hunt:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: scala-digest/agent4s-action@v1
        with:
          command: bug-hunt
          args: '--severity high --no-tests'
```

### Dead code check

```yaml
      - uses: scala-digest/agent4s-action@v1
        with:
          command: unused
          args: 'com.myproject --kind class'
          fail-on-findings: 'false'
```

### Multiple checks

```yaml
jobs:
  agent4s:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Bug hunt (critical)
        uses: scala-digest/agent4s-action@v1
        with:
          command: bug-hunt
          args: '--severity critical --no-tests'

      - name: Unused code report
        uses: scala-digest/agent4s-action@v1
        with:
          command: unused
          args: 'com.myproject'
          fail-on-findings: 'false'
```

## Inputs

| Input | Description | Required | Default |
|-------|-------------|----------|---------|
| `command` | Command to run: `bug-hunt`, `unused`, or any agent4s command | Yes | — |
| `args` | Additional arguments | No | `''` |
| `version` | agent4s version (e.g. `0.5.0`) | No | `latest` |
| `workspace` | Path to Scala project | No | `.` |
| `fail-on-findings` | Fail step if findings reported | No | `true` |

## Outputs

| Output | Description |
|--------|-------------|
| `findings-count` | Number of findings |
| `report` | Full text output (truncated to 500 lines) |

Results are also posted to the **Job Summary** tab.

## Platforms

Supports `ubuntu-latest`, `macos-latest` (arm64/x64), and `windows-latest`.
