# `files` — the phone's own storage, behind the gate

Read and write files in a fixed sandbox on the phone, through the same approval gate as every
other route. Drive already covers cloud files; this covers the case where the file should land on
the device and never leave it.

Every decision below was asked and answered before any code existed. This file is the argument the
next change has to beat, not a description of what was convenient.

---

## Scope — one folder, resolved then checked

`Downloads/STMP/` and nothing else.

**Rejected, not merely cleaned.** The path is resolved with `getCanonicalFile()` and then compared
against the resolved root with `Path.startsWith`. A `..` that was removed by normalisation would
make `../../data/data/com.mido.phonebridge/` *look* safe; resolving first means the traversal is
visible at its destination and can be refused. A symlink pointing out of the sandbox resolves to
its target and is refused for the same reason — `getCanonicalFile()` is the only thing that catches
that, which is why it is not optional here.

**Why not the whole card.** This app holds a vault key and can run sudo commands. A `path`
parameter that reaches anything turns the API token into a file-read primitive over every photo and
every other app's exports.

**Drive writes to a different folder.** `PhoneSave.RELATIVE_SUBDIR` is `STMP` now, so `?to=phone`
and `/files` share a root. Older Drive saves sit in `Downloads/PhoneBridge/` and are left there:
the app never moves or deletes a file it did not just create.

## Routes and verbs

| Route | Verb | Notes |
|---|---|---|
| `GET /files?path=<dir>` | READ | listing: name, size, mtime, isDir |
| `GET /files/content?path=<file>` | READ | raw bytes, the file's own content type |
| `POST /files/upload?path=<file>&type=<mime>` | WRITE | the body IS the file |
| `DELETE /files?path=<file>` | DELETE | the target is named in the modal |

**Every route opens a card, including the listing.** Browsing is a read of the device, and this
app treats reads as things a human approves. Accepting the cost of a tap per call is the price of
not having a route that answers without one.

A plugin that is switched off answers `503 plugin_disabled` — never `404`. "No such route" and
"you turned this off" are different facts and the log should be able to tell them apart.

## Bytes

- **Inbound:** `BoundedCopy.exactly` with the cap enforced by a counting loop. Never `readBytes()`,
  never `copyTo` — at 200 MiB a buffered body is an OOM, and an OOM mid-write leaves a truncated
  file that reports success.
- **Outbound:** `setFixedLengthStreamingMode(length)` so the caller gets a real `Content-Length`
  and a mid-stream failure is a broken connection rather than a short file with a clean 200.
- **Neither route audits the body.** The audit blob cap is 32 KiB; a file body would silently eat
  retention.

## The cap: 200 MiB

Checked against `Content-Length` **before the gate opens**, so a refusal never costs a tap. A
request with no `Content-Length` is refused rather than streamed blind — the cap is the one number
that decides whether the transfer is allowed at all, and it cannot be enforced against a length
nobody declared.

`206/413` carry the code `file_too_large` with the limit in the message, so a caller learns the
number instead of guessing.

## Collision: refuse

An upload to an existing name answers `409 file_exists` and writes nothing.

The alternatives are worse in a way that is easy to miss: `name (1)` quietly fills the folder with
copies nobody asked for, and a replace destroys a file behind a card that says "upload". Replacing
is a deliberate two-step — DELETE, then upload — and each step gets its own card, which is what
makes it deliberate.

The modal names the full target path, so the human sees what will be written before approving.

## Storage robustness — the matrix

Filesystem behaviour is the part most likely to differ per device, so each case is decided here
rather than discovered:

| Case | Answer |
|---|---|
| API < 29 | `503 unsupported_storage`, same wording Drive uses. MediaStore Downloads is API 29+, and on 29+ direct writes into `/sdcard/Download` are blocked anyway — so there is one permission-free path and it has a floor. |
| No external storage / unmounted | `503 storage_unavailable`. Not `500`: the phone is in a legitimate state, not broken. |
| Read-only volume | `503 storage_read_only` on writes; reads still work. |
| Folder does not exist | Created on first upload, as part of the same gated operation. A read of a missing folder is `404`. |
| Path with `//`, `./`, trailing `/` | Normalised by the same canonicalisation the traversal check uses. |
| Unicode, emoji, spaces, newline in a name | Allowed. The name reaches JSON as escaped text; the newline survives because a filename may legally contain one. |
| `..` or an absolute path | Refused by the root check, not by string cleaning. |
| Symlink out of the sandbox | Resolved, then refused — same check. |
| A directory where a file is expected | `400 not_a_file`; a file where a directory is expected | `400 not_a_directory`. |
| File deleted between the card and the write | `404 file_gone`. Never a partial success. |

## What this must not do

- **No route reads or writes outside `Downloads/STMP`.** The check is at the resolved path, and it
  is the only place that decides.
- **No silent truncation.** A body shorter than `Content-Length` is an error, never a smaller file
  that answers 200.
- **No audit of file bytes.**
- **No overwrite, ever, without an explicit delete first.**

## Tests

- **Traversal:** `../`, `..%2f`, absolute paths, `//`, a symlink out, and a name that is literally
  `..` — each refused, each as `403 path_outside_root` rather than a generic failure.
- **Cap:** over-limit refused before the gate (asserted by a gate that is never asked); no
  `Content-Length` refused; exactly-at-limit accepted.
- **Collision:** an existing name gives `409 file_exists` and the original is byte-identical
  afterwards — the assertion that proves "refuse" actually refused.
- **Short body:** a body shorter than declared leaves no file.
- **Verification:** a completed upload's on-disk size equals the size the response reported. This
  is the documented trap — a mid-stream break cannot be reported once the headers are gone.
- **API floor:** below 29, `unsupported_storage` and no card.
