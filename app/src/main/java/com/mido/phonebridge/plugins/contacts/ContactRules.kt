package com.mido.phonebridge.plugins.contacts

/**
 * The rules for contact search, as rules.
 *
 * Pure on purpose. These are the decisions that matter — how short a query may be, how many
 * results come back, how raw rows collapse into people, and exactly how much of a phone number
 * or an address survives into the audit — and a decision that can only be checked by looking at a
 * real address book is one nobody re-checks. The `ContentResolver` half lives in [ContactsPlugin];
 * nothing here touches Android, so all of it is unit-testable.
 */
object ContactRules {

    /**
     * Shortest query accepted.
     *
     * One character is not a search, it is a download: `a` matches a large fraction of any real
     * address book, and every result costs a cursor row and a redaction. Two characters is the
     * shortest thing a human actually types.
     */
    const val MIN_QUERY = 2

    /** Longest query accepted — a name plus a number, not a pasted document. */
    const val MAX_QUERY = 64

    /**
     * Most contacts one call may return.
     *
     * A cap rather than trust in the query: the caller chooses the string, and a two-letter query
     * in a 3000-contact book would otherwise return thousands of rows — each one a stranger's
     * number leaving the phone in a single response.
     */
    const val MAX_RESULTS = 25

    /** Which column of the provider a raw row came from. */
    enum class Kind { PHONE, EMAIL }

    /** One cursor row, before rows are collapsed into a person. */
    data class Raw(val contactId: Long, val name: String, val kind: Kind, val value: String)

    /** One person, as the reply presents them: every number and every address they have. */
    data class Person(val name: String, val numbers: List<String>, val emails: List<String>)

    /**
     * Null when the query is usable, otherwise why it is not.
     *
     * Fails rather than "returns nothing": an empty query that silently returns zero results looks
     * identical to "no such contact", which teaches the caller the wrong thing about the data.
     */
    fun rejectReason(query: String): String? {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return "query is required; this route never lists the address book"
        if (trimmed.length < MIN_QUERY) {
            return "query must be at least $MIN_QUERY characters; '$trimmed' would match " +
                "most of the address book"
        }
        if (trimmed.length > MAX_QUERY) {
            return "query is ${trimmed.length} characters; the limit is $MAX_QUERY"
        }
        return null
    }

    /**
     * Collapses provider rows into people: one entry per `CONTACT_ID`, numbers and addresses
     * merged, duplicates dropped, first-seen order preserved.
     *
     * A contact with a mobile, a WhatsApp entry for the same digits and two email addresses is
     * FOUR rows in the provider and ONE person here. That is what makes `count` mean people
     * rather than rows — otherwise a search for a name returns four copies of it and the caller
     * has no way to tell that from four people who share a name.
     *
     * Order is first-seen rather than sorted: the provider already sorts by name, and re-sorting
     * here would need a collator to agree with SQL, which it will not.
     */
    fun collapse(raw: List<Raw>): List<Person> {
        val order = ArrayList<Long>()
        val names = HashMap<Long, String>()
        val numbers = HashMap<Long, MutableList<String>>()
        val emails = HashMap<Long, MutableList<String>>()

        for (r in raw) {
            val value = r.value.trim()
            if (value.isEmpty()) continue
            if (r.name.isBlank() && value.isEmpty()) continue
            if (!names.containsKey(r.contactId)) {
                order.add(r.contactId)
                names[r.contactId] = r.name
            }
            // A blank name on a later row must not erase a good one: the provider can return the
            // same id twice with the name only populated on one of them.
            if (names[r.contactId].isNullOrBlank() && r.name.isNotBlank()) {
                names[r.contactId] = r.name
            }
            when (r.kind) {
                Kind.PHONE -> numbers.getOrPut(r.contactId) { ArrayList() }
                Kind.EMAIL -> emails.getOrPut(r.contactId) { ArrayList() }
            }.let { list -> if (value !in list) list.add(value) }
        }

        return order.map { id ->
            Person(
                name = names[id] ?: "",
                numbers = numbers[id]?.toList() ?: emptyList(),
                emails = emails[id]?.toList() ?: emptyList(),
            )
        }
    }

    /**
     * Streaming version of [collapse], for the real cursor.
     *
     * The whole address book is not read into memory: rows are folded in as they arrive and the
     * caller stops the scan as soon as [overflowed] turns true. Reading one person past the cap is
     * deliberate — it is how `truncated: true` becomes a fact rather than a guess.
     */
    class Accumulator(private val cap: Int = MAX_RESULTS) {
        private val order = ArrayList<Long>()
        private val names = HashMap<Long, String>()
        private val numbers = HashMap<Long, MutableList<String>>()
        private val emails = HashMap<Long, MutableList<String>>()

        /** True once more people were seen than [cap] allows. */
        var overflowed = false
            private set

        /** Number of distinct people seen so far. */
        val count: Int get() = order.size

        /**
         * Fold in one cursor row. Returns false when the caller should stop scanning.
         *
         * A NEW contact that pushes the count past the cap is the stop signal: it is kept (so the
         * totals stay honest) and [overflowed] is set, which is exactly "there was at least one
         * more" without reading the rest of the book.
         */
        fun add(row: Raw): Boolean {
            val value = row.value.trim()
            if (value.isEmpty()) return true
            val isNew = !names.containsKey(row.contactId)
            if (isNew) {
                if (order.size >= cap) {
                    overflowed = true
                    return false
                }
                order.add(row.contactId)
                names[row.contactId] = row.name
            } else if (names[row.contactId].isNullOrBlank() && row.name.isNotBlank()) {
                names[row.contactId] = row.name
            }
            when (row.kind) {
                Kind.PHONE -> numbers.getOrPut(row.contactId) { ArrayList() }
                Kind.EMAIL -> emails.getOrPut(row.contactId) { ArrayList() }
            }.let { list -> if (value !in list) list.add(value) }
            return true
        }

        /**
         * The ids whose name is still blank, so the caller can resolve them in ONE extra query.
         *
         * `CommonDataKinds.Email.DISPLAY_NAME` comes back empty on real devices for contacts that
         * have an address but no structured name row — which is most email-only entries. Without
         * this pass the reply is a list of nameless addresses, and the human cannot tell who they
         * are looking at (Mido, 2026-11-02, after `query=gmail` answered 25 contacts all named "").
         */
        fun blankNameIds(): List<Long> = order.filter { names[it].isNullOrBlank() }

        /** Every id collected so far, in first-seen order. Used to attach a second data kind. */
        fun ids(): List<Long> = order.toList()

        /** Name a person already in the accumulator. Used only by that resolution pass. */
        fun setName(id: Long, name: String) {
            if (name.isNotBlank() && names.containsKey(id)) names[id] = name
        }

        fun result(): List<Person> = order.map { id ->
            Person(
                name = names[id] ?: "",
                numbers = numbers[id]?.toList() ?: emptyList(),
                emails = emails[id]?.toList() ?: emptyList(),
            )
        }
    }

    /**
     * Half the digits kept, the other half replaced with `•`.
     *
     * Mido, 2026-11-02: *"audit both but keep number half redacted"*. The caller needs the real
     * number — a search that returns a masked one is useless — so this function exists ONLY for
     * the audit copy, and the response is untouched.
     *
     * The LEADING half is kept, because that is what makes a number recognisable to its owner
     * (country and area code) while the subscriber digits — the part that actually reaches a
     * person — are the part dropped. Masking the middle instead would preserve both ends and
     * identify the number almost completely, which would be redaction theatre.
     *
     * Formatting is preserved so the log stays readable: `+49 151 1234 5678` becomes
     * `+49 151 12•• ••••`, not a digit soup. A string with no digits is returned unchanged —
     * there is nothing to hide and inventing a mask would be a lie about the data.
     */
    fun redactHalf(number: String): String {
        val digits = number.count { it.isDigit() }
        if (digits == 0) return number
        // Round UP so a 3-digit number keeps 2 and a 1-digit keeps 1: rounding down would mask
        // more than half on odd lengths, and "half" is the promise.
        val keep = (digits + 1) / 2
        var seen = 0
        return buildString(number.length) {
            for (c in number) {
                if (c.isDigit()) {
                    seen++
                    append(if (seen <= keep) c else '•')
                } else {
                    append(c)
                }
            }
        }
    }

    /**
     * Same half rule applied to the part of an address that identifies a person, keeping the
     * domain intact.
     *
     * The domain is what makes the address readable as an address (`@gmail.com` tells you more
     * than three masked characters do) and is shared with thousands of other people, so it
     * identifies nobody. The local part is the identifying half and gets the number treatment.
     * A string with no `@` is treated as the local part alone rather than returned raw, because
     * returning it raw would leak on exactly the malformed input nobody expects.
     */
    fun redactEmail(email: String): String {
        val at = email.lastIndexOf('@')
        if (at <= 0) return redactLocal(email)
        return redactLocal(email.substring(0, at)) + email.substring(at)
    }

    private fun redactLocal(local: String): String {
        if (local.isEmpty()) return local
        // Round UP, matching redactHalf: "half" must never mask more than it says.
        val keep = (local.length + 1) / 2
        return buildString(local.length) {
            for ((i, c) in local.withIndex()) append(if (i < keep) c else '•')
        }
    }
}
