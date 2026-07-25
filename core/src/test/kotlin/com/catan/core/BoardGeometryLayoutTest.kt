package com.catan.core

import com.catan.core.geometry.BoardGeometry
import com.catan.core.geometry.Point
import com.catan.core.model.Board
import com.catan.core.model.Hex
import com.catan.core.model.endpoints
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Checks the on-screen layout maths. A rules test cannot see a board that renders with gaps
 * between tiles or roads drawn at the wrong angle, so it gets its own tests.
 */
class BoardGeometryLayoutTest {

    private val size = 50f
    private val geo = BoardGeometry(size)
    private val board = Board.random(Random(3))

    private fun distance(a: Point, b: Point): Float =
        sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y))

    @Test
    fun `neighbouring tiles sit exactly one tile width apart`() {
        val expected = size * sqrt(3f)
        for (hex in board.hexes) {
            for (neighbor in hex.neighbors()) {
                if (neighbor !in board.hexes) continue
                val gap = distance(geo.centerOf(hex), geo.centerOf(neighbor))
                assertTrue(abs(gap - expected) < 0.01f) {
                    "$hex to $neighbor measured $gap, expected $expected"
                }
            }
        }
    }

    @Test
    fun `every corner sits exactly one circumradius from each of its tiles`() {
        for (vertex in board.vertices) {
            val position = geo.positionOf(vertex)
            for (hex in vertex.hexes) {
                val reach = distance(position, geo.centerOf(hex))
                assertTrue(abs(reach - size) < 0.01f) {
                    "$vertex is $reach from $hex, expected $size"
                }
            }
        }
    }

    @Test
    fun `every road slot is exactly one hex side long`() {
        for (edge in board.edges) {
            val (a, b) = edge.endpoints()
            val length = distance(geo.positionOf(a), geo.positionOf(b))
            assertTrue(abs(length - size) < 0.01f) {
                "$edge measured $length, expected $size"
            }
        }
    }

    @Test
    fun `all 54 corners land on distinct points`() {
        val points = board.vertices.map { geo.positionOf(it) }
            .map { "%.2f,%.2f".format(it.x, it.y) }
        assertEquals(54, points.size)
        assertEquals(54, points.distinct().size) { "two corners drew to the same spot" }
    }

    @Test
    fun `all 72 road slots land on distinct midpoints`() {
        val points = board.edges.map { geo.midpointOf(it) }
            .map { "%.2f,%.2f".format(it.x, it.y) }
        assertEquals(72, points.size)
        assertEquals(72, points.distinct().size)
    }

    @Test
    fun `a road midpoint is the average of the two tiles it separates`() {
        for (edge in board.edges) {
            val a = geo.centerOf(edge.hexes[0])
            val b = geo.centerOf(edge.hexes[1])
            val mid = geo.midpointOf(edge)
            assertTrue(abs(mid.x - (a.x + b.x) / 2f) < 0.01f)
            assertTrue(abs(mid.y - (a.y + b.y) / 2f) < 0.01f)
        }
    }

    @Test
    fun `road angles take only the three hex orientations`() {
        val angles = board.edges.map { edge ->
            // Directions 180 degrees apart draw the same road.
            val raw = geo.angleDegreesOf(edge)
            val normalised = ((raw % 180f) + 180f) % 180f
            Math.round(normalised).toInt()
        }.distinct().sorted()

        // A pointy-top grid has edges at 30, 90 and 150 degrees.
        assertEquals(listOf(30, 90, 150), angles) { "unexpected road angles: $angles" }
    }

    @Test
    fun `harbours point away from the land`() {
        for (harbor in board.harbors) {
            val outward = geo.outwardOf(harbor.edge, board)
            val magnitude = sqrt(outward.x * outward.x + outward.y * outward.y)
            assertTrue(abs(magnitude - 1f) < 0.01f) { "outward vector is not a unit vector" }

            // Stepping outward must move away from the board's centre.
            val mid = geo.midpointOf(harbor.edge)
            val stepped = Point(mid.x + outward.x * size, mid.y + outward.y * size)
            val centre = geo.centerOf(Hex(0, 0))
            assertTrue(distance(stepped, centre) > distance(mid, centre)) {
                "harbour at ${harbor.edge} points inland"
            }
        }
    }

    @Test
    fun `tile images are drawn large enough to meet their neighbours`() {
        // Drawn hex height must equal the true hex height of 2 * size.
        val imageSide = size * BoardGeometry.TILE_IMAGE_SCALE
        val drawnHexHeight = imageSide * BoardGeometry.TILE_HEX_FRACTION
        assertTrue(abs(drawnHexHeight - 2f * size) < 0.01f) {
            "a tile would render $drawnHexHeight tall instead of ${2 * size}"
        }
    }

    @Test
    fun `the whole board fits inside the canvas it was fitted to`() {
        val width = 1600f
        val height = 900f
        val fitted = BoardGeometry.fit(width, height)

        val corners = Board.STANDARD_HEXES.map { fitted.centerOf(it) }
        val halfTileWidth = fitted.size * sqrt(3f) / 2f
        val halfTileHeight = fitted.size

        assertTrue(corners.all { it.x - halfTileWidth >= 0f && it.x + halfTileWidth <= width }) {
            "board overflows horizontally"
        }
        assertTrue(corners.all { it.y - halfTileHeight >= 0f && it.y + halfTileHeight <= height }) {
            "board overflows vertically"
        }
    }

    @Test
    fun `the board is centred on the canvas`() {
        val fitted = BoardGeometry.fit(1600f, 900f)
        val centre = fitted.centerOf(Hex(0, 0))
        assertTrue(abs(centre.x - 800f) < 0.01f)
        assertTrue(abs(centre.y - 450f) < 0.01f)
    }
}
