package com.catan.core.rules

import com.catan.core.model.BuildingType
import com.catan.core.model.EdgeId
import com.catan.core.model.GameState
import com.catan.core.model.PlayerId
import com.catan.core.model.VertexId
import com.catan.core.model.endpoints
import com.catan.core.model.touches

/** Where pieces may legally go. Shared by the engine and by the client's highlighting. */
object Placement {

    /**
     * A corner is open for a settlement when it is empty and every neighbouring corner is
     * empty too - the distance rule.
     */
    fun satisfiesDistanceRule(state: GameState, vertex: VertexId): Boolean =
        state.buildings[vertex] == null &&
            state.board.neighborsOf(vertex).none { state.buildings[it] != null }

    /** Corners where [playerId] may place a settlement during setup: distance rule only. */
    fun setupSettlementSpots(state: GameState): Set<VertexId> =
        state.board.vertices.filterTo(mutableSetOf()) { satisfiesDistanceRule(state, it) }

    /**
     * Corners where [playerId] may build a settlement in normal play: the distance rule plus
     * a road of their own running into the corner.
     */
    fun settlementSpots(state: GameState, playerId: PlayerId): Set<VertexId> {
        val ownRoads = state.roadsOf(playerId)
        return state.board.vertices.filterTo(mutableSetOf()) { vertex ->
            satisfiesDistanceRule(state, vertex) && ownRoads.any { it.touches(vertex) }
        }
    }

    /** Corners where [playerId] may upgrade a settlement to a city. */
    fun citySpots(state: GameState, playerId: PlayerId): Set<VertexId> =
        state.buildings
            .filterValues { it.owner == playerId && it.type == BuildingType.SETTLEMENT }
            .keys

    /**
     * True when [playerId] may attach a road to [vertex].
     *
     * An opponent's settlement or city seals the corner: a road may not be routed through it,
     * even though the corner is physically shared.
     */
    private fun canConnectAt(state: GameState, playerId: PlayerId, vertex: VertexId): Boolean {
        val building = state.buildings[vertex]
        if (building != null && building.owner != playerId) return false
        if (building != null) return true
        return state.roadsOf(playerId).any { it.touches(vertex) }
    }

    /** Edges where [playerId] may build a road. */
    fun roadSpots(state: GameState, playerId: PlayerId): Set<EdgeId> =
        state.board.edges.filterTo(mutableSetOf()) { edge ->
            if (state.roads.containsKey(edge)) return@filterTo false
            val (a, b) = edge.endpoints()
            canConnectAt(state, playerId, a) || canConnectAt(state, playerId, b)
        }

    /**
     * During setup the road must run out of the settlement just placed, not merely touch the
     * player's existing network.
     */
    fun setupRoadSpots(state: GameState, settlement: VertexId): Set<EdgeId> =
        state.board.edgesAt(settlement).filterTo(mutableSetOf()) { !state.roads.containsKey(it) }
}
