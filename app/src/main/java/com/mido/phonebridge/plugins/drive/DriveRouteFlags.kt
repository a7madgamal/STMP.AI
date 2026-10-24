package com.mido.phonebridge.plugins.drive

/**
 * The audit/streaming flags for every Drive route, as PURE DATA.
 *
 * **Why this exists as its own object.** Mido, 2026-10-21: streaming is acceptable *"as long as it
 * doesn't leak secrets"*. That is a property that has to be enforced by a test, and a test cannot
 * build a `Context` on the JVM — so `DrivePlugin.routes()` is unreachable from the unit suite.
 *
 * The tempting fix is to copy the flags into the test and assert against the copy. That is worse
 * than no test: it passes no matter what the plugin does. This project has already paid for that
 * twice (a `/debug` route re-declared inside its own test; a payload helper duplicated so the test
 * validated its own copy).
 *
 * So the flags live here, ONCE, and `DrivePlugin.routes()` reads them to build its routes. The
 * test reads the same table. There is no second copy to drift, and adding a route without deciding
 * its flags becomes a compile-visible omission rather than a silent default.
 */
object DriveRouteFlags {

    /**
     * One route's byte-handling policy.
     *
     * @param auditRequestBody record the request body in the audit log (default true).
     * @param auditResponseBody record the response body in the audit log (default true).
     * @param streamsRequest hand the handler the raw body stream instead of parsed JSON.
     */
    data class Flags(
        val method: String,
        val path: String,
        val auditRequestBody: Boolean = true,
        val auditResponseBody: Boolean = true,
        val streamsRequest: Boolean = false,
    ) {
        /** True when this route carries file bytes in either direction. */
        val carriesBytes: Boolean get() = streamsRequest || !auditResponseBody
    }

    /**
     * Every Drive route, in the order `DrivePlugin.routes()` declares them.
     *
     * The two opt-outs are surgical and each has one reason:
     *
     *  - `POST /drive/files/upload` — `streamsRequest` because `readRawBody` buffers the whole body
     *    into a String (fatal for a large file), and `auditRequestBody = false` because the audit
     *    stores bodies verbatim up to 32 KiB, which would write the first 32 KB of the user's
     *    document permanently into an append-only log.
     *  - `GET /drive/files/{id}/content` — `auditResponseBody = false` for the mirror reason: the
     *    first 32 KB of the downloaded file would be persisted.
     *
     * **Every other route keeps full auditing**, including every metadata write. An audit you can
     * quietly skip is worse than no audit, so the opt-outs stay at exactly two.
     */
    val ALL: List<Flags> = listOf(
        Flags("GET", "/drive/files"),
        Flags("GET", "/drive/files/{id}"),
        Flags("GET", "/drive/files/{id}/content", auditResponseBody = false),
        Flags("POST", "/drive/files"),
        Flags("POST", "/drive/files/{id}/rename"),
        Flags("POST", "/drive/files/{id}/move"),
        Flags("POST", "/drive/files/{id}/trash"),
        Flags("POST", "/drive/files/upload", auditRequestBody = false, streamsRequest = true),
    )

    /** The flags for a route, or null when the path is not a Drive route. */
    fun forPath(method: String, path: String): Flags? =
        ALL.firstOrNull { it.method.equals(method, ignoreCase = true) && it.path == path }

    /** Every route that carries file bytes. Two, and the tests assert that. */
    fun byteCarriers(): List<Flags> = ALL.filter { it.carriesBytes }
}
