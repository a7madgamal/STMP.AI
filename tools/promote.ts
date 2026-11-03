/**
 * Promote a BETA release to STABLE.
 *
 *   node tools/promote.ts v0.43.66
 *
 * Promotion is a METADATA change: the release's `prerelease` flag flips to false.
 * The APK is not rebuilt, re-tagged or re-signed, so the artifact everyone on the
 * stable channel receives is byte-for-byte the one that was tested as a beta.
 *
 * This is the local equivalent of .github/workflows/promote.yml and performs the
 * same checks in the same order, so the two cannot drift into disagreeing about
 * what "promotable" means.
 *
 * Requires `gh` authenticated as someone with write access to the repo.
 */

import { execFileSync } from "node:child_process";
import { mkdtempSync, readdirSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const EXPECTED_SHA1 = "87DDD5DEFA068933F44EC699533A1B00ED313103";

function fail(message: string): never {
  process.stderr.write(`promote: FAIL — ${message}\n`);
  process.exit(1);
}

/** Run a command and return stdout, or fail with its stderr. */
function run(cmd: string, args: readonly string[]): string {
  try {
    return execFileSync(cmd, args, { cwd: ROOT, encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
  } catch (error: unknown) {
    const stderr = (error as { stderr?: string }).stderr;
    fail(`${cmd} ${args.join(" ")} failed: ${stderr === undefined ? String(error) : stderr.trim()}`);
  }
}

interface ReleaseState {
  readonly isDraft: boolean;
  readonly isPrerelease: boolean;
  readonly isLatest: boolean;
  readonly databaseId: number;
  readonly assets: readonly { readonly name: string }[];
}

function releaseState(tag: string): ReleaseState {
  const raw = run("gh", ["release", "view", tag, "--json", "isDraft,isPrerelease,isLatest,databaseId,assets"]);
  return JSON.parse(raw) as ReleaseState;
}

/**
 * Extract the signer SHA-1 from an APK.
 *
 * `keytool -printcert -jarfile` reads straight out of the APK, so this needs no
 * Android SDK — only a JDK.
 *
 * Exactly one certificate, or the run stops. A multi-signed APK is not something
 * this project produces, and picking a winner out of several would verify the
 * wrong key.
 */
function signerSha1(apkPath: string): string {
  const out = run("keytool", ["-printcert", "-jarfile", apkPath]);
  const matches = [...out.matchAll(/SHA1:\s*([0-9A-F:]+)/g)];
  const first = matches[0];
  if (first === undefined || matches.length !== 1) {
    fail(`expected exactly 1 signing certificate in the APK, found ${String(matches.length)}`);
  }
  const hex = first[1];
  if (hex === undefined) fail("SHA-1 matched with no value");
  return hex.replaceAll(":", "").toUpperCase();
}

function main(): void {
  const tag = process.argv[2];
  if (tag === undefined || tag.length === 0) {
    process.stderr.write("usage: node tools/promote.ts <tag>    e.g. node tools/promote.ts v0.43.66\n");
    process.exit(2);
  }

  // --- Preconditions. Each failure below is a reason NOT to promote. -----------

  const before = releaseState(tag);
  const apkCount = before.assets.filter((a) => a.name.endsWith(".apk")).length;

  process.stdout.write(
    `tag=${tag} draft=${String(before.isDraft)} prerelease=${String(before.isPrerelease)} ` +
      `apk_assets=${String(apkCount)}\n`,
  );

  if (before.isDraft) {
    fail(`${tag} is a DRAFT. Drafts are invisible to the updater, so promoting one ships nothing.`);
  }
  if (!before.isPrerelease) {
    fail(`${tag} is already stable — refusing to promote it twice.`);
  }
  if (apkCount !== 1) {
    fail(`${tag} carries ${String(apkCount)} APK assets; expected exactly 1.`);
  }

  // --- Verify the artifact before it becomes the stable channel ----------------

  const work = mkdtempSync(join(tmpdir(), "stmp-promote-"));
  try {
    run("gh", ["release", "download", tag, "--pattern", "*.apk", "--dir", work, "--clobber"]);

    const found = readdirSync(work).filter((f) => f.endsWith(".apk"));
    const only = found[0];
    if (only === undefined || found.length !== 1) {
      fail(`expected exactly 1 APK in ${tag}, downloaded ${String(found.length)}`);
    }

    const signer = signerSha1(join(work, only));
    process.stdout.write(`signer SHA-1: ${signer}\n`);
    if (signer !== EXPECTED_SHA1) {
      fail(
        `APK in ${tag} is not signed by the expected key.\n` +
          `       got ${signer}, expected ${EXPECTED_SHA1}`,
      );
    }
  } finally {
    rmSync(work, { recursive: true, force: true });
  }

  // --- Promote, then confirm the new state ------------------------------------
  //
  // REST PATCH rather than `gh release edit --prerelease=false`: the CLI's boolean
  // flags have historically ignored an explicit value, so the release stayed a
  // prerelease while the command exited 0. A false success is the one outcome this
  // script must never report, so the state is re-read afterwards.

  run("gh", [
    "api",
    "-X",
    "PATCH",
    `repos/{owner}/{repo}/releases/${String(before.databaseId)}`,
    "-F",
    "prerelease=false",
    "-F",
    "make_latest=true",
  ]);

  const after = releaseState(tag);
  process.stdout.write(
    `after: prerelease=${String(after.isPrerelease)} latest=${String(after.isLatest)}\n`,
  );
  if (after.isPrerelease) {
    fail(`promotion did not take — ${tag} is still marked prerelease.`);
  }

  process.stdout.write(`promoted ${tag} to stable\n`);
}

main();
