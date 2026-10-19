#!/usr/bin/env python3
"""
Changelog guard — run before every commit that changes the app.

WHY THIS EXISTS
    CHANGELOG.md is the only record of *why* each release looks the way it does, so it has to
    be true at commit time, not fixed up later. Two failure modes had already shipped by hand:
      - `— CURRENT` sat on 0.23.2 while the tree was on 0.25.0, so three releases looked
        unshipped.
      - the newest entry's build/version disagreed with `app/build.gradle.kts`.
    Both are mechanical, so a script should catch them, not memory.

WHAT IT CHECKS
    1. Exactly one `— CURRENT`, and it is on the NEWEST entry.
    2. The newest entry's version and build number match `app/build.gradle.kts`.
    3. No leftover `<hash>` placeholder in any entry.

ON COMMIT HASHES
    An entry must NOT carry the hash of the commit that contains it: the hash is computed FROM
    the content, so writing it in changes the hash — a genuine self-reference. The convention is
    therefore:
      - the entry lands in the SAME commit as the change, with no hash;
      - the commit is found later by version, e.g. `git log --oneline --grep "0.25.0"`.
    This check enforces (3), which is what catches a half-written entry.

USAGE
    python3 tools/changelog-check.py        # exit 0 = fine, 1 = fix it

Exit codes are the contract: a failed check must fail loudly (the project's fail-hard rule), never
warn-and-continue.
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CHANGELOG = ROOT / "CHANGELOG.md"
GRADLE = ROOT / "app" / "build.gradle.kts"
MARKER = "## Keeping this file honest"


def fail(msg: str) -> None:
    print(f"changelog-check: FAIL — {msg}", file=sys.stderr)
    sys.exit(1)


def main() -> None:
    if not CHANGELOG.is_file():
        fail(f"{CHANGELOG} not found")
    if not GRADLE.is_file():
        fail(f"{GRADLE} not found")

    text = CHANGELOG.read_text(encoding="utf-8")

    # Entries are everything above the trailing house-rules section. Splitting on the marker is
    # deliberate: a heading inside that section must never be mistaken for a release entry.
    if MARKER not in text:
        fail(f"missing the '{MARKER}' section — the file's structure has drifted")
    body = text.split(MARKER, 1)[0]

    entries = [line for line in body.splitlines() if line.startswith("## ")]
    if not entries:
        fail("no release entries found — the scan is broken, not the file")

    newest = entries[-1]
    current = [e for e in entries if "— CURRENT" in e]

    if len(current) == 0:
        fail(
            "no entry is marked `— CURRENT`. Exactly one must be, or a reader cannot tell "
            "which release is live."
        )
    if len(current) > 1:
        fail(
            "more than one entry is marked `— CURRENT`:\n  "
            + "\n  ".join(current)
            + "\nMove the marker to the newest entry only."
        )
    if current[0] != newest:
        fail(
            "`— CURRENT` is on a stale entry.\n"
            f"  marked:  {current[0]}\n"
            f"  newest:  {newest}\n"
            "Move the marker to the newest entry."
        )

    # The newest entry must describe the build the tree actually produces.
    m = re.search(r"\(build (\d+)\)", newest)
    if not m:
        fail(f"newest entry has no '(build N)': {newest}")
    entry_build = int(m.group(1))

    gradle = GRADLE.read_text(encoding="utf-8")
    vc = re.search(r"versionCode\s*=\s*(\d+)", gradle)
    vn = re.search(r'versionName\s*=\s*"([^"]+)"', gradle)
    if not vc or not vn:
        fail("could not read versionCode/versionName from app/build.gradle.kts")

    if int(vc.group(1)) != entry_build:
        fail(
            f"build number disagrees: newest changelog entry says build {entry_build}, "
            f"but app/build.gradle.kts sets versionCode = {vc.group(1)}"
        )

    entry_version = re.search(r"([0-9]+\.[0-9]+\.[0-9]+)", newest)
    if not entry_version:
        fail(f"newest entry has no semantic version: {newest}")
    if entry_version.group(1) != vn.group(1):
        fail(
            f"version disagrees: newest changelog entry says {entry_version.group(1)}, "
            f"but app/build.gradle.kts sets versionName = \"{vn.group(1)}\""
        )

    # A placeholder means the entry was written but never finished.
    for e in entries:
        if "<hash>" in e:
            fail(
                f"unfinished placeholder in entry: {e}\n"
                "An entry must NOT carry its own commit hash (self-reference). Drop the hash "
                "and find the commit later with `git log --oneline --grep \"<version>\"`."
            )

    print(
        f"changelog-check: ok — {len(entries)} entries, current {vn.group(1)} / "
        f"build {entry_build}"
    )


if __name__ == "__main__":
    main()
