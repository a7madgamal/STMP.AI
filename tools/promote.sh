#!/usr/bin/env bash
# Promote a BETA release to STABLE.
#
#   tools/promote.sh v0.43.66
#
# Promotion is a METADATA change: the release's `prerelease` flag flips to false.
# The APK is not rebuilt, re-tagged or re-signed, so the artifact everyone on the
# stable channel receives is byte-for-byte the one that was tested as a beta.
#
# This is the local equivalent of the Promote workflow (.github/workflows/promote.yml)
# and performs the same checks in the same order, so the two cannot drift into
# disagreeing about what "promotable" means.
#
# Requires `gh` authenticated as someone with write access to the repo.
set -euo pipefail

cd "$(dirname "$0")/.."

EXPECTED_SHA1="87DDD5DEFA068933F44EC699533A1B00ED313103"

if [ $# -ne 1 ]; then
    echo "usage: $0 <tag>    e.g. $0 v0.43.66" >&2
    exit 2
fi

TAG="$1"

command -v gh >/dev/null || { echo "error: gh CLI is not installed" >&2; exit 1; }

# --- Preconditions. Each failure below is a reason NOT to promote. -------------

gh release view "$TAG" --json isDraft,isPrerelease,assets > /tmp/stmp-promote.json

read -r DRAFT PRE APKS <<EOF
$(python3 - <<'PY'
import json
d = json.load(open('/tmp/stmp-promote.json'))
apks = [a for a in d['assets'] if a['name'].endswith('.apk')]
print(d['isDraft'], d['isPrerelease'], len(apks))
PY
)
EOF

echo "tag=$TAG draft=$DRAFT prerelease=$PRE apk_assets=$APKS"

if [ "$DRAFT" = "True" ]; then
    echo "error: $TAG is a DRAFT. Drafts are invisible to the updater, so promoting one ships nothing." >&2
    exit 1
fi
if [ "$PRE" = "False" ]; then
    echo "error: $TAG is already stable — refusing to promote it twice." >&2
    exit 1
fi
if [ "$APKS" -ne 1 ]; then
    echo "error: $TAG carries $APKS APK assets; expected exactly 1." >&2
    exit 1
fi

# --- Verify the artifact before it becomes the stable channel -----------------
#
# `keytool -printcert -jarfile` reads the signer straight out of the APK, so this
# needs no Android SDK — only a JDK.

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

gh release download "$TAG" --pattern '*.apk' --dir "$WORK" --clobber

# Exactly one APK, or stop. `| head -1` would pick a winner out of several and
# verify the wrong file while a second one sat beside it.
mapfile -t FOUND < <(find "$WORK" -name '*.apk')
if [ "${#FOUND[@]}" -ne 1 ]; then
    echo "error: expected exactly 1 APK in $TAG, downloaded ${#FOUND[@]}" >&2
    exit 1
fi
APK="${FOUND[0]}"

SIGNER=$(keytool -printcert -jarfile "$APK" 2>/dev/null \
    | grep -oE 'SHA1: [0-9A-F:]+' | head -1 | sed 's/SHA1: //; s/://g' || true)

echo "signer SHA-1: ${SIGNER:-<none>}"
if [ -z "$SIGNER" ] || [ "$SIGNER" != "$EXPECTED_SHA1" ]; then
    echo "error: APK in $TAG is not signed by the expected key." >&2
    echo "       got ${SIGNER:-none}, expected $EXPECTED_SHA1" >&2
    exit 1
fi

# --- Promote, then confirm the new state -------------------------------------
#
# REST PATCH rather than `gh release edit --prerelease=false`: the CLI's boolean
# flags have historically ignored an explicit value, so the release stayed a
# prerelease while the command exited 0. A false success is the one outcome this
# script must never report.

ID=$(gh release view "$TAG" --json databaseId -q .databaseId)
if [ -z "$ID" ]; then
    echo "error: could not resolve a release id for $TAG" >&2
    exit 1
fi

gh api -X PATCH "repos/{owner}/{repo}/releases/$ID" \
    -F prerelease=false -F make_latest=true > /dev/null

gh release view "$TAG" --json isPrerelease,isLatest > /tmp/stmp-promote-after.json
read -r PRE2 LATEST <<EOF
$(python3 - <<'PY'
import json
d = json.load(open('/tmp/stmp-promote-after.json'))
print(d['isPrerelease'], d['isLatest'])
PY
)
EOF

echo "after: prerelease=$PRE2 latest=$LATEST"
if [ "$PRE2" != "False" ]; then
    echo "error: promotion did not take — $TAG is still marked prerelease." >&2
    exit 1
fi

echo "promoted $TAG to stable"
