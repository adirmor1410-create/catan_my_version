package com.catan.core.geometry

import com.catan.core.model.Board
import com.catan.core.model.EdgeId
import com.catan.core.model.Hex
import com.catan.core.model.VertexId
import com.catan.core.model.endpoints
import kotlin.math.atan2
import kotlin.math.sqrt

data class Point(val x: Float, val y: Float)

/**
 * Where the pieces of a board sit on screen.
 *
 * This lives in `core` rather than in the Android module so it can be unit tested. Rendering maths
 * is easy to get subtly wrong - a tile that overlaps its neighbour by a pixel, or a road drawn at
 * the wrong angle - and none of that is visible to a rules test.
 *
 * [size] is a hex's circumradius: the distance from its centre to any of its corners. For a
 * pointy-top hex that makes it `sqrt(3) * size` wide, `2 * size` tall, with a side length that is
 * also exactly [size].
 */
class BoardGeometry(val size: Float, val originX: Float = 0f, val originY: Float = 0f) {

    fun centerOf(hex: Hex): Point = Point(
        originX + size * SQRT3 * (hex.q + hex.r / 2f),
        originY + size * 1.5f * hex.r,
    )

    /**
     * A corner is the centroid of the three hexes meeting there, which for a regular hex grid is
     * exactly the shared corner point.
     */
    fun positionOf(vertex: VertexId): Point {
        var x = 0f
        var y = 0f
        for (hex in vertex.hexes) {
            val c = centerOf(hex)
            x += c.x
            y += c.y
        }
        return Point(x / 3f, y / 3f)
    }

    fun midpointOf(edge: EdgeId): Point {
        val (a, b) = edge.endpoints()
        val pa = positionOf(a)
        val pb = positionOf(b)
        return Point((pa.x + pb.x) / 2f, (pa.y + pb.y) / 2f)
    }

    /** The angle a road along [edge] should be drawn at, in degrees. */
    fun angleDegreesOf(edge: EdgeId): Float {
        val (a, b) = edge.endpoints()
        val pa = positionOf(a)
        val pb = positionOf(b)
        return Math.toDegrees(atan2((pb.y - pa.y).toDouble(), (pb.x - pa.x).toDouble())).toFloat()
    }

    /** A unit vector pointing from the land out to sea at [edge], for placing a harbour. */
    fun outwardOf(edge: EdgeId, board: Board): Point {
        val mid = midpointOf(edge)
        val land = edge.hexes.filter { board.tileAt(it) != null }
        if (land.isEmpty()) return Point(0f, -1f)

        var x = 0f
        var y = 0f
        for (hex in land) {
            val c = centerOf(hex)
            x += c.x
            y += c.y
        }
        val landCentre = Point(x / land.size, y / land.size)
        val dx = mid.x - landCentre.x
        val dy = mid.y - landCentre.y
        val length = sqrt(dx * dx + dy * dy)
        return if (length < 1e-4f) Point(0f, -1f) else Point(dx / length, dy / length)
    }

    companion object {
        val SQRT3 = sqrt(3f)

        /**
         * The tile artwork is a hex drawn inside a square canvas with transparent padding; the hex
         * is this fraction of the canvas. Tiles must be drawn larger than the hex itself by the
         * reciprocal of this, or neighbours leave gaps.
         */
        const val TILE_HEX_FRACTION = 0.832f

        /** Multiplier from hex height to the square tile image's side. */
        const val TILE_IMAGE_SCALE = 2f / TILE_HEX_FRACTION

        /**
         * Picks a hex size that fits the whole board, with room around it for harbours.
         *
         * The board reaches 4.33 * size horizontally and 4 * size vertically from the centre; the
         * larger divisors leave a margin for the harbour markers.
         */
        fun fit(width: Float, height: Float): BoardGeometry =
            BoardGeometry(
                size = minOf(width / 9.9f, height / 9.2f),
                originX = width / 2f,
                originY = height / 2f,
            )
    }
}
