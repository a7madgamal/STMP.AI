#!/usr/bin/env bash
# Taildrop the STMP APK to the S22 Ultra. Node name and file name are BOTH enforced.
#
# Node:  ahmeds-s22-ultra  (never "s22", "phone", the IP, etc.)
# File:  stmp-v<major>.<minor>.<patch>.apk  — and NOTHING else. No hash, no build
#         type, no "app-debug". If the name does not match, this script refuses and
#         prints what went wrong instead of sending a wrongly-named file.
set -euo pipefail

NODE="ahmeds-s22-ultra"

if [[ $# -lt 1 ]]; then
  echo "usage: $0 <stmp-vX.Y.Z.apk>" >&2
  exit 2
fi

SRC="$1"; shift
NAME="$(basename "$SRC")"

# The one true name format. Enforced here so a wrongly-named build can never leave.
if [[ ! "$NAME" =~ ^stmp-v[0-9]+\.[0-9]+\.[0-9]+\.apk$ ]]; then
  echo "REFUSING: file must be named stmp-vX.Y.Z.apk, got '$NAME'" >&2
  echo "         a wrong name is how 'app-debug.apk' leaked before." >&2
  exit 1
fi

[[ -f "$SRC" ]] || { echo "no such file: $SRC" >&2; exit 1; }

echo "taildrop -> $NODE : $NAME"
tailscale file cp "$SRC" "$NODE:" "$@"
