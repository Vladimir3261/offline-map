package dev.routeodo.core

import kotlin.math.*
import java.util.PriorityQueue

data class RoadEdge(val from: Int, val to: Int)
data class RoadHypothesis(val edge: RoadEdge, val meters: Double, val error: Double, val headingChange: Double = 0.0, val prior: Double = error)
data class RoadEstimate(val position: RoutePosition, val confidence: Double)

/** Directed, drivable graph. Intersections must share node IDs; crossings alone never connect. */
class RoadGraph(val nodes: List<Coordinate>, val edges: List<RoadEdge>) {
    val outgoing = edges.groupBy { it.from }
    init {
        require(nodes.size in 2..50000 && edges.size in 1..100000)
        require(edges.all { it.from in nodes.indices && it.to in nodes.indices && length(it) > 0.1 })
    }
    fun length(e: RoadEdge) = Route.distance(nodes[e.from], nodes[e.to])
    fun heading(e: RoadEdge) = Route.bearing(nodes[e.from], nodes[e.to])
    fun position(h: RoadHypothesis) = Route("edge", "", listOf(nodes[h.edge.from], nodes[h.edge.to]), emptyList()).position(h.meters)

    /** Shortest legal directed path from a hypothesis, with local geometry-derived guidance. */
    fun route(h: RoadHypothesis, destination: Coordinate): Route? {
        val target = nodes.indices.minBy { Route.distance(nodes[it], destination) }
        if (Route.distance(nodes[target], destination) > 100) return null
        val costs = mutableMapOf(h.edge.to to 0.0)
        val previous = mutableMapOf<Int, Int>()
        val queue = PriorityQueue<Pair<Int, Double>>(compareBy { it.second })
        queue.add(h.edge.to to 0.0)
        while (queue.isNotEmpty()) {
            val (node, cost) = queue.remove()
            if (cost > costs.getValue(node)) continue
            if (node == target) break
            for (edge in outgoing[node].orEmpty()) {
                val next = cost + length(edge)
                if (next < (costs[edge.to] ?: Double.POSITIVE_INFINITY)) {
                    costs[edge.to] = next; previous[edge.to] = node; queue.add(edge.to to next)
                }
            }
        }
        if (target !in costs) return null
        val path = mutableListOf(target)
        while (path.last() != h.edge.to) path += previous[path.last()] ?: return null
        val points = (listOf(position(h).coordinate) + path.reversed().map { nodes[it] }).fold(mutableListOf<Coordinate>()) { acc, p ->
            if (acc.isEmpty() || Route.distance(acc.last(), p) > 0.1) acc.add(p)
            acc
        }
        if (points.size < 2) return null
        val turns = (1 until points.lastIndex).mapNotNull { i ->
            val angle = signedAngle(Route.bearing(points[i-1], points[i]), Route.bearing(points[i], points[i+1]))
            val instruction = when { abs(angle) > 145 -> "Turn around"; angle > 30 -> "Turn right"; angle < -30 -> "Turn left"; else -> null }
            instruction?.let { Maneuver(i, it) }
        }
        return Route("prediction-${System.nanoTime()}", "Experimental offline route", points, turns)
    }
}

fun signedAngle(from: Double, to: Double) = (((to - from) % 360 + 540) % 360) - 180

/** Beam search advances only over connected roads; gyro yaw is relative to the starting route heading. */
class RoadPredictor(private val graph: RoadGraph, start: Coordinate, bearing: Double) {
    private val initialBearing = bearing
    private var yaw = 0.0
    private var hypotheses = graph.edges.mapNotNull { edge ->
        val line = Route("seed", "", listOf(graph.nodes[edge.from], graph.nodes[edge.to]), emptyList())
        val projection = line.project(start).first()
        if (projection.distanceFromRouteMeters > 50 || abs(signedAngle(bearing, graph.heading(edge))) > 60) null
        else RoadHypothesis(edge, projection.progressMeters, projection.distanceFromRouteMeters / 10 + abs(signedAngle(bearing, graph.heading(edge))) / 30)
    }.sortedBy { it.error }.take(12)
    var best: RoadHypothesis? = hypotheses.firstOrNull(); private set
    fun advance(distance: Double, clockwiseYaw: Double): RoadEstimate? {
        require(distance.isFinite() && distance >= 0 && clockwiseYaw.isFinite())
        yaw = ((yaw + clockwiseYaw) % 360 + 360) % 360
        if (distance > 500) { hypotheses = emptyList(); best = null; return null }
        val expanded = mutableListOf<RoadHypothesis>()
        fun walk(h: RoadHypothesis, remaining: Double, depth: Int) {
            if (depth > 64 || expanded.size >= 512) return
            val available = graph.length(h.edge) - h.meters
            if (remaining <= available) {
                expanded += h.copy(meters = h.meters + remaining)
                // Odometer anchors are approximate: a measured turn can identify a nearby junction
                // before the distance estimate reaches its exact node. Never snap while stationary.
                val shortfall = available - remaining
                val measuredHeading = initialBearing + yaw
                if (distance > 0 && shortfall <= 25 && abs(signedAngle(measuredHeading, graph.heading(h.edge))) > 45) {
                    for (edge in graph.outgoing[h.edge.to].orEmpty()) {
                        if (abs(signedAngle(measuredHeading, graph.heading(edge))) <= 35) {
                            val prior = h.prior + shortfall / 10
                            expanded += RoadHypothesis(edge, 0.0, prior,
                                h.headingChange + signedAngle(graph.heading(h.edge), graph.heading(edge)), prior)
                        }
                    }
                }
                return
            }
            for (edge in graph.outgoing[h.edge.to].orEmpty()) {
                walk(RoadHypothesis(edge, 0.0, h.error, h.headingChange + signedAngle(graph.heading(h.edge), graph.heading(edge)), h.prior), remaining - available, depth + 1)
            }
        }
        hypotheses.forEach { walk(it, distance, 0) }
        hypotheses = expanded.map { h ->
            val headingError = abs(signedAngle((initialBearing + yaw + 3600) % 360, graph.heading(h.edge)))
            h.copy(error = h.prior + (headingError / 25).pow(2))
        }.sortedBy { it.error }.distinctBy { it.edge to (it.meters / 5).toInt() }.take(12)
        best = hypotheses.firstOrNull()
        val first = best ?: return null
        val confidence = if (first.error > 12) 0.0 else {
            val gap = hypotheses.getOrNull(1)?.let { it.error - first.error } ?: 6.0
            (1 - exp(-gap.coerceAtLeast(0.0))).coerceIn(0.0, 1.0)
        }
        return RoadEstimate(graph.position(first), confidence)
    }
}
