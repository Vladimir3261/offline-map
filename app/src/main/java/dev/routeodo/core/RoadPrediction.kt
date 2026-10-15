package dev.routeodo.core

import kotlin.math.*
import java.util.PriorityQueue

data class RoadEdge(val from: Int, val to: Int)
data class RoadTurn(val incoming: RoadEdge, val outgoing: RoadEdge)
data class RoadHypothesis(val edge: RoadEdge, val meters: Double, val error: Double, val headingChange: Double = 0.0, val prior: Double = error)
data class RoadEstimate(val position: RoutePosition, val confidence: Double)

/** Directed, drivable graph. Intersections must share node IDs; crossings alone never connect. */
class RoadGraph(
    val nodes: List<Coordinate>, val edges: List<RoadEdge>,
    val blockedTurns: Set<RoadTurn> = emptySet(),
    val coverage: RoadCoverage? = null, val downloadedAtMillis: Long? = null,
) {
    val outgoing = edges.groupBy { it.from }
    init {
        require(nodes.size in 2..50000 && edges.size in 1..100000)
        require(edges.all { it.from in nodes.indices && it.to in nodes.indices && length(it) > 0.1 })
        val known = edges.toHashSet()
        require(blockedTurns.size <= 200000 && blockedTurns.all {
            it.incoming in known && it.outgoing in known && it.incoming.to == it.outgoing.from
        })
    }
    fun successors(edge: RoadEdge) = outgoing[edge.to].orEmpty().filter { RoadTurn(edge, it) !in blockedTurns }
    fun length(e: RoadEdge) = Route.distance(nodes[e.from], nodes[e.to])
    fun heading(e: RoadEdge) = Route.bearing(nodes[e.from], nodes[e.to])
    fun position(h: RoadHypothesis) = Route("edge", "", listOf(nodes[h.edge.from], nodes[h.edge.to]), emptyList()).position(h.meters)

    /** Dijkstra over incoming edges retains turn restrictions and projected destinations. */
    fun route(h: RoadHypothesis, destination: Coordinate): Route? {
        val targets = edges.map { edge ->
            val line = Route("target", "", listOf(nodes[edge.from], nodes[edge.to]), emptyList())
            val projection = line.project(destination).first()
            Triple(edge, projection.progressMeters, projection.distanceFromRouteMeters)
        }
        val nearest = targets.minOf { it.third }
        if (nearest > 100) return null
        val nearby = targets.filter { it.third <= minOf(100.0, nearest + 2.0) }
        val byFrom = nearby.groupBy { it.first.from }
        var bestCost = Double.POSITIVE_INFINITY
        var goalPrevious: RoadEdge? = null
        var goal: Pair<RoadEdge, Double>? = null
        for ((edge, meters) in nearby) {
            if (edge == h.edge && meters >= h.meters) {
                bestCost = meters - h.meters; goal = edge to meters
            }
        }
        val costs = mutableMapOf(h.edge to (length(h.edge) - h.meters))
        val previous = mutableMapOf<RoadEdge, RoadEdge>()
        val queue = PriorityQueue<Pair<RoadEdge, Double>>(compareBy { it.second })
        queue.add(h.edge to costs.getValue(h.edge))
        while (queue.isNotEmpty()) {
            val (edge, cost) = queue.remove()
            if (cost > costs.getValue(edge) || cost >= bestCost) continue
            for ((target, meters) in byFrom[edge.to].orEmpty()) {
                if (RoadTurn(edge, target) !in blockedTurns && cost + meters < bestCost) {
                    bestCost = cost + meters; goalPrevious = edge; goal = target to meters
                }
            }
            for (next in successors(edge)) {
                val nextCost = cost + length(next)
                if (nextCost < (costs[next] ?: Double.POSITIVE_INFINITY) && nextCost < bestCost) {
                    costs[next] = nextCost; previous[next] = edge; queue.add(next to nextCost)
                }
            }
        }
        val (target, meters) = goal ?: return null
        val points = mutableListOf(position(h).coordinate)
        goalPrevious?.let { last ->
            val path = mutableListOf(last)
            while (path.last() != h.edge) path += previous[path.last()] ?: return null
            points += path.reversed().map { nodes[it.to] }
        }
        points += position(RoadHypothesis(target, meters, 0.0)).coordinate
        val distinct = points.fold(mutableListOf<Coordinate>()) { acc, point ->
            if (acc.isEmpty() || Route.distance(acc.last(), point) > 0.1) acc.add(point)
            acc
        }
        if (distinct.size < 2) return null
        val turns = (1 until distinct.lastIndex).mapNotNull { i ->
            val angle = signedAngle(Route.bearing(distinct[i-1], distinct[i]), Route.bearing(distinct[i], distinct[i+1]))
            val instruction = when { abs(angle) > 145 -> "Turn around"; angle > 30 -> "Turn right"; angle < -30 -> "Turn left"; else -> null }
            instruction?.let { Maneuver(i, it) }
        }
        return Route("prediction-${System.nanoTime()}", "Experimental offline route", distinct, turns)
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
                    for (edge in graph.successors(h.edge)) {
                        if (abs(signedAngle(measuredHeading, graph.heading(edge))) <= 35) {
                            val prior = h.prior + shortfall / 10
                            expanded += RoadHypothesis(edge, 0.0, prior,
                                h.headingChange + signedAngle(graph.heading(h.edge), graph.heading(edge)), prior)
                        }
                    }
                }
                return
            }
            for (edge in graph.successors(h.edge)) {
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
