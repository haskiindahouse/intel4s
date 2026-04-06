#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUT="${1:-$SCRIPT_DIR/scalex}"

# WHY: Coursier installs scala-cli as .bat on Windows; bash can't find it without extension
SCALA_CLI="scala-cli"
case "$(uname -s)" in
  MINGW*|CYGWIN*|MSYS*) SCALA_CLI="scala-cli.bat" ;;
esac

# -march=native is only safe for local builds (not portable across machines)
MARCH_FLAG=()
if [ -z "${CI:-}" ]; then
  MARCH_FLAG=(-march=native)
fi

echo "Building Scalex native image..."
"$SCALA_CLI" package --native-image \
  "$SCRIPT_DIR/src/" \
  -o "$OUT" \
  --force \
  -- --no-fallback \
  --initialize-at-run-time=com.google.common.hash.Striped64,com.google.common.hash.LongAdder,com.google.common.hash.BloomFilter,com.google.common.hash.BloomFilterStrategies \
  --exclude-config '.*jline.*' '.*' \
  ${MARCH_FLAG[@]+"${MARCH_FLAG[@]}"}

echo ""
echo "Built: $OUT"
ls -lh "$OUT"
