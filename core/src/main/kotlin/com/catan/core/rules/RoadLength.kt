package com.catan.core.rules

import com.catan.core.model.EdgeId
import com.catan.core.model.GameState
import com.catan.core.model.PlayerId
import com.catan.core.model.Rules
import com.catan.core.model.VertexId
import com.catan.core.model.endpoints

/**
 * Longest-road bookkeeping.
 *
 * "Longest road" is the longest trail through a player's own road segments: a route that never
 * reuses a segment, though it may pass through the same corner twice (a figure-eight is legal).
 * A route is cut at any corner holding an opponent's settlement or city - it may end there, but
 * it cannot continue through.
 */
object RoadLength {

    /** The length of [playerId]'s longest continuous road. */
    fun longestFor(state: GameState, playerId: PlayerId): Int {
        val owned = state.roadsOf(playerId)
        if (owned.isEmpty()) return 0

        val incident = mutableMapOf<VertexId, MutableList<EdgeId>>()
        for (edge in owned) {
            val (a, b) = edge.endpoints()
            incident.getOrPut(a) { mutableListOf() }.add(edge)
            incident.getOrPut(b) { mutableListOf() }.add(edge)
        }

        // A corner blocks travel when someone else has built on it.
        val blocked: Set<VertexId> = incident.keys.filterTo(mutableSetOf()) { vertex ->
            state.buildings[vertex]?.let { it.owner != playerId } == true
        }

        var best = 0
        val used = mutableSetOf<EdgeId>()

        fun explore(from: VertexId): Int {
            var longest = 0
            for (edge in incident[from].orEmpty()) {
                if (edge in used) continue
                val (a, b) = edge.endpoints()
                val next = if (a == from) b else a

                used.add(edge)
                val onward = if (next in blocked) 0 else explore(next)
                longest = maxOf(longest, 1 + onward)
                used.remove(edge)
            }
            return longest
        }

        for (start in incident.keys) {
            best = maxOf(best, explore(start))
            if (best == owned.size) break // cannot do better than using every segment
        }
        return best
    }

    /**
     * Recomputes the Longest Road holder.
     *
     * The holder keeps the card while tied. If the holder falls behind and several others are
     * tied for the new best, the card is set aside until one player leads outright, exactly as
     * the printed rules describe.
     */
    fun recalculate(state: GameState): GameState {
        val lengths = state.players.associate { it.id to longestFor(state, it.id) }
        val best = lengths.values.maxOrNull() ?: 0

        if (best < Rules.MIN_LONGEST_ROAD) {
            return if (state.longestRoadHolder == null) {
                state.copy(longestRoadLength = 0)
            } else {
                state.copy(longestRoadHolder = null, longestRoadLength = 0)
                    .withLog("Longest Road is no longer held by anyone.")
            }
        }

        val leaders = lengths.filterValues { it == best }.keys
        val holder = state.longestRoadHolder

        val newHolder = when {
            holder != null && holder in leaders -> holder
            leaders.size == 1 -> leaders.first()
            else -> null
        }

        if (newHolder == holder) {
            return state.copy(longestRoadLength = if (holder == null) 0 else best)
        }

        val message = if (newHolder == null) {
            "Longest Road is set aside - several players are tied."
        } else {
            "${state.player(newHolder).name} takes Longest Road ($best)."
        }
        return state
            .copy(longestRoadHolder = newHolder, longestRoadLength = if (newHolder == null) 0 else best)
            .withLog(message)
    }

    /**
     * Recomputes the Largest Army holder. It moves only on a strictly larger army, and once
     * three knights are played it can never return to nobody.
     */
    fun recalculateArmy(state: GameState): GameState {
        val leader = state.players
            .filter { it.knightsPlayed >= Rules.MIN_LARGEST_ARMY }
            .maxByOrNull { it.knightsPlayed }
            ?: return state

        if (leader.knightsPlayed <= state.largestArmySize) return state
        if (state.largestArmyHolder == leader.id) {
            return state.copy(largestArmySize = leader.knightsPlayed)
        }
        return state
            .copy(largestArmyHolder = leader.id, largestArmySize = leader.knightsPlayed)
            .withLog("${leader.name} takes Largest Army (${leader.knightsPlayed} knights).")
    }
}
