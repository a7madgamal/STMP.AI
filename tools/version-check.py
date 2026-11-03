#!/usr/bin/env python3
"""
Version guard — a released version must never be released twice.

WHY THIS EXISTS
    The old check compared the working tree against `HEAD`, which only answers
    "did you edit this file since the last commit". That is not the invariant
    anyone cares about, and in CI it is worse than useless: on a clean checkout
    the two are identical by definition, so it failed on EVERY run.

    The real invariant is: **two releases must never carry the same version.**
    A release is identified by its tag, so the tag is what this checks.

WHAT IT CHECKS
    1. `app/build.gradle.kts` declares `versionCode` and `versionName`.
    2. No tag `v<versionName>` already exists.

    Both are facts about the repository, so the check behaves identically on a
    laptop and in CI — no flags, no event payload, no `--base`.

EXIT
    0 — this version has never been released
    1 — it has, or the version is unreadable

USAGE
    python3 tools/version-check.py
"""

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GRADLE = ROOT / "app" / "build.gradle.kts"

VERSION_CODE_RE = re.compile(r"\bversionCode\s*=\s*(\d+)")
VERSION_NAME_RE = re.compile(r'\bversionName\s*=\s*"([^"]+)"')

TAG_PREFIX = "v"


def fail(msg: str) -> None:
    print(f"version-check: FAIL — {msg}", file=sys.stderr)
    sys.exit(1)


def read_version(text: str) -> tuple[str, str]:
    code = VERSION_CODE_RE.search(text)
    name = VERSION_NAME_RE.search(text)
    if not code or not name:
        fail("versionCode or versionName not found in app/build.gradle.kts")
    return code.group(1), name.group(1)


def tag_exists(tag: str) -> bool:
    """Is `tag` already in this repo?

    `git rev-parse --verify` is the check rather than parsing `git tag`, because
    it is exact: `refs/tags/v1.0` matches only that ref, while a text search
    would also match `v1.0.1` and `v1.0-rc1`.

    A NON-EXISTENT-REF exit code is the answer "no"; any OTHER failure is a real
    error (not a git repo, corrupt object store) and must not be read as "no".
    """
    result = subprocess.run(
        ["git", "-C", str(ROOT), "rev-parse", "--verify", "--quiet",
         f"refs/tags/{tag}"],
        capture_output=True,
        text=True,
    )
    if result.returncode == 0:
        return True
    if result.returncode == 1:
        # 1 means "no such ref" — the only failure that is a legitimate answer.
        return False
    fail(
        f"could not query git for tag {tag!r} (exit {result.returncode}): "
        f"{result.stderr.strip() or 'no stderr'}"
    )
    raise AssertionError("unreachable")


def main() -> None:
    if not GRADLE.is_file():
        fail(f"{GRADLE} not found")

    code, name = read_version(GRADLE.read_text(encoding="utf-8"))
    tag = f"{TAG_PREFIX}{name}"

    if tag_exists(tag):
        fail(
            f"tag {tag} already exists — version {name} (code {code}) has been "
            f"released. Two releases must never share a version: bump "
            f"versionCode and versionName in app/build.gradle.kts."
        )

    print(f"version-check: ok — {name} (code {code}); tag {tag} is unused")


if __name__ == "__main__":
    main()
