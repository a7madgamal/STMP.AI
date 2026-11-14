/**
 * Generates the escaped code point table of the text sanitiser (contract v2).
 *
 * WHY THIS EXISTS
 *   Contract v1 said "escape every Cc/Cf/Zl/Zp/Cs code point", which each runtime resolves through
 *   its own Unicode tables (Android API 26 = Unicode 9, current iOS newer), so a code point such as
 *   U+0890 or U+13430 was escaped on one platform and shown raw on the other. Contract v2 lists every
 *   escaped code point explicitly, at one stated Unicode version, so the answer cannot depend on the
 *   runtime. This script is the only thing that writes that list.
 *
 * INPUT (pinned, committed, never the JVM's or Node's own tables)
 *   tools/unicode/15.1.0/UnicodeData.subset.txt                    Cc, Cf, Zl, Zp, Cs lines of UnicodeData.txt
 *   tools/unicode/15.1.0/DerivedCoreProperties.default-ignorable.txt  Default_Ignorable_Code_Point lines
 *   Both are verbatim line subsets of the official 15.1.0 files from
 *   https://www.unicode.org/Public/15.1.0/ucd/ (the full files also work as input: the script filters).
 *
 * RULE
 *   escaped = Cc + Cf + Zl + Zp + all surrogates U+D800..U+DFFF + Default_Ignorable_Code_Point
 *             + EXTRA_RANGES (kept from contract v1), minus U+0009 and U+000A.
 *
 * OUTPUT
 *   contract/text-sanitiser.json  "unicodeVersion" and "escapedRanges" (the rest of the file is
 *                                 hand-edited and kept as is)
 *   app/src/main/java/com/mido/phonebridge/ui/TextSanitiserTable.kt  the same ranges as Kotlin arrays
 *
 * REGENERATE
 *   node --experimental-strip-types tools/gen-text-sanitiser.ts          # write both files
 *   node --experimental-strip-types tools/gen-text-sanitiser.ts --check  # exit 1 if either is stale
 *   To move to a new Unicode version: add tools/unicode/<version>/ with the two files, change
 *   UNICODE_VERSION below, regenerate, bump "version" in the JSON, add vectors for what changed.
 */
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const UNICODE_VERSION = "15.1";
const DATA_DIR = `unicode/${UNICODE_VERSION}.0`;
const MAX_CODE_POINT = 0x10ffff;
const NEVER_ESCAPED = [0x09, 0x0a];
const ESCAPED_CATEGORIES = new Set(["Cc", "Cf", "Zl", "Zp", "Cs"]);
const SURROGATES: Range = [0xd800, 0xdfff];
/** Kept from contract v1: ranges the project escapes on top of the Unicode properties. */
const EXTRA_RANGES: Range[] = [
  [0x034f, 0x034f], [0x115f, 0x1160], [0x17b4, 0x17b5], [0x180b, 0x180f], [0x2060, 0x206f],
  [0x3164, 0x3164], [0xfe00, 0xfe0f], [0xffa0, 0xffa0], [0xfff0, 0xfff8], [0x1bca0, 0x1bca3],
  [0x1d173, 0x1d17a], [0xe0000, 0xe0fff],
];

type Range = [number, number];

const toolsDir = dirname(fileURLToPath(import.meta.url));
const root = join(toolsDir, "..");
const jsonPath = join(root, "contract/text-sanitiser.json");
const kotlinPath = join(root, "app/src/main/java/com/mido/phonebridge/ui/TextSanitiserTable.kt");

function dataLines(file: string): string[] {
  return readFileSync(join(toolsDir, DATA_DIR, file), "utf8")
    .split("\n")
    .filter((l) => l.trim() !== "" && !l.startsWith("#"));
}

/** Ranges of the categories in ESCAPED_CATEGORIES, honouring the `<..., First>` / `<..., Last>` pairs. */
function categoryRanges(): Range[] {
  const out: Range[] = [];
  let first: number | null = null;
  for (const line of dataLines("UnicodeData.subset.txt")) {
    const [hex = "", name = "", category = ""] = line.split(";");
    const cp = parseInt(hex, 16);
    if (!ESCAPED_CATEGORIES.has(category)) continue;
    if (name.endsWith(", First>")) {
      first = cp;
    } else if (name.endsWith(", Last>")) {
      if (first === null) throw new Error(`Last without First at ${hex}`);
      out.push([first, cp]);
      first = null;
    } else {
      out.push([cp, cp]);
    }
  }
  return out;
}

function defaultIgnorableRanges(): Range[] {
  const out: Range[] = [];
  for (const line of dataLines("DerivedCoreProperties.default-ignorable.txt")) {
    const m = /^([0-9A-F]+)(?:\.\.([0-9A-F]+))?\s*;\s*Default_Ignorable_Code_Point\b/.exec(line);
    if (m === null) continue;
    const start = parseInt(m[1] ?? "", 16);
    out.push([start, m[2] === undefined ? start : parseInt(m[2], 16)]);
  }
  return out;
}

function merge(ranges: Range[]): Range[] {
  const sorted = [...ranges].sort((a, b) => a[0] - b[0]);
  const out: Range[] = [];
  for (const [s, e] of sorted) {
    const last = out[out.length - 1];
    if (last !== undefined && s <= last[1] + 1) out[out.length - 1] = [last[0], Math.max(last[1], e)];
    else out.push([s, e]);
  }
  return out;
}

/** Removes the code points in `holes` from `ranges`. */
function subtract(ranges: Range[], holes: number[]): Range[] {
  let out = ranges;
  for (const h of holes) {
    out = out.flatMap(([s, e]): Range[] => {
      if (h < s || h > e) return [[s, e]];
      const parts: Range[] = [];
      if (h > s) parts.push([s, h - 1]);
      if (h < e) parts.push([h + 1, e]);
      return parts;
    });
  }
  return out;
}

function escapedRanges(): Range[] {
  const all = [...categoryRanges(), SURROGATES, ...defaultIgnorableRanges(), ...EXTRA_RANGES];
  const merged = subtract(merge(all), NEVER_ESCAPED);
  for (const [s, e] of merged) if (s < 0 || e > MAX_CODE_POINT || s > e) throw new Error(`bad range ${s}..${e}`);
  return merged;
}

/** One-line JSON ("a": 1, [1, 2]) with every non-ASCII character written as a lower-case \\uXXXX escape. */
function ascii(value: unknown): string {
  if (Array.isArray(value)) return "[" + value.map(ascii).join(", ") + "]";
  if (value !== null && typeof value === "object") {
    return "{" + Object.entries(value).map(([k, v]) => `${ascii(k)}: ${ascii(v)}`).join(", ") + "}";
  }
  return JSON.stringify(value).replace(/[\u007f-\uffff]/g, (c) => "\\u" + c.charCodeAt(0).toString(16).padStart(4, "0"));
}

function rangeRows(ranges: Range[]): string {
  const perLine = 8;
  const rows: string[] = [];
  for (let i = 0; i < ranges.length; i += perLine) {
    rows.push("    " + ranges.slice(i, i + perLine).map((r) => `[${r[0]}, ${r[1]}]`).join(", "));
  }
  return "[\n" + rows.join(",\n") + "\n  ]";
}

const KEY_ORDER = [
  "version", "unicodeVersion", "token", "neverEscaped", "escapedRanges", "wrapGlyphs",
  "systemSurface", "vectors", "systemSurfaceVectors",
];

function renderJson(ranges: Range[]): string {
  const existing = JSON.parse(readFileSync(jsonPath, "utf8")) as Record<string, unknown>;
  const doc: Record<string, unknown> = { ...existing, unicodeVersion: UNICODE_VERSION };
  delete doc["escapedCategories"];
  const unknown = Object.keys(doc).filter((k) => !KEY_ORDER.includes(k));
  if (unknown.length > 0) throw new Error(`unexpected keys in contract: ${unknown.join(", ")}`);
  const parts: string[] = [];
  for (const key of KEY_ORDER) {
    const value = doc[key];
    if (key === "escapedRanges") {
      parts.push(`  "escapedRanges": ${rangeRows(ranges)}`);
    } else if ((key === "vectors" || key === "systemSurfaceVectors") && Array.isArray(value)) {
      parts.push(`  ${ascii(key)}: [\n${value.map((v) => "    " + ascii(v)).join(",\n")}\n  ]`);
    } else if (value !== undefined) {
      parts.push(`  ${ascii(key)}: ${ascii(value)}`);
    }
  }
  return "{\n" + parts.join(",\n") + "\n}\n";
}

function hex(n: number): string {
  return "0x" + n.toString(16).toUpperCase();
}

function renderKotlin(ranges: Range[]): string {
  const rows = (pick: (r: Range) => number): string => {
    const lines: string[] = [];
    for (let i = 0; i < ranges.length; i += 8) {
      lines.push("        " + ranges.slice(i, i + 8).map((r) => hex(pick(r))).join(", ") + ",");
    }
    return lines.join("\n");
  };
  return `package com.mido.phonebridge.ui

// GENERATED by tools/gen-text-sanitiser.ts from the pinned Unicode ${UNICODE_VERSION} data in tools/unicode/.
// Do not edit by hand: change the generator or contract/text-sanitiser.json and run
//   node --experimental-strip-types tools/gen-text-sanitiser.ts
// TextSanitiserContractTest fails if this table differs from "escapedRanges" in the contract file.

/**
 * Every escaped code point at Unicode ${UNICODE_VERSION}, as sorted, non-overlapping, merged ranges
 * (U+0009 and U+000A excluded). Not derived from the JVM's Character tables, so API 26 and API 36
 * answer alike.
 */
internal object TextSanitiserTable {
    const val UNICODE_VERSION = "${UNICODE_VERSION}"

    /** First code point of each range, ascending. */
    val STARTS: IntArray = intArrayOf(
${rows((r) => r[0])}
    )

    /** Last code point of each range (inclusive), same index as [STARTS]. */
    val ENDS: IntArray = intArrayOf(
${rows((r) => r[1])}
    )

    /** True when [codePoint] lies in a range: a binary search over [STARTS]. */
    fun contains(codePoint: Int): Boolean {
        var lo = 0
        var hi = STARTS.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            when {
                codePoint < STARTS[mid] -> hi = mid - 1
                codePoint > ENDS[mid] -> lo = mid + 1
                else -> return true
            }
        }
        return false
    }
}
`;
}

const ranges = escapedRanges();
const outputs: Array<[string, string]> = [[jsonPath, renderJson(ranges)], [kotlinPath, renderKotlin(ranges)]];
if (process.argv.includes("--check")) {
  const stale = outputs.filter(([path, text]) => readFileSync(path, "utf8") !== text).map(([path]) => path);
  if (stale.length > 0) {
    console.error(`stale, run tools/gen-text-sanitiser.ts: ${stale.join(", ")}`);
    process.exit(1);
  }
  console.log(`text sanitiser table is current (${ranges.length} ranges, Unicode ${UNICODE_VERSION})`);
} else {
  for (const [path, text] of outputs) writeFileSync(path, text);
  console.log(`wrote ${ranges.length} ranges, Unicode ${UNICODE_VERSION}`);
}
