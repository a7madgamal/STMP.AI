/**
 * Extract the changelog entry for the current version, for a GitHub Release body.
 *
 * WHY THIS EXISTS
 *   The release body used to be a fixed line: "Beta build of vX. Promote with the Promote
 *   workflow to move this exact artifact to stable." That is an instruction to the maintainer,
 *   not release notes — Mido, 2026-11-03: *"the release description should be the change log,
 *   lost the upgrade note it's for me not the users"*.
 *
 *   CHANGELOG.md already records what shipped and WHY. It is the only honest source for a
 *   release body, because it is written at commit time by whoever made the change, and it
 *   cannot drift from the code the way a hand-written note does.
 *
 * WHAT IT RETURNS
 *   The newest entry — from its `## <version> — ...` heading up to the next `## ` heading or the
 *   house-rules marker. The `— CURRENT` marker is stripped, since it describes this file's own
 *   bookkeeping rather than the release.
 *
 * FAILS HARD
 *   No entry for the current version, or an empty one, is a release with nothing to say. That is
 *   a defect in the commit, not an occasion for a placeholder body — so this exits non-zero and
 *   the workflow stops before publishing.
 *
 * USAGE
 *   node tools/release-notes.ts            # writes the body to stdout
 *   node tools/release-notes.ts --version 0.43.74
 */

import { readFileSync, existsSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const CHANGELOG = join(ROOT, "CHANGELOG.md");
const GRADLE = join(ROOT, "app", "build.gradle.kts");
const MARKER = "## Keeping this file honest";

function fail(message: string): never {
  process.stderr.write(`release-notes: FAIL — ${message}\n`);
  process.exit(1);
}

function versionFromGradle(): string {
  const text = readFileSync(GRADLE, "utf8");
  const match = /versionName\s*=\s*"([^"]+)"/.exec(text);
  const value = match?.[1];
  if (value === undefined) fail(`could not read versionName from ${GRADLE}`);
  return value;
}

function versionFromArgs(): string | null {
  const i = process.argv.indexOf("--version");
  if (i === -1) return null;
  const value = process.argv[i + 1];
  if (value === undefined || value.trim().length === 0) {
    fail("--version was given with no value");
  }
  return value.trim();
}

function main(): void {
  if (!existsSync(CHANGELOG)) fail(`${CHANGELOG} not found`);

  const version = versionFromArgs() ?? versionFromGradle();
  const text = readFileSync(CHANGELOG, "utf8");

  // Entries stop at the house-rules section, which contains its own `## ` heading and must
  // never be mistaken for a release.
  const body = text.split(MARKER)[0] ?? "";

  const lines = body.split("\n");
  const startIndex = lines.findIndex((l) => l.startsWith(`## ${version} `) || l.startsWith(`## ${version}`));
  if (startIndex === -1) {
    fail(
      `no changelog entry for version ${version}. The release body IS the changelog entry, so ` +
        "there is nothing to publish — add the entry in the same commit as the version bump.",
    );
  }

  // Up to (not including) the next `## ` heading.
  let endIndex = lines.length;
  for (let i = startIndex + 1; i < lines.length; i++) {
    const line = lines[i];
    if (line !== undefined && line.startsWith("## ")) {
      endIndex = i;
      break;
    }
  }

  const raw = lines.slice(startIndex, endIndex).join("\n").trim();

  // Strip the file's own bookkeeping marker: it says "this is the newest entry in
  // CHANGELOG.md", which is meaningless to someone reading a release page.
  const cleaned = raw
    .replace(/\s*—\s*CURRENT\s*$/m, "")
    .replace(/[ \t]+$/gm, "")
    .trim();

  if (cleaned.length === 0) {
    fail(`the changelog entry for ${version} is empty — a release with no notes is a defect`);
  }

  // A release body that only restates the heading says nothing. Require at least one line of
  // prose beyond it, or the publish step is shipping a placeholder in a different costume.
  const withoutHeading = cleaned.split("\n").slice(1).join("\n").trim();
  if (withoutHeading.length === 0) {
    fail(
      `the changelog entry for ${version} has a heading and nothing else. Write the WHY — ` +
        "the reasoning is what stops a future change undoing the fix.",
    );
  }

  process.stdout.write(cleaned + "\n");
}

main();
