package com.mido.phonebridge.plugins.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.util.Log
import com.mido.phonebridge.core.ApprovalRow
import com.mido.phonebridge.core.Plugin
import com.mido.phonebridge.core.PluginPermission
import com.mido.phonebridge.core.PluginError
import com.mido.phonebridge.core.PluginStatus
import com.mido.phonebridge.core.Req
import com.mido.phonebridge.core.Resp
import com.mido.phonebridge.core.Route
import com.mido.phonebridge.core.Verb
import org.json.JSONArray
import org.json.JSONObject

/**
 * `GET /contacts?query=` — search the phone's address book: names, phone numbers and email
 * addresses.
 *
 * **Search-only, and that is the security property, not a convenience.** There is deliberately no
 * "list all", no page-through, no export. A route that can dump the address book is a route that
 * hands a stranger every number on the phone in one call; a route that REQUIRES a query can only
 * answer about names the caller already half-knows. The backlog item said exactly this —
 * *"must allow search"* — and the wording is the design: the search is the whole feature.
 *
 * **Two providers, one person.** Phone numbers live in `CommonDataKinds.Phone` and addresses in
 * `CommonDataKinds.Email`; a contact can have entries in both, several of each, or neither. Rows
 * from both are folded together by `CONTACT_ID` in [ContactRules.collapse], so one person is one
 * entry carrying every number and address they have. Reporting them as two separate lists would
 * make the caller do the join against a key it never sees.
 *
 * **`READ_CONTACTS` is a runtime permission, and a route cannot ask for one.** A permission dialog
 * needs an Activity and a user tap, and a route handler has neither. So the split is forced and
 * simple: this plugin CHECKS and refuses with `contacts_unavailable`, and the APPROVE button on
 * the approval card owns the prompt (see `ApprovalActivity.requestContactsThenApprove`). A route
 * that tried to prompt would either hang or lie.
 *
 * **The audit keeps the matches, numbers and addresses half-masked** (Mido, 2026-11-02: *"audit
 * both but keep number half redacted"*). The caller receives the real values — a search returning
 * masked ones is useless — and the log keeps a recognisable but non-dialable copy, so the record
 * shows WHAT was looked up without becoming an address book of people who never installed this
 * app. The transform lives here and runs in the single dispatch site, so it cannot be honoured on
 * one path and ignored on another.
 */
class ContactsPlugin(private val ctx: Context) : Plugin {

    override val id = "contacts"
    override val displayName = "Contacts"

    /**
     * The need is declared HERE, once, rather than only checked inline in [search].
     *
     * The inline check still exists and must: it is the runtime boundary that stops a read
     * without permission. This declaration is what lets the UI say what is needed and why
     * BEFORE a caller ever asks, instead of reporting a 503 after the fact.
     */
    override fun permissions(): List<PluginPermission> = listOf(
        PluginPermission(
            permission = Manifest.permission.READ_CONTACTS,
            label = "Contacts",
            why = "Searching your contacts needs read access. Without it, every lookup answers " +
                "503 and the plugin cannot serve at all.",
            optional = false,
        )
    )

    override fun routes(): List<Route> = listOf(
        Route(
            "GET", "/contacts", "Search your contacts", Verb.READ,
            // Pre-gate: a query that cannot succeed must not cost a tap. This also refuses the
            // empty query that would otherwise mean "dump everything".
            validate = { req ->
                ContactRules.rejectReason(req.query["query"] ?: "")?.let {
                    throw PluginError("bad_request", it)
                }
            },
            // The LOG gets a different object: same shape, `number` → `numberRedacted` and
            // `emails` → `emailsRedacted`. The caller's reply is never rewritten.
            auditResponseTransform = { body -> redactForAudit(body) },
        ) { req -> search(req) }
    )

    /**
     * What the approval card shows.
     *
     * **This is why the card used to be useless** (Mido, 2026-11-02: *"I can't see more details
     * about the search modal request"*). Nothing was declared for this route, so `rowsFor` fell
     * through to the empty-read default and the card rendered one endpoint line — the actual query
     * was only reachable by expanding the raw JSON blob. A human cannot judge "should this search
     * run?" without being told WHAT is being searched for, so the query is the row.
     *
     * Declared in `writes` rather than `reads` because `rowsFor` consults `writes` FIRST and that
     * is the only hook that can build rows from the request. A GET never mutates, so the "writes
     * must declare rows" guarantee is unaffected by putting a read here.
     */
    override fun describeRows(req: Req): List<ApprovalRow> = rowsFor(
        req,
        reads = setOf("/contacts"),
        writes = mapOf(
            "GET /contacts" to { r ->
                val query = r.query["query"]?.trim() ?: ""
                buildList {
                    add(ApprovalRow("SEARCH", query, ApprovalRow.Style.MONO))
                    add(
                        ApprovalRow(
                            "RETURNS",
                            "names, phone numbers and email addresses",
                            ApprovalRow.Style.NORMAL
                        )
                    )
                    add(
                        ApprovalRow(
                            "LIMIT",
                            "up to ${ContactRules.MAX_RESULTS} people",
                            ApprovalRow.Style.NORMAL
                        )
                    )
                }
            }
        )
    )

    override fun status(): PluginStatus {
        val granted = ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        return if (granted) {
            PluginStatus(
                "Contacts",
                PluginStatus.State.OK,
                "GET /contacts?query= — names, numbers and emails, " +
                    "up to ${ContactRules.MAX_RESULTS} people"
            )
        } else {
            // NEEDS_ATTENTION, not ERROR: nothing is broken, something is unasked. The distinction
            // is the difference between "fix code" and "tap a button", and the home screen is the
            // only place a human finds that out.
            PluginStatus(
                "Contacts",
                PluginStatus.State.NEEDS_ATTENTION,
                "READ_CONTACTS not granted — searching answers 503 contacts_unavailable"
            )
        }
    }

    private fun search(req: Req): Resp {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // Retryable by a human tap, not by a caller retry: the code says which it is.
            throw PluginError(
                "contacts_unavailable",
                "READ_CONTACTS is not granted; approve a contacts request in STMP to allow it"
            )
        }
        val query = (req.query["query"] ?: "").trim()
        // Re-checked here even though `validate` already ran. Two reasons, both real: the handler
        // must be safe if it is ever called without the gate (the dispatcher's shape has changed
        // once already), and the rules are cheap enough that a second run is cheaper than a
        // proof that the first one always happened.
        ContactRules.rejectReason(query)?.let { throw PluginError("bad_request", it) }

        // `%` and `_` are LIKE wildcards, so an unescaped query of `%` matches every row in the
        // book — a one-character request that returns everything, which is exactly the bulk
        // download this route refuses to be. ESCAPE makes the pattern literal.
        val pattern = "%" + escapeLike(query) + "%"
        val acc = ContactRules.Accumulator()

        var scanned = 0L

        // Phone rows first: a contact with only a number is the common case, and stopping early
        // on the cap then never costs the email scan at all.
        scanned += scanPhones(pattern, acc)
        // Emails only if the cap was not already reached. A person with an address but no number
        // still needs the second pass, so this is a cap check rather than a phone-count check.
        if (!acc.overflowed) scanned += scanEmails(pattern, acc)

        // Names last, in one query. `Email.DISPLAY_NAME` and `Phone.DISPLAY_NAME` come back empty
        // for a contact with no structured name row — which is most email-only entries — so the
        // reply was a list of nameless addresses and the human could not tell who they were
        // looking at. Resolving by CONTACT_ID against the Contacts table fixes both kinds at once.
        resolveMissingNames(acc)

        val people = acc.result()
        val contacts = JSONArray()
        var capped = false
        for (p in people) {
            if (contacts.length() >= ContactRules.MAX_RESULTS) {
                capped = true
                break
            }
            contacts.put(
                JSONObject()
                    .put("name", p.name)
                    .put("numbers", JSONArray(p.numbers))
                    .put("emails", JSONArray(p.emails))
            )
        }
        return Resp.ok(
            JSONObject()
                .put("query", query)
                .put("count", contacts.length())
                // Optimistic on the accumulator OR the slice: either one proves there was more.
                .put("truncated", acc.overflowed || capped)
                .put("scannedRows", scanned)
                .put("contacts", contacts)
        )
    }

    /**
     * Fold every matching phone row into [acc]. Returns how many rows were read.
     *
     * `use` closes the cursor on every path, including an exception from `moveToNext`. There is
     * deliberately no `finally` beside it: an empty cleanup block reads like a cleanup that was
     * forgotten rather than one that is unnecessary.
     */
    // Lint's Recycle check flags these cursors because they are created inside a `try` whose
    // `catch` blocks rethrow: it cannot prove the subsequent `use {}` runs on that path. It does
    // — `use` is a finally, and every branch below reaches it. Suppressed at the method rather
    // than restructured, because wrapping the acquisition in another `use` would nest two
    // close mechanisms for one cursor, which is strictly worse to read.
    @Suppress("Recycle")
    private fun scanPhones(pattern: String, acc: ContactRules.Accumulator): Long {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val selection =
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? ESCAPE '\\' OR " +
                "${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ? ESCAPE '\\'"

        val cursor = try {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                selection,
                arrayOf(pattern, pattern),
                // Sorted by name so the same query answers in the same order twice — a caller
                // comparing two searches should not see the list reshuffle under it.
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )
        } catch (e: SecurityException) {
            // The permission can be revoked between the check above and this call; a crash here
            // would take down the bridge thread rather than fail one request.
            throw PluginError("contacts_unavailable", "READ_CONTACTS was revoked mid-request")
        } catch (e: Exception) {
            throw PluginError("contacts_query_failed", e.message ?: "the contacts provider failed")
        } ?: throw PluginError("contacts_query_failed", "the contacts provider returned no cursor")

        var rows = 0L
        cursor.use { c ->
            val idIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (c.moveToNext()) {
                rows++
                val id = if (idIdx >= 0) c.getLong(idIdx) else 0L
                val name = if (nameIdx >= 0) c.getString(nameIdx) ?: "" else ""
                val number = if (numIdx >= 0) c.getString(numIdx) ?: "" else ""
                if (!acc.add(ContactRules.Raw(id, name, ContactRules.Kind.PHONE, number))) break
            }
        }
        return rows
    }

    /**
     * Fold every matching email row into [acc]. Returns how many rows were read.
     *
     * Matched on the address OR the name, so `GET /contacts?query=ahmed` finds someone whose
     * display name is Ahmed even though his address says nothing about it.
     */
    // Lint's Recycle check flags these cursors because they are created inside a `try` whose
    // `catch` blocks rethrow: it cannot prove the subsequent `use {}` runs on that path. It does
    // — `use` is a finally, and every branch below reaches it. Suppressed at the method rather
    // than restructured, because wrapping the acquisition in another `use` would nest two
    // close mechanisms for one cursor, which is strictly worse to read.
    @Suppress("Recycle")
    private fun scanEmails(pattern: String, acc: ContactRules.Accumulator): Long {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Email.CONTACT_ID,
            ContactsContract.CommonDataKinds.Email.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Email.ADDRESS,
        )
        val selection =
            "${ContactsContract.CommonDataKinds.Email.DISPLAY_NAME} LIKE ? ESCAPE '\\' OR " +
                "${ContactsContract.CommonDataKinds.Email.ADDRESS} LIKE ? ESCAPE '\\'"

        val cursor = try {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                projection,
                selection,
                arrayOf(pattern, pattern),
                "${ContactsContract.CommonDataKinds.Email.DISPLAY_NAME} ASC"
            )
        } catch (e: SecurityException) {
            throw PluginError("contacts_unavailable", "READ_CONTACTS was revoked mid-request")
        } catch (e: Exception) {
            // An email failure must not sink a search that already has phone matches: the provider
            // can be missing entirely on a device with no account configured. Names and numbers
            // are still a true answer, and the reply says which half is complete.
            return 0L
        } ?: return 0L

        var rows = 0L
        cursor.use { c ->
            val idIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Email.CONTACT_ID)
            val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Email.DISPLAY_NAME)
            val addrIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)
            while (c.moveToNext()) {
                rows++
                val id = if (idIdx >= 0) c.getLong(idIdx) else 0L
                val name = if (nameIdx >= 0) c.getString(nameIdx) ?: "" else ""
                val address = if (addrIdx >= 0) c.getString(addrIdx) ?: "" else ""
                if (!acc.add(ContactRules.Raw(id, name, ContactRules.Kind.EMAIL, address))) break
            }
        }
        return rows
    }

    /**
     * Fill in blank names from the Contacts table, in ONE query.
     *
     * A person's display name lives on `ContactsContract.Contacts`, not necessarily on the data
     * row that matched the search: `CommonDataKinds.Email.DISPLAY_NAME` is routinely empty, and
     * `Phone.DISPLAY_NAME` is empty for a contact with only an address. Without this the reply
     * reads `{"name":"","emails":["someone@x.com"]}` and the caller has to guess who that is.
     *
     * Batched with an `IN (...)` rather than one query per id: the cap is 25, so this is at most
     * 25 ids in a single round trip instead of 25 round trips.
     *
     * A failure here is NOT fatal. The names are missing either way, and sinking a search that
     * already has correct numbers and addresses would answer less than the device can.
     */
    // Lint's Recycle check flags these cursors because they are created inside a `try` whose
    // `catch` blocks rethrow: it cannot prove the subsequent `use {}` runs on that path. It does
    // — `use` is a finally, and every branch below reaches it. Suppressed at the method rather
    // than restructured, because wrapping the acquisition in another `use` would nest two
    // close mechanisms for one cursor, which is strictly worse to read.
    @Suppress("Recycle")
    private fun resolveMissingNames(acc: ContactRules.Accumulator) {
        val ids = acc.blankNameIds()
        if (ids.isEmpty()) return
        val placeholders = ids.joinToString(",") { "?" }
        val cursor = try {
            ctx.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(
                    ContactsContract.Contacts._ID,
                    ContactsContract.Contacts.DISPLAY_NAME,
                ),
                "${ContactsContract.Contacts._ID} IN ($placeholders)",
                ids.map { it.toString() }.toTypedArray(),
                null
            )
        } catch (e: Exception) {
            Log.w("ContactsPlugin", "name resolution failed: ${e.message}")
            return
        } ?: return

        cursor.use { c ->
            val idIdx = c.getColumnIndex(ContactsContract.Contacts._ID)
            val nameIdx = c.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
            if (idIdx < 0 || nameIdx < 0) return
            while (c.moveToNext()) {
                acc.setName(c.getLong(idIdx), c.getString(nameIdx) ?: "")
            }
        }
    }

    /**
     * The audit copy: identical except each contact carries `numbersRedacted`/`emailsRedacted`
     * and no raw `numbers`/`emails`.
     *
     * The keys are RENAMED rather than the values masked in place, so a reader of the log cannot
     * mistake a masked value for a real one, and nothing downstream has to know to re-mask.
     */
    private fun redactForAudit(body: JSONObject): JSONObject {
        val out = JSONObject()
        // Copied key by key rather than `JSONObject(body.toString())`: that round-trip would put
        // the real values in a temporary string, which is the leak this function exists to avoid.
        body.keys().forEach { key ->
            if (key != "contacts") out.put(key, body.get(key))
        }
        val contacts = body.optJSONArray("contacts") ?: JSONArray()
        val masked = JSONArray()
        for (i in 0 until contacts.length()) {
            val c = contacts.optJSONObject(i) ?: continue
            val numbers = c.optJSONArray("numbers") ?: JSONArray()
            val emails = c.optJSONArray("emails") ?: JSONArray()
            masked.put(
                JSONObject()
                    .put("name", c.optString("name"))
                    .put(
                        "numbersRedacted",
                        JSONArray().apply {
                            for (j in 0 until numbers.length()) {
                                put(ContactRules.redactHalf(numbers.optString(j)))
                            }
                        }
                    )
                    .put(
                        "emailsRedacted",
                        JSONArray().apply {
                            for (j in 0 until emails.length()) {
                                put(ContactRules.redactEmail(emails.optString(j)))
                            }
                        }
                    )
            )
        }
        out.put("contacts", masked)
        return out
    }

    /**
     * Make a query literal for `LIKE`, honouring the `ESCAPE '\'` in the selection.
     *
     * Order matters: the escape character must be escaped FIRST, or the backslashes added for `%`
     * and `_` would themselves be doubled and the pattern would look for literal backslashes.
     */
    private fun escapeLike(raw: String): String = buildString(raw.length + 8) {
        for (c in raw) {
            when (c) {
                '\\' -> append("\\\\")
                '%' -> append("\\%")
                '_' -> append("\\_")
                else -> append(c)
            }
        }
    }
}
