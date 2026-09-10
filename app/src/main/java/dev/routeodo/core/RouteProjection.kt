package dev.routeodo.core

import kotlin.math.*

data class RouteProjection(val progressMeters: Double, val distanceFromRouteMeters: Double)

/** One-off GPS fixes are projected onto the selected route; this never drives live tracking. */
fun Route.project(fix: Coordinate): List<RouteProjection> {
    val candidates = points.zipWithNext().mapIndexed { i, (a, b) ->
        fun x(p: Coordinate): Double {
            val longitudeDelta = ((p.longitude - fix.longitude + 540) % 360) - 180
            return Math.toRadians(longitudeDelta) * 6_371_008.8 * cos(Math.toRadians(fix.latitude))
        }
        fun y(p: Coordinate) = Math.toRadians(p.latitude - fix.latitude) * 6_371_008.8
        val ax = x(a); val ay = y(a); val bx = x(b); val by = y(b)
        val dx = bx - ax; val dy = by - ay
        val squaredLength = dx*dx + dy*dy
        val t = if (squaredLength > 0) (-(ax*dx + ay*dy) / squaredLength).coerceIn(0.0, 1.0) else 0.0
        RouteProjection(cumulative[i] + t * (cumulative[i+1] - cumulative[i]), hypot(ax + t*dx, ay + t*dy))
    }.sortedBy { it.distanceFromRouteMeters }
    // Neighboring segments often represent the same point. Keep distinct route occurrences.
    val distinct = mutableListOf<RouteProjection>()
    for (candidate in candidates) {
        if (distinct.none { abs(it.progressMeters - candidate.progressMeters) < 40 }) distinct += candidate
        if (distinct.size == 8) break
    }
    return distinct
}
