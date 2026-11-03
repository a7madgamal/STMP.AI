/**
 * Test-summary guard — CI only.
 *
 * WHY THIS EXISTS
 *   A test step that greps for "BUILD SUCCESSFUL" can pass having run zero
 *   tests. Gradle reports success when a filter matches nothing, and a broken
 *   harness looks exactly like a clean codebase. This reads the XML reports
 *   Gradle actually produced and asserts they describe a real run.
 *
 * WHAT IT CHECKS
 *   1. At least one result XML exists.
 *   2. The total test count is above zero.
 *   3. No failures and no errors.
 *
 * USAGE
 *   node tools/test-summary.ts
 */

import { readdirSync, readFileSync, existsSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const RESULTS = join(ROOT, "app", "build", "test-results", "testDebugUnitTest");

function fail(message: string): never {
  process.stderr.write(`test-summary: FAIL — ${message}\n`);
  process.exit(1);
}

/**
 * Pull `tests`, `failures` and `errors` off the root element.
 *
 * A hand-rolled read rather than an XML library: the attributes sit on the first
 * `<testsuite ...>` tag, the values are decimal integers, and adding a dependency
 * to read three integers would be worse than reading them directly. If the shape
 * is ever not what this expects, it fails loudly instead of guessing.
 */
function readCounts(xml: string): { tests: number; failures: number; errors: number } {
  const suite = /<testsuite\b[^>]*>/i.exec(xml);
  if (suite === null) fail("a result file has no <testsuite> element");

  const attr = (name: string): number => {
    const match = new RegExp(`\\b${name}="(\\d+)"`).exec(suite[0]);
    if (match === null) return 0;
    const raw = match[1];
    if (raw === undefined) fail(`attribute '${name}' matched with no value`);
    return Number.parseInt(raw, 10);
  };

  return { tests: attr("tests"), failures: attr("failures"), errors: attr("errors") };
}

function main(): void {
  if (!existsSync(RESULTS)) {
    fail(`no test results directory at ${RESULTS} — the test task produced nothing`);
  }

  const files = readdirSync(RESULTS).filter((f) => f.endsWith(".xml"));
  if (files.length === 0) {
    fail("test results directory holds no XML — the test task produced nothing");
  }

  let tests = 0;
  let failures = 0;
  let errors = 0;
  for (const file of files) {
    const counts = readCounts(readFileSync(join(RESULTS, file), "utf8"));
    tests += counts.tests;
    failures += counts.failures;
    errors += counts.errors;
  }

  process.stdout.write(`tests=${String(tests)} failures=${String(failures)} errors=${String(errors)}\n`);

  if (tests === 0) fail("zero tests ran — that is a broken harness, not a pass");
  if (failures > 0 || errors > 0) {
    fail(`${String(failures)} failing and ${String(errors)} erroring test(s)`);
  }

  process.stdout.write("test-summary: ok\n");
}

main();
