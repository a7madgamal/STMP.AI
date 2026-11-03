/**
 * Changelog guard — run before every commit that changes the app.
 *
 * WHY THIS EXISTS
 *   CHANGELOG.md is the only record of why each release looks the way it does, so
 *   it has to be true at commit time, not fixed up later. Two failure modes had
 *   already shipped by hand:
 *     - `— CURRENT` sat on 0.23.2 while the tree was on 0.25.0, so three releases
 *       looked unshipped.
 *     - the newest entry's build/version disagreed with app/build.gradle.kts.
 *   Both are mechanical, so a script catches them, not memory.
 *
 * WHAT IT CHECKS
 *   1. Exactly one `— CURRENT`, and it is on the NEWEST entry.
 *   2. The newest entry's version and build number match app/build.gradle.kts.
 *   3. No leftover `<hash>` placeholder in any entry.
 *
 * ON COMMIT HASHES
 *   An entry must NOT carry the hash of the commit that contains it: the hash is
 *   computed FROM the content, so writing it in changes the hash — a genuine
 *   self-reference. The entry lands in the SAME commit as the change, with no
 *   hash, and the commit is found later by version:
 *   `git log --oneline --grep "0.25.0"`.
 *
 * USAGE
 *   node tools/changelog-check.ts        # exit 0 = fine, 1 = fix it
 *
 * Exit codes are the contract: a failed check must fail loudly (the project's
 * fail-hard rule), never warn-and-continue.
 */

import { readFileSync, existsSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const CHANGELOG = join(ROOT, "CHANGELOG.md");
const GRADLE = join(ROOT, "app", "build.gradle.kts");
const MARKER = "## Keeping this file honest";

function fail(message: string): never {
  process.stderr.write(`changelog-check: FAIL — ${message}\n`);
  process.exit(1);
}

function main(): void {
  if (!existsSync(CHANGELOG)) fail(`${CHANGELOG} not found`);
  if (!existsSync(GRADLE)) fail(`${GRADLE} not found`);

  const text = readFileSync(CHANGELOG, "utf8");

  // Entries are everything above the trailing house-rules section. Splitting on
  // the marker is deliberate: a heading inside that section must never be
  // mistaken for a release entry.
  if (!text.includes(MARKER)) {
    fail(`missing the '${MARKER}' section — the file's structure has drifted`);
  }
  const body = text.split(MARKER)[0] ?? "";

  const entries = body.split("\n").filter((line) => line.startsWith("## "));
  if (entries.length === 0) {
    fail("no release entries found — the scan is broken, not the file");
  }

  const newest = entries[entries.length - 1];
  if (newest === undefined) fail("no newest entry — the scan is broken");

  const current = entries.filter((e) => e.includes("— CURRENT"));

  const firstCurrent = current[0];
  if (firstCurrent === undefined) {
    fail(
      "no entry is marked `— CURRENT`. Exactly one must be, or a reader cannot " +
        "tell which release is live.",
    );
  }
  if (current.length > 1) {
    fail(
      "more than one entry is marked `— CURRENT`:\n  " +
        current.join("\n  ") +
        "\nMove the marker to the newest entry only.",
    );
  }
  if (firstCurrent !== newest) {
    fail(
      "`— CURRENT` is on a stale entry.\n" +
        `  marked:  ${firstCurrent}\n` +
        `  newest:  ${newest}\n` +
        "Move the marker to the newest entry.",
    );
  }

  // The newest entry must describe the build the tree actually produces.
  const buildMatch = /\(build (\d+)\)/.exec(newest);
  if (buildMatch === null) fail(`newest entry has no '(build N)': ${newest}`);
  const buildRaw = buildMatch[1];
  if (buildRaw === undefined) fail(`newest entry has no '(build N)': ${newest}`);
  const entryBuild = Number.parseInt(buildRaw, 10);

  const gradle = readFileSync(GRADLE, "utf8");
  const codeMatch = /versionCode\s*=\s*(\d+)/.exec(gradle);
  const nameMatch = /versionName\s*=\s*"([^"]+)"/.exec(gradle);
  const codeValue = codeMatch?.[1];
  const nameValue = nameMatch?.[1];
  if (codeValue === undefined || nameValue === undefined) {
    fail("could not read versionCode/versionName from app/build.gradle.kts");
  }

  if (Number.parseInt(codeValue, 10) !== entryBuild) {
    fail(
      `build number disagrees: newest changelog entry says build ${String(entryBuild)}, ` +
        `but app/build.gradle.kts sets versionCode = ${codeValue}`,
    );
  }

  const entryVersion = /([0-9]+\.[0-9]+\.[0-9]+)/.exec(newest);
  if (entryVersion === null) fail(`newest entry has no semantic version: ${newest}`);
  const versionValue = entryVersion[1];
  if (versionValue === undefined) fail(`newest entry has no semantic version: ${newest}`);
  if (versionValue !== nameValue) {
    fail(
      `version disagrees: newest changelog entry says ${versionValue}, ` +
        `but app/build.gradle.kts sets versionName = "${nameValue}"`,
    );
  }

  // A placeholder means the entry was written but never finished.
  for (const entry of entries) {
    if (entry.includes("<hash>")) {
      fail(
        `unfinished placeholder in entry: ${entry}\n` +
          "An entry must NOT carry its own commit hash (self-reference). Drop the hash " +
          'and find the commit later with `git log --oneline --grep "<version>"`.',
      );
    }
  }

  process.stdout.write(
    `changelog-check: ok — ${String(entries.length)} entries, ` +
      `current ${nameValue} / build ${String(entryBuild)}\n`,
  );
}

main();
