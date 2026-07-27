package com.catan.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.catan.app.R
import com.catan.core.geometry.BoardGeometry
import com.catan.core.geometry.Point
import com.catan.core.model.Board
import com.catan.core.model.DevCardType
import com.catan.core.model.EdgeId
import com.catan.core.model.Hex
import com.catan.core.model.PlayerColor
import com.catan.core.model.Resource
import com.catan.core.model.TileType
import com.catan.core.model.VertexId

/**
 * Screen positions for the board.
 *
 * The arithmetic lives in `core` as [BoardGeometry] so it can be unit tested on the JVM; this is
 * only the Compose-facing wrapper that turns its points into [Offset]s.
 */
class BoardLayout(private val geometry: BoardGeometry) {

    val size: Float get() = geometry.size

    private fun Point.toOffset() = Offset(x, y)

    fun centerOf(hex: Hex): Offset = geometry.centerOf(hex).toOffset()

    fun positionOf(vertex: VertexId): Offset = geometry.positionOf(vertex).toOffset()

    fun midpointOf(edge: EdgeId): Offset = geometry.midpointOf(edge).toOffset()

    fun angleOf(edge: EdgeId): Float = geometry.angleDegreesOf(edge)

    fun outwardOf(edge: EdgeId, board: Board): Offset = geometry.outwardOf(edge, board).toOffset()

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
        /** How much larger than the hex a tile image must be drawn. See [BoardGeometry]. */
        const val TILE_IMAGE_SCALE = BoardGeometry.TILE_IMAGE_SCALE

        fun fit(canvas: Size): BoardLayout =
            BoardLayout(BoardGeometry.fit(canvas.width, canvas.height))
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

    val ROBBER = R.drawable.robber
}
