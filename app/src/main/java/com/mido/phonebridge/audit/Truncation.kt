package com.mido.phonebridge.audit

/**
 * How much of a request or response body the record actually holds.
 *
 * Both fields are "bytes over the cap", so 0 means the body was stored whole. The
 * two `absent` flags are the separate fact "a body existed and was deliberately not
 * kept" — `/debug` opts out of storing its response because its reply IS the log,
 * and a Drive upload opts out of storing its request because those bytes are the
 * user's document.
 *
 * Keeping absence apart from emptiness is the whole point: an empty `responseBody`
 * with `responseBodyAbsent = true` says "we chose not to keep it", while the same
 * empty string without the flag says "the handler returned nothing". Collapsing them
 * would make the log lie in exactly the case someone is reading it to find out what
 * happened.
 */
data class Truncation(
    val requestBody: Int,
    val responseBody: Int,
    val responseBodyAbsent: Boolean = false,
    val requestBodyAbsent: Boolean = false,
) {
    /** True when anything was cut. Note this ignores the `absent` flags. */
    val any: Boolean get() = requestBody > 0 || responseBody > 0
}
