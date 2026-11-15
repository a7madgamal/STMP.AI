/**
 * Version guard for incoming branches — a merge must never move `main` BACKWARDS.
 *
 * WHY THIS EXISTS
 *   `version-check.ts` stops a version being RELEASED twice. It cannot stop a branch
 *   being merged that carries an OLDER build number than `main`, because at merge time
 *   nothing has been released yet — the damage is written to the default branch and only
 *   discovered at the next install, as Android's "App not installed", which reads like a
 *   corrupt download rather than a version regression.
 *
 *   Measured on 2026-11-15: `main` was at versionCode 351 (0.51.25) while the three open
 *   PRs carried 324, 326 and 333. Every one of them would have taken `main` backwards. The
 *   worst was PR 12 — versionName "0.52.9", which reads as newer than 0.51.25 in every
 *   place a human looks, under a versionCode 27 lower than the build already on the phone.
 *
 * WHAT IT CHECKS
 *   Compares the working tree against a base ref (normally `origin/main`):
 *     1. code <  base.code                       -> FAIL (a downgrade: nothing will install)
 *     2. code == base.code && name != base.name  -> FAIL (same code, different name: the
 *                                                        update can never be offered, and
 *                                                        two versions claim one identity)
 *     3. code >  base.code && name <= base.name  -> FAIL (the code moved but the name did
 *                                                        not, and the in-app updater orders
 *                                                        by name — nobody would be offered it)
 *   code == base.code && name == base.name is OK: a PR that does not bump is legitimate and
 *   common, and failing it would make this check noise nobody reads.
 *
 * USAGE
 *   node tools/version-guard.ts                    # base defaults to origin/main
 *   node tools/version-guard.ts --base origin/main
 *   node tools/version-guard.ts --file <path>      # judge a build file that is not the working
 *                                                  # tree's, e.g. one read out of a commit being
 *                                                  # pushed. The BASE always comes from git.
 */

import { readFileSync, existsSync } from "node:fs";
import { execFileSync } from "node:child_process";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const GRADLE = join(ROOT, "app", "build.gradle.kts");
const DEFAULT_BASE = "origin/main";

interface Version {
  readonly code: number;
  readonly name: string;
}

function fail(message: string): never {
  process.stderr.write(`version-guard: FAIL — ${message}\n`);
  process.exit(1);
}

function readVersion(text: string, where: string): Version {
  const code = /versionCode\s*=\s*(\d+)/.exec(text);
  const name = /versionName\s*=\s*"([^"]+)"/.exec(text);
  if (code === null || name === null) {
    fail(`versionCode or versionName not found in ${where}`);
  }
  // `noUncheckedIndexedAccess` leaves [1] possibly-undefined; the null check above does not
  // narrow it, so narrow explicitly rather than asserting.
  const codeRaw = code[1];
  const nameRaw = name[1];
  if (codeRaw === undefined || nameRaw === undefined) {
    fail(`versionCode or versionName matched with no captured value in ${where}`);
  }
  return { code: Number.parseInt(codeRaw, 10), name: nameRaw };
}

/** The base ref's own copy of the build file, read out of git rather than the working tree. */
function readBaseVersion(base: string): Version {
  let text: string;
  try {
    text = execFileSync("git", ["-C", ROOT, "show", `${base}:app/build.gradle.kts`], {
      encoding: "utf8",
      stdio: ["ignore", "pipe", "pipe"],
      timeout: 20_000,
    });
  } catch (error: unknown) {
    const stderr = (error as { stderr?: Buffer }).stderr;
    fail(
      `could not read app/build.gradle.kts from '${base}': ` +
        `${stderr === undefined ? String(error) : stderr.toString().trim()}\n` +
        "Refusing to report this branch as safe on the strength of a failed lookup — " +
        "fetch the base branch (git fetch origin main) and run again.",
    );
  }
  return readVersion(text, base);
}

/**
 * Dotted-number comparison, as the updater does it: `0.9.0` is older than `0.44.0` even
 * though it sorts newer as a string. A non-numeric component counts as 0 so a suffix like
 * `0.52.0-rc1` cannot make the comparison throw.
 */
function compareNames(a: string, b: string): number {
  const left = a.split(".").map((p) => Number.parseInt(p, 10));
  const right = b.split(".").map((p) => Number.parseInt(p, 10));
  const length = Math.max(left.length, right.length);
  for (let i = 0; i < length; i++) {
    const x = left[i] ?? 0;
    const y = right[i] ?? 0;
    const nx = Number.isNaN(x) ? 0 : x;
    const ny = Number.isNaN(y) ? 0 : y;
    if (nx !== ny) return nx < ny ? -1 : 1;
  }
  return 0;
}

function baseFromArgs(): string {
  const i = process.argv.indexOf("--base");
  if (i === -1) return DEFAULT_BASE;
  const value = process.argv[i + 1];
  if (value === undefined || value.trim().length === 0) {
    fail("--base was given with no value");
  }
  return value.trim();
}

/**
 * The file to judge. Defaults to the working tree's build file.
 *
 * `--file` exists for the pre-push hook, which judges a COMMIT that may not be checked out: the
 * hook reads `app/build.gradle.kts` out of the pushed sha and hands it over here. The base still
 * comes from git, so the comparison is between two real builds rather than two opinions.
 */
function incomingPath(): string {
  const i = process.argv.indexOf("--file");
  if (i === -1) return GRADLE;
  const value = process.argv[i + 1];
  if (value === undefined || value.trim().length === 0) {
    fail("--file was given with no value");
  }
  return resolve(value.trim());
}

function main(): void {
  const base = baseFromArgs();
  const file = incomingPath();
  if (!existsSync(file)) fail(`${file} not found`);
  const incoming = readVersion(readFileSync(file, "utf8"), file);
  const current = readBaseVersion(base);

  // 1. A lower code is a downgrade. Android refuses it outright, so merging one guarantees
  //    the next release cannot be installed over what is already on the phone.
  if (incoming.code < current.code) {
    fail(
      `this branch moves ${base} BACKWARDS: versionCode ${String(incoming.code)} ` +
        `(${incoming.name}) is lower than ${String(current.code)} (${current.name}).\n` +
        "  Android refuses an APK with a lower versionCode, so the release built from this " +
        "merge could never install over the build already on a phone.\n" +
        "  Rebase onto the current base and carry its versionCode forward — do not lower it.",
    );
  }

  // 2. One build code, one name. Two names sharing a code means the updater can never
  //    distinguish them, and `version-check` cannot tell them apart either.
  if (incoming.code === current.code && incoming.name !== current.name) {
    fail(
      `versionCode ${String(incoming.code)} is already taken by ${current.name} in ${base}, ` +
        `but this branch calls it ${incoming.name}.\n` +
        "  A build code identifies a build: give this branch its own (higher) versionCode, " +
        "or leave both fields alone.",
    );
  }

  // 3. The code moved but the name did not. The in-app updater orders by NAME, so a build
  //    nobody is offered is a build that does not exist as far as the phone is concerned.
  if (incoming.code > current.code && compareNames(incoming.name, current.name) <= 0) {
    fail(
      `versionCode moved to ${String(incoming.code)} but versionName is still ` +
        `${incoming.name} (base has ${current.name}).\n` +
        "  The updater compares names, so nobody would ever be offered this build. " +
        "Bump versionName with versionCode.",
    );
  }

  process.stdout.write(
    `version-guard: ok — ${incoming.name} (code ${String(incoming.code)}) ` +
      `vs ${base} ${current.name} (code ${String(current.code)})\n`,
  );
}

main();
