package com.catan.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.catan.core.model.Board
import com.catan.core.model.BuildingType
import com.catan.core.model.EdgeId
import com.catan.core.model.Harbor
import com.catan.core.model.Hex
import com.catan.core.model.PlayerColor
import com.catan.core.model.Resource
import com.catan.core.model.TileType
import com.catan.core.model.VertexId
import com.catan.core.model.endpoints
import com.catan.core.net.PlayerView
import kotlin.math.roundToInt

/** What a tap on the board currently means. */
enum class BoardMode { NONE, ROAD, SETTLEMENT, CITY, ROBBER }

private const val ROAD_IMAGE_SCALE = 500f / 421f
private const val SETTLEMENT_SCALE = 1.0f
private const val CITY_SCALE = 1.1f
private const val TOKEN_SCALE = 1.05f
private const val ROBBER_SCALE = 1.15f

private val SeaColor = Color(0xFF14618C)
private val HighlightColor = Color(0xFFFFF176)

@Composable
fun BoardView(
    view: PlayerView,
    mode: BoardMode,
    legalVertices: Set<VertexId>,
    legalEdges: Set<EdgeId>,
    onVertexTap: (VertexId) -> Unit,
    onEdgeTap: (EdgeId) -> Unit,
    onHexTap: (Hex) -> Unit,
    modifier: Modifier = Modifier,
) {
    val board = view.state.board

    val tileArt = TileType.entries.associateWith { ImageBitmap.imageResource(Art.tile(it)) }
    val tokenArt = listOf(2, 3, 4, 5, 6, 8, 9, 10, 11, 12)
        .associateWith { ImageBitmap.imageResource(Art.token(it)) }
    val settlementArt = PlayerColor.entries
        .associateWith { ImageBitmap.imageResource(Art.settlement(it)) }
    val cityArt = PlayerColor.entries.associateWith { ImageBitmap.imageResource(Art.city(it)) }
    val roadArt = PlayerColor.entries.associateWith { ImageBitmap.imageResource(Art.road(it)) }
    val resourceArt = Resource.entries.associateWith { ImageBitmap.imageResource(Art.card(it)) }
    val robberArt = ImageBitmap.imageResource(Art.ROBBER)

    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    // Named to avoid shadowing the top-level colorOf(PlayerColor) in this package.
    val colorFor: (com.catan.core.model.PlayerId) -> PlayerColor = { id ->
        view.state.players.first { it.id == id }.color
    }

    Canvas(
        modifier = modifier
            .onSizeChanged { boxSize = it }
            .pointerInput(boxSize, mode, legalVertices, legalEdges) {
                if (boxSize == IntSize.Zero) return@pointerInput
                val layout = BoardLayout.fit(
                    Size(boxSize.width.toFloat(), boxSize.height.toFloat()),
                )
                detectTapGestures { point ->
                    when (mode) {
                        BoardMode.SETTLEMENT, BoardMode.CITY ->
                            layout.nearestVertex(point, legalVertices)?.let(onVertexTap)

                        BoardMode.ROAD ->
                            layout.nearestEdge(point, legalEdges)?.let(onEdgeTap)

                        BoardMode.ROBBER ->
                            layout.nearestHex(point, board.hexes.filter { it != board.robber })
                                ?.let(onHexTap)

                        BoardMode.NONE -> Unit
                    }
                }
            },
    ) {
        drawRect(SeaColor)
        val layout = BoardLayout.fit(size)

        for (harbor in board.harbors) {
            drawHarbor(layout, board, harbor, resourceArt)
        }

        val tileSide = layout.size * BoardLayout.TILE_IMAGE_SCALE
        for (tile in board.tiles) {
            drawCentred(tileArt.getValue(tile.type), layout.centerOf(tile.hex), tileSide)
        }

        for (tile in board.tiles) {
            val number = tile.number ?: continue
            val art = tokenArt[number] ?: continue
            drawCentred(art, layout.centerOf(tile.hex), layout.size * TOKEN_SCALE)
        }

        drawCentred(robberArt, layout.centerOf(board.robber), layout.size * ROBBER_SCALE)

        for ((edge, owner) in view.state.roads) {
            val art = roadArt.getValue(colorFor(owner))
            val mid = layout.midpointOf(edge)
            rotate(degrees = layout.angleOf(edge), pivot = mid) {
                drawCentred(art, mid, layout.size * ROAD_IMAGE_SCALE)
            }
        }

        for ((vertex, building) in view.state.buildings) {
            val color = colorFor(building.owner)
            val position = layout.positionOf(vertex)
            if (building.type == BuildingType.CITY) {
                drawCentred(cityArt.getValue(color), position, layout.size * CITY_SCALE)
            } else {
                drawCentred(settlementArt.getValue(color), position, layout.size * SETTLEMENT_SCALE)
            }
        }

        // Show the player where they may act.
        when (mode) {
            BoardMode.SETTLEMENT, BoardMode.CITY ->
                for (vertex in legalVertices) {
                    drawCircle(
                        color = HighlightColor,
                        radius = layout.size * 0.22f,
                        center = layout.positionOf(vertex),
                        alpha = 0.85f,
                        style = Stroke(width = layout.size * 0.09f),
                    )
                }

            BoardMode.ROAD ->
                for (edge in legalEdges) {
                    val mid = layout.midpointOf(edge)
                    rotate(degrees = layout.angleOf(edge), pivot = mid) {
                        drawRoundRectCentred(
                            center = mid,
                            width = layout.size * 0.78f,
                            height = layout.size * 0.20f,
                            color = HighlightColor,
                        )
                    }
                }

            BoardMode.ROBBER ->
                for (hex in board.hexes) {
                    if (hex == board.robber) continue
                    drawCircle(
                        color = HighlightColor,
                        radius = layout.size * 0.42f,
                        center = layout.centerOf(hex),
                        alpha = 0.55f,
                        style = Stroke(width = layout.size * 0.10f),
                    )
                }

            BoardMode.NONE -> Unit
        }
    }
}

private fun DrawScope.drawCentred(image: ImageBitmap, center: Offset, side: Float) {
    val length = side.roundToInt()
    drawImage(
        image = image,
        dstOffset = IntOffset(
            (center.x - side / 2f).roundToInt(),
            (center.y - side / 2f).roundToInt(),
        ),
        dstSize = IntSize(length, length),
    )
}

private fun DrawScope.drawRoundRectCentred(
    center: Offset,
    width: Float,
    height: Float,
    color: Color,
) {
    drawRoundRect(
        color = color,
        topLeft = Offset(center.x - width / 2f, center.y - height / 2f),
        size = Size(width, height),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(height / 2f, height / 2f),
    )
}

/**
 * Draws a harbour.
 *
 * There is no harbour artwork in the asset set, so this builds one: a plank sitting in the sea
 * just off the coast, two jetties running to the two corners the harbour serves, and the trade
 * rate. A 2:1 port also shows the resource it takes.
 */
private fun DrawScope.drawHarbor(
    layout: BoardLayout,
    board: Board,
    harbor: Harbor,
    resourceArt: Map<Resource, ImageBitmap>,
) {
    val (cornerA, cornerB) = harbor.edge.endpoints()
    val a = layout.positionOf(cornerA)
    val b = layout.positionOf(cornerB)
    val mid = layout.midpointOf(harbor.edge)
    val outward = layout.outwardOf(harbor.edge, board)

    val dock = Offset(
        mid.x + outward.x * layout.size * 0.92f,
        mid.y + outward.y * layout.size * 0.92f,
    )

    val jetty = Color(0xFF6B4423)
    drawLine(jetty, a, dock, strokeWidth = layout.size * 0.10f)
    drawLine(jetty, b, dock, strokeWidth = layout.size * 0.10f)

    val plankRadius = layout.size * 0.40f
    drawCircle(Color(0xFF8D6231), radius = plankRadius, center = dock)
    drawCircle(
        color = Color(0xFF3E2A17),
        radius = plankRadius,
        center = dock,
        style = Stroke(width = layout.size * 0.07f),
    )

    if (harbor.resource != null) {
        resourceArt[harbor.resource]?.let { art ->
            drawCentred(art, Offset(dock.x, dock.y - plankRadius * 0.28f), plankRadius * 0.95f)
        }
    }

    val label = if (harbor.resource == null) "3:1" else "2:1"
    val textSize = plankRadius * (if (harbor.resource == null) 0.85f else 0.62f)
    val baseline = if (harbor.resource == null) {
        dock.y + textSize * 0.36f
    } else {
        dock.y + plankRadius * 0.78f
    }

    drawIntoCanvas { canvas ->
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.WHITE
            this.textSize = textSize
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setShadowLayer(textSize * 0.18f, 0f, 0f, android.graphics.Color.BLACK)
        }
        canvas.nativeCanvas.drawText(label, dock.x, baseline, paint)
    }
}
