# `files` — the phone's own storage, behind the gate

Read and write files in a folder you choose, through the same approval gate as every other route.
Drive covers cloud files; this covers the case where the file should land on the device and never
leave it.

Every decision below was asked and answered before any code existed. **The first version of this
plan was wrong in its core** and the review that caught it is recorded here, because the next change
needs to beat the reasoning rather than the shape.

---

## What went wrong in the first draft

The plan said "permission-free, like Drive's `?to=phone`". That was true of **writing** and false of
everything else: Drive inserts a row through MediaStore and never reads one back, so its mechanism
proves nothing about reading. Three consequences, and the third decides the design:

1. MediaStore is not a filesystem — `list`/`download` become `query`/`openInputStream`, so a
   `getCanonicalFile` traversal check has nothing to run against.
2. `DISPLAY_NAME` can contain `/`, which MediaStore will happily expand into a subdirectory — a
   path-injection point that the filesystem version never had.
3. **On API 29+ without a storage grant, a MediaStore query returns only rows your own app
   contributed.** A file dropped into the folder with a file manager — the main reason to want this
   plugin — would be invisible to `list`.

So MediaStore-only cannot do the job.

## Access: SAF, one folder, granted ON DEMAND

The user picks the folder in the system picker; the app keeps the grant with
`takePersistableUriPermission`. Everything then goes through `DocumentsContract` and
`ContentResolver`.

**The grant is asked for when a request needs it, not at setup.** No onboarding screen, no prompt on
first app open, no permission the user has to understand before they have a reason to. A route that
arrives without a grant answers `503 folder_not_granted` and posts a notification whose tap opens
the picker; the caller retries once the human has chosen.

**The HTTP call does not wait for the picker.** A folder picker is a human browsing a filesystem —
unbounded time, and often on another app's screen. Blocking the request on it would either exceed
the gate's window or hang the caller on an action it cannot influence. So the answer is immediate
and the grant is the human's next step, which also means the retry is explicit rather than implicit.

- Works from **API 26**, so the `unsupported_storage` floor Drive needs disappears.
- The boundary is **the URI the human chose**, which is stronger than any path string: there is no
  root to escape from, because there is no path arithmetic at all.
- `list` sees every file in the folder, including ones put there outside the app.
- The grant can be revoked in system settings, so an unreachable folder is a normal state:
  `503 folder_not_granted` again, and asking again is the same one notification.

## Routes: READ and WRITE only

| Route | Verb | Notes |
|---|---|---|
| `GET /files?path=<dir>` | READ | LIST FOLDER: name, size, mtime, isDir |
| `GET /files/content?path=<file>` | READ | DOWNLOAD: raw bytes, the file's own content type |
| `POST /files/upload?path=<file>&type=<mime>` | WRITE | UPLOAD: the body IS the file |

**Every route opens a card**, listing included. Browsing is a read of the device and this app treats
reads as things a human approves.

**The cards say UPLOAD and DOWNLOAD, not WRITE and READ.** `Verb` is the app's internal taxonomy and
its direction depends on whose side you are standing on — the phone writes, the caller receives. A
human reading a card has no such context. DOWNLOAD also cannot be mistaken for "open it on the
phone", which "READ" could: the bytes leave the device, and the row says so.

**There is no DELETE.** It was the only irreversible action in the plugin, and the review found its
card could not honestly describe itself: `Downloads` has no trash, so the words would have to be
"permanently, no undo, no backup". Dropping the route removes the problem instead of wording around
it. **`files` can add files and read files; it cannot destroy one.** Adding delete later is a
separate decision with its own card, not a quiet extension.

A disabled plugin answers `503 plugin_disabled`, never `404` — "no such route" and "you turned this
off" are different facts and the log must tell them apart.

## Bytes and the cap

- **Inbound:** `BoundedCopy.exactly` with the cap enforced by a counting loop. Never `readBytes()`,
  never `copyTo`: at 200 MiB a buffered body is an OOM, and an OOM mid-write leaves a truncated file
  that reports success.
- **Outbound:** `setFixedLengthStreamingMode(length)`, so a mid-stream failure is a broken connection
  rather than a short file behind a clean 200.
- **Neither route audits the body.** The audit blob cap is 32 KiB; a file body would silently eat
  retention.

**Cap: 200 MiB**, checked against `Content-Length` before the gate opens, so a refusal never costs a
tap. No `Content-Length` means refused, not streamed blind — the cap is the number that decides
whether the transfer may happen at all, and it cannot be enforced against an undeclared length.
Over the cap: `413 file_too_large`, with the limit in the message.

## Progress: a notification, because the card cannot stay

An approved 200 MiB upload takes minutes. The gate cannot be held open for it — a modal pinned for
minutes blocks every other request — so the approval ends and the transfer continues.

That leaves two failures the human would otherwise never see: a silent success, and a failure at
180 MiB with nothing on screen to report it. So the upload runs with a **notification** on the app's
existing channel, showing transferred bytes while it runs and collapsing to one of:

- `uploaded report.pdf (42 MB)`
- `upload failed: connection lost — nothing was written`

The failure text says **nothing was written** only when that is true: a partial document is rolled
back before the notification is updated, so the message and the disk agree.

**A failed upload deletes the document it just created**, and that is not the DELETE route coming
back. The promise is about the user's files; a half-written document from a transfer that failed is
not a file they asked for, and leaving it is worse than removing it because the Files app shows it
as complete and nobody knows it is truncated. The call is `DocumentsContract.deleteDocument` on the
URI this upload created — never on a document that existed before it started, which the collision
rule guarantees cannot happen.

## Collision: refuse, and nothing else can either

An upload to an existing name answers `409 file_exists` and writes nothing.

There is no overwrite path at all now that DELETE is gone, so **no route in this plugin can remove
or replace an existing file**. The modal names the full target path, so "upload" is never really
"overwrite".

## The matrix

| Case | Answer |
|---|---|
| No folder granted yet | `503 folder_not_granted` **and** a notification to open the picker. Asked at the moment a request needs it, never at setup. |
| Grant revoked in system settings | Same code, same notification — the state is indistinguishable and needs the same fix |
| Folder deleted after the grant | `404 folder_gone` |
| Read-only provider (some cloud-backed SAF roots) | `503 storage_read_only` on upload; reads work |
| Unicode, emoji, spaces, newline in a name | Allowed. Names are provider-escaped by `DocumentsContract`, and a newline is legal in a filename. |
| `..` or an absolute path in `path` | `400 bad_path`. Compared as a document NAME against a listing, never resolved — there is no path to traverse. |
| Name containing `/` | `400 bad_name`. This is the MediaStore injection the first draft missed; SAF would treat it as a nested document. |
| Directory where a file is expected | `400 not_a_file` |
| Expected type does not match the provider's | `415 unsupported_document` rather than a silent coercion |
| File deleted between the card and the read | `404 file_gone` |

## What this must not do

- **No route outside the granted folder.** The grant is the boundary; the code never constructs a
  path of its own.
- **No silent truncation.** A body shorter than `Content-Length` is an error, never a smaller file
  that answers 200.
- **No audit of file bytes.**
- **No delete, no overwrite.** Two absences that are the plugin's promise.

## Tests

- **Grant:** no grant → `503` plus exactly one picker notification (not one per request); a granted
  folder → the same request succeeds on retry; revoked mid-life → back to `503`.
- **Cap:** over-limit refused before the gate (asserted by a gate that is never asked); no
  `Content-Length` refused; exactly-at-limit accepted.
- **Collision:** an existing name gives `409`, and the original is byte-identical afterwards — the
  assertion that proves "refuse" actually refused.
- **Short body:** a body shorter than declared leaves no file behind.
- **Verification:** a completed upload's on-disk size equals the size the response reported.
- **Names:** `/` in a name, a leading `..`, and an empty name each refused with a specific code.
- **No delete route exists:** a DELETE to `/files` answers `not_found`, which is the regression guard
  for the decision itself.
