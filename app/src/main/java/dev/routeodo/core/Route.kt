package dev.routeodo.core

import kotlin.math.*

data class Coordinate(val latitude: Double, val longitude: Double) {
    init {
        require(latitude.isFinite() && latitude in -85.0..85.0) { "Latitude must be between -85 and 85" }
        require(longitude.isFinite() && longitude in -180.0..180.0) { "Longitude must be between -180 and 180" }
    }
}

data class Maneuver(val vertexIndex: Int, val instruction: String)
data class RoutePosition(val coordinate: Coordinate, val bearing: Double, val segment: Int)
data class NextTurn(val instruction: String, val remainingMeters: Double)

class Route(val id: String, val name: String, val points: List<Coordinate>, val maneuvers: List<Maneuver>) {
    val cumulative: DoubleArray
    val lengthMeters: Double get() = cumulative.last()

    init {
        require(points.size >= 2) { "Route needs at least two coordinates" }
        cumulative = DoubleArray(points.size)
        for (i in 1 until points.size) cumulative[i] = cumulative[i - 1] + distance(points[i - 1], points[i])
        require(lengthMeters > 0.01) { "Route has no length" }
        require(maneuvers.all { it.vertexIndex in points.indices }) { "Invalid maneuver index" }
        require(maneuvers.zipWithNext().all { it.first.vertexIndex <= it.second.vertexIndex }) { "Maneuvers out of order" }
    }

    fun position(meters: Double): RoutePosition {
        require(meters.isFinite())
        val d = meters.coerceIn(0.0, lengthMeters)
        // Upper bound preserves route order even at crossings and duplicate vertices.
        var low = 0
        var high = cumulative.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (cumulative[mid] <= d) low = mid + 1 else high = mid
        }
        var i = (low - 1).coerceIn(0, points.lastIndex - 1)
        while (i > 0 && cumulative[i + 1] == cumulative[i]) i--
        val span = cumulative[i + 1] - cumulative[i]
        val fraction = if (span > 0) ((d - cumulative[i]) / span).coerceIn(0.0, 1.0) else 0.0
        return RoutePosition(interpolate(points[i], points[i + 1], fraction), bearing(points[i], points[i + 1]), i)
    }

    fun nextTurn(meters: Double): NextTurn {
        if (meters >= lengthMeters) return NextTurn("Route complete", 0.0)
        val maneuver = maneuvers.firstOrNull { cumulative[it.vertexIndex] > meters + 0.5 }
        return if (maneuver == null) NextTurn("Arrive at destination", (lengthMeters - meters).coerceAtLeast(0.0))
        else NextTurn(maneuver.instruction, (cumulative[maneuver.vertexIndex] - meters).coerceAtLeast(0.0))
    }

    companion object {
        private const val EARTH_RADIUS = 6_371_008.8
        fun distance(a: Coordinate, b: Coordinate): Double {
            val lat1 = Math.toRadians(a.latitude)
            val lat2 = Math.toRadians(b.latitude)
            val dLat = lat2 - lat1
            val dLon = Math.toRadians(b.longitude - a.longitude)
            val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
            return 2 * EARTH_RADIUS * asin(sqrt(h.coerceIn(0.0, 1.0)))
        }

        fun bearing(a: Coordinate, b: Coordinate): Double {
            val lat1 = Math.toRadians(a.latitude)
            val lat2 = Math.toRadians(b.latitude)
            val dLon = Math.toRadians(b.longitude - a.longitude)
            val y = sin(dLon) * cos(lat2)
            val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
            return (Math.toDegrees(atan2(y, x)) + 360) % 360
        }

        private fun interpolate(a: Coordinate, b: Coordinate, t: Double): Coordinate {
            if (t == 0.0) return a
            if (t == 1.0) return b
            val angular = distance(a, b) / EARTH_RADIUS
            if (angular < 1e-12) return a
            val p = sin((1 - t) * angular) / sin(angular)
            val q = sin(t * angular) / sin(angular)
            val lat1 = Math.toRadians(a.latitude)
            val lon1 = Math.toRadians(a.longitude)
            val lat2 = Math.toRadians(b.latitude)
            val lon2 = Math.toRadians(b.longitude)
            val x = p * cos(lat1) * cos(lon1) + q * cos(lat2) * cos(lon2)
            val y = p * cos(lat1) * sin(lon1) + q * cos(lat2) * sin(lon2)
            val z = p * sin(lat1) + q * sin(lat2)
            return Coordinate(Math.toDegrees(atan2(z, sqrt(x*x + y*y))), Math.toDegrees(atan2(y, x)))
        }
    }
}
