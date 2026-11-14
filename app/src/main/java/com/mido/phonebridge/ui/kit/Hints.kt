package com.mido.phonebridge.ui.kit

/** Every placeholder hint the app puts in a [Field]; HintFitTest measures this list, so add new hints here. */
object Hints {
    const val API_DOCS = "API docs (https://…)"
    const val KEY_SOURCE = "Key page (https://…)"
    const val SECRET_NAME = "Name (e.g. todoist)"
    const val SECRET_VALUE = "Secret value"
    const val API_DOCS_OPTIONAL = "API docs URL (optional)"
    const val KEY_SOURCE_OPTIONAL = "Where to get it (optional)"
    const val ALLOWED_HOSTS = "Hosts, comma-separated"
    const val HOST_PATTERN = "e.g. *.todoist.com"
    const val DEVICE_NAME = "e.g. Laptop"
    const val SERVER_URL = "http://host:port"
    const val TOKEN = "Empty if no token needed"
    const val SSH_NAME = "name (e.g. prod)"
    const val SSH_HOST = "host (box.example.com)"
    const val SSH_PORT = "port (default 22)"
    const val SSH_USER = "user (e.g. deploy)"

    val all: List<String> = listOf(
        API_DOCS, KEY_SOURCE, SECRET_NAME, SECRET_VALUE, API_DOCS_OPTIONAL, KEY_SOURCE_OPTIONAL,
        ALLOWED_HOSTS, HOST_PATTERN, DEVICE_NAME, SERVER_URL, TOKEN, SSH_NAME, SSH_HOST, SSH_PORT, SSH_USER,
    )
}
