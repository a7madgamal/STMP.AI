package com.mido.phonebridge

import com.mido.phonebridge.core.Route
import com.mido.phonebridge.core.Verb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `Route.auditResponseBody` decides whether the audit keeps a handler's reply.
 *
 * The reason it is not decoration: `/debug` returns the log tail, so storing its
 * response copies the log into the log — self-referential, and at the 32 KiB blob
 * cap it can quietly consume a large share of the 20 MB retention budget. Defaults
 * matter more than usual here, so the default is asserted rather than assumed.
 */
class RouteAuditTest {

    private fun route(stores: Boolean? = null): Route {
        val handler: suspend (com.mido.phonebridge.core.Req) -> com.mido.phonebridge.core.Resp =
            { com.mido.phonebridge.core.Resp.ok() }
        return if (stores == null) {
            Route("GET", "/x", "x", Verb.READ, handler = handler)
        } else {
            Route("GET", "/x", "x", Verb.READ, auditResponseBody = stores, handler = handler)
        }
    }

    @Test
    fun `a route stores its response body by default`() {
        assertTrue(route().auditResponseBody)
    }

    @Test
    fun `a route can opt out, and the opt-out is explicit`() {
        assertFalse(route(stores = false).auditResponseBody)
        assertTrue(route(stores = true).auditResponseBody)
    }
}
