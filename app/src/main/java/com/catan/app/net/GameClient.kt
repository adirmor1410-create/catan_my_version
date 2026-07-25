package com.catan.app.net

import com.catan.core.net.Act
import com.catan.core.net.ActionRejected
import com.catan.core.net.CatanJson
import com.catan.core.net.ClientMessage
import com.catan.core.net.CreateRoom
import com.catan.core.net.ErrorMessage
import com.catan.core.net.GameUpdate
import com.catan.core.net.JoinRoom
import com.catan.core.net.LobbyUpdate
import com.catan.core.net.LobbyView
import com.catan.core.net.PlayerView
import com.catan.core.net.Rejoin
import com.catan.core.net.ServerMessage
import com.catan.core.net.StartGame
import com.catan.core.net.Welcome
import com.catan.core.rules.GameAction
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

sealed interface Connection {
    data object Offline : Connection
    data object Connecting : Connection
    data object Online : Connection
    data class Failed(val message: String) : Connection
}

/**
 * Talks to the game server over a WebSocket.
 *
 * The server is authoritative, so this class never decides anything about the game: it sends
 * what the player asked for and publishes whatever comes back.
 */
class GameClient {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = HttpClient(OkHttp) { install(WebSockets) }

    private var session: DefaultClientWebSocketSession? = null
    private var reader: Job? = null

    private val _connection = MutableStateFlow<Connection>(Connection.Offline)
    val connection: StateFlow<Connection> = _connection.asStateFlow()

    private val _lobby = MutableStateFlow<LobbyView?>(null)
    val lobby: StateFlow<LobbyView?> = _lobby.asStateFlow()

    private val _view = MutableStateFlow<PlayerView?>(null)
    val view: StateFlow<PlayerView?> = _view.asStateFlow()

    /** Transient message to show the player: a rejected move or a server error. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private var serverUrl: String? = null
    private var roomCode: String? = null
    private var token: String? = null

    /** Set once the player deliberately leaves, so we stop trying to reconnect. */
    @Volatile
    private var closedByUser = false

    fun clearNotice() {
        _notice.value = null
    }

    fun host(url: String, playerName: String) = scope.launch {
        if (open(url)) send(CreateRoom(playerName))
    }

    fun join(url: String, code: String, playerName: String) = scope.launch {
        if (open(url)) send(JoinRoom(code.trim().uppercase(), playerName))
    }

    fun startGame() = scope.launch { send(StartGame) }

    fun act(action: GameAction) = scope.launch { send(Act(action)) }

    fun leave() = scope.launch {
        closedByUser = true
        reader?.cancel()
        runCatching { session?.close() }
        session = null
        _lobby.value = null
        _view.value = null
        _connection.value = Connection.Offline
    }

    private suspend fun open(url: String): Boolean {
        closedByUser = false
        _connection.value = Connection.Connecting
        serverUrl = normalize(url)

        return try {
            reader?.cancel()
            runCatching { session?.close() }
            session = http.webSocketSession(serverUrl!!)
            _connection.value = Connection.Online
            reader = scope.launch { readLoop() }
            true
        } catch (failure: Exception) {
            _connection.value = Connection.Failed(
                failure.message ?: "Could not reach the server.",
            )
            false
        }
    }

    private suspend fun readLoop() {
        val active = session ?: return
        try {
            for (frame in active.incoming) {
                if (frame !is Frame.Text) continue
                val message = runCatching {
                    CatanJson.decodeFromString<ServerMessage>(frame.readText())
                }.getOrNull() ?: continue
                handle(message)
            }
        } catch (_: Exception) {
            // falls through to the reconnect attempt below
        }
        if (!closedByUser) {
            _connection.value = Connection.Offline
            reconnect()
        }
    }

    private fun handle(message: ServerMessage) {
        when (message) {
            is Welcome -> {
                roomCode = message.roomCode
                token = message.token
            }
            is LobbyUpdate -> {
                _lobby.value = message.lobby
                _view.value = null
            }
            is GameUpdate -> {
                _view.value = message.view
                _lobby.value = null
            }
            is ActionRejected -> _notice.value = message.reason
            is ErrorMessage -> _notice.value = message.message
        }
    }

    /** Mobile connections drop. Re-attach to the same seat rather than losing the game. */
    private suspend fun reconnect() {
        val url = serverUrl ?: return
        val code = roomCode ?: return
        val seat = token ?: return

        repeat(5) { attempt ->
            if (closedByUser) return
            delay(1000L * (attempt + 1))
            _connection.value = Connection.Connecting
            try {
                session = http.webSocketSession(url)
                _connection.value = Connection.Online
                send(Rejoin(code, seat))
                reader = scope.launch { readLoop() }
                return
            } catch (_: Exception) {
                _connection.value = Connection.Offline
            }
        }
        _connection.value = Connection.Failed("Lost the connection to the server.")
    }

    private suspend fun send(message: ClientMessage) {
        val active = session
        if (active == null) {
            _notice.value = "Not connected."
            return
        }
        runCatching { active.send(Frame.Text(CatanJson.encodeToString(message))) }
            .onFailure { _notice.value = "Could not reach the server." }
    }

    /** Accepts "10.0.0.5", "10.0.0.5:8080" or a full ws:// / wss:// URL. */
    private fun normalize(input: String): String {
        val trimmed = input.trim().removeSuffix("/")
        val withScheme = when {
            trimmed.startsWith("ws://") || trimmed.startsWith("wss://") -> trimmed
            trimmed.startsWith("https://") -> "wss://" + trimmed.removePrefix("https://")
            trimmed.startsWith("http://") -> "ws://" + trimmed.removePrefix("http://")
            else -> "ws://$trimmed"
        }
        return if (withScheme.substringAfter("://").contains('/')) {
            withScheme
        } else {
            val hasPort = withScheme.substringAfter("://").contains(':')
            if (hasPort) "$withScheme/play" else "$withScheme:8080/play"
        }
    }
}
