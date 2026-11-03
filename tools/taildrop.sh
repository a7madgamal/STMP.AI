#!/usr/bin/env bash
# Taildrop an STMP APK to a device over Tailscale.
#
# The node name is NOT hardcoded: pass it as $1 or set STMP_NODE. A personal
# machine name baked into a script is how a repo stops being usable by anyone
# who is not its author.
#
#   tools/taildrop.sh <node> stmp-vX.Y.Z.apk
#   STMP_NODE=<node> tools/taildrop.sh stmp-vX.Y.Z.apk
#
# The FILE NAME is still enforced. A wrong name is how `app-debug.apk` leaked
# onto a phone once, so the rule stays: stmp-v<major>.<minor>.<patch>.apk and
# nothing else. Also set STMP_APK_NAME to a different pattern.
set -euo pipefail

DEFAULT_NAME_PATTERN='^stmp-v[0-9]+\.[0-9]+\.[0-9]+\.apk$'
NAME_PATTERN="${STMP_APK_NAME:-$DEFAULT_NAME_PATTERN}"

# A node is either the first argument or the environment, never a default. A
# silent default would send an APK to whichever device the author happened to
# own, which is exactly the bug this script used to carry.
if [[ $# -ge 2 ]]; then
  NODE="$1"; shift
elif [[ -n "${STMP_NODE:-}" ]]; then
  NODE="$STMP_NODE"
else
  echo "REFUSING: no target node." >&2
  echo "  usage: $0 <node> <apk>" >&2
  echo "  or set STMP_NODE=<node>" >&2
  exit 2
fi

if [[ $# -lt 1 ]]; then
  echo "usage: $0 <node> <apk>" >&2
  exit 2
fi

SRC="$1"; shift
NAME="$(basename "$SRC")"

if [[ ! "$NAME" =~ $NAME_PATTERN ]]; then
  echo "REFUSING: file name '$NAME' does not match $NAME_PATTERN" >&2
  echo "         a wrong name is how 'app-debug.apk' leaked before." >&2
  exit 1
fi

[[ -f "$SRC" ]] || { echo "no such file: $SRC" >&2; exit 1; }

echo "taildrop -> $NODE : $NAME"
tailscale file cp "$SRC" "$NODE:" "$@"
