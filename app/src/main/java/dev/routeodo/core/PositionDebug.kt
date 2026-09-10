package dev.routeodo.core

data class GpsFix(val coordinate: Coordinate, val accuracyMeters: Double, val elapsedRealtimeMs: Long) {
    init {
        require(accuracyMeters.isFinite() && accuracyMeters >= 0)
        require(elapsedRealtimeMs >= 0)
    }
}

enum class GpsDisplayState { LIVE, STALE, UNAVAILABLE, PERMISSION_DENIED }

/** Independent display sources. Neither coordinate is merged, snapped, or used to alter progress. */
class PositionDebug private constructor(
    val calculated: Coordinate,
    val gps: GpsFix?,
    val separationMeters: Double?,
    val status: GpsDisplayState,
) {
    companion object {
        const val MAX_GPS_AGE_MS = 5_000L
        fun compare(calculated: Coordinate, fix: GpsFix?, nowElapsedMs: Long, permissionGranted: Boolean = true): PositionDebug {
            val fresh = fix?.takeIf { permissionGranted && nowElapsedMs >= it.elapsedRealtimeMs &&
                nowElapsedMs - it.elapsedRealtimeMs <= MAX_GPS_AGE_MS }
            val status = when {
                !permissionGranted -> GpsDisplayState.PERMISSION_DENIED
                fresh != null -> GpsDisplayState.LIVE
                fix != null -> GpsDisplayState.STALE
                else -> GpsDisplayState.UNAVAILABLE
            }
            return PositionDebug(calculated, fresh, fresh?.let { Route.distance(calculated, it.coordinate) }, status)
        }
    }
}
