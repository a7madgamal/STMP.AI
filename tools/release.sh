#!/usr/bin/env bash
# One command for the whole STMP ship path. Nothing is trusted to memory.
#
#   version-check  -> fails if this version's tag already exists
#   changelog-check -> fails if CHANGELOG.md is stale or has a <hash>
#   assembleDebug  -> builds stmp-v<version>.apk (name is automatic)
#   taildrop       -> refuses any filename that is not stmp-vX.Y.Z.apk
#
# When this succeeds, the ONLY thing printed to stdout is the APK filename — the
# handover is one line, no hash, no size, no warnings. If anything fails, it stops
# before a single byte leaves the box.
set -euo pipefail

cd "$(dirname "$0")/.."

echo "== version-check ==" >&2
node tools/version-check.ts

echo "== changelog-check ==" >&2
node tools/changelog-check.ts

echo "== build ==" >&2
./gradlew :app:assembleDebug --console=plain >&2

APK="app/build/outputs/apk/debug/stmp-v$(grep -oP 'versionName\s*=\s*"\K[^"]+' app/build.gradle.kts | head -1).apk"

echo "== taildrop ==" >&2
tools/taildrop-s22.sh "$APK" >&2

# The one true handover line. stdout only. Everything else above went to stderr.
printf '%s\n' "stmp-v$(grep -oP 'versionName\s*=\s*"\K[^"]+' app/build.gradle.kts | head -1).apk"
