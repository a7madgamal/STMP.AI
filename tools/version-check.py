#!/usr/bin/env python3
"""
Version-bump guard — a NEW change must be a NEW version. Enforced, not remembered.

WHY THIS EXISTS
    "Never use the last version" is a standing rule (Mido), and it was violated by building a
    change without bumping `versionName`/`versionCode`, so the APK came out as the SAME
    version the user already had. That is mechanical, so a script enforces it: compare the
    working-tree version against the last COMMITTED version (`HEAD`), and fail if they are
    equal. A change with no version bump can never ship.

WHAT IT CHECKS
    1. `app/build.gradle.kts` has a `versionCode` and `versionName` in `defaultConfig`.
    2. The working-tree values differ from `HEAD`'s values. Equal -> FAIL ("you reused the
       last version").
       - On the very first commit (no HEAD version to read) it passes with a note: there is
         no prior version to collide with.

USAGE
    python3 tools/version-check.py        # exit 0 = bumped (or first commit), 1 = reused

Note: this checks the VERSION, not the changelog or the filename. `changelog-check.py` covers
the changelog; the filename is produced mechanically from `versionName` by the Gradle build,
and `taildrop-s22.sh` refuses any filename that is not `stmp-vX.Y.Z.apk`.
"""

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GRADLE = ROOT / "app" / "build.gradle.kts"

VERSION_CODE_RE = re.compile(r"\bversionCode\s*=\s*(\d+)")
VERSION_NAME_RE = re.compile(r'\bversionName\s*=\s*"([^"]+)"')


def fail(msg: str) -> None:
    print(f"version-check: FAIL — {msg}", file=sys.stderr)
    sys.exit(1)


def read_version(text: str) -> tuple[str, str]:
    code = VERSION_CODE_RE.search(text)
    name = VERSION_NAME_RE.search(text)
    if not code or not name:
        fail("versionCode or versionName not found in app/build.gradle.kts")
    return code.group(1), name.group(1)


def head_version() -> tuple[str, str] | None:
    try:
        out = subprocess.run(
            ["git", "-C", str(ROOT), "show", "HEAD:app/build.gradle.kts"],
            capture_output=True, text=True, check=True,
        ).stdout
    except subprocess.CalledProcessError:
        # No committed copy — this is the first commit; nothing to collide with.
        return None
    code = VERSION_CODE_RE.search(out)
    name = VERSION_NAME_RE.search(out)
    if not code or not name:
        return None
    return code.group(1), name.group(1)


def main() -> None:
    if not GRADLE.is_file():
        fail(f"{GRADLE} not found")

    work = read_version(GRADLE.read_text(encoding="utf-8"))
    head = head_version()

    if head is None:
        print("version-check: ok — no committed version to collide with")
        return

    if work == head:
        fail(
            f"working version {work[1]} (code {work[0]}) is unchanged from HEAD — "
            "a new change must bump versionCode and versionName"
        )

    print(
        f"version-check: ok — {head[1]} (code {head[0]}) -> "
        f"{work[1]} (code {work[0]})"
    )


if __name__ == "__main__":
    main()
