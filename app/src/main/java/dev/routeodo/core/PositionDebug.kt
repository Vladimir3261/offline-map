package dev.routeodo.core

data class GpsFix(val coordinate: Coordinate, val accuracyMeters: Double, val elapsedRealtimeMs: Long) {
    init {
        require(accuracyMeters.isFinite() && accuracyMeters >= 0)
        require(elapsedRealtimeMs >= 0)
    }
}

/** Display-only comparison: neither coordinate is snapped, averaged, or used to change trip progress. */
class PositionDebug private constructor(
    val calculated: Coordinate,
    val gps: GpsFix?,
    val separationMeters: Double?,
) {
    val merged: Boolean get() = separationMeters != null && separationMeters <= MERGE_DISTANCE_METERS
    val separateGps: Coordinate? get() = gps?.coordinate?.takeUnless { merged }

    companion object {
        const val MERGE_DISTANCE_METERS = 50.0
        const val MAX_GPS_AGE_MS = 5_000L

        fun compare(calculated: Coordinate, fix: GpsFix?, nowElapsedMs: Long): PositionDebug {
            val fresh = fix?.takeIf { nowElapsedMs >= it.elapsedRealtimeMs &&
                nowElapsedMs - it.elapsedRealtimeMs <= MAX_GPS_AGE_MS }
            return PositionDebug(calculated, fresh, fresh?.let { Route.distance(calculated, it.coordinate) })
        }
    }
}
