package com.catan.server

import com.catan.core.net.Act
import com.catan.core.net.CatanJson
import com.catan.core.net.ClientMessage
import com.catan.core.net.CreateRoom
import com.catan.core.net.ErrorMessage
import com.catan.core.net.JoinRoom
import com.catan.core.net.Leave
import com.catan.core.net.Rejoin
import com.catan.core.net.ServerMessage
import com.catan.core.net.StartGame
import com.catan.core.net.Welcome
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlin.random.Random

/** All live rooms, keyed by their four-letter code. */
object RoomRegistry {
    private val lock = Mutex()
    private val rooms = mutableMapOf<String, GameRoom>()

    private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    suspend fun create(random: Random = Random.Default): GameRoom = lock.withLock {
        var code: String
        do {
            code = (1..4).map { CODE_ALPHABET.random(random) }.joinToString("")
        } while (rooms.containsKey(code))

        GameRoom(code, random).also { rooms[code] = it }
    }

    suspend fun find(code: String): GameRoom? = lock.withLock { rooms[code.uppercase()] }

    suspend fun remove(code: String) = lock.withLock { rooms.remove(code) }

    suspend fun count(): Int = lock.withLock { rooms.size }
}

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::catanModule)
        .start(wait = true)
}

fun Application.catanModule() {
    install(ContentNegotiation) { json(CatanJson) }
    install(WebSockets) {
        pingPeriodMillis = 15_000
        timeoutMillis = 60_000
        maxFrameSize = 4L * 1024 * 1024
    }

    routing {
        get("/health") { call.respondText("ok") }

        webSocket("/play") {
            var room: GameRoom? = null
            var me: Member? = null

            suspend fun push(message: ServerMessage) {
                send(Frame.Text(CatanJson.encodeToString(message)))
            }

            /** Wires a member to this socket and sends them the current picture. */
            suspend fun attach(newRoom: GameRoom, member: Member) {
                room = newRoom
                me = member
                member.send = { push(it) }
                push(Welcome(newRoom.code, member.playerId, member.token))
                newRoom.broadcastState()
            }

            try {
                incoming.consumeEach { frame ->
                    if (frame !is Frame.Text) return@consumeEach

                    val message = runCatching {
                        CatanJson.decodeFromString<ClientMessage>(frame.readText())
                    }.getOrElse {
                        push(ErrorMessage("Could not read that message."))
                        return@consumeEach
                    }

                    when (message) {
                        is CreateRoom -> {
                            if (me != null) {
                                push(ErrorMessage("You are already in a room."))
                                return@consumeEach
                            }
                            val newRoom = RoomRegistry.create()
                            newRoom.join(message.playerName).fold(
                                onSuccess = { attach(newRoom, it) },
                                onFailure = { push(ErrorMessage(it.message ?: "Could not create a room.")) },
                            )
                        }

                        is JoinRoom -> {
                            if (me != null) {
                                push(ErrorMessage("You are already in a room."))
                                return@consumeEach
                            }
                            val target = RoomRegistry.find(message.roomCode)
                            if (target == null) {
                                push(ErrorMessage("No room with that code."))
                                return@consumeEach
                            }
                            target.join(message.playerName).fold(
                                onSuccess = { attach(target, it) },
                                onFailure = { push(ErrorMessage(it.message ?: "Could not join.")) },
                            )
                        }

                        is Rejoin -> {
                            val target = RoomRegistry.find(message.roomCode)
                            val existing = target?.reattach(message.token)
                            if (target == null || existing == null) {
                                push(ErrorMessage("That seat is no longer available."))
                                return@consumeEach
                            }
                            attach(target, existing)
                        }

                        is StartGame -> {
                            val current = room
                            val player = me
                            if (current == null || player == null) {
                                push(ErrorMessage("Join a room first."))
                                return@consumeEach
                            }
                            current.start(player.playerId).onFailure {
                                push(ErrorMessage(it.message ?: "Could not start."))
                            }
                        }

                        is Act -> {
                            val current = room
                            val player = me
                            if (current == null || player == null) {
                                push(ErrorMessage("Join a room first."))
                                return@consumeEach
                            }
                            current.submit(player.playerId, message.action)?.let { reason ->
                                current.rejectTo(player.playerId, reason)
                            }
                        }

                        is Leave -> {
                            room?.detach(me?.playerId ?: return@consumeEach)
                            room = null
                            me = null
                        }
                    }
                }
            } finally {
                val current = room
                val player = me
                if (current != null && player != null) {
                    current.detach(player.playerId)
                    if (current.connectedCount() == 0) RoomRegistry.remove(current.code)
                }
            }
        }
    }
}
