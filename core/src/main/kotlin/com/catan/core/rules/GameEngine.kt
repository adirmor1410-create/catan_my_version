package com.catan.core.rules

import com.catan.core.model.Board
import com.catan.core.model.Building
import com.catan.core.model.BuildingType
import com.catan.core.model.Costs
import com.catan.core.model.DevCardType
import com.catan.core.model.GamePhase
import com.catan.core.model.GameState
import com.catan.core.model.Hex
import com.catan.core.model.HexDirection
import com.catan.core.model.OwnedDevCard
import com.catan.core.model.PlayerColor
import com.catan.core.model.PlayerId
import com.catan.core.model.PlayerState
import com.catan.core.model.Resource
import com.catan.core.model.ResourceCounts
import com.catan.core.model.Rules
import com.catan.core.model.TradeOffer
import com.catan.core.model.VertexId
import com.catan.core.model.covers
import com.catan.core.model.emptyResources
import com.catan.core.model.endpoints
import com.catan.core.model.minusCards
import com.catan.core.model.plusCards
import com.catan.core.model.toCardList
import com.catan.core.model.toCounts
import com.catan.core.model.total
import kotlin.random.Random

/**
 * The single authority on what may happen in a game.
 *
 * Every rule lives here. The server runs this to validate and advance games; the Android client
 * runs the same code to grey out illegal buttons, so the two can never disagree.
 */
object GameEngine {

    data class Seat(val name: String, val color: PlayerColor)

    fun newGame(seats: List<Seat>, random: Random = Random.Default): GameState {
        require(seats.size in 2..4) { "Catan is for 2 to 4 players, got ${seats.size}" }
        require(seats.map { it.color }.distinct().size == seats.size) { "Duplicate player colours" }

        val players = seats.mapIndexed { i, seat ->
            PlayerState(id = PlayerId(i), name = seat.name, color = seat.color)
        }
        return GameState(
            board = Board.random(random),
            players = players,
            devDeck = Rules.DEV_DECK.shuffled(random),
            phase = GamePhase.SETUP_ROUND_1,
        ).withLog("Game started with ${seats.size} players. ${players[0].name} places first.")
    }

    fun apply(
        state: GameState,
        actor: PlayerId,
        action: GameAction,
        random: Random = Random.Default,
    ): ActionResult {
        if (state.phase == GamePhase.GAME_OVER) {
            return ActionResult.Rejected("The game is over.")
        }
        if (state.playerOrNull(actor) == null) {
            return ActionResult.Rejected("Unknown player.")
        }

        val result = when (action) {
            is SetupSettlement -> setupSettlement(state, actor, action)
            is SetupRoad -> setupRoad(state, actor, action)
            is RollDice -> rollDice(state, actor, random)
            is BuildRoad -> buildRoad(state, actor, action)
            is BuildSettlement -> buildSettlement(state, actor, action)
            is BuildCity -> buildCity(state, actor, action)
            is BuyDevCard -> buyDevCard(state, actor)
            is PlayKnight -> playKnight(state, actor)
            is PlayRoadBuilding -> playRoadBuilding(state, actor)
            is PlayYearOfPlenty -> playYearOfPlenty(state, actor, action)
            is PlayMonopoly -> playMonopoly(state, actor, action)
            is BankTrade -> bankTrade(state, actor, action)
            is OfferTrade -> offerTrade(state, actor, action)
            is RespondToTrade -> respondToTrade(state, actor, action)
            is ConfirmTrade -> confirmTrade(state, actor, action)
            is CancelTrade -> cancelTrade(state, actor)
            is Discard -> discard(state, actor, action)
            is MoveRobber -> moveRobber(state, actor, action, random)
            is StealFrom -> stealFrom(state, actor, action, random)
            is EndTurn -> endTurn(state, actor)
        }

        return when (result) {
            is ActionResult.Rejected -> result
            is ActionResult.Success -> ActionResult.Success(checkForWinner(result.state))
        }
    }

    // ---------------------------------------------------------------- guards

    private inline fun guard(condition: Boolean, reason: () -> String): ActionResult.Rejected? =
        if (condition) null else ActionResult.Rejected(reason())

    private fun requireTurn(state: GameState, actor: PlayerId): ActionResult.Rejected? =
        guard(state.isCurrent(actor)) { "It is not your turn." }

    private fun requirePhase(state: GameState, vararg phases: GamePhase): ActionResult.Rejected? =
        guard(state.phase in phases) { "You cannot do that right now (${state.phase})." }

    /** Free roads owed by a Road Building card must be placed before anything else. */
    private fun requireNoPendingFreeRoads(state: GameState): ActionResult.Rejected? =
        guard(state.freeRoadsRemaining == 0) { "Place your remaining free road first." }

    // ---------------------------------------------------------------- setup

    private fun setupSettlement(
        state: GameState,
        actor: PlayerId,
        action: SetupSettlement,
    ): ActionResult {
        requireTurn(state, actor)?.let { return it }
        requirePhase(state, GamePhase.SETUP_ROUND_1, GamePhase.SETUP_ROUND_2)?.let { return it }
        guard(state.setupSettlement == null) { "Place your road before another settlement." }
            ?.let { return it }
        guard(action.vertex in state.board.vertices) { "That corner is not on the board." }
            ?.let { return it }
        guard(Placement.satisfiesDistanceRule(state, action.vertex)) {
            "Too close to another settlement."
        }?.let { return it }

        val player = state.player(actor)
        guard(player.settlementsLeft > 0) { "No settlements left." }?.let { return it }

        var next = state
            .copy(
                buildings = state.buildings +
                    (action.vertex to Building(BuildingType.SETTLEMENT, actor)),
                setupSettlement = action.vertex,
            )
            .updatePlayer(actor) { it.copy(settlementsLeft = it.settlementsLeft - 1) }
            .withLog("${player.name} places a settlement.")

        // The second settlement pays out its surrounding tiles immediately.
        if (state.phase == GamePhase.SETUP_ROUND_2) {
            val gains = next.board.tilesAround(action.vertex)
                .mapNotNull { it.type.resource }
                .toCounts()
            next = next
                .updatePlayer(actor) { it.copy(resources = it.resources.plusCards(gains)) }
                .copy(bank = next.bank.minusCards(gains))
            if (gains.total > 0) {
                next = next.withLog("${player.name} collects ${describe(gains)}.")
            }
        }
        return ActionResult.Success(next)
    }

    private fun setupRoad(state: GameState, actor: PlayerId, action: SetupRoad): ActionResult {
        requireTurn(state, actor)?.let { return it }
        requirePhase(state, GamePhase.SETUP_ROUND_1, GamePhase.SETUP_ROUND_2)?.let { return it }

        val settlement = state.setupSettlement
            ?: return ActionResult.Rejected("Place your settlement first.")
        guard(action.edge in Placement.setupRoadSpots(state, settlement)) {
            "That road must start at the settlement you just placed."
        }?.let { return it }

        val player = state.player(actor)
        guard(player.roadsLeft > 0) { "No roads left." }?.let { return it }

        val placed = state
            .copy(roads = state.roads + (action.edge to actor), setupSettlement = null)
            .updatePlayer(actor) { it.copy(roadsLeft = it.roadsLeft - 1) }
            .withLog("${player.name} places a road.")

        return ActionResult.Success(RoadLength.recalculate(advanceSetup(placed)))
    }

    /** Moves setup along its snake order, and starts the real game once it finishes. */
    private fun advanceSetup(state: GameState): GameState {
        val last = state.players.lastIndex
        return when (state.phase) {
            GamePhase.SETUP_ROUND_1 ->
                if (state.currentPlayerIndex < last) {
                    state.copy(currentPlayerIndex = state.currentPlayerIndex + 1)
                } else {
                    // The last player places again immediately, so the seat does not move.
                    state.copy(phase = GamePhase.SETUP_ROUND_2)
                        .withLog("Second placement round begins.")
                }

            GamePhase.SETUP_ROUND_2 ->
                if (state.currentPlayerIndex > 0) {
                    state.copy(currentPlayerIndex = state.currentPlayerIndex - 1)
                } else {
                    state.copy(
                        phase = GamePhase.ROLL,
                        currentPlayerIndex = 0,
                        turnNumber = 1,
                        hasRolled = false,
                    ).withLog("Setup complete. ${state.players[0].name} to roll.")
                }

            else -> state
        }
    }

    // ---------------------------------------------------------------- dice

    private fun rollDice(state: GameState, actor: PlayerId, random: Random): ActionResult {
        requireTurn(state, actor)?.let { return it }
        requirePhase(state, GamePhase.ROLL)?.let { return it }

        val die1 = random.nextInt(1, 7)
        val die2 = random.nextInt(1, 7)
        val roll = die1 + die2

        val rolled = state
            .copy(lastRoll = die1 to die2, hasRolled = true)
            .withLog("${state.player(actor).name} rolls $roll.")

        if (roll == 7) {
            val owing = rolled.players
                .filter { it.handSize > Rules.HAND_LIMIT_ON_SEVEN }
                .map { it.id }
                .toSet()

            return ActionResult.Success(
                if (owing.isEmpty()) {
                    rolled.copy(phase = GamePhase.MOVE_ROBBER)
                        .withLog("Move the robber.")
                } else {
                    rolled.copy(phase = GamePhase.DISCARD, pendingDiscards = owing)
                        .withLog("Players over 7 cards must discard half.")
                },
            )
        }

        return ActionResult.Success(produce(rolled, roll).copy(phase = GamePhase.MAIN))
    }

    /**
     * Pays out every settlement and city next to a tile showing [roll].
     *
     * When the bank cannot cover the demand for a resource, the official shortage rule applies:
     * a single claimant takes whatever is left, but if several players are owed that resource,
     * none of them receive any.
     */
    internal fun produce(state: GameState, roll: Int): GameState {
        val demand = mutableMapOf<PlayerId, MutableMap<Resource, Int>>()

        for (tile in state.board.tiles) {
            if (tile.number != roll) continue
            if (tile.hex == state.board.robber) continue
            val resource = tile.type.resource ?: continue

            for (direction in HexDirection.entries) {
                val corner = VertexId.corner(tile.hex, direction)
                val building = state.buildings[corner] ?: continue
                val amount = if (building.type == BuildingType.CITY) 2 else 1
                demand.getOrPut(building.owner) { mutableMapOf() }
                    .merge(resource, amount, Int::plus)
            }
        }
        if (demand.isEmpty()) return state.withLog("No resources produced.")

        var bank = state.bank
        val granted = mutableMapOf<PlayerId, ResourceCounts>()

        for (resource in Resource.entries) {
            val claimants = demand.filterValues { (it[resource] ?: 0) > 0 }
            if (claimants.isEmpty()) continue

            val available = bank[resource] ?: 0
            val requested = claimants.values.sumOf { it[resource] ?: 0 }

            val payouts: Map<PlayerId, Int> = when {
                requested <= available -> claimants.mapValues { it.value[resource] ?: 0 }
                claimants.size == 1 -> mapOf(claimants.keys.first() to available)
                else -> emptyMap() // shortage with several claimants: nobody is paid
            }

            for ((playerId, amount) in payouts) {
                if (amount <= 0) continue
                granted[playerId] = (granted[playerId] ?: emptyResources()) +
                    mapOf(resource to amount)
                bank = bank.plusCards(mapOf(resource to -amount))
            }
        }

        var next = state.copy(bank = bank)
        for ((playerId, gain) in granted) {
            next = next.updatePlayer(playerId) { it.copy(resources = it.resources.plusCards(gain)) }
        }

        val summary = granted.entries
            .filter { it.value.total > 0 }
            .joinToString("; ") { "${state.player(it.key).name} +${describe(it.value)}" }
        return if (summary.isEmpty()) {
            next.withLog("No resources produced.")
        } else {
            next.withLog(summary)
        }
    }

    // ---------------------------------------------------------------- discard

    private fun discard(state: GameState, actor: PlayerId, action: Discard): ActionResult {
        requirePhase(state, GamePhase.DISCARD)?.let { return it }
        guard(actor in state.pendingDiscards) { "You do not need to discard." }?.let { return it }

        val player = state.player(actor)
        val required = player.handSize / 2
        val offered = action.resources

        guard(offered.values.all { it >= 0 }) { "Negative discard." }?.let { return it }
        guard(offered.total == required) { "You must discard exactly $required cards." }
            ?.let { return it }
        guard(player.resources.covers(offered)) { "You do not hold those cards." }
            ?.let { return it }

        val afterDiscard = state
            .updatePlayer(actor) { it.copy(resources = it.resources.minusCards(offered)) }
            .copy(
                bank = state.bank.plusCards(offered),
                pendingDiscards = state.pendingDiscards - actor,
            )
            .withLog("${player.name} discards $required cards.")

        return ActionResult.Success(
            if (afterDiscard.pendingDiscards.isEmpty()) {
                afterDiscard.copy(phase = GamePhase.MOVE_ROBBER).withLog("Move the robber.")
            } else {
                afterDiscard
            },
        )
    }

    // ---------------------------------------------------------------- robber

    private fun moveRobber(
        state: GameState,
        actor: PlayerId,
        action: MoveRobber,
        random: Random,
    ): ActionResult {
        requireTurn(state, actor)?.let { return it }
        requirePhase(state, GamePhase.MOVE_ROBBER)?.let { return it }
        guard(action.to in state.board.hexes) { "That tile is not on the board." }?.let { return it }
        guard(action.to != state.board.robber) { "The robber must move to a different tile." }
            ?.let { return it }

        val moved = state
            .copy(board = state.board.copy(robber = action.to))
            .withLog("${state.player(actor).name} moves the robber.")

        val victims = victimsAt(moved, action.to, actor)

        return ActionResult.Success(
            when (victims.size) {
                0 -> resumeAfterRobber(moved).withLog("Nobody to steal from.")
                1 -> stealResource(moved, actor, victims.first(), random)
                else -> moved.copy(phase = GamePhase.STEAL, stealCandidates = victims)
            },
        )
    }

    /** Players other than [actor] holding cards and building on a corner of [hex]. */
    private fun victimsAt(state: GameState, hex: Hex, actor: PlayerId): Set<PlayerId> =
        HexDirection.entries
            .mapNotNull { state.buildings[VertexId.corner(hex, it)] }
            .map { it.owner }
            .filter { it != actor && state.player(it).handSize > 0 }
            .toSet()

    private fun stealFrom(
        state: GameState,
        actor: PlayerId,
        action: StealFrom,
        random: Random,
    ): ActionResult {
        requireTurn(state, actor)?.let { return it }
        requirePhase(state, GamePhase.STEAL)?.let { return it }
        guard(action.victim in state.stealCandidates) { "You cannot steal from that player." }
            ?.let { return it }

        return ActionResult.Success(stealResource(state, actor, action.victim, random))
    }

    private fun stealResource(
        state: GameState,
        thief: PlayerId,
        victim: PlayerId,
        random: Random,
    ): GameState {
        val hand = state.player(victim).resources.toCardList()
        if (hand.isEmpty()) return resumeAfterRobber(state)

        val card = hand[random.nextInt(hand.size)]
        val moved = state
            .updatePlayer(victim) { it.copy(resources = it.resources.plusCards(mapOf(card to -1))) }
            .updatePlayer(thief) { it.copy(resources = it.resources.plusCards(mapOf(card to 1))) }
            .withLog("${state.player(thief).name} steals a card from ${state.player(victim).name}.")

        return resumeAfterRobber(moved)
    }

    /** Returns to whatever the turn was doing before the robber interrupted it. */
    private fun resumeAfterRobber(state: GameState): GameState =
        state.copy(
            phase = if (state.hasRolled) GamePhase.MAIN else GamePhase.ROLL,
            stealCandidates = emptySet(),
        )

    // ---------------------------------------------------------------- building

    private fun buildRoad(state: GameState, actor: PlayerId, action: BuildRoad): ActionResult {
        requireTurn(state, actor)?.let { return it }
        requirePhase(state, GamePhase.MAIN)?.let { return it }

        val player = state.player(actor)
        guard(player.roadsLeft > 0) { "No roads left." }?.let { return it }
        guard(action.edge in Placement.roadSpots(state, actor)) {
            "A road must connect to your own road, settlement or city."
        }?.let { return it }

        val free = state.freeRoadsRemaining > 0
        if (!free) {
            guard(player.resources.covers(Costs.ROAD)) { "You cannot afford a road." }
                ?.let { return it }
        }

        var next = state
            .copy(roads = state.roads + (action.edge to actor))
            .updatePlayer(actor) { it.copy(roadsLeft = it.roadsLeft - 1) }
            .withLog("${player.name} builds a road.")

        next = if (free) {
            next.copy(freeRoadsRemaining = next.freeRoadsRemaining - 1)
        } else {
            next.updatePlayer(actor) { it.copy(resources = it.resources.minusCards(Costs.ROAD)) }
                .copy(bank = next.bank.plusCards(Costs.ROAD))
        }

        return ActionResult.Success(RoadLength.recalculate(clearFreeRoadsIfStuck(next, actor)))
    }

    /** A Road Building card is spent even if the board leaves nowhere legal to put the roads. */
    private fun clearFreeRoadsIfStuck(state: GameState, actor: PlayerId): GameState {
        if (state.freeRoadsRemaining == 0) return state
        val stuck = state.player(actor).roadsLeft == 0 ||
            Placement.roadSpots(state, actor).isEmpty()
        return if (stuck) state.copy(freeRoadsRemaining = 0) else state
    }

    private fun buildSettlement(
        state: GameState,
        actor: PlayerId,
        action: BuildSettlement,
    ): ActionResult {
        requireTurn(state, actor)?.let { return it }
        requirePhase(state, GamePhase.MAIN)?.let { return it }
        requireNoPendingFreeRoads(state)?.let { return it }

        val player = state.player(actor)
        guard(player.settlementsLeft > 0) { "No settlements left." }?.let { return it }
        guard(action.vertex in Placement.settlementSpots(state, actor)) {
            "A settlement needs your own road and two clear corners around it."
        }?.let { return it }
        guard(player.resources.covers(Costs.SETTLEMENT)) { "You cannot afford a settlement." }
            ?.let { return it }

        val next = state
            .copy(
                buildings = state.buildings +
                    (action.vertex to Building(BuildingType.SETTLEMENT, actor)),
                bank = state.bank.plusCards(Costs.SETTLEMENT),
            )
            .updatePlayer(actor) {
                it.copy(
                    settlementsLeft = it.settlementsLeft - 1,
                    resources = it.resources.minusCards(Costs.SETTLEMENT),
                )
            }
            .withLog("${player.name} builds a settlement.")

        // A new settlement can cut an opponent's road, so this must be re-evaluated.
        return ActionResult.Success(RoadLength.recalculate(next))
    }

    private fun buildCity(state: GameState, actor: PlayerId, action: BuildCity): ActionResult {
        requireTurn(state, actor)?.let { return it }
        requirePhase(state, GamePhase.MAIN)?.let { return it }
        requireNoPendingFreeRoads(state)?.let { return it }

        val player = state.player(actor)
        guard(player.citiesLeft > 0) { "No cities left." }?.let { return it }
        guard(action.vertex in Placement.citySpots(state, actor)) {
            "You can only upgrade your own settlement."
        }?.let { return it }
        guard(player.resources.covers(Costs.CITY)) { "You cannot afford a city." }?.let { return it }

        val next = state
            .copy(
                buildings = state.buildings + (action.vertex to Building(BuildingType.CITY, actor)),
                bank = state.bank.plusCards(Costs.CITY),
            )
            .updatePlayer(actor) {
                it.copy(
                    citiesLeft = it.citiesLeft - 1,
                    settlementsLeft = it.settlementsLeft + 1, // the settlement returns to supply
                    resources = it.resources.minusCards(Costs.CITY),
                )
            }
            .withLog("${player.name} builds a city.")

        return ActionResult.Success(next)
    }

    // ---------------------------------------------------------------- dev cards

    private fun buyDevCard(state: GameState, actor: PlayerId): ActionResult {
        requireTurn(state, actor)?.let { return it }
        requirePhase(state, GamePhase.MAIN)?.let { return it }
        requireNoPendingFreeRoads(state)?.let { return it }

        guard(state.devDeck.isNotEmpty()) { "The development deck is empty." }?.let { return it }

        val player = state.player(actor)
        guard(player.resources.covers(Costs.DEV_CARD)) { "You cannot afford a development card." }
            ?.let { return it }

        val card = state.devDeck.first()
        val next = state
            .copy(devDeck = state.devDeck.drop(1), bank = state.bank.plusCards(Costs.DEV_CARD))
            .updatePlayer(actor) {
                it.copy(
                    resources = it.resources.minusCards(Costs.DEV_CARD),
                    devCards = it.devCards + OwnedDevCard(card, state.turnNumber),
                )
            }
            .withLog("${player.name} buys a development card.")

        return ActionResult.Success(next)
    }

    /**
     * Finds a playable card of [type]: not already played, and not bought this turn.
     * Also enforces the one-card-per-turn limit.
     */
    private fun takeDevCard(
        state: GameState,
        actor: PlayerId,
        type: DevCardType,
    ): Result<GameState> {
        val player = state.player(actor)
        if (player.playedDevCardThisTurn) {
            return Result.failure(IllegalStateException("Only one development card per turn."))
        }
        val index = player.devCards.indexOfFirst {
            it.type == type && !it.played && it.boughtOnTurn < state.turnNumber
        }
        if (index < 0) {
            val heldButFresh = player.devCards.any {
                it.type == type && !it.played && it.boughtOnTurn >= state.turnNumber
            }
            val reason = if (heldButFresh) {
                "You cannot play a card on the turn you bought it."
            } else {
                "You have no ${type.name.lowercase().replace('_', ' ')} card."
            }
            return Result.failure(IllegalStateException(reason))
        }

        val updated = player.devCards.toMutableList()
        updated[index] = updated[index].copy(played = true)
        return Result.success(
            state.updatePlayer(actor) { it.copy(devCards = updated, playedDevCardThisTurn = true) },
        )
    }

    private inline fun withDevCard(
        state: GameState,
        actor: PlayerId,
        type: DevCardType,
        body: (GameState) -> ActionResult,
    ): ActionResult {
        requireTurn(state, actor)?.let { return it }
        requirePhase(state, GamePhase.ROLL, GamePhase.MAIN)?.let { return it }
        requireNoPendingFreeRoads(state)?.let { return it }
        val taken = takeDevCard(state, actor, type)
        val next = taken.getOrElse {
            return ActionResult.Rejected(it.message ?: "You cannot play that card.")
        }
        return body(next)
    }

    private fun playKnight(state: GameState, actor: PlayerId): ActionResult =
        withDevCard(state, actor, DevCardType.KNIGHT) { afterCard ->
            val next = afterCard
                .updatePlayer(actor) { it.copy(knightsPlayed = it.knightsPlayed + 1) }
                .copy(phase = GamePhase.MOVE_ROBBER)
                .withLog("${state.player(actor).name} plays a Knight.")

            ActionResult.Success(RoadLength.recalculateArmy(next))
        }

    private fun playRoadBuilding(state: GameState, actor: PlayerId): ActionResult =
        withDevCard(state, actor, DevCardType.ROAD_BUILDING) { afterCard ->
            // Road Building is a build action, so the dice must already be rolled.
            if (afterCard.phase != GamePhase.MAIN) {
                return@withDevCard ActionResult.Rejected("Roll the dice first.")
            }
            val allowance = minOf(2, state.player(actor).roadsLeft)
            val next = afterCard
                .copy(freeRoadsRemaining = allowance)
                .withLog("${state.player(actor).name} plays Road Building.")

            ActionResult.Success(clearFreeRoadsIfStuck(next, actor))
        }

    private fun playYearOfPlenty(
        state: GameState,
        actor: PlayerId,
        action: PlayYearOfPlenty,
    ): ActionResult =
        withDevCard(state, actor, DevCardType.YEAR_OF_PLENTY) { afterCard ->
            val wanted = listOf(action.first, action.second).toCounts()
            if (!afterCard.bank.covers(wanted)) {
                return@withDevCard ActionResult.Rejected("The bank cannot supply those two cards.")
            }
            val next = afterCard
                .copy(bank = afterCard.bank.minusCards(wanted))
                .updatePlayer(actor) { it.copy(resources = it.resources.plusCards(wanted)) }
                .withLog("${state.player(actor).name} plays Year of Plenty: ${describe(wanted)}.")

            ActionResult.Success(next)
        }

    private fun playMonopoly(
        state: GameState,
        actor: PlayerId,
        action: PlayMonopoly,
    ): ActionResult =
        withDevCard(state, actor, DevCardType.MONOPOLY) { afterCard ->
            var next = afterCard
            var taken = 0
            for (victim in afterCard.players) {
                if (victim.id == actor) continue
                val held = victim.resources[action.resource] ?: 0
                if (held == 0) continue
                taken += held
                next = next.updatePlayer(victim.id) {
                    it.copy(resources = it.resources.plusCards(mapOf(action.resource to -held)))
                }
            }
            next = next.updatePlayer(actor) {
                it.copy(resources = it.resources.plusCards(mapOf(action.resource to taken)))
            }
            ActionResult.Success(
                next.withLog(
                    "${state.player(actor).name} plays Monopoly on " +
                        "${action.resource.name.lowercase()} and takes $taken.",
                ),
            )
        }

    // ---------------------------------------------------------------- trading

    private fun bankTrade(state: GameState, actor: PlayerId, action: BankTrade): ActionResult {
        requireTurn(state, actor)?.let { return it }
        requirePhase(state, GamePhase.MAIN)?.let { return it }
        requireNoPendingFreeRoads(state)?.let { return it }
        guard(action.give != action.receive) { "Pick two different resources." }?.let { return it }

        val rate = state.tradeRatio(actor, action.give)
        val player = state.player(actor)
        guard((player.resources[action.give] ?: 0) >= rate) {
            "You need $rate ${action.give.name.lowercase()} for that trade."
        }?.let { return it }
        guard((state.bank[action.receive] ?: 0) >= 1) { "The bank is out of that resource." }
            ?.let { return it }

        val next = state
            .updatePlayer(actor) {
                it.copy(
                    resources = it.resources.plusCards(
                        mapOf(action.give to -rate, action.receive to 1),
                    ),
                )
            }
            .copy(bank = state.bank.plusCards(mapOf(action.give to rate, action.receive to -1)))
            .withLog(
                "${player.name} trades $rate ${action.give.name.lowercase()} " +
                    "for 1 ${action.receive.name.lowercase()}.",
            )

        return ActionResult.Success(next)
    }

    private fun offerTrade(state: GameState, actor: PlayerId, action: OfferTrade): ActionResult {
        requireTurn(state, actor)?.let { return it }
        requirePhase(state, GamePhase.MAIN)?.let { return it }
        requireNoPendingFreeRoads(state)?.let { return it }
        guard(state.openTrade == null) { "You already have an offer open." }?.let { return it }
        guard(state.players.size > 1) { "Nobody to trade with." }?.let { return it }

        val give = action.give.filterValues { it > 0 }
        val receive = action.receive.filterValues { it > 0 }
        guard(give.values.all { it >= 0 } && receive.values.all { it >= 0 }) {
            "Negative amounts."
        }?.let { return it }
        guard(give.isNotEmpty() && receive.isNotEmpty()) { "Offer must go both ways." }
            ?.let { return it }
        guard(give.keys.none { it in receive.keys }) {
            "You cannot trade a resource for the same resource."
        }?.let { return it }
        guard(state.player(actor).resources.covers(give)) { "You do not hold what you offered." }
            ?.let { return it }

        return ActionResult.Success(
            state
                .copy(openTrade = TradeOffer(from = actor, give = give, receive = receive))
                .withLog(
                    "${state.player(actor).name} offers ${describe(give)} " +
                        "for ${describe(receive)}.",
                ),
        )
    }

    private fun respondToTrade(
        state: GameState,
        actor: PlayerId,
        action: RespondToTrade,
    ): ActionResult {
        val offer = state.openTrade ?: return ActionResult.Rejected("There is no open offer.")
        guard(actor != offer.from) { "You cannot respond to your own offer." }?.let { return it }

        // Only a player who can actually pay may accept.
        if (action.accept) {
            guard(state.player(actor).resources.covers(offer.receive)) {
                "You do not hold what was asked for."
            }?.let { return it }
        }

        val updated = if (action.accept) {
            offer.copy(
                acceptedBy = offer.acceptedBy + actor,
                rejectedBy = offer.rejectedBy - actor,
            )
        } else {
            offer.copy(
                rejectedBy = offer.rejectedBy + actor,
                acceptedBy = offer.acceptedBy - actor,
            )
        }
        return ActionResult.Success(state.copy(openTrade = updated))
    }

    private fun confirmTrade(
        state: GameState,
        actor: PlayerId,
        action: ConfirmTrade,
    ): ActionResult {
        requireTurn(state, actor)?.let { return it }
        val offer = state.openTrade ?: return ActionResult.Rejected("There is no open offer.")
        guard(offer.from == actor) { "Only the offering player can close a trade." }
            ?.let { return it }
        guard(action.with in offer.acceptedBy) { "That player has not accepted." }?.let { return it }

        val partner = state.player(action.with)
        guard(partner.resources.covers(offer.receive)) { "That player can no longer pay." }
            ?.let { return it }
        guard(state.player(actor).resources.covers(offer.give)) { "You can no longer pay." }
            ?.let { return it }

        val next = state
            .updatePlayer(actor) {
                it.copy(resources = it.resources.minusCards(offer.give).plusCards(offer.receive))
            }
            .updatePlayer(action.with) {
                it.copy(resources = it.resources.minusCards(offer.receive).plusCards(offer.give))
            }
            .copy(openTrade = null)
            .withLog("${state.player(actor).name} trades with ${partner.name}.")

        return ActionResult.Success(next)
    }

    private fun cancelTrade(state: GameState, actor: PlayerId): ActionResult {
        val offer = state.openTrade ?: return ActionResult.Rejected("There is no open offer.")
        guard(offer.from == actor) { "Only the offering player can withdraw it." }?.let { return it }
        return ActionResult.Success(state.copy(openTrade = null).withLog("Trade withdrawn."))
    }

    // ---------------------------------------------------------------- turn

    private fun endTurn(state: GameState, actor: PlayerId): ActionResult {
        requireTurn(state, actor)?.let { return it }
        requirePhase(state, GamePhase.MAIN)?.let { return it }
        requireNoPendingFreeRoads(state)?.let { return it }

        val nextIndex = (state.currentPlayerIndex + 1) % state.players.size
        val next = state
            .updatePlayer(actor) { it.copy(playedDevCardThisTurn = false) }
            .copy(
                currentPlayerIndex = nextIndex,
                phase = GamePhase.ROLL,
                turnNumber = state.turnNumber + 1,
                hasRolled = false,
                lastRoll = null,
                openTrade = null,
                freeRoadsRemaining = 0,
            )
            .withLog("${state.players[nextIndex].name}'s turn.")

        return ActionResult.Success(next)
    }

    /**
     * A player wins the moment they reach ten points on their own turn. Points gained while
     * another player is acting (a broken Longest Road, say) wait until their next turn.
     */
    private fun checkForWinner(state: GameState): GameState {
        if (state.phase == GamePhase.GAME_OVER || state.winner != null) return state
        if (state.turnNumber == 0) return state

        val current = state.currentPlayer
        if (state.victoryPoints(current.id) < Rules.VICTORY_POINTS_TO_WIN) return state

        return state
            .copy(winner = current.id, phase = GamePhase.GAME_OVER)
            .withLog("${current.name} wins with ${state.victoryPoints(current.id)} points!")
    }

    private fun describe(counts: ResourceCounts): String =
        counts.entries
            .filter { it.value > 0 }
            .joinToString(", ") { "${it.value} ${it.key.name.lowercase()}" }
            .ifEmpty { "nothing" }
}
