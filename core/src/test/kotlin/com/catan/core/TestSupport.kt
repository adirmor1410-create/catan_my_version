package com.catan.core

import com.catan.core.model.GamePhase
import com.catan.core.model.GameState
import com.catan.core.model.PlayerColor
import com.catan.core.model.PlayerId
import com.catan.core.model.Resource
import com.catan.core.model.ResourceCounts
import com.catan.core.model.emptyResources
import com.catan.core.model.minusCards
import com.catan.core.rules.ActionResult
import com.catan.core.rules.GameAction
import com.catan.core.rules.GameEngine
import com.catan.core.rules.Placement
import com.catan.core.rules.SetupRoad
import com.catan.core.rules.SetupSettlement
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertTrue

/** A [Random] whose integer draws are fixed in advance, so dice rolls can be scripted. */
class ScriptedRandom(values: List<Int>) : Random() {
    private val queue = ArrayDeque(values)
    private val fallback = Random(0)

    override fun nextBits(bitCount: Int): Int = fallback.nextBits(bitCount)

    override fun nextInt(from: Int, until: Int): Int {
        val next = queue.removeFirstOrNull() ?: return fallback.nextInt(from, until)
        return next.coerceIn(from, until - 1)
    }
}

/** Dice values that add up to [total], as the pair of draws [GameEngine] will make. */
fun diceFor(total: Int): List<Int> = when {
    total == 7 -> listOf(3, 4)
    total <= 7 -> listOf(1, total - 1)
    else -> listOf(6, total - 6)
}

fun newGame(playerCount: Int = 3, seed: Long = 42): GameState {
    val seats = listOf(
        GameEngine.Seat("Alice", PlayerColor.RED),
        GameEngine.Seat("Bob", PlayerColor.BLUE),
        GameEngine.Seat("Cara", PlayerColor.WHITE),
        GameEngine.Seat("Dan", PlayerColor.ORANGE),
    ).take(playerCount)
    return GameEngine.newGame(seats, Random(seed))
}

/** Runs the whole setup phase, always taking the lowest-ordered legal spot. */
fun completeSetup(start: GameState): GameState {
    var state = start
    var guardCounter = 0
    while (state.phase == GamePhase.SETUP_ROUND_1 || state.phase == GamePhase.SETUP_ROUND_2) {
        check(guardCounter++ < 100) { "Setup did not terminate" }
        val actor = state.currentPlayer.id
        val pending = state.setupSettlement
        val action: GameAction = if (pending == null) {
            SetupSettlement(Placement.setupSettlementSpots(state).sorted().first())
        } else {
            SetupRoad(Placement.setupRoadSpots(state, pending).sorted().first())
        }
        state = state.expectOk(actor, action)
    }
    return state
}

fun startedGame(playerCount: Int = 3, seed: Long = 42): GameState =
    completeSetup(newGame(playerCount, seed))

/** Applies [action] and fails the test if the engine rejects it. */
fun GameState.expectOk(
    actor: PlayerId,
    action: GameAction,
    random: Random = Random(7),
): GameState {
    return when (val result = GameEngine.apply(this, actor, action, random)) {
        is ActionResult.Success -> result.state
        is ActionResult.Rejected ->
            throw AssertionError("Expected $action to be legal, but: ${result.reason}")
    }
}

/** Applies [action] expecting a rejection, and returns the reason. */
fun GameState.expectRejected(
    actor: PlayerId,
    action: GameAction,
    random: Random = Random(7),
): String {
    return when (val result = GameEngine.apply(this, actor, action, random)) {
        is ActionResult.Success ->
            throw AssertionError("Expected $action to be rejected, but it succeeded")
        is ActionResult.Rejected -> result.reason
    }
}

/** Overwrites a player's hand, moving the difference in or out of the bank so totals stay right. */
fun GameState.giveExactly(id: PlayerId, vararg cards: Pair<Resource, Int>): GameState {
    val hand: ResourceCounts = emptyResources() + cards.toMap()
    val delta = hand.minusCards(player(id).resources)
    return updatePlayer(id) { it.copy(resources = hand) }.copy(bank = bank.minusCards(delta))
}

/**
 * Every resource card must always be somewhere: in the bank or in a hand. Nineteen of each,
 * always.
 */
fun assertCardsConserved(state: GameState, note: String = "") {
    for (resource in Resource.entries) {
        val inHands = state.players.sumOf { it.resources[resource] ?: 0 }
        val inBank = state.bank[resource] ?: 0
        assertTrue(inHands + inBank == 19) {
            "$note ${resource.name}: $inHands in hands + $inBank in bank != 19"
        }
        assertTrue(inBank >= 0) { "$note bank went negative for ${resource.name}" }
        assertTrue(state.players.all { (it.resources[resource] ?: 0) >= 0 }) {
            "$note a player has a negative ${resource.name} count"
        }
    }
}
