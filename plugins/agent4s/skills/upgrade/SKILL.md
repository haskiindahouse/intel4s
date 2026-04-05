---
name: upgrade
description: "Upgrade the scalex binary to the latest release from GitHub. Checks current version, fetches latest release, clears cache, and re-downloads."
disable-model-invocation: true
---

You are upgrading the scalex binary to the latest version.

## Step 1: Locate scalex-cli

The scalex-cli bootstrap script is at the path relative to this skill:
`../agent4s/scripts/scalex-cli`

Resolve the absolute path and verify it exists.

## Step 2: Get current version

```bash
bash "<scalex-cli-path>" --version
```

Note the current version.

## Step 3: Get latest release version

Try `gh` first (most reliable), fall back to `curl`:

```bash
gh release view --repo scala-digest/agent4s --json tagName -q .tagName 2>/dev/null
```

If `gh` is not available:
```bash
curl -sL "https://api.github.com/repos/scala-digest/agent4s/releases/latest" | grep '"tag_name"' | head -1 | sed 's/.*"tag_name": *"//;s/".*//'
```

The tag format is `vX.Y.Z`. Strip the `v` prefix to compare with the current version.

## Step 4: Compare versions

If current version matches latest → print "Already up to date (vX.Y.Z)" and stop.

If versions differ → continue with upgrade.

## Step 5: Clear cached binary

```bash
rm -rf ~/.cache/agent4s/*
```

This removes the cached native binary. The bootstrap script will re-download it on next invocation.

## Step 6: Trigger re-download

Run any scalex command to trigger the bootstrap script's download:

```bash
bash "<scalex-cli-path>" --version
```

The bootstrap script will detect the missing binary, download the new version, and cache it.

## Step 7: Verify

Check the new version:

```bash
bash "<scalex-cli-path>" --version
```

Print a summary:
```
scalex upgraded: vOLD → vNEW
```

If the version didn't change, check:
- Is the `EXPECTED_VERSION` in `scalex-cli` script up to date?
- Was the download successful? Check for error messages in the output.
- The bootstrap script pins to `EXPECTED_VERSION` — if this hasn't been bumped in the plugin, the binary won't upgrade past that version. Tell the user to update the plugin itself: `claude plugins install agent4s`
