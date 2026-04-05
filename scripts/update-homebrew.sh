#!/usr/bin/env bash
# Updates the Homebrew formula with SHA256 checksums from a GitHub release.
# Usage: ./scripts/update-homebrew.sh v0.5.0
set -euo pipefail

VERSION="${1:?Usage: update-homebrew.sh <version-tag, e.g. v0.5.0>}"
FORMULA="homebrew/agent4s.rb"

if [[ ! -f "$FORMULA" ]]; then
  echo "ERROR: $FORMULA not found. Run from repo root." >&2
  exit 1
fi

echo "Fetching checksums for $VERSION..."

SHA_ARM64=$(gh release download "$VERSION" -p "scalex-macos-arm64.sha256" -O - | awk '{print $1}')
SHA_X64=$(gh release download "$VERSION" -p "scalex-macos-x64.sha256" -O - | awk '{print $1}')
SHA_LINUX=$(gh release download "$VERSION" -p "scalex-linux-x64.sha256" -O - | awk '{print $1}')

BARE_VERSION="${VERSION#v}"

echo "Updating $FORMULA..."
sed -i.bak \
  -e "s/version \".*\"/version \"$BARE_VERSION\"/" \
  -e "s/PLACEHOLDER_SHA256_MACOS_ARM64/$SHA_ARM64/" \
  -e "s/PLACEHOLDER_SHA256_MACOS_X64/$SHA_X64/" \
  -e "s/PLACEHOLDER_SHA256_LINUX_X64/$SHA_LINUX/" \
  "$FORMULA"
rm -f "${FORMULA}.bak"

# Also replace any existing SHA256 hashes (for re-runs)
sed -i.bak \
  -e "/scalex-macos-arm64/{ n; s/sha256 \"[a-f0-9]\{64\}\"/sha256 \"$SHA_ARM64\"/; }" \
  -e "/scalex-macos-x64/{ n; s/sha256 \"[a-f0-9]\{64\}\"/sha256 \"$SHA_X64\"/; }" \
  -e "/scalex-linux-x64/{ n; s/sha256 \"[a-f0-9]\{64\}\"/sha256 \"$SHA_LINUX\"/; }" \
  "$FORMULA"
rm -f "${FORMULA}.bak"

echo "Done. Updated $FORMULA to $BARE_VERSION:"
echo "  macOS arm64: $SHA_ARM64"
echo "  macOS x64:   $SHA_X64"
echo "  Linux x64:   $SHA_LINUX"
echo ""
echo "Next: copy $FORMULA to scala-digest/homebrew-tap/Formula/agent4s.rb and push."
