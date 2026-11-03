#!/usr/bin/env bash
# Local ship path: version-check -> changelog-check -> build -> optional taildrop.
#
# CI is the real release path (`.github/workflows/release.yml` publishes a beta
# release on every push to main). This script exists for the other job: getting a
# build onto a phone without waiting for CI.
#
#   tools/release.sh                 build only; print the APK name
#   STMP_NODE=<node> tools/release.sh  build, then taildrop to <node>
#
# The node is never hardcoded. Nothing is trusted to memory: both guards run, and
# the APK name is enforced by taildrop.sh.
set -euo pipefail

cd "$(dirname "$0")/.."

version_name() {
  grep -oP 'versionName\s*=\s*"\K[^"]+' app/build.gradle.kts | head -1
}

echo "== version-check ==" >&2
node tools/version-check.ts

echo "== changelog-check ==" >&2
node tools/changelog-check.ts

echo "== build ==" >&2
./gradlew :app:assembleDebug --console=plain >&2

APK="app/build/outputs/apk/debug/stmp-v$(version_name).apk"

# Taildrop only when asked. A build that silently phones home to a device is a
# build you cannot run in CI or on a machine without Tailscale.
if [[ -n "${STMP_NODE:-}" ]]; then
  echo "== taildrop ==" >&2
  tools/taildrop.sh "$STMP_NODE" "$APK" >&2
else
  echo "== no STMP_NODE set — skipping taildrop ==" >&2
fi

# The one true handover line. stdout only; everything above went to stderr.
printf '%s\n' "$(basename "$APK")"
