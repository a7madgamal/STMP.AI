# Proton Drive / Calendar — findings and decision

Investigation only (the todo's deliverable is *"a short findings doc + a decision, not code"*).
Researched 2026-10-24. Sources are Proton's own support/blog pages and the public SDK repo; nothing
below is inferred from a third-party summary where a primary source existed.

**Decision in one line: no Proton Drive plugin on the phone, and Calendar is worth doing only as a
read-only ICS reader — which the existing secret broker cannot serve today for a concrete,
fixable reason.**

---

## 1. Proton Drive — NOT viable as a PhoneBridge plugin

**There is no public API.** Proton states plainly that the Drive SDK is *"not officially supported
for third-party use at present"* and *"not ready for public use yet"*; the SDK repo says Proton
*"strongly recommends integrating through this SDK rather than calling the Drive API directly"* —
which is a recommendation to avoid the API, not an offer of one.

**The SDK is JavaScript and C# only.** No Kotlin/Java artifact, so it cannot be used from this app
at all. There is no "add the dependency and call it" path.

**The reason there is no plain API is architectural, not neglect.** Proton Drive is end-to-end
encrypted: the client holds a key derived from the account password, and every file, name and
share is encrypted before it leaves the device. A server-side "token + REST" model cannot exist
without Proton holding the keys, which is the one thing the product is for. Any integration must
therefore implement Proton's crypto, key hierarchy and share/folder format — not a REST client.

**The third-party route exists but is not a foundation.** `henrybear327/Proton-API-Bridge` is the
de-facto implementation, used by rclone's Proton backend. It works, but:

- rclone's Proton Drive backend has **no active maintainer** (open issue: *"Mark proton drive
  backend as unsupported"*), and Proton engineers have publicly had to step in to keep rclone
  functional after upload-flow changes.
- It carries the entire client-side crypto stack, which is exactly the kind of dependency this
  project has already been burned by three times in one day (JSch's Multi-Release JAR,
  `org.json`, and a provider-dependent ed25519). A crypto stack that must match a changing
  server-side format is a maintenance commitment, not a dependency.

**Verdict: do not build it on the phone.** If Proton Drive access is wanted, run `rclone` on a
machine that already has it (the NUC, or this host) and reach it however that machine is reached.
The phone would add a large crypto surface and a fragile third-party dependency to gain nothing —
it has no privileged path to Proton's servers that any other machine lacks.

**What would change this answer:** an official Proton Drive API with server-side keys (which would
contradict the product), or a maintained Kotlin SDK. Neither exists today. Worth re-checking in a
year; not worth building around in the meantime.

---

## 2. Proton Calendar — read-only only, and only via a shared link

**No CalDAV, deliberately.** Proton's own support page: *"Proton Calendar doesn't support CalDAV,
so you can't set up a direct two-way sync with an external calendar."* Proton's community answer
gives the reason, and it is the same one as Drive: CalDAV would require server-side access to
encrypted calendar data.

**The one officially reachable interface is a shared calendar link** — a read-only ICS feed. That
is a plain HTTPS GET returning `text/calendar`. No auth header, no crypto: the secrecy of the URL
IS the credential.

So the only automatable Proton Calendar capability is: **fetch a shared ICS URL and read the
events.** No create, no edit, no delete, no two-way sync. That is a real limitation and should not
be dressed up as "calendar support".

### Why the existing broker cannot serve it (a real finding, not a guess)

The obvious approach — store the ICS URL as a vault secret and fetch it through
`POST /secrets/{name}/call` — **does not work on the current build**, for a reason unrelated to
Proton. The broker's response rule accepts only a body that parses as a **JSON object**; anything
else is refused as `unsupported_content_type`. A calendar feed answers `text/calendar`, so the
broker would refuse a perfectly successful upstream response.

That rule is deliberate and correct for an API broker (it is what stops a HTML error page or a PDF
being reported as a successful API call), so the fix is not to loosen it. Proton Calendar needs a
**purpose-built route**, not a broker tweak.

### The smallest honest shape

One route, one job — fetch a shared calendar and return parsed events:

```
GET /proton/calendar?url=<secret-name or inline>
→ {ok, calendarName, events:[{uid, summary, start, end, allDay, location, description}]}

POST /proton/calendar/refresh      (optional: cache the last fetch)
```

- **Read-only by construction.** There is no write route, because there is no write API. A route
  that cannot write needs no `Verb.WRITE` policy and no `EXECUTE` confirmation — `READ` under the
  normal gate is the correct verb.
- **The URL is the credential**, so it belongs in the vault like any other secret
  (host-restricted to `calendar.proton.me`), and the route takes the secret NAME rather than the
  URL. That keeps the existing rule intact: one vault, one unlock, secrets never in a request.
- **The card should name what is being fetched** — "Proton calendar (read-only)" — since a READ of
  an external feed discloses event titles and times to whoever is reading the response.
- **Parsing is the actual work.** ICS is a line-folding format (a continuation is a leading space),
  with `VEVENT` blocks, `DTSTART`/`DTEND`, and both floating and `TZID`-qualified times. That
  parser is the whole deliverable and is fully unit-testable — which matters, because this project
  now runs tests on Android's runtime and a timezone mistake here would silently shift every event.

### Effort, honestly

- ICS fetch + parser + route: **one session**, most of it the parser and its tests.
- No crypto, no OAuth, no new dependency, no native code.
- The `TZID` handling needs a decision up front (map to `java.time.ZoneId`, reject unknown zones
  rather than defaulting to UTC), because a wrong default here is the classic silent bug.

**Verdict: buildable, small, and honestly limited (read-only, no native Proton), but worth doing if
the goal is "see my Proton calendar on demand". It is NOT worth doing as a step toward "manage my
Proton calendar".**

---

## 3. What this means for the PhoneBridge plugin list

| Item | Verdict | Why |
|---|---|---|
| Proton Drive plugin | **No** | No API, no Kotlin SDK, E2E crypto means a full client implementation |
| Proton Calendar plugin | **Yes, read-only only** | A shared ICS link is the only reachable interface; a small parser + route |
| Broker change to allow non-JSON | **No** | The rule is correct for an API broker; add a route instead |

## 4. Recommended next action

Do the **calendar reader** only if the read-only limitation is actually useful. If what is wanted
is Drive (upload/download files), the correct answer is `rclone` on a machine that has it, not a new
phone plugin — and that is a ten-minute job there versus a multi-session crypto project here.

---

## Open question for the human

Two separate capabilities were bundled in one todo. **Which is actually wanted: seeing Proton
calendar events, or moving files to/from Proton Drive?** The first is a small plugin; the second is
not a plugin at all.
