package com.catan.core

import com.catan.core.model.Board
import com.catan.core.model.EdgeId
import com.catan.core.model.Hex
import com.catan.core.model.Resource
import com.catan.core.model.TileType
import com.catan.core.model.endpoints
import com.catan.core.model.touches
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class BoardGeometryTest {

    private val board = Board.random(Random(1))

    @Test
    fun `board has the 19 standard tiles`() {
        assertEquals(19, Board.STANDARD_HEXES.size)
        assertEquals(19, board.tiles.size)
        assertEquals(19, board.tiles.map { it.hex }.distinct().size)
    }

    @Test
    fun `board has 54 corners and 72 edges`() {
        assertEquals(54, board.vertices.size)
        assertEquals(72, board.edges.size)
    }

    @Test
    fun `tile mix matches the base game`() {
        val counts = board.tiles.groupingBy { it.type }.eachCount()
        assertEquals(4, counts[TileType.FOREST])
        assertEquals(4, counts[TileType.PASTURE])
        assertEquals(4, counts[TileType.FIELDS])
        assertEquals(3, counts[TileType.HILLS])
        assertEquals(3, counts[TileType.MOUNTAINS])
        assertEquals(1, counts[TileType.DESERT])
    }

    @Test
    fun `number tokens match the base game and skip the desert`() {
        val numbers = board.tiles.mapNotNull { it.number }.sorted()
        assertEquals(Board.NUMBER_POOL.sorted(), numbers)
        assertTrue(board.tiles.filter { it.type == TileType.DESERT }.all { it.number == null })
        assertTrue(board.tiles.filter { it.type != TileType.DESERT }.all { it.number != null })
    }

    @Test
    fun `no 7 is ever placed`() {
        assertTrue(board.tiles.none { it.number == 7 })
    }

    @Test
    fun `red numbers are never adjacent, across many boards`() {
        repeat(300) { seed ->
            val b = Board.random(Random(seed.toLong()))
            for (tile in b.tiles) {
                if (tile.number !in setOf(6, 8)) continue
                val touchingRed = tile.hex.neighbors()
                    .mapNotNull { b.tileAt(it) }
                    .filter { it.number == 6 || it.number == 8 }
                assertTrue(touchingRed.isEmpty()) {
                    "seed $seed: ${tile.number} at ${tile.hex} touches ${touchingRed.map { it.number }}"
                }
            }
        }
    }

    @Test
    fun `robber starts on the desert`() {
        assertEquals(TileType.DESERT, board.tileAt(board.robber)?.type)
    }

    @Test
    fun `coastline is a closed 30-edge loop`() {
        val ring = Board.coastlineInOrder()
        assertEquals(30, ring.size)
        assertEquals(30, ring.distinct().size)
        // consecutive entries, including the wrap-around, must share a corner
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[(i + 1) % ring.size]
            val shared = a.endpoints().toList().intersect(b.endpoints().toList().toSet())
            assertTrue(shared.size == 1) { "ring[$i] $a and $b share ${shared.size} corners" }
        }
    }

    @Test
    fun `there are 9 harbors with the correct mix`() {
        assertEquals(9, board.harbors.size)
        assertEquals(4, board.harbors.count { it.resource == null })
        assertEquals(
            Resource.entries.toSet(),
            board.harbors.mapNotNull { it.resource }.toSet(),
        )
        assertEquals(5, board.harbors.mapNotNull { it.resource }.distinct().size)
    }

    @Test
    fun `harbors sit on the coast and never share a corner`() {
        val coast = Board.coastlineInOrder().toSet()
        assertTrue(board.harbors.all { it.edge in coast })

        val corners = board.harbors.flatMap { it.edge.endpoints().toList() }
        assertEquals(18, corners.size)
        assertEquals(18, corners.distinct().size) { "two harbors share a corner" }
    }

    @Test
    fun `every edge has exactly two endpoints that are board corners`() {
        for (edge in board.edges) {
            val (v1, v2) = edge.endpoints()
            assertTrue(v1 in board.vertices) { "$edge endpoint $v1 missing" }
            assertTrue(v2 in board.vertices) { "$edge endpoint $v2 missing" }
            assertTrue(edge.touches(v1) && edge.touches(v2))
        }
    }

    @Test
    fun `corner adjacency is symmetric`() {
        for (v in board.vertices) {
            for (n in board.neighborsOf(v)) {
                assertTrue(v in board.neighborsOf(n)) { "$v -> $n not symmetric" }
            }
        }
    }

    @Test
    fun `every corner has two or three neighbours`() {
        for (v in board.vertices) {
            assertTrue(board.neighborsOf(v).size in 2..3) {
                "$v has ${board.neighborsOf(v).size} neighbours"
            }
        }
    }

    @Test
    fun `every corner touches one to three tiles`() {
        for (v in board.vertices) {
            assertTrue(board.tilesAround(v).size in 1..3)
        }
        // the six innermost corners of the middle tile touch three tiles each
        val centre = Hex(0, 0)
        val cornersOfCentre = board.vertices.filter { it.hexes.contains(centre) }
        assertEquals(6, cornersOfCentre.size)
        assertTrue(cornersOfCentre.all { board.tilesAround(it).size == 3 })
    }

    @Test
    fun `edge endpoints agree with corner incidence`() {
        for (v in board.vertices) {
            for (e in board.edgesAt(v)) {
                assertTrue(v in e.endpoints().toList()) { "$e listed at $v but does not end there" }
            }
        }
    }

    @Test
    fun `a corner resolves to the same id from every touching tile`() {
        val a = Hex(0, 0)
        val b = Hex(1, 0)
        val c = Hex(1, -1)
        val fromA = com.catan.core.model.VertexId.of(a, b, c)
        val fromB = com.catan.core.model.VertexId.of(b, c, a)
        val fromC = com.catan.core.model.VertexId.of(c, a, b)
        assertEquals(fromA, fromB)
        assertEquals(fromB, fromC)
    }

    @Test
    fun `an edge resolves to the same id from either side`() {
        assertEquals(EdgeId.of(Hex(0, 0), Hex(1, 0)), EdgeId.of(Hex(1, 0), Hex(0, 0)))
    }
}
