package com.mido.phonebridge.core

/**
 * A prefilled starting point for adding a secret.
 *
 * **It is deliberately NOT a credential.** [docs] and [key] are pointers (where the API is
 * documented, where a key is issued) and [hosts] is the allowlist. The VALUE is never here and
 * never will be — a template cannot create a working secret on its own, which is the property
 * that makes this feature safe to ship with a fixed list.
 */
data class SecretTemplate(
    /** The vault secret name. Becomes the plugin id, so it must satisfy `requireName`. */
    val name: String,
    /** Where the API is documented. A pointer, not a secret — stored in the plaintext index. */
    val docs: String,
    /** Where the key is issued. Ditto. */
    val key: String,
    /**
     * Hosts this secret may reach.
     *
     * **This is a SECURITY decision, which is why it is a template field at all and why every
     * entry is validated by a test.** `HostAllowlist` is what stops a stored credential leaving
     * the device to somewhere it should not; a wrong entry here widens that permanently, at
     * creation, before the human has thought about it. The rule the tests enforce: prefer the
     * EXACT api host over a wildcard. `*.todoist.com` would also cover every other subdomain
     * Todoist ever runs, which is not what "let this key call the API" means.
     */
    val hosts: List<String>,
    /** One line for the picker, so the choice is not made from a bare lowercase name. */
    val note: String,
) {
    /** Secret names are plugin ids; this is the label the picker shows. */
    val displayName: String get() = name.replaceFirstChar { it.uppercase() }
}

/**
 * The built-in templates offered by "ADD FROM TEMPLATE".
 *
 * **A PURE object with no Android and no I/O, so a JVM test can check every entry against the
 * rules it must satisfy.** That matters more than it looks: an invalid template fails at SAVE
 * time — inside the dialog, after the human has typed a real API key — with a `bad_request` from
 * the vault. Moving that failure to the build means a template that cannot be saved cannot ship,
 * and the person adding a secret never sees an error they cannot act on.
 *
 * `SecretTemplatesTest` asserts all four rules per entry: `requireName` accepts the name,
 * `requireUrlOrEmpty` accepts both links (https only), and `HostAllowlist.requireRule` accepts
 * every host.
 *
 * **Only three, on purpose** (Mido, 2026-10-20: *"add github, notion only"*). A long list of
 * integrations is a list of hosts nobody reads, and the point of a template is that the human
 * already trusts the entry. Adding one later is a single object; the shape is settled.
 */
object SecretTemplates {

    val ALL: List<SecretTemplate> = listOf(
        SecretTemplate(
            name = "todoist",
            docs = "https://developer.todoist.com/api/v1/",
            key = "https://app.todoist.com/app/settings/integrations/developer",
            // Exact api host, not *.todoist.com: the wildcard would also permit every other
            // subdomain Todoist runs, which is a wider blast radius than the API needs.
            hosts = listOf("api.todoist.com"),
            note = "Tasks, projects, labels",
        ),
        SecretTemplate(
            name = "github",
            docs = "https://docs.github.com/en/rest",
            key = "https://github.com/settings/tokens",
            // api.github.com only. A PAT minted for the REST API has no business reaching
            // gist.github.com or raw.githubusercontent.com, and those are separate hosts.
            hosts = listOf("api.github.com"),
            note = "Repos, issues, pull requests",
        ),
        SecretTemplate(
            name = "notion",
            docs = "https://developers.notion.com/reference/intro",
            key = "https://www.notion.so/my-integrations",
            hosts = listOf("api.notion.com"),
            note = "Pages, databases, blocks",
        ),
    )

    /** Lookup for the picker's tap handler. Null for an unknown name — never a silent default. */
    fun byName(name: String): SecretTemplate? = ALL.firstOrNull { it.name == name }
}
