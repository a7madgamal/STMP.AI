/**
 * Version guard — a released version must never be released twice.
 *
 * WHY THIS EXISTS
 *   The old check compared the working tree against `HEAD`, which only answers
 *   "did you edit this file since the last commit". That is not the invariant
 *   anyone cares about, and in CI it is worse than useless: on a clean checkout
 *   the two are identical by definition, so it failed on EVERY run.
 *
 *   The real invariant is: two releases must never carry the same version.
 *   A release is identified by its tag, so the tag is what this checks.
 *
 * WHAT IT CHECKS
 *   1. `app/build.gradle.kts` declares `versionCode` and `versionName`.
 *   2. No tag `v<versionName>` already exists.
 *
 *   Both are facts about the repository, so the check behaves identically on a
 *   laptop and in CI — no flags, no event payload, no `--base`.
 *
 * USAGE
 *   node tools/version-check.ts
 */

import { readFileSync, existsSync } from "node:fs";
import { execFileSync } from "node:child_process";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const GRADLE = join(ROOT, "app", "build.gradle.kts");

const TAG_PREFIX = "v";

interface Version {
  readonly code: string;
  readonly name: string;
}

function fail(message: string): never {
  process.stderr.write(`version-check: FAIL — ${message}\n`);
  process.exit(1);
}

function readVersion(text: string): Version {
  const code = /versionCode\s*=\s*(\d+)/.exec(text);
  const name = /versionName\s*=\s*"([^"]+)"/.exec(text);
  if (code === null || name === null) {
    fail("versionCode or versionName not found in app/build.gradle.kts");
  }
  // `noUncheckedIndexedAccess` makes [1] possibly-undefined; the null check
  // above does not narrow it, so narrow explicitly rather than asserting.
  const codeValue = code[1];
  const nameValue = name[1];
  if (codeValue === undefined || nameValue === undefined) {
    fail("versionCode or versionName matched with no captured value");
  }
  return { code: codeValue, name: nameValue };
}

/**
 * Is `tag` already in this repo?
 *
 * `git rev-parse --verify --quiet refs/tags/<tag>` is the check rather than
 * parsing `git tag`, because it is exact: it matches only that ref, where a text
 * search would also match `v1.0.1` and `v1.0-rc1`.
 *
 * Exit 1 means "no such ref" and is the only failure that is a legitimate
 * answer. Anything else (not a repo, corrupt object store) is a real error and
 * must not be read as "no".
 */
function tagExists(tag: string): boolean {
  try {
    execFileSync("git", ["-C", ROOT, "rev-parse", "--verify", "--quiet", `refs/tags/${tag}`], {
      stdio: ["ignore", "pipe", "pipe"],
    });
    return true;
  } catch (error: unknown) {
    const status = (error as { status?: number }).status;
    if (status === 1) return false;
    const stderr = (error as { stderr?: Buffer }).stderr;
    fail(
      `could not query git for tag '${tag}' (exit ${String(status)}): ` +
        `${stderr === undefined ? "no stderr" : stderr.toString().trim()}`,
    );
  }
}

function main(): void {
  if (!existsSync(GRADLE)) fail(`${GRADLE} not found`);

  const version = readVersion(readFileSync(GRADLE, "utf8"));
  const tag = `${TAG_PREFIX}${version.name}`;

  if (tagExists(tag)) {
    fail(
      `tag ${tag} already exists — version ${version.name} (code ${version.code}) has been ` +
        `released. Two releases must never share a version: bump versionCode and versionName ` +
        `in app/build.gradle.kts.`,
    );
  }

  process.stdout.write(
    `version-check: ok — ${version.name} (code ${version.code}); tag ${tag} is unused\n`,
  );
}

main();
