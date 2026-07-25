package com.catan.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class Resource {
    BRICK, LUMBER, WOOL, GRAIN, ORE;

    /** Asset file name in `assets/resources/`. */
    val assetName: String
        get() = when (this) {
            BRICK -> "clay"
            LUMBER -> "wood"
            WOOL -> "sheep"
            GRAIN -> "wheat"
            ORE -> "ore"
        }
}

@Serializable
enum class TileType {
    HILLS, FOREST, PASTURE, FIELDS, MOUNTAINS, DESERT;

    /** The resource this tile produces, or null for the desert. */
    val resource: Resource?
        get() = when (this) {
            HILLS -> Resource.BRICK
            FOREST -> Resource.LUMBER
            PASTURE -> Resource.WOOL
            FIELDS -> Resource.GRAIN
            MOUNTAINS -> Resource.ORE
            DESERT -> null
        }

    /** Asset file name in `assets/tiles/`. */
    val assetName: String
        get() = when (this) {
            HILLS -> "clay"
            FOREST -> "forest"
            PASTURE -> "pasture"
            FIELDS -> "wheat"
            MOUNTAINS -> "mountain"
            DESERT -> "desert"
        }
}

@Serializable
enum class DevCardType {
    KNIGHT, ROAD_BUILDING, YEAR_OF_PLENTY, MONOPOLY, VICTORY_POINT;

    val isVictoryPoint: Boolean get() = this == VICTORY_POINT

    val assetName: String
        get() = when (this) {
            KNIGHT -> "knight"
            ROAD_BUILDING -> "road building"
            YEAR_OF_PLENTY -> "year of plenty"
            MONOPOLY -> "monopoly"
            VICTORY_POINT -> "victory point"
        }
}

@Serializable
enum class PlayerColor {
    RED, BLUE, WHITE, ORANGE;

    val assetName: String get() = name.lowercase()
}

/** A harbour. A null [resource] is the generic 3:1 port. */
@Serializable
data class Harbor(
    val edge: EdgeId,
    val resource: Resource?,
) {
    val ratio: Int get() = if (resource == null) 3 else 2
}

@Serializable
enum class BuildingType { SETTLEMENT, CITY }

@Serializable
data class Building(
    val type: BuildingType,
    val owner: PlayerId,
) {
    val victoryPoints: Int get() = if (type == BuildingType.CITY) 2 else 1
}

@JvmInline
@Serializable
value class PlayerId(val value: Int) {
    override fun toString(): String = "P$value"
}

/**
 * What the game is waiting for. Nothing else may happen until the current phase is satisfied.
 */
@Serializable
enum class GamePhase {
    /** Placing the first settlement + road, in seating order. */
    SETUP_ROUND_1,

    /** Placing the second settlement + road, in reverse seating order. */
    SETUP_ROUND_2,

    /** Current player must roll before doing anything else. */
    ROLL,

    /** Dice rolled; the player may build, trade and play cards. */
    MAIN,

    /** A 7 was rolled and some players hold more than 7 cards. */
    DISCARD,

    /** The robber must be moved (from a 7, or from a knight). */
    MOVE_ROBBER,

    /** The robber moved onto hexes with more than one victim; pick one. */
    STEAL,

    GAME_OVER,
}
