package dev.routeodo.ui

import dev.routeodo.core.*
import dev.routeodo.data.CarFeedIssue
import kotlin.math.max
import kotlin.math.roundToInt

/** Snapshot of existing state, with no authority to change the car feed or navigation tracker. */
data class DrivingInputs(
    val route: Route, val progress: Double, val trip: TripState, val debug: PositionDebug, val now: Long,
    val live: Boolean = true, val simRunning: Boolean = false, val starting: Boolean = false,
    val latest: CarSample? = null, val latestAt: Long = 0, val connectionStartedAt: Long = 0,
    val carIssue: CarFeedIssue = CarFeedIssue.NONE, val carIssueMessage: String = "", val transport: String = "",
    val feedMessage: String = "", val follow: Boolean = true, val saveError: String? = null,
)

data class DrivingPresentation(val screen: DrivingState, val feedText: String)

object DrivingPresenter {
    fun present(input: DrivingInputs, formatDistance: (Double) -> String): DrivingPresentation = with(input) {
        val next = route.nextTurn(progress)
        val carOffline = live && now - max(latestAt, connectionStartedAt) >= 10_000
        val stale = live && (carOffline || latest?.anchorFresh == false || carIssue != CarFeedIssue.NONE)
        val feedText = when {
            !live -> if (simRunning) "Preview drive · simulated 50 km/h" else "Route preview · Start route uses your odometer API"
            carIssue == CarFeedIssue.AUTH -> carIssueMessage
            carIssue == CarFeedIssue.UNAVAILABLE -> "Odometer unavailable · car channel not loaded"
            carIssue == CarFeedIssue.INVALID -> carIssueMessage
            carOffline -> "Car offline · no response for 10 seconds"
            trip.blockedReason != null -> trip.blockedReason!!
            starting -> "Waiting for the starting odometer anchor…"
            carIssue == CarFeedIssue.NETWORK -> "Car connection unavailable · holding position"
            else -> "$feedMessage · $transport"
        }
        val source = when {
            !live -> "PREVIEW"
            carIssue == CarFeedIssue.AUTH -> "API AUTH ERROR"
            carIssue == CarFeedIssue.UNAVAILABLE -> "ODO UNAVAILABLE"
            carIssue == CarFeedIssue.INVALID -> "API DATA ERROR"
            carOffline -> "CAR OFFLINE"
            starting || latest == null && carIssue == CarFeedIssue.NONE -> "API CONNECTING"
            stale || trip.blockedReason != null -> "ODO · HOLDING"
            !trip.running -> "ODO · STOPPED"
            debug.status == GpsDisplayState.STALE -> "GPS STALE"
            debug.gps != null -> "ODO + GPS"
            else -> "ODO"
        }
        val comparison = when (debug.status) {
            GpsDisplayState.LIVE -> "GPS ↔ ODO: ${formatDistance(debug.separationMeters!!)}"
            GpsDisplayState.STALE -> "GPS stale"
            GpsDisplayState.PERMISSION_DENIED -> "Location permission denied"
            GpsDisplayState.UNAVAILABLE -> "GPS unavailable"
        }
        val accuracy = debug.gps?.let { "GPS accuracy: ±${it.accuracyMeters.roundToInt()} m" }
            ?: if (debug.status == GpsDisplayState.STALE) "Waiting for a fresh fix · tap for details" else "Tap for GPS details and settings"
        val connection = when {
            !live -> if (simRunning) "Simulated drive" else "Simulation paused"
            carIssue == CarFeedIssue.AUTH -> "API authentication failed"
            carIssue == CarFeedIssue.UNAVAILABLE -> "Odometer unavailable"
            carIssue == CarFeedIssue.INVALID -> "Invalid API response"
            carOffline -> "No car response"
            latest?.connected == false -> "ECU disconnected"
            stale -> "Position stale · holding"
            latest == null -> "Connecting to car…"
            else -> transport.ifBlank { "Car connected" }
        }
        val speed = if (!live) if (simRunning) "50" else "0"
            else latest?.takeIf { it.speedFresh && it.anchorFresh && !stale && now - latestAt < 1500 }?.speedKmh?.toString() ?: "—"
        val notice = saveError ?: when {
            !live -> "PREVIEW · simulated position"
            carIssue == CarFeedIssue.AUTH -> "Update token in Services → API"
            trip.blockedReason != null -> "Odometer changed · alignment needed"
            stale -> "${if (carOffline) "Car offline" else "Position stale"} · holding position"
            trip.gaps.isNotEmpty() -> "${trip.gaps.size} distance gap(s) marked on route"
            else -> ""
        }
        return DrivingPresentation(DrivingState(if (progress >= route.lengthMeters) "Arrived" else formatDistance(next.remainingMeters), next.instruction,
            "${formatDistance((route.lengthMeters - progress).coerceAtLeast(0.0))} remaining", speed, source, connection, notice,
            comparison, accuracy, debug.status, !live, stale, follow), feedText)
    }
}
