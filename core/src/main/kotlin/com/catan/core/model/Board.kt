package com.catan.core.model

import kotlinx.serialization.Serializable
import kotlin.random.Random

@Serializable
data class Tile(
    val hex: Hex,
    val type: TileType,
    /** Dice number, or null on the desert. */
    val number: Int? = null,
)

@Serializable
data class Board(
    val tiles: List<Tile>,
    val harbors: List<Harbor>,
    val robber: Hex,
) {
    private val tileByHex: Map<Hex, Tile> = tiles.associateBy { it.hex }

    val hexes: Set<Hex> get() = tileByHex.keys

    fun tileAt(hex: Hex): Tile? = tileByHex[hex]

    /** Every legal building corner on this board. */
    val vertices: Set<VertexId> by lazy {
        tiles.flatMap { t -> HexDirection.entries.map { VertexId.corner(t.hex, it) } }.toSet()
    }

    /** Every legal road slot on this board. */
    val edges: Set<EdgeId> by lazy {
        tiles.flatMap { t -> HexDirection.entries.map { EdgeId.side(t.hex, it) } }.toSet()
    }

    /** Tiles touching [vertex] (1 to 3 of them; rim corners touch fewer). */
    fun tilesAround(vertex: VertexId): List<Tile> = vertex.hexes.mapNotNull { tileByHex[it] }

    /** Edges of this board that meet at [vertex]. */
    fun edgesAt(vertex: VertexId): List<EdgeId> = vertex.incidentEdges().filter { it in edges }

    /** Corners of this board one road-step from [vertex]. */
    fun neighborsOf(vertex: VertexId): List<VertexId> =
        vertex.adjacentVertices().filter { it in vertices }

    /** The harbour served by [vertex], if any. A harbour serves both corners of its edge. */
    fun harborAt(vertex: VertexId): Harbor? = harbors.firstOrNull { it.edge.touches(vertex) }

    companion object {

        /** The 19 hexes of the standard board: rows of 3, 4, 5, 4, 3. */
        val STANDARD_HEXES: List<Hex> = buildList {
            for (r in -2..2) {
                val qMin = maxOf(-2, -2 - r)
                val qMax = minOf(2, 2 - r)
                for (q in qMin..qMax) add(Hex(q, r))
            }
        }

        /** Official base-game tile mix for 3-4 players. */
        val TILE_POOL: List<TileType> = buildList {
            repeat(4) { add(TileType.FOREST) }
            repeat(4) { add(TileType.PASTURE) }
            repeat(4) { add(TileType.FIELDS) }
            repeat(3) { add(TileType.HILLS) }
            repeat(3) { add(TileType.MOUNTAINS) }
            add(TileType.DESERT)
        }

        /** Official base-game number tokens. 7 is absent; that is the robber. */
        val NUMBER_POOL: List<Int> =
            listOf(2, 3, 3, 4, 4, 5, 5, 6, 6, 8, 8, 9, 9, 10, 10, 11, 11, 12)

        /**
         * Harbour types in clockwise coastline order. Four generic 3:1 ports and one 2:1 port
         * for each resource, matching the printed frame.
         */
        val HARBOR_SEQUENCE: List<Resource?> = listOf(
            null,
            Resource.WOOL,
            null,
            Resource.ORE,
            Resource.GRAIN,
            null,
            Resource.BRICK,
            Resource.LUMBER,
            null,
        )

        /** Gaps, in coastal edges, between consecutive harbours. Sums to the 30-edge coastline. */
        private val HARBOR_STEPS = listOf(3, 3, 4, 3, 3, 4, 3, 3, 4)

        /** The red numbers, which may never be placed on touching tiles. */
        private val RED_NUMBERS = setOf(6, 8)

        fun random(random: Random = Random.Default): Board {
            val types = TILE_POOL.shuffled(random)
            val numbers = drawValidNumbers(types, random)

            val tiles = STANDARD_HEXES.mapIndexed { i, hex -> Tile(hex, types[i], numbers[i]) }
            val desert = tiles.first { it.type == TileType.DESERT }

            return Board(
                tiles = tiles,
                harbors = standardHarbors(),
                robber = desert.hex,
            )
        }

        /**
         * Assigns number tokens so that no two red numbers (6 and 8) end up on adjacent tiles,
         * as the official setup rules require.
         */
        private fun drawValidNumbers(types: List<TileType>, random: Random): List<Int?> {
            val adjacency = STANDARD_HEXES.map { hex ->
                hex.neighbors().mapNotNull { n -> STANDARD_HEXES.indexOf(n).takeIf { it >= 0 } }
            }

            repeat(2000) {
                val pool = NUMBER_POOL.shuffled(random).toMutableList()
                val assigned = types.map { if (it == TileType.DESERT) null else pool.removeAt(0) }

                val valid = STANDARD_HEXES.indices.none { i ->
                    assigned[i] in RED_NUMBERS &&
                        adjacency[i].any { j -> assigned[j] in RED_NUMBERS }
                }
                if (valid) return assigned
            }
            error("Could not lay out number tokens without adjacent red numbers")
        }

        /**
         * Walks the coastline and drops the nine harbours onto it at the printed spacing.
         */
        fun standardHarbors(): List<Harbor> {
            val ring = coastlineInOrder()
            check(ring.size == 30) { "Expected a 30-edge coastline, found ${ring.size}" }

            var index = 0
            return HARBOR_SEQUENCE.mapIndexed { i, resource ->
                val harbor = Harbor(ring[index % ring.size], resource)
                index += HARBOR_STEPS[i]
                harbor
            }
        }

        /**
         * The sea-facing edges of the board, walked as a closed loop so that consecutive entries
         * are physically adjacent.
         */
        fun coastlineInOrder(): List<EdgeId> {
            val onBoard = STANDARD_HEXES.toSet()
            val coastal = STANDARD_HEXES.flatMap { hex ->
                HexDirection.entries
                    .filter { (hex + it) !in onBoard }
                    .map { EdgeId.side(hex, it) }
            }.distinct()

            val byVertex = mutableMapOf<VertexId, MutableList<EdgeId>>()
            for (edge in coastal) {
                val (v1, v2) = edge.endpoints()
                byVertex.getOrPut(v1) { mutableListOf() }.add(edge)
                byVertex.getOrPut(v2) { mutableListOf() }.add(edge)
            }
            check(byVertex.values.all { it.size == 2 }) {
                "Coastline is not a simple loop; a corner has ${byVertex.values.map { it.size }} edges"
            }

            val ordered = mutableListOf(coastal.first())
            var previous: EdgeId? = null
            var current = coastal.first()

            while (ordered.size < coastal.size) {
                val (v1, v2) = current.endpoints()
                val next = listOf(v1, v2)
                    .flatMap { byVertex.getValue(it) }
                    .firstOrNull { it != current && it != previous }
                    ?: error("Coastline walk dead-ended at $current")
                ordered.add(next)
                previous = current
                current = next
            }
            return ordered
        }
    }
}
