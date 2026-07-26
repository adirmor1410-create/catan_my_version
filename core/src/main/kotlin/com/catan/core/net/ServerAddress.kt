package com.catan.core.net

/**
 * Turns whatever a player types on the connect screen into a WebSocket URL.
 *
 * Players type three quite different things, and the difference matters:
 *
 *  - `192.168.1.20` or `10.0.2.2:8080` - a server on the local network, reached over plain `ws://`
 *    on the game's own port.
 *  - `wss://catan.example.com` - a server on the internet behind TLS. These listen on the standard
 *    443, so forcing the game's port onto them breaks the connection.
 *  - a full URL including the path, which should be left alone.
 *
 * This lives in `core` rather than in the Android client so it can be unit tested.
 */
object ServerAddress {

    const val DEFAULT_PORT = 8080
    const val PATH = "/play"

    private val schemes = listOf("wss://", "ws://", "https://", "http://")

    fun normalize(input: String): String {
        val trimmed = input.trim()

        // The scheme has to be taken off before any trailing slashes are, or "https://" collapses
        // to "https:" and stops looking like a scheme at all.
        val typedScheme = schemes.firstOrNull { trimmed.startsWith(it, ignoreCase = true) }
        val body = trimmed.drop(typedScheme?.length ?: 0).trimEnd('/')
        require(body.isNotEmpty()) { "Enter a server address." }

        // Rebuilt rather than reused, so a typed "WSS://" comes back lowercase.
        val scheme = when (typedScheme?.lowercase()) {
            "wss://", "https://" -> "wss://"
            else -> "ws://"
        }

        val authority = body.substringBefore('/')
        val hasPort = authority.contains(':')

        // Only a bare host typed without any scheme gets the game's default port. An address the
        // player scheme-qualified themselves keeps its scheme's own port, which is what makes
        // hosted servers and tunnels (wss:// on 443) work.
        val withPort =
            if (typedScheme == null && !hasPort) "$authority:$DEFAULT_PORT" else authority

        return if (body.contains('/')) {
            scheme + withPort + "/" + body.substringAfter('/')
        } else {
            scheme + withPort + PATH
        }
    }
}
