package com.catan.server

import com.catan.core.model.Costs
import com.catan.core.model.DevCardType
import com.catan.core.model.GamePhase
import com.catan.core.model.GameState
import com.catan.core.model.PlayerId
import com.catan.core.model.Resource
import com.catan.core.model.Rules
import com.catan.core.model.covers
import com.catan.core.model.toCardList
import com.catan.core.net.Act
import com.catan.core.net.ActionRejected
import com.catan.core.net.CatanJson
import com.catan.core.net.ClientMessage
import com.catan.core.net.CreateRoom
import com.catan.core.net.ErrorMessage
import com.catan.core.net.GameUpdate
import com.catan.core.net.JoinRoom
import com.catan.core.net.LobbyUpdate
import com.catan.core.net.PlayerView
import com.catan.core.net.ServerMessage
import com.catan.core.net.StartGame
import com.catan.core.net.Welcome
import com.catan.core.rules.BankTrade
import com.catan.core.rules.BuildCity
import com.catan.core.rules.BuildRoad
import com.catan.core.rules.BuildSettlement
import com.catan.core.rules.BuyDevCard
import com.catan.core.rules.Discard
import com.catan.core.rules.EndTurn
import com.catan.core.rules.GameAction
import com.catan.core.rules.MoveRobber
import com.catan.core.rules.PlayKnight
import com.catan.core.rules.PlayMonopoly
import com.catan.core.rules.PlayRoadBuilding
import com.catan.core.rules.PlayYearOfPlenty
import com.catan.core.rules.Placement
import com.catan.core.rules.RollDice
import com.catan.core.rules.SetupRoad
import com.catan.core.rules.SetupSettlement
import com.catan.core.rules.StealFrom
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Drives real WebSocket clients against a real server instance.
 *
 * The unit tests prove the rules; this proves the transport, the room lifecycle, the server's
 * authority over dice and steals, and that no player is ever sent another player's hand.
 */
class ServerIntegrationTest {

    private class TestClient(
        val session: DefaultClientWebSocketSession,
        val scope: CoroutineScope,
    ) {
        val inbox = Channel<ServerMessage>(Channel.UNLIMITED)
        val rejections = mutableListOf<String>()
        val errors = mutableListOf<String>()

        @Volatile
        var view: PlayerView? = null

        @Volatile
        var lobbySeen = false
        lateinit var welcome: Welcome

        fun startReading(): Job = scope.launch {
            for (frame in session.incoming) {
                if (frame !is Frame.Text) continue
                val message = CatanJson.decodeFromString<ServerMessage>(frame.readText())
                when (message) {
                    is GameUpdate -> view = message.view
                    is LobbyUpdate -> lobbySeen = true
                    is ActionRejected -> rejections.add(message.reason)
                    is ErrorMessage -> errors.add(message.message)
                    is Welcome -> welcome = message
                }
                inbox.trySend(message)
            }
        }

        suspend fun send(message: ClientMessage) {
            session.send(Frame.Text(CatanJson.encodeToString(message)))
        }

        suspend fun awaitWelcome(): Welcome = withTimeout(10_000) {
            while (!::welcome.isInitialized) delay(5)
            welcome
        }

        suspend fun awaitView(): PlayerView = withTimeout(10_000) {
            while (view == null) delay(5)
            view!!
        }
    }

    /** Chooses a legal action for whoever is on the clock, from that player's own view. */
    private fun chooseAction(state: GameState, me: PlayerId, random: Random): GameAction {
        val player = state.player(me)

        if (state.phase == GamePhase.DISCARD) {
            val hand = player.resources.toCardList().shuffled(random)
            return Discard(hand.take(hand.size / 2).groupingBy { it }.eachCount())
        }

        return when (state.phase) {
            GamePhase.SETUP_ROUND_1, GamePhase.SETUP_ROUND_2 -> {
                val pending = state.setupSettlement
                if (pending == null) {
                    SetupSettlement(Placement.setupSettlementSpots(state).random(random))
                } else {
                    SetupRoad(Placement.setupRoadSpots(state, pending).random(random))
                }
            }

            GamePhase.ROLL -> RollDice

            GamePhase.MOVE_ROBBER ->
                MoveRobber(state.board.hexes.filter { it != state.board.robber }.random(random))

            GamePhase.STEAL -> StealFrom(state.stealCandidates.random(random))

            GamePhase.MAIN -> {
                if (state.freeRoadsRemaining > 0) {
                    val spots = Placement.roadSpots(state, me)
                    if (spots.isNotEmpty()) return BuildRoad(spots.random(random))
                }
                val cities = Placement.citySpots(state, me)
                if (player.resources.covers(Costs.CITY) && player.citiesLeft > 0 &&
                    cities.isNotEmpty()
                ) {
                    return BuildCity(cities.random(random))
                }
                val settlements = Placement.settlementSpots(state, me)
                if (player.resources.covers(Costs.SETTLEMENT) && player.settlementsLeft > 0 &&
                    settlements.isNotEmpty()
                ) {
                    return BuildSettlement(settlements.random(random))
                }
                val playable = player.devCards.filter {
                    !it.played && it.boughtOnTurn < state.turnNumber && !it.type.isVictoryPoint
                }
                if (!player.playedDevCardThisTurn && playable.isNotEmpty() && random.nextInt(3) == 0) {
                    when (playable.random(random).type) {
                        DevCardType.KNIGHT -> return PlayKnight
                        DevCardType.MONOPOLY -> return PlayMonopoly(Resource.entries.random(random))
                        DevCardType.YEAR_OF_PLENTY -> {
                            val ok = Resource.entries.filter { (state.bank[it] ?: 0) >= 2 }
                            if (ok.isNotEmpty()) {
                                val pick = ok.random(random)
                                return PlayYearOfPlenty(pick, pick)
                            }
                        }
                        DevCardType.ROAD_BUILDING ->
                            if (player.roadsLeft > 0) return PlayRoadBuilding
                        DevCardType.VICTORY_POINT -> Unit
                    }
                }
                val roads = Placement.roadSpots(state, me)
                if (player.resources.covers(Costs.ROAD) && player.roadsLeft > 0 &&
                    roads.isNotEmpty() && random.nextInt(2) == 0
                ) {
                    return BuildRoad(roads.random(random))
                }
                if (player.resources.covers(Costs.DEV_CARD) && state.devDeckSizeUnknownSafe()) {
                    return BuyDevCard
                }
                val surplus = Resource.entries
                    .filter { (player.resources[it] ?: 0) >= state.tradeRatio(me, it) }
                    .randomOrNull(random)
                if (surplus != null) {
                    val wanted = Resource.entries
                        .filter { it != surplus && (state.bank[it] ?: 0) > 0 }
                        .randomOrNull(random)
                    if (wanted != null) return BankTrade(surplus, wanted)
                }
                EndTurn
            }

            else -> EndTurn
        }
    }

    /** The redacted view hides the deck, so the bot just tries and accepts a rejection. */
    private fun GameState.devDeckSizeUnknownSafe(): Boolean = true

    @Test
    fun `four clients play a full game over websockets`() = runBlocking {
        val server = embeddedServer(Netty, port = 0, module = Application::catanModule)
            .start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        val http = HttpClient(CIO) { install(WebSockets) }
        val scope = CoroutineScope(Dispatchers.IO + Job())

        try {
            val names = listOf("Alice", "Bob", "Cara", "Dan")
            val clients = names.map {
                TestClient(http.webSocketSession(host = "127.0.0.1", port = port, path = "/play"), scope)
            }
            clients.forEach { it.startReading() }

            clients[0].send(CreateRoom(names[0]))
            val roomCode = clients[0].awaitWelcome().roomCode
            assertEquals(4, roomCode.length)

            for (i in 1..3) {
                clients[i].send(JoinRoom(roomCode, names[i]))
                assertEquals(PlayerId(i), clients[i].awaitWelcome().playerId)
            }

            // A non-host may not start the game.
            clients[1].send(StartGame)
            withTimeout(5_000) {
                while (clients[1].errors.isEmpty()) delay(5)
            }
            assertTrue(clients[1].errors.any { it.contains("host") }) { clients[1].errors.toString() }

            clients[0].send(StartGame)
            clients.forEach { it.awaitView() }

            // Someone who is not on the clock cannot act.
            val opener = clients[0].awaitView().state.currentPlayerIndex
            val bystander = clients[(opener + 1) % 4]
            bystander.send(Act(RollDice))
            withTimeout(5_000) {
                while (bystander.rejections.isEmpty()) delay(5)
            }
            assertTrue(bystander.rejections.any { it.contains("not your turn") }) {
                bystander.rejections.toString()
            }
            bystander.rejections.clear()

            // Drive the game to a finish.
            val random = Random(20250725)
            var steps = 0
            var opponentsEverHeldCards = false

            withTimeout(180_000) {
                while (true) {
                    val public = clients[0].awaitView()
                    val state = public.state
                    if (state.phase == GamePhase.GAME_OVER) break
                    check(steps++ < 20_000) { "game did not finish" }

                    // Redaction must hold on every single update, not just at the end.
                    for (client in clients) {
                        val v = client.view ?: continue
                        for (other in v.state.players.filter { it.id != v.you }) {
                            assertTrue(other.resources.values.all { it == 0 }) {
                                "client ${v.you} was sent ${other.name}'s resource cards"
                            }
                            assertTrue(other.devCards.isEmpty()) {
                                "client ${v.you} was sent ${other.name}'s development cards"
                            }
                        }
                        assertTrue(v.state.devDeck.isEmpty()) { "the undrawn deck leaked" }
                        if (v.handSizes.filterKeys { it != v.you }.values.any { it > 0 }) {
                            opponentsEverHeldCards = true
                        }
                    }

                    val actorId = if (state.phase == GamePhase.DISCARD) {
                        state.pendingDiscards.first()
                    } else {
                        state.currentPlayer.id
                    }
                    val actorClient = clients[actorId.value]
                    val actorState = actorClient.awaitView().state
                    val action = chooseAction(actorState, actorId, random)

                    val before = actorState
                    actorClient.send(Act(action))

                    // Wait for the broadcast that reflects it.
                    withTimeout(15_000) {
                        while (actorClient.view?.state == before &&
                            actorClient.rejections.isEmpty()
                        ) {
                            delay(2)
                        }
                    }
                    if (actorClient.rejections.isNotEmpty()) {
                        // The only rejection the bot can legitimately provoke is buying from an
                        // empty deck, which it cannot see.
                        val reasons = actorClient.rejections.toList()
                        actorClient.rejections.clear()
                        assertTrue(reasons.all { it.contains("empty") }) {
                            "unexpected rejection for $action: $reasons"
                        }
                        actorClient.send(Act(EndTurn))
                        withTimeout(15_000) {
                            while (actorClient.view?.state == before) delay(2)
                        }
                    }
                }
            }

            val finalView = clients[0].awaitView()
            val winner = finalView.state.winner
            assertNotNull(winner) { "the game ended with no winner" }
            assertTrue(finalView.handSizes.isNotEmpty())
            assertTrue(opponentsEverHeldCards) {
                "redaction check never had anything to hide"
            }

            // Every client must agree on the result.
            for (client in clients) {
                val v = client.awaitView()
                assertEquals(GamePhase.GAME_OVER, v.state.phase)
                assertEquals(winner, v.state.winner)
                assertTrue(v.state.publicVictoryPoints(winner!!) >= 2)
            }

            // The winner's own view must show at least ten points.
            val winnerView = clients[winner!!.value].awaitView()
            assertTrue(winnerView.state.victoryPoints(winner) >= Rules.VICTORY_POINTS_TO_WIN) {
                "winner shows ${winnerView.state.victoryPoints(winner)} points in their own view"
            }

            clients.forEach { it.session.close() }
            scope.cancel()
        } finally {
            http.close()
            server.stop(500, 1000)
        }
    }

    @Test
    fun `a dropped player can rejoin and keeps their seat`() = runBlocking {
        val server = embeddedServer(Netty, port = 0, module = Application::catanModule)
            .start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        val http = HttpClient(CIO) { install(WebSockets) }
        val scope = CoroutineScope(Dispatchers.IO + Job())

        try {
            val host = TestClient(
                http.webSocketSession(host = "127.0.0.1", port = port, path = "/play"),
                scope,
            )
            host.startReading()
            host.send(CreateRoom("Alice"))
            val code = host.awaitWelcome().roomCode

            val guest = TestClient(
                http.webSocketSession(host = "127.0.0.1", port = port, path = "/play"),
                scope,
            )
            guest.startReading()
            guest.send(JoinRoom(code, "Bob"))
            val guestWelcome = guest.awaitWelcome()

            host.send(StartGame)
            val before = host.awaitView().state

            // Bob's connection drops.
            guest.session.close()
            delay(300)

            val reconnected = TestClient(
                http.webSocketSession(host = "127.0.0.1", port = port, path = "/play"),
                scope,
            )
            reconnected.startReading()
            reconnected.send(
                com.catan.core.net.Rejoin(code, guestWelcome.token),
            )
            val resumed = reconnected.awaitWelcome()

            assertEquals(guestWelcome.playerId, resumed.playerId) { "seat was not restored" }
            val resumedView = reconnected.awaitView()
            assertEquals(before.board.tiles, resumedView.state.board.tiles) {
                "the board changed across a reconnect"
            }
            assertEquals(guestWelcome.playerId, resumedView.you)

            listOf(host, reconnected).forEach { it.session.close() }
            scope.cancel()
        } finally {
            http.close()
            server.stop(500, 1000)
        }
    }

    @Test
    fun `joining a room that does not exist is refused`() = runBlocking {
        val server = embeddedServer(Netty, port = 0, module = Application::catanModule)
            .start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        val http = HttpClient(CIO) { install(WebSockets) }
        val scope = CoroutineScope(Dispatchers.IO + Job())

        try {
            val client = TestClient(
                http.webSocketSession(host = "127.0.0.1", port = port, path = "/play"),
                scope,
            )
            client.startReading()
            client.send(JoinRoom("ZZZZ", "Nobody"))

            withTimeout(10_000) {
                while (client.errors.isEmpty()) delay(5)
            }
            assertTrue(client.errors.first().contains("No room")) { client.errors.toString() }

            client.session.close()
            scope.cancel()
        } finally {
            http.close()
            server.stop(500, 1000)
        }
    }
}
