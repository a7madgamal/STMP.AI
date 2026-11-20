package com.mido.phonebridge

import com.mido.phonebridge.core.ParamSpec
import com.mido.phonebridge.core.QueryParams
import com.mido.phonebridge.core.PluginError
import com.mido.phonebridge.core.Req
import com.mido.phonebridge.core.Route
import com.mido.phonebridge.core.Verb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject

/**
 * The guard on query-parameter declarations: documented exactly, and enforced hard.
 *
 * **Why this exists.** A live call in 2026-11-20 sent `GET /gmail/messages?maxResults=10` and got
 * 20 rows back with no hint anything was ignored — the parameter was called `max`. Every route used
 * to read the parameters it knew and silently drop the rest, so a caller with the name slightly
 * wrong got a default and went on to reason about data it had not asked for. That is the one
 * failure mode that cannot be debugged from outside, because the answer looks like an answer.
 *
 * Mido's instruction was two-sided and both halves are tested here: *"document ALL params exactly
 * and fail hard on ANY unexpected params with a clear error response"*.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RouteParamsTest {

    /**
     * Build a request the way DISPATCH does: wrapped with the route's declared names.
     *
     * Testing against a bare map would prove nothing about the guarantee this change exists for —
     * the wrapper is the guard, so it has to be in the fixtures.
     */
    private fun req(vararg query: Pair<String, String>, declared: Set<String> = emptySet()) = Req(
        method = "GET",
        path = "/test",
        query = QueryParams(declared, query.toMap()),
        pathParams = emptyMap(),
        body = JSONObject(),
        remote = "127.0.0.1",
    )

    private fun route(params: List<ParamSpec>) = Route(
        method = "GET",
        path = "/test",
        describe = "test route",
        verb = Verb.READ,
        params = params,
        handler = { com.mido.phonebridge.core.Resp(200, JSONObject()) },
    )

    @Test
    fun `a declared parameter is accepted`() {
        val r = route(listOf(ParamSpec("max", "how many", type = "int", default = "20")))
        r.checkParams(req("max" to "5"))
    }

    @Test
    fun `an undeclared parameter is refused`() {
        // The exact defect: `maxResults` where the route accepts `max`.
        val r = route(listOf(ParamSpec("max", "how many", type = "int", default = "20")))
        try {
            r.checkParams(req("maxResults" to "10"))
            fail("must refuse an undeclared parameter")
        } catch (e: PluginError) {
            assertEquals("unknown_param", e.code)
        }
    }

    @Test
    fun `the refusal names what was accepted, so the repair is visible`() {
        val r = route(
            listOf(
                ParamSpec("max", "how many", type = "int"),
                ParamSpec("q", "search"),
                ParamSpec("pageToken", "next page"),
            )
        )
        try {
            r.checkParams(req("maxResults" to "10"))
            fail("must refuse")
        } catch (e: PluginError) {
            val msg = e.message!!
            // "unknown parameter" alone costs a second guess; naming the real ones ends it.
            assertTrue("must name the rejected parameter: $msg", msg.contains("maxResults"))
            assertTrue("must list what is accepted: $msg", msg.contains("max"))
            assertTrue("must list what is accepted: $msg", msg.contains("pageToken"))
        }
    }

    @Test
    fun `every offending parameter is named at once`() {
        val r = route(listOf(ParamSpec("max", "how many")))
        try {
            r.checkParams(req("one" to "a", "two" to "b", "max" to "3"))
            fail("must refuse")
        } catch (e: PluginError) {
            val msg = e.message!!
            assertTrue(msg.contains("one"))
            assertTrue(msg.contains("two"))
            // The valid one is not accused.
            assertFalse("the accepted parameter must not be listed as unknown", msg.contains("'max'"))
        }
    }

    @Test
    fun `a route with no declared parameters refuses any query string`() {
        // The honest default: a route that forgot to declare fails loudly on first use rather
        // than quietly ignoring input. `GET /gmail/profile` and friends are in this class.
        val r = route(emptyList())
        try {
            r.checkParams(req("anything" to "1"))
            fail("must refuse")
        } catch (e: PluginError) {
            assertEquals("unknown_param", e.code)
            assertTrue(e.message!!.contains("NO query parameters"))
        }
    }

    @Test
    fun `a route with no declared parameters accepts a bare request`() {
        route(emptyList()).checkParams(req())
    }

    @Test
    fun `a declared parameter with no value sent is fine — required-ness is the validate hook's job`() {
        // Absence is not an "unexpected parameter": the route's own validate names a missing
        // REQUIRED one with its own code (`bad_timezone`, `bad_max`, …), which is more specific
        // than this rule could be.
        val r = route(
            listOf(
                ParamSpec("timeMin", "start", required = true),
                ParamSpec("timeMax", "end"),
            )
        )
        // Sending only one of two declared parameters is fine; the route's own validate names the
        // missing one with a more specific code (`bad_time_range`, `bad_timezone`, …).
        r.checkParams(req("timeMax" to "2026-11-20T00:00:00Z"))
    }

    // --- the structural guarantee -------------------------------------------------------------

    @Test
    fun `a handler cannot READ a parameter its route never declared`() {
        // **This is what makes drift impossible rather than merely tested for.**
        //
        // A caller-side refusal fixes half the problem. The other half is a HANDLER reading a name
        // the route never declared — `query["maximum"]` where the declaration says `max` — which
        // compiles, returns null, and produces a silently wrong answer. A source-grep test cannot
        // be trusted for that (it produced two false positives on Calendar's helper-read params and
        // would have been deleted), so the map itself refuses.
        val q = QueryParams(setOf("max"), mapOf("max" to "5", "maximum" to "500"))
        try {
            q["maximum"]
            fail("reading an undeclared parameter must throw, not return null")
        } catch (e: IllegalStateException) {
            val msg = e.message!!
            assertTrue("must name the offending parameter: $msg", msg.contains("maximum"))
            assertTrue("must name what IS declared, so the fix is obvious: $msg", msg.contains("max"))
        }
    }

    @Test
    fun `a handler cannot CONTAINS-CHECK an undeclared parameter either`() {
        // `containsKey` is the first half of a read, so letting it pass would leave the same hole.
        val q = QueryParams(setOf("max"), mapOf("maximum" to "500"))
        try {
            q.containsKey("maximum")
            fail("containsKey on an undeclared name must throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("maximum"))
        }
    }

    @Test
    fun `iterating keys only yields declared names`() {
        // A handler looping `query.keys` must not see a name the route never accepted — otherwise
        // it could echo an undeclared parameter back to the caller as though it were understood.
        val q = QueryParams(setOf("max", "q"), mapOf("max" to "5", "q" to "x", "bogus" to "1"))
        assertEquals(setOf("max", "q"), q.keys)
    }

    @Test
    fun `a declared name that was not sent still reads as null`() {
        // Declared-and-absent is the normal case, and it must NOT throw: that is what an optional
        // parameter means. The route's own validate decides whether it is required.
        val q = QueryParams(setOf("pageToken"), emptyMap())
        assertNull(q["pageToken"])
        assertFalse(q.containsKey("pageToken"))
    }

    @Test
    fun `dispatch refuses undeclared parameters before the gate`() {
        // Guard the enforcement itself: `checkParams` runs in BridgeService BEFORE `validate`, so a
        // wrong parameter name is answered rather than ignored — and because `gated()` is still
        // false there, it is a 4xx with no approval tap.
        val source = java.io.File("src/main/java/com/mido/phonebridge/BridgeService.kt").readText()
        val check = source.indexOf("checkParams(req)")
        val validate = source.indexOf("validate?.invoke(req)")
        assertTrue("dispatch must call checkParams", check > 0)
        assertTrue("dispatch must call validate", validate > 0)
        assertTrue(
            "checkParams must run BEFORE validate: a parameter the route never declared is a " +
                "request it cannot answer, not a bad value",
            check < validate,
        )
    }

    @Test
    fun `the wrapper is built from the route's own declaration in both dispatch sites`() {
        // The structural guarantee only holds where the wrapper is CONSTRUCTED. Both sites — the
        // handler request and the pre-gate/describe request — must pass the route's declared names,
        // or a handler could read an undeclared name through the unwrapped one.
        val source = java.io.File("src/main/java/com/mido/phonebridge/BridgeService.kt").readText()
        val builds = Regex("""QueryParams\([^)]*params\.map \{ it\.name \}\.toSet\(\)""")
        assertEquals(
            "both Req construction sites must wrap the query with the route's declared names",
            2,
            builds.findAll(source).count(),
        )
    }

    @Test
    fun `a required parameter is marked in the refusal hint`() {
        val r = route(
            listOf(
                ParamSpec("timeMin", "start", required = true),
                ParamSpec("max", "how many"),
            )
        )
        try {
            r.checkParams(req("bogus" to "1"))
            fail("must refuse")
        } catch (e: PluginError) {
            assertTrue(
                "the hint must show which parameters are required: ${e.message}",
                e.message!!.contains("timeMin (required)"),
            )
        }
    }
}
