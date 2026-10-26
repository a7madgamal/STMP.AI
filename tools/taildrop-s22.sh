#!/usr/bin/env bash
# Taildrop an APK (or any file) to the S22 Ultra.
#
# The node name on the tailnet is EXACTLY: ahmeds-s22-ultra  (Ahmed's S22 Ultra)
# Do NOT use "s22", "phone", "ahmeds-s22", or the IP — tailscale file cp matches the
# tailnet device name, and a wrong name silently queues nowhere.
set -euo pipefail

NODE="ahmeds-s22-ultra"   # the one true name

if [[ $# -lt 1 ]]; then
  echo "usage: $0 <file> [tailscale-extra-args...]" >&2
  exit 2
fi

SRC="$1"; shift
[[ -f "$SRC" ]] || { echo "no such file: $SRC" >&2; exit 1; }

echo "taildrop -> $NODE : $SRC"
tailscale file cp "$SRC" "$NODE:" "$@"
