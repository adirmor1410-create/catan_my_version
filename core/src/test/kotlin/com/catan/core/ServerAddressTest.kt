package com.catan.core

import com.catan.core.net.ServerAddress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ServerAddressTest {

    @Test
    fun `a bare host on the local network gets the game's port`() {
        assertEquals("ws://192.168.1.20:8080/play", ServerAddress.normalize("192.168.1.20"))
    }

    @Test
    fun `a host with an explicit port keeps it`() {
        assertEquals("ws://10.0.2.2:8080/play", ServerAddress.normalize("10.0.2.2:8080"))
        assertEquals("ws://192.168.1.20:9000/play", ServerAddress.normalize("192.168.1.20:9000"))
    }

    /**
     * The case that matters for playing over the internet. Tunnels and hosted servers serve TLS on
     * 443; appending the game's port would send the connection somewhere nothing is listening.
     */
    @Test
    fun `a secure host keeps its own port`() {
        assertEquals(
            "wss://catan.example.com/play",
            ServerAddress.normalize("wss://catan.example.com"),
        )
        assertEquals(
            "wss://vast-lion-42.trycloudflare.com/play",
            ServerAddress.normalize("https://vast-lion-42.trycloudflare.com"),
        )
        assertEquals(
            "wss://catan.onrender.com/play",
            ServerAddress.normalize("https://catan.onrender.com/"),
        )
    }

    @Test
    fun `http and https map to the matching websocket scheme`() {
        assertEquals("ws://example.com/play", ServerAddress.normalize("http://example.com"))
        assertEquals("wss://example.com/play", ServerAddress.normalize("https://example.com"))
    }

    @Test
    fun `a scheme-qualified host may still name a port`() {
        assertEquals("ws://example.com:9000/play", ServerAddress.normalize("ws://example.com:9000"))
        assertEquals("wss://example.com:8443/play", ServerAddress.normalize("wss://example.com:8443"))
    }

    @Test
    fun `an address that already names a path is left alone`() {
        assertEquals("wss://example.com/play", ServerAddress.normalize("wss://example.com/play"))
        assertEquals(
            "wss://example.com/games/play",
            ServerAddress.normalize("wss://example.com/games/play"),
        )
    }

    @Test
    fun `surrounding whitespace and trailing slashes are forgiven`() {
        assertEquals("ws://192.168.1.20:8080/play", ServerAddress.normalize("  192.168.1.20  "))
        assertEquals("ws://192.168.1.20:8080/play", ServerAddress.normalize("192.168.1.20/"))
    }

    @Test
    fun `scheme matching is case insensitive`() {
        assertEquals("wss://example.com/play", ServerAddress.normalize("WSS://example.com"))
        assertEquals("wss://example.com/play", ServerAddress.normalize("HTTPS://example.com"))
    }

    @Test
    fun `an empty address is refused`() {
        assertThrows<IllegalArgumentException> { ServerAddress.normalize("   ") }
        assertThrows<IllegalArgumentException> { ServerAddress.normalize("https://") }
    }
}
