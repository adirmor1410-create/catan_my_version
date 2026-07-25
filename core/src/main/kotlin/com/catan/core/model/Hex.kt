package com.catan.core.model

import kotlinx.serialization.Serializable

/**
 * Axial coordinate for a pointy-top hex.
 *
 * World position of the centre, for a hex of size `s`:
 *   x = s * sqrt(3) * (q + r / 2)
 *   y = s * 3/2 * r
 */
@Serializable
data class Hex(val q: Int, val r: Int) : Comparable<Hex> {

    operator fun plus(d: HexDirection): Hex = Hex(q + d.dq, r + d.dr)

    fun neighbors(): List<Hex> = HexDirection.entries.map { this + it }

    override fun compareTo(other: Hex): Int =
        if (q != other.q) q.compareTo(other.q) else r.compareTo(other.r)

    override fun toString(): String = "($q,$r)"
}

/** The six neighbour directions, in counter-clockwise order starting East. */
@Serializable
enum class HexDirection(val dq: Int, val dr: Int) {
    E(1, 0),
    NE(1, -1),
    NW(0, -1),
    W(-1, 0),
    SW(-1, 1),
    SE(0, 1);

    fun next(): HexDirection = entries[(ordinal + 1) % 6]
    fun opposite(): HexDirection = entries[(ordinal + 3) % 6]
}

/**
 * A board corner, identified by the three hexes that meet at it.
 *
 * Some of those hexes may lie outside the playable board; including them anyway is what
 * makes the identity canonical, so the same corner reached from two different tiles always
 * produces the same [VertexId].
 */
@Serializable
data class VertexId(val hexes: List<Hex>) : Comparable<VertexId> {

    init {
        require(hexes.size == 3) { "A vertex is defined by exactly 3 hexes, got ${hexes.size}" }
    }

    override fun compareTo(other: VertexId): Int {
        for (i in 0 until 3) {
            val c = hexes[i].compareTo(other.hexes[i])
            if (c != 0) return c
        }
        return 0
    }

    override fun toString(): String = "V[" + hexes.joinToString(",") + "]"

    companion object {
        fun of(a: Hex, b: Hex, c: Hex): VertexId = VertexId(listOf(a, b, c).sorted())

        /** The corner of [hex] lying between neighbour [dir] and the next neighbour counter-clockwise. */
        fun corner(hex: Hex, dir: HexDirection): VertexId = of(hex, hex + dir, hex + dir.next())
    }
}

/** A board edge, identified by the two hexes that share it. */
@Serializable
data class EdgeId(val hexes: List<Hex>) : Comparable<EdgeId> {

    init {
        require(hexes.size == 2) { "An edge is defined by exactly 2 hexes, got ${hexes.size}" }
    }

    override fun compareTo(other: EdgeId): Int {
        for (i in 0 until 2) {
            val c = hexes[i].compareTo(other.hexes[i])
            if (c != 0) return c
        }
        return 0
    }

    override fun toString(): String = "E[" + hexes.joinToString(",") + "]"

    companion object {
        fun of(a: Hex, b: Hex): EdgeId = EdgeId(listOf(a, b).sorted())

        fun side(hex: Hex, dir: HexDirection): EdgeId = of(hex, hex + dir)
    }
}

/**
 * The two corners at the ends of [edge].
 *
 * For an edge shared by hexes A and B, the endpoints are the two corners {A,B,C} and {A,B,D},
 * where C and D are the hexes adjacent to both A and B.
 */
fun EdgeId.endpoints(): Pair<VertexId, VertexId> {
    val (a, b) = hexes
    val shared = a.neighbors().filter { it != b && b.neighbors().contains(it) }
    check(shared.size == 2) { "Edge $this should have exactly 2 flanking hexes, found ${shared.size}" }
    return VertexId.of(a, b, shared[0]) to VertexId.of(a, b, shared[1])
}

/** The (up to three) edges meeting at [vertex]: one per pair of its defining hexes. */
fun VertexId.incidentEdges(): List<EdgeId> {
    val (a, b, c) = hexes
    return listOf(EdgeId.of(a, b), EdgeId.of(a, c), EdgeId.of(b, c))
}

/** The (up to three) corners one edge-step away from [vertex]. */
fun VertexId.adjacentVertices(): List<VertexId> =
    incidentEdges().flatMap { edge ->
        edge.endpoints().toList().filter { it != this }
    }.distinct()

/** True when [edge] touches [vertex]. */
fun EdgeId.touches(vertex: VertexId): Boolean = hexes.all { vertex.hexes.contains(it) }
