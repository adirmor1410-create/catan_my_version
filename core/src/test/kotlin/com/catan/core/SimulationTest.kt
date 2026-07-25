package com.catan.core

import com.catan.core.model.BuildingType
import com.catan.core.model.Costs
import com.catan.core.model.DevCardType
import com.catan.core.model.GamePhase
import com.catan.core.model.GameState
import com.catan.core.model.PlayerId
import com.catan.core.model.Resource
import com.catan.core.model.Rules
import com.catan.core.model.covers
import com.catan.core.model.endpoints
import com.catan.core.model.toCardList
import com.catan.core.rules.ActionResult
import com.catan.core.rules.BankTrade
import com.catan.core.rules.BuildCity
import com.catan.core.rules.BuildRoad
import com.catan.core.rules.BuildSettlement
import com.catan.core.rules.BuyDevCard
import com.catan.core.rules.Discard
import com.catan.core.rules.EndTurn
import com.catan.core.rules.GameAction
import com.catan.core.rules.GameEngine
import com.catan.core.rules.MoveRobber
import com.catan.core.rules.PlayKnight
import com.catan.core.rules.PlayMonopoly
import com.catan.core.rules.PlayRoadBuilding
import com.catan.core.rules.PlayYearOfPlenty
import com.catan.core.rules.Placement
import com.catan.core.rules.RoadLength
import com.catan.core.rules.RollDice
import com.catan.core.rules.SetupRoad
import com.catan.core.rules.SetupSettlement
import com.catan.core.rules.StealFrom
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Plays complete games with a simple bot and checks the rulebook invariants after every single
 * action. This is what catches rule interactions that targeted tests miss.
 */
class SimulationTest {

    /** Picks a reasonable legal action, biased toward building so games actually finish. */
    private fun chooseAction(state: GameState, random: Random): Pair<PlayerId, GameAction>? {
        // Discards are owed by specific players, whoever's turn it is.
        if (state.phase == GamePhase.DISCARD) {
            val who = state.pendingDiscards.first()
            val hand = state.player(who).resources.toCardList().shuffled(random)
            val toDrop = hand.take(hand.size / 2)
            return who to Discard(toDrop.groupingBy { it }.eachCount())
        }

        val me = state.currentPlayer.id
        val player = state.player(me)

        return me to when (state.phase) {
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

            GamePhase.MAIN -> mainPhaseAction(state, player.id, random)

            else -> return null
        }
    }

    private fun mainPhaseAction(state: GameState, me: PlayerId, random: Random): GameAction {
        val player = state.player(me)

        // Owed free roads must be placed first.
        if (state.freeRoadsRemaining > 0) {
            val spots = Placement.roadSpots(state, me)
            if (spots.isNotEmpty()) return BuildRoad(spots.random(random))
        }

        val cities = Placement.citySpots(state, me)
        if (player.resources.covers(Costs.CITY) && player.citiesLeft > 0 && cities.isNotEmpty()) {
            return BuildCity(cities.random(random))
        }

        val settlements = Placement.settlementSpots(state, me)
        if (player.resources.covers(Costs.SETTLEMENT) &&
            player.settlementsLeft > 0 &&
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
                    val available = Resource.entries.filter { (state.bank[it] ?: 0) >= 2 }
                    if (available.isNotEmpty()) {
                        val pick = available.random(random)
                        return PlayYearOfPlenty(pick, pick)
                    }
                }
                DevCardType.ROAD_BUILDING -> if (player.roadsLeft > 0) return PlayRoadBuilding
                DevCardType.VICTORY_POINT -> Unit
            }
        }

        val roads = Placement.roadSpots(state, me)
        if (player.resources.covers(Costs.ROAD) &&
            player.roadsLeft > 0 &&
            roads.isNotEmpty() &&
            random.nextInt(2) == 0
        ) {
            return BuildRoad(roads.random(random))
        }

        if (player.resources.covers(Costs.DEV_CARD) && state.devDeck.isNotEmpty()) {
            return BuyDevCard
        }

        // Trade the biggest pile down toward something useful.
        val surplus = Resource.entries
            .filter { (player.resources[it] ?: 0) >= state.tradeRatio(me, it) }
            .randomOrNull(random)
        if (surplus != null) {
            val wanted = Resource.entries
                .filter { it != surplus && (state.bank[it] ?: 0) > 0 }
                .randomOrNull(random)
            if (wanted != null) return BankTrade(surplus, wanted)
        }

        return EndTurn
    }

    private fun checkInvariants(state: GameState, note: String) {
        assertCardsConserved(state, note)

        for (player in state.players) {
            assertTrue(player.roadsLeft in 0..Rules.MAX_ROADS) { "$note bad road supply" }
            assertTrue(player.settlementsLeft in 0..Rules.MAX_SETTLEMENTS) {
                "$note bad settlement supply"
            }
            assertTrue(player.citiesLeft in 0..Rules.MAX_CITIES) { "$note bad city supply" }

            val placedRoads = state.roadsOf(player.id).size
            assertEquals(Rules.MAX_ROADS - placedRoads, player.roadsLeft) {
                "$note road supply does not match the board"
            }

            val built = state.buildingsOf(player.id).values
            val settlementsOnBoard = built.count { it.type == BuildingType.SETTLEMENT }
            val citiesOnBoard = built.count { it.type == BuildingType.CITY }
            assertEquals(Rules.MAX_CITIES - citiesOnBoard, player.citiesLeft) {
                "$note city supply does not match the board"
            }
            // Cities are built by replacing a settlement, so the settlement piece comes back.
            assertEquals(Rules.MAX_SETTLEMENTS - settlementsOnBoard, player.settlementsLeft) {
                "$note settlement supply does not match the board"
            }

            assertTrue(player.knightsPlayed <= 14) { "$note more knights played than exist" }
        }

        // The distance rule must hold for every building on the board, always.
        for ((vertex, _) in state.buildings) {
            val clash = state.board.neighborsOf(vertex).firstOrNull { state.buildings[it] != null }
            assertTrue(clash == null) { "$note buildings at $vertex and $clash are adjacent" }
        }

        // Every road must sit on a real edge, and every building on a real corner.
        assertTrue(state.roads.keys.all { it in state.board.edges }) { "$note road off-board" }
        assertTrue(state.buildings.keys.all { it in state.board.vertices }) {
            "$note building off-board"
        }

        // Development cards can never exceed the printed deck.
        val dealt = state.players.sumOf { it.devCards.size }
        assertEquals(Rules.DEV_DECK.size, dealt + state.devDeck.size) { "$note dev cards leaked" }

        // The recorded bonus holders must match a fresh computation.
        if (state.longestRoadHolder != null) {
            assertEquals(
                state.longestRoadLength,
                RoadLength.longestFor(state, state.longestRoadHolder!!),
            ) { "$note longest road length is stale" }
            assertTrue(state.longestRoadLength >= Rules.MIN_LONGEST_ROAD) {
                "$note longest road held with fewer than 5 segments"
            }
        }
        if (state.largestArmyHolder != null) {
            assertTrue(state.largestArmySize >= Rules.MIN_LARGEST_ARMY) {
                "$note largest army held with fewer than 3 knights"
            }
        }
    }

    /** Every road a player owns must connect to their own network. */
    private fun assertRoadsConnected(state: GameState) {
        for (player in state.players) {
            val own = state.roadsOf(player.id)
            if (own.isEmpty()) continue
            val ownCorners = state.buildingsOf(player.id).keys
            for (road in own) {
                val (a, b) = road.endpoints()
                val touchesOwnBuilding = a in ownCorners || b in ownCorners
                val touchesOwnRoad = own.any { other ->
                    other != road &&
                        other.endpoints().toList().intersect(setOf(a, b)).isNotEmpty()
                }
                assertTrue(touchesOwnBuilding || touchesOwnRoad) {
                    "${player.name} has an orphaned road at $road"
                }
            }
        }
    }

    private fun playOneGame(seed: Long, playerCount: Int): GameState {
        val random = Random(seed)
        var state = newGame(playerCount, seed)
        var steps = 0

        while (state.phase != GamePhase.GAME_OVER && steps < 20_000) {
            val choice = chooseAction(state, random) ?: break
            val (actor, action) = choice

            when (val result = GameEngine.apply(state, actor, action, random)) {
                is ActionResult.Success -> state = result.state
                is ActionResult.Rejected ->
                    throw AssertionError(
                        "seed $seed step $steps: bot played $action as $actor in " +
                            "${state.phase} but engine said: ${result.reason}",
                    )
            }
            steps++
            checkInvariants(state, "seed $seed step $steps ($action):")
        }
        assertRoadsConnected(state)
        return state
    }

    @Test
    fun `three player games run to a legal finish`() {
        var finished = 0
        repeat(60) { seed ->
            val end = playOneGame(seed.toLong(), 3)
            if (end.phase == GamePhase.GAME_OVER) {
                finished++
                val winner = end.winner!!
                assertTrue(end.victoryPoints(winner) >= Rules.VICTORY_POINTS_TO_WIN) {
                    "seed $seed declared a winner on ${end.victoryPoints(winner)} points"
                }
                // Nobody else may have been sitting on 10 points already.
                val others = end.players.filter { it.id != winner }
                assertTrue(others.all { end.victoryPoints(it.id) < Rules.VICTORY_POINTS_TO_WIN }) {
                    "seed $seed ended with two players on 10+"
                }
            }
        }
        assertTrue(finished >= 50) { "only $finished of 60 games reached a winner" }
    }

    @Test
    fun `four player games run to a legal finish`() {
        var finished = 0
        repeat(40) { seed ->
            val end = playOneGame(1000L + seed, 4)
            if (end.phase == GamePhase.GAME_OVER) finished++
        }
        assertTrue(finished >= 30) { "only $finished of 40 four-player games reached a winner" }
    }

    @Test
    fun `two player games run to a legal finish`() {
        repeat(20) { seed -> playOneGame(5000L + seed, 2) }
    }

    @Test
    fun `dice are fair over many rolls`() {
        val random = Random(99)
        val counts = IntArray(13)
        repeat(120_000) {
            counts[random.nextInt(1, 7) + random.nextInt(1, 7)]++
        }
        // 7 must be the mode, and 2 and 12 the rarest.
        assertEquals(7, counts.indices.maxByOrNull { counts[it] })
        assertTrue(counts[2] < counts[7] / 4)
        assertTrue(counts[12] < counts[7] / 4)
    }
}
