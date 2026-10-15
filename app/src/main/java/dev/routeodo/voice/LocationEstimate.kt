package dev.routeodo.voice

import dev.routeodo.core.*
import kotlin.math.*

/** Frozen when the driver presses Talk, not when a slow place search finishes. */
data class LocationObservation(
    val coordinate: Coordinate, val atMillis: Long, val travelMeters: Double,
    val heading: Double?, val route: Route?, val progressMeters: Double?, val sessionId: String?,
    val speedMetersPerSecond: Double? = null
)

data class LocationEstimate(
    val coordinate: Coordinate, val progressMeters: Double?, val routeId: String?, val sessionId: String?,
    val offRouteMeters: Double?, val movementMeters: Double, val uncertain: Boolean
)

object LocationEstimator {
    fun estimate(place: Place, observation: LocationObservation, currentTravel: Double, secondsAgo: Double = 0.0): LocationEstimate {
        require(secondsAgo.isFinite() && secondsAgo in 0.0..120.0)
        val delta = (currentTravel - observation.travelMeters).coerceAtLeast(0.0)
        val past = secondsAgo * (observation.speedMetersPerSecond ?: 0.0)
        val route = observation.route
        val prior = observation.progressMeters
        if (route == null || prior == null) return LocationEstimate(place.coordinate, null, null, observation.sessionId,
            null, delta + past, true)
        val candidates = route.project(place.coordinate)
        val best = candidates.minBy { it.distanceFromRouteMeters + abs(it.progressMeters - (prior - past)) * .15 }
        val progress = (best.progressMeters + delta + past).coerceIn(0.0, route.lengthMeters)
        val ambiguous = candidates.any { abs(it.progressMeters - best.progressMeters) > 100 &&
            it.distanceFromRouteMeters <= best.distanceFromRouteMeters + 15 &&
            abs(it.progressMeters - (prior - past)) <= abs(best.progressMeters - (prior - past)) + 100 }
        val suitable = best.distanceFromRouteMeters <= 100
        return LocationEstimate(if (suitable) route.position(progress).coordinate else place.coordinate,
            if (suitable) progress else null, route.id, observation.sessionId, best.distanceFromRouteMeters,
            delta + past, ambiguous || !suitable || (secondsAgo > 0 && observation.speedMetersPerSecond == null))
    }
}
