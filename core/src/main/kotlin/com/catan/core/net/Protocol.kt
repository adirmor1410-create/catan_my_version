package com.catan.core.net

import com.catan.core.model.GameState
import com.catan.core.model.PlayerColor
import com.catan.core.model.PlayerId
import com.catan.core.model.emptyResources
import com.catan.core.rules.GameAction
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A seat in the lobby, before the game starts. */
@Serializable
data class LobbySeat(
    val playerId: PlayerId,
    val name: String,
    val color: PlayerColor,
    val isHost: Boolean,
    val connected: Boolean,
)

@Serializable
data class LobbyView(
    val roomCode: String,
    val seats: List<LobbySeat>,
    val canStart: Boolean,
    val you: PlayerId,
)

/**
 * One player's view of a game.
 *
 * [state] has been stripped of everything this player is not entitled to see: other players'
 * resource cards, their development cards, and the order of the undrawn deck. The counts that
 * *are* public travel alongside in [handSizes] and [devCardCounts].
 */
@Serializable
data class PlayerView(
    val you: PlayerId,
    val state: GameState,
    val handSizes: Map<PlayerId, Int>,
    val devCardCounts: Map<PlayerId, Int>,
    val devDeckSize: Int,
    val connected: Map<PlayerId, Boolean>,
)

/**
 * Strips [full] down to what [viewer] may see.
 *
 * This is the only thing standing between a curious player and their opponents' hands, so it
 * removes data rather than trusting the client to ignore it.
 */
fun redactFor(full: GameState, viewer: PlayerId, connected: Map<PlayerId, Boolean>): PlayerView {
    val redacted = full.copy(
        players = full.players.map { player ->
            if (player.id == viewer) {
                player
            } else {
                player.copy(resources = emptyResources(), devCards = emptyList())
            }
        },
        // The undrawn deck's order would tell a player exactly what they are about to buy.
        devDeck = emptyList(),
    )

    return PlayerView(
        you = viewer,
        state = redacted,
        handSizes = full.players.associate { it.id to it.handSize },
        devCardCounts = full.players.associate { it.id to it.devCards.size },
        devDeckSize = full.devDeck.size,
        connected = connected,
    )
}

// ------------------------------------------------------------------ client -> server

@Serializable
sealed interface ClientMessage

@Serializable
@SerialName("create_room")
data class CreateRoom(val playerName: String) : ClientMessage

@Serializable
@SerialName("join_room")
data class JoinRoom(val roomCode: String, val playerName: String) : ClientMessage

/** Re-attach to a seat after a dropped connection. */
@Serializable
@SerialName("rejoin")
data class Rejoin(val roomCode: String, val token: String) : ClientMessage

@Serializable
@SerialName("start_game")
data object StartGame : ClientMessage

@Serializable
@SerialName("act")
data class Act(val action: GameAction) : ClientMessage

@Serializable
@SerialName("leave")
data object Leave : ClientMessage

// ------------------------------------------------------------------ server -> client

@Serializable
sealed interface ServerMessage

/** Sent once on joining. [token] is what a client presents to [Rejoin]. */
@Serializable
@SerialName("welcome")
data class Welcome(
    val roomCode: String,
    val playerId: PlayerId,
    val token: String,
) : ServerMessage

@Serializable
@SerialName("lobby")
data class LobbyUpdate(val lobby: LobbyView) : ServerMessage

@Serializable
@SerialName("game")
data class GameUpdate(val view: PlayerView) : ServerMessage

/** The requested action broke a rule. The game state is unchanged. */
@Serializable
@SerialName("rejected")
data class ActionRejected(val reason: String) : ServerMessage

@Serializable
@SerialName("error")
data class ErrorMessage(val message: String) : ServerMessage
