package com.catan.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.catan.app.R
import com.catan.core.model.Board
import com.catan.core.model.DevCardType
import com.catan.core.model.EdgeId
import com.catan.core.model.Hex
import com.catan.core.model.PlayerColor
import com.catan.core.model.Resource
import com.catan.core.model.TileType
import com.catan.core.model.VertexId
import com.catan.core.model.endpoints
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Turns board coordinates into screen positions.
 *
 * [size] is the circumradius of a hex: the distance from its centre to a corner. For a pointy-top
 * hex that makes it `sqrt(3) * size` wide and `2 * size` tall.
 */
data class BoardLayout(val size: Float, val origin: Offset) {

    fun centerOf(hex: Hex): Offset = Offset(
        origin.x + size * SQRT3 * (hex.q + hex.r / 2f),
        origin.y + size * 1.5f * hex.r,
    )

    /** A corner sits at the average of the three hex centres that meet there. */
    fun positionOf(vertex: VertexId): Offset {
        var x = 0f
        var y = 0f
        for (hex in vertex.hexes) {
            val c = centerOf(hex)
            x += c.x
            y += c.y
        }
        return Offset(x / 3f, y / 3f)
    }

    fun midpointOf(edge: EdgeId): Offset {
        val (a, b) = edge.endpoints()
        val pa = positionOf(a)
        val pb = positionOf(b)
        return Offset((pa.x + pb.x) / 2f, (pa.y + pb.y) / 2f)
    }

    /** Rotation in degrees for a road drawn along [edge]. */
    fun angleOf(edge: EdgeId): Float {
        val (a, b) = edge.endpoints()
        val pa = positionOf(a)
        val pb = positionOf(b)
        return Math.toDegrees(atan2((pb.y - pa.y).toDouble(), (pb.x - pa.x).toDouble())).toFloat()
    }

    /** The direction pointing away from the board at [edge], for placing a harbour marker. */
    fun outwardOf(edge: EdgeId, board: Board): Offset {
        val mid = midpointOf(edge)
        val land = edge.hexes.filter { board.tileAt(it) != null }
        if (land.isEmpty()) return Offset(0f, -1f)
        var x = 0f
        var y = 0f
        for (hex in land) {
            val c = centerOf(hex)
            x += c.x
            y += c.y
        }
        val landCentre = Offset(x / land.size, y / land.size)
        val dx = mid.x - landCentre.x
        val dy = mid.y - landCentre.y
        val length = sqrt(dx * dx + dy * dy).takeIf { it > 0.001f } ?: return Offset(0f, -1f)
        return Offset(dx / length, dy / length)
    }

    fun nearestVertex(point: Offset, candidates: Collection<VertexId>): VertexId? =
        candidates.minByOrNull { (positionOf(it) - point).getDistanceSquared() }
            ?.takeIf { (positionOf(it) - point).getDistance() <= size * 0.45f }

    fun nearestEdge(point: Offset, candidates: Collection<EdgeId>): EdgeId? =
        candidates.minByOrNull { (midpointOf(it) - point).getDistanceSquared() }
            ?.takeIf { (midpointOf(it) - point).getDistance() <= size * 0.40f }

    fun nearestHex(point: Offset, candidates: Collection<Hex>): Hex? =
        candidates.minByOrNull { (centerOf(it) - point).getDistanceSquared() }
            ?.takeIf { (centerOf(it) - point).getDistance() <= size * 0.95f }

    companion object {
        val SQRT3 = sqrt(3f)

        /**
         * The tile art is a hex drawn inside a square canvas with transparent padding. The hex
         * itself is 83.2% of the canvas height, so the image has to be drawn larger than the hex
         * for neighbouring tiles to meet cleanly.
         */
        const val TILE_IMAGE_SCALE = 2f / 0.832f

        /** Fits the whole 19-tile board, plus room for harbour markers, into [canvas]. */
        fun fit(canvas: Size): BoardLayout {
            // The board spans 5 hexes corner to corner: 4.33 * size horizontally from the centre
            // and 4 * size vertically. The extra margin leaves room for the harbours.
            val size = minOf(canvas.width / 9.9f, canvas.height / 9.2f)
            return BoardLayout(size, Offset(canvas.width / 2f, canvas.height / 2f))
        }
    }
}

/** Drawable ids for the artwork in `res/drawable-nodpi`. */
object Art {

    fun tile(type: TileType): Int = when (type) {
        TileType.FOREST -> R.drawable.tile_forest
        TileType.PASTURE -> R.drawable.tile_pasture
        TileType.FIELDS -> R.drawable.tile_fields
        TileType.HILLS -> R.drawable.tile_hills
        TileType.MOUNTAINS -> R.drawable.tile_mountains
        TileType.DESERT -> R.drawable.tile_desert
    }

    fun token(number: Int): Int = when (number) {
        2 -> R.drawable.token_2
        3 -> R.drawable.token_3
        4 -> R.drawable.token_4
        5 -> R.drawable.token_5
        6 -> R.drawable.token_6
        8 -> R.drawable.token_8
        9 -> R.drawable.token_9
        10 -> R.drawable.token_10
        11 -> R.drawable.token_11
        12 -> R.drawable.token_12
        else -> R.drawable.token_2
    }

    fun settlement(color: PlayerColor): Int = when (color) {
        PlayerColor.RED -> R.drawable.settlement_red
        PlayerColor.BLUE -> R.drawable.settlement_blue
        PlayerColor.WHITE -> R.drawable.settlement_white
        PlayerColor.ORANGE -> R.drawable.settlement_orange
    }

    fun city(color: PlayerColor): Int = when (color) {
        PlayerColor.RED -> R.drawable.city_red
        PlayerColor.BLUE -> R.drawable.city_blue
        PlayerColor.WHITE -> R.drawable.city_white
        PlayerColor.ORANGE -> R.drawable.city_orange
    }

    fun road(color: PlayerColor): Int = when (color) {
        PlayerColor.RED -> R.drawable.road_red
        PlayerColor.BLUE -> R.drawable.road_blue
        PlayerColor.WHITE -> R.drawable.road_white
        PlayerColor.ORANGE -> R.drawable.road_orange
    }

    fun card(resource: Resource): Int = when (resource) {
        Resource.BRICK -> R.drawable.card_brick
        Resource.LUMBER -> R.drawable.card_lumber
        Resource.WOOL -> R.drawable.card_wool
        Resource.GRAIN -> R.drawable.card_grain
        Resource.ORE -> R.drawable.card_ore
    }

    fun devCard(type: DevCardType): Int = when (type) {
        DevCardType.KNIGHT -> R.drawable.dev_knight
        DevCardType.MONOPOLY -> R.drawable.dev_monopoly
        DevCardType.ROAD_BUILDING -> R.drawable.dev_road_building
        DevCardType.VICTORY_POINT -> R.drawable.dev_victory_point
        DevCardType.YEAR_OF_PLENTY -> R.drawable.dev_year_of_plenty
    }

    const val ROBBER = R.drawable.robber
}
