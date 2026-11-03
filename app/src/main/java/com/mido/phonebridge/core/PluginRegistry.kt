package com.mido.phonebridge.core

import android.util.Log

/**
 * Holds the registered plugins and resolves incoming routes to them.
 *
 * Routing is structural (segment matching), never regex: notification ids and
 * Gmail ids can contain characters that a greedy regex mishandles.
 */
object PluginRegistry {

    private const val TAG = "PluginRegistry"
    private val plugins = LinkedHashMap<String, Plugin>()
    private val routes = mutableListOf<Pair<Plugin, Route>>()

    @Synchronized
    fun register(plugin: Plugin) {
        if (plugins.containsKey(plugin.id)) return
        plugins[plugin.id] = plugin
        for (r in plugin.routes()) routes.add(plugin to r)
        Log.i(TAG, "registered plugin '${plugin.id}' with ${plugin.routes().size} route(s)")
    }

    @Synchronized
    fun all(): List<Plugin> = plugins.values.toList()

    /**
     * Every registered plugin, ON or OFF.
     *
     * The registry deliberately does NOT filter by enabled state. A disabled plugin must still
     * RESOLVE, so a caller gets `plugin_disabled` instead of `not_found` — telling someone the
     * capability does not exist when they merely turned it off is a different, wrong answer.
     * Filtering happens at dispatch, once, where the refusal can carry a reason.
     */
    @Synchronized
    fun isEnabled(pluginId: String): Boolean {
        val plugin = plugins[pluginId] ?: return false
        return enabledLookup?.invoke(plugin) ?: plugin.enabledByDefault
    }

    /**
     * How the registry learns whether a plugin is on.
     *
     * Injected rather than read from a store the registry owns, because the registry is a core
     * object with no `Context` — and giving it one would make the whole routing table untestable
     * on a bare JVM. `BridgeService` installs the lookup at startup; until then every plugin
     * answers from its own default, which is the correct answer for a process that has not read
     * the store yet.
     */
    @Volatile
    var enabledLookup: ((Plugin) -> Boolean)? = null


    @Synchronized
    fun get(id: String): Plugin? = plugins[id]

    /** All scopes required by all registered plugins (used for consent). */
    @Synchronized
    fun allScopes(): List<String> = plugins.values.flatMap { it.scopes }.distinct()

    /**
     * Resolve a request to a handler. Returns null if no route matches.
     * Matching: same method; path equal, or path template with {x} segments.
     */
    @Synchronized
    fun resolve(method: String, path: String): Resolved? {
        val reqSegs = path.trim('/').split('/')
        for ((plugin, route) in routes) {
            if (!route.method.equals(method, ignoreCase = true)) continue
            val tplSegs = route.path.trim('/').split('/')
            if (tplSegs.size != reqSegs.size) continue
            val params = HashMap<String, String>()
            var match = true
            for (i in tplSegs.indices) {
                val t = tplSegs[i]
                val a = reqSegs[i]
                when {
                    t.startsWith("{") && t.endsWith("}") ->
                        params[t.substring(1, t.length - 1)] = a
                    t != a -> { match = false; break }
                }
            }
            if (match) return Resolved(plugin, route, params)
        }
        return null
    }

    /** Cheap check used before parsing: does the path prefix belong to us? */
    @Synchronized
    fun knownPrefix(path: String): Boolean {
        val p = path.trim('/')
        return plugins.values.any { pl ->
            pl.routes().any { r ->
                p.startsWith(r.path.trim('/').split('/').firstOrNull() ?: "")
            }
        }
    }

    class Resolved(
        val plugin: Plugin,
        val route: Route,
        val pathParams: Map<String, String>
    )
}
