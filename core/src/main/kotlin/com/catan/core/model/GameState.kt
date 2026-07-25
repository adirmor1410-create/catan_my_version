package com.catan.core.model

import kotlinx.serialization.Serializable

typealias ResourceCounts = Map<Resource, Int>

fun emptyResources(): ResourceCounts = Resource.entries.associateWith { 0 }

fun resourcesOf(vararg pairs: Pair<Resource, Int>): ResourceCounts =
    emptyResources() + pairs.toMap()

val ResourceCounts.total: Int get() = values.sum()

/**
 * Adds two card piles together.
 *
 * Deliberately not named `plus`: an operator overload on `Map<Resource, Int>` competes with the
 * standard library's map merge, which *overwrites* values instead of summing them. Any file that
 * forgot the import would silently get the wrong arithmetic. A distinct name turns that into a
 * compile error.
 */
fun ResourceCounts.plusCards(other: ResourceCounts): ResourceCounts =
    Resource.entries.associateWith { (this[it] ?: 0) + (other[it] ?: 0) }

/** Subtracts one card pile from another. See [plusCards] for why it is not named `minus`. */
fun ResourceCounts.minusCards(other: ResourceCounts): ResourceCounts =
    Resource.entries.associateWith { (this[it] ?: 0) - (other[it] ?: 0) }

/** True when this holding covers every resource in [cost]. */
fun ResourceCounts.covers(cost: ResourceCounts): Boolean =
    Resource.entries.all { (this[it] ?: 0) >= (cost[it] ?: 0) }

fun ResourceCounts.isNotNegative(): Boolean = values.all { it >= 0 }

/** Expands a count map into one entry per card, e.g. {WOOL:2} -> [WOOL, WOOL]. */
fun ResourceCounts.toCardList(): List<Resource> =
    entries.flatMap { (r, n) -> List(maxOf(0, n)) { r } }

fun List<Resource>.toCounts(): ResourceCounts =
    emptyResources() + groupingBy { it }.eachCount()

/** The official build costs. */
object Costs {
    val ROAD: ResourceCounts = resourcesOf(Resource.BRICK to 1, Resource.LUMBER to 1)

    val SETTLEMENT: ResourceCounts = resourcesOf(
        Resource.BRICK to 1,
        Resource.LUMBER to 1,
        Resource.WOOL to 1,
        Resource.GRAIN to 1,
    )

    val CITY: ResourceCounts = resourcesOf(Resource.GRAIN to 2, Resource.ORE to 3)

    val DEV_CARD: ResourceCounts = resourcesOf(
        Resource.WOOL to 1,
        Resource.GRAIN to 1,
        Resource.ORE to 1,
    )
}

object Rules {
    const val VICTORY_POINTS_TO_WIN = 10
    const val MAX_ROADS = 15
    const val MAX_SETTLEMENTS = 5
    const val MAX_CITIES = 4
    const val BANK_PER_RESOURCE = 19
    const val HAND_LIMIT_ON_SEVEN = 7
    const val MIN_LONGEST_ROAD = 5
    const val MIN_LARGEST_ARMY = 3

    /** The 25-card development deck. */
    val DEV_DECK: List<DevCardType> = buildList {
        repeat(14) { add(DevCardType.KNIGHT) }
        repeat(5) { add(DevCardType.VICTORY_POINT) }
        repeat(2) { add(DevCardType.ROAD_BUILDING) }
        repeat(2) { add(DevCardType.MONOPOLY) }
        repeat(2) { add(DevCardType.YEAR_OF_PLENTY) }
    }
}

@Serializable
data class OwnedDevCard(
    val type: DevCardType,
    /** Turn index on which it was bought; it cannot be played on that turn. */
    val boughtOnTurn: Int,
    val played: Boolean = false,
)

@Serializable
data class PlayerState(
    val id: PlayerId,
    val name: String,
    val color: PlayerColor,
    val resources: ResourceCounts = emptyResources(),
    val devCards: List<OwnedDevCard> = emptyList(),
    val knightsPlayed: Int = 0,
    val roadsLeft: Int = Rules.MAX_ROADS,
    val settlementsLeft: Int = Rules.MAX_SETTLEMENTS,
    val citiesLeft: Int = Rules.MAX_CITIES,
    val playedDevCardThisTurn: Boolean = false,
) {
    val handSize: Int get() = resources.total

    val unplayedDevCards: List<OwnedDevCard> get() = devCards.filter { !it.played }

    val victoryPointCards: Int
        get() = devCards.count { it.type == DevCardType.VICTORY_POINT }
}

/**
 * A trade one player has offered to the others. Only one may be open at a time, and only
 * during the offering player's own turn.
 */
@Serializable
data class TradeOffer(
    val from: PlayerId,
    val give: ResourceCounts,
    val receive: ResourceCounts,
    /** Players who have said yes. The offering player chooses which one to complete with. */
    val acceptedBy: Set<PlayerId> = emptySet(),
    val rejectedBy: Set<PlayerId> = emptySet(),
)

/**
 * A complete, authoritative snapshot of a game.
 *
 * Every field is public and serializable so the server can persist or replay a game, but the
 * client is only ever sent a redacted view (see `PlayerView`) that hides other players' hands.
 */
@Serializable
data class GameState(
    val board: Board,
    val players: List<PlayerState>,
    val currentPlayerIndex: Int = 0,
    val phase: GamePhase = GamePhase.SETUP_ROUND_1,
    val turnNumber: Int = 0,
    val bank: ResourceCounts = Resource.entries.associateWith { Rules.BANK_PER_RESOURCE },
    val devDeck: List<DevCardType> = emptyList(),
    val roads: Map<EdgeId, PlayerId> = emptyMap(),
    val buildings: Map<VertexId, Building> = emptyMap(),
    val lastRoll: Pair<Int, Int>? = null,
    /** Whether the current player has already rolled this turn. */
    val hasRolled: Boolean = false,
    val longestRoadHolder: PlayerId? = null,
    val longestRoadLength: Int = 0,
    val largestArmyHolder: PlayerId? = null,
    val largestArmySize: Int = 0,
    /** Players who still owe a discard after a 7. */
    val pendingDiscards: Set<PlayerId> = emptySet(),
    /** Candidates to steal from after the robber moved. */
    val stealCandidates: Set<PlayerId> = emptySet(),
    /** Free roads still owed by a Road Building card. */
    val freeRoadsRemaining: Int = 0,
    /** Set during setup once the settlement is down and the road is still owed. */
    val setupSettlement: VertexId? = null,
    val openTrade: TradeOffer? = null,
    val winner: PlayerId? = null,
    val log: List<String> = emptyList(),
    /**
     * Increments once per applied action.
     *
     * Broadcasts can arrive out of order or be duplicated, so a client compares this against the
     * version it already has and ignores anything that is not strictly newer.
     */
    val version: Int = 0,
) {
    val currentPlayer: PlayerState get() = players[currentPlayerIndex]

    fun player(id: PlayerId): PlayerState =
        players.firstOrNull { it.id == id } ?: error("No such player: $id")

    fun playerOrNull(id: PlayerId): PlayerState? = players.firstOrNull { it.id == id }

    fun isCurrent(id: PlayerId): Boolean = currentPlayer.id == id

    /** Roads belonging to [id]. */
    fun roadsOf(id: PlayerId): Set<EdgeId> =
        roads.filterValues { it == id }.keys

    fun buildingsOf(id: PlayerId): Map<VertexId, Building> =
        buildings.filterValues { it.owner == id }

    /**
     * Total victory points for [id], including the hidden card points.
     *
     * Only ever shown in full to the owning player; other players see [publicVictoryPoints].
     */
    fun victoryPoints(id: PlayerId): Int =
        publicVictoryPoints(id) + player(id).victoryPointCards

    /** Victory points everyone can see: buildings plus the two bonus tiles. */
    fun publicVictoryPoints(id: PlayerId): Int {
        var points = buildingsOf(id).values.sumOf { it.victoryPoints }
        if (longestRoadHolder == id) points += 2
        if (largestArmyHolder == id) points += 2
        return points
    }

    /** Harbours reachable by [id], i.e. those touching one of their buildings. */
    fun harborsOf(id: PlayerId): List<Harbor> =
        buildingsOf(id).keys.mapNotNull { board.harborAt(it) }.distinct()

    /** The best bank rate [id] can get for [resource]: 4:1, or better with a harbour. */
    fun tradeRatio(id: PlayerId, resource: Resource): Int {
        val harbors = harborsOf(id)
        return when {
            harbors.any { it.resource == resource } -> 2
            harbors.any { it.resource == null } -> 3
            else -> 4
        }
    }

    fun updatePlayer(id: PlayerId, transform: (PlayerState) -> PlayerState): GameState =
        copy(players = players.map { if (it.id == id) transform(it) else it })

    /**
     * Appends a line to the running commentary, keeping only the most recent [LOG_LIMIT].
     *
     * The whole state is broadcast to every player after every action, so an unbounded log would
     * make each message grow with the length of the game - quadratic traffic over a full match.
     */
    fun withLog(message: String): GameState =
        copy(log = (log + message).takeLast(LOG_LIMIT))

    companion object {
        const val LOG_LIMIT = 60
    }
}
