package com.catan.server

import com.catan.core.model.GameState
import com.catan.core.model.PlayerColor
import com.catan.core.model.PlayerId
import com.catan.core.net.ActionRejected
import com.catan.core.net.GameUpdate
import com.catan.core.net.LobbySeat
import com.catan.core.net.LobbyUpdate
import com.catan.core.net.LobbyView
import com.catan.core.net.ServerMessage
import com.catan.core.net.redactFor
import com.catan.core.rules.ActionResult
import com.catan.core.rules.GameAction
import com.catan.core.rules.GameEngine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/** One connected player. [send] delivers a message, or does nothing if they have dropped. */
class Member(
    val playerId: PlayerId,
    val name: String,
    val color: PlayerColor,
    val token: String,
    val isHost: Boolean,
) {
    @Volatile
    var send: (suspend (ServerMessage) -> Unit)? = null

    val connected: Boolean get() = send != null
}

/**
 * A single game, from lobby to result.
 *
 * Everything that mutates the room goes through [lock], so two players acting at the same instant
 * cannot interleave and corrupt the state.
 */
class GameRoom(
    val code: String,
    private val random: Random = Random.Default,
) {
    private val lock = Mutex()
    private val members = mutableListOf<Member>()

    var game: GameState? = null
        private set

    val isInProgress: Boolean get() = game != null

    private val availableColors: List<PlayerColor> get() = PlayerColor.entries

    suspend fun join(name: String): Result<Member> = lock.withLock {
        if (game != null) return Result.failure(IllegalStateException("That game has already started."))
        if (members.size >= 4) return Result.failure(IllegalStateException("That room is full."))

        val taken = members.map { it.color }.toSet()
        val color = availableColors.firstOrNull { it !in taken }
            ?: return Result.failure(IllegalStateException("That room is full."))

        val member = Member(
            playerId = PlayerId(members.size),
            name = name.take(16).ifBlank { "Player ${members.size + 1}" },
            color = color,
            token = java.util.UUID.randomUUID().toString(),
            isHost = members.isEmpty(),
        )
        members.add(member)
        Result.success(member)
    }

    suspend fun reattach(token: String): Member? = lock.withLock {
        members.firstOrNull { it.token == token }
    }

    suspend fun memberCount(): Int = lock.withLock { members.size }

    /** How many members still have a live socket. A room with none can be reclaimed. */
    suspend fun connectedCount(): Int = lock.withLock { members.count { it.connected } }

    suspend fun detach(playerId: PlayerId) {
        lock.withLock { members.firstOrNull { it.playerId == playerId }?.send = null }
        broadcastState()
    }

    /** Starts the game. Only the host may do this, and only with at least two players. */
    suspend fun start(by: PlayerId): Result<Unit> {
        lock.withLock {
            if (game != null) return Result.failure(IllegalStateException("Already started."))
            val host = members.firstOrNull { it.isHost }
            if (host?.playerId != by) {
                return Result.failure(IllegalStateException("Only the host can start the game."))
            }
            if (members.size < 2) {
                return Result.failure(IllegalStateException("At least two players are needed."))
            }
            game = GameEngine.newGame(
                members.map { GameEngine.Seat(it.name, it.color) },
                random,
            )
        }
        broadcastState()
        return Result.success(Unit)
    }

    /**
     * Validates and applies [action] on behalf of [actor].
     *
     * The client is never trusted: the action is re-checked here against the authoritative state,
     * and dice and steals are rolled server-side.
     */
    suspend fun submit(actor: PlayerId, action: GameAction): String? {
        val rejection = lock.withLock {
            val current = game ?: return "The game has not started."
            when (val result = GameEngine.apply(current, actor, action, random)) {
                is ActionResult.Success -> {
                    game = result.state
                    null
                }
                is ActionResult.Rejected -> result.reason
            }
        }
        if (rejection == null) broadcastState()
        return rejection
    }

    /** Sends every member the view they are entitled to. */
    suspend fun broadcastState() {
        val (snapshot, recipients) = lock.withLock {
            game to members.toList()
        }
        val connected = recipients.associate { it.playerId to it.connected }

        for (member in recipients) {
            val message: ServerMessage = if (snapshot == null) {
                LobbyUpdate(lobbyViewFor(member, recipients))
            } else {
                GameUpdate(redactFor(snapshot, member.playerId, connected))
            }
            member.send?.let { deliver ->
                runCatching { deliver(message) }
            }
        }
    }

    suspend fun sendTo(playerId: PlayerId, message: ServerMessage) {
        val member = lock.withLock { members.firstOrNull { it.playerId == playerId } }
        member?.send?.let { runCatching { it(message) } }
    }

    suspend fun rejectTo(playerId: PlayerId, reason: String) =
        sendTo(playerId, ActionRejected(reason))

    private fun lobbyViewFor(member: Member, all: List<Member>) = LobbyView(
        roomCode = code,
        seats = all.map {
            LobbySeat(
                playerId = it.playerId,
                name = it.name,
                color = it.color,
                isHost = it.isHost,
                connected = it.connected,
            )
        },
        canStart = all.size >= 2 && member.isHost,
        you = member.playerId,
    )
}
