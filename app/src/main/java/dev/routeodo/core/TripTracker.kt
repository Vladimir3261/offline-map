package dev.routeodo.core

import java.util.UUID
import kotlin.math.max

data class CarSample(
    val t: Double,
    val epoch: String,
    val odometerM: Long?,
    val odometerT: Double?,
    val speedKmh: Int?,
    val speedT: Double?,
    val connected: Boolean,
    val clockSynced: Boolean = true,
    val resets: Int = 0,
    val source: String = "",
    val mappingVersion: Int = 5,
) {
    init {
        require(t.isFinite() && t >= 0 && epoch.isNotBlank() && epoch.length <= 200)
        require(odometerM == null || odometerM >= 0)
        require(speedKmh == null || speedKmh >= 0)
        require(listOfNotNull(odometerT, speedT).all { it.isFinite() && it >= 0 })
    }
    // Acquisition freshness is always measured on the car's clock, never against phone UTC.
    val anchorFresh: Boolean get() = connected && odometerM != null && odometerT != null && t - odometerT in 0.0..5.0
    val speedFresh: Boolean get() = speedKmh != null && speedT != null && t - speedT in 0.0..1.0
}

data class RouteGap(val progressMeters: Double, val fromEpoch: String, val toEpoch: String)

data class TripState(
    val routeAnchorMeters: Double = 0.0,
    val travelAnchorMeters: Double = 0.0,
    val scale: Double = 1.0,
    val displayedTravelMeters: Double = 0.0,
    val confirmedTravelMeters: Double = 0.0,
    val carriedMeters: Double = 0.0,
    val baseEpoch: String? = null,
    val baseM: Long? = null,
    val lastAnchorM: Long? = null,
    val lastAnchorT: Double? = null,
    val holdingForAnchor: Boolean = false,
    val blockedReason: String? = null,
    val navigationSessionId: String? = null,
    val running: Boolean = false,
    val gaps: List<RouteGap> = emptyList(),
) {
    val progressMeters: Double get() = routeAnchorMeters + (displayedTravelMeters - travelAnchorMeters) * scale
}

data class FeedResult(val accepted: Boolean, val message: String)

class TripTracker(initial: TripState = TripState()) {
    var state: TripState = initial
        private set

    /** Start waits for a usable anchor; the car's epoch is separate from this local navigation session. */
    fun start() {
        state = TripState(scale = state.scale, navigationSessionId = UUID.randomUUID().toString(), running = true)
    }

    fun stop() { state = state.copy(running = false) }

    fun accept(sample: CarSample): FeedResult {
        if (!state.running) return FeedResult(true, if (sample.anchorFresh) "Car connected · press Start route" else "Car connected · waiting for odometer")
        if (state.baseEpoch == null) {
            if (!sample.anchorFresh) return FeedResult(false, staleMessage(sample))
            state = state.copy(baseEpoch = sample.epoch, baseM = sample.odometerM, lastAnchorM = sample.odometerM, lastAnchorT = sample.odometerT)
            return FeedResult(true, "Route started · odometer anchor saved")
        }
        if (sample.epoch != state.baseEpoch) {
            // Carry only confirmed distance. Keep the previous display until a new anchor catches it.
            val gap = RouteGap(state.progressMeters, state.baseEpoch!!, sample.epoch)
            state = state.copy(carriedMeters = state.confirmedTravelMeters, baseEpoch = sample.epoch,
                baseM = null, lastAnchorM = null, lastAnchorT = null, holdingForAnchor = true,
                gaps = (state.gaps + gap).takeLast(100), blockedReason = null)
        }
        state.blockedReason?.let { return FeedResult(false, it) }
        if (!sample.anchorFresh) return FeedResult(false, staleMessage(sample))
        val odometer = sample.odometerM!!
        if (state.baseM == null) {
            state = state.copy(baseM = odometer, lastAnchorM = odometer, lastAnchorT = sample.odometerT,
                holdingForAnchor = state.carriedMeters < state.displayedTravelMeters)
            return FeedResult(true, "Car restarted · new anchor set · distance gap marked")
        }
        if (odometer < state.lastAnchorM!!) return block("Odometer decreased within epoch · use Align to rebase")
        val confirmed = state.carriedMeters + (odometer - state.baseM!!).toDouble()
        val newAnchor = odometer != state.lastAnchorM || sample.odometerT != state.lastAnchorT
        if (newAnchor) {
            state = state.copy(confirmedTravelMeters = confirmed, lastAnchorM = odometer, lastAnchorT = sample.odometerT,
                holdingForAnchor = confirmed < state.displayedTravelMeters,
                displayedTravelMeters = max(confirmed, state.displayedTravelMeters))
            return FeedResult(true, if (state.holdingForAnchor) "Holding until odometer catches estimate" else "Navigating · odometer anchor")
        }
        if (state.holdingForAnchor) return FeedResult(true, "Holding until odometer catches estimate")
        if (!sample.speedFresh) return FeedResult(true, "Speed unavailable · odometer only")
        val age = sample.t - sample.odometerT!!
        val estimate = confirmed + sample.speedKmh!! / 3.6 * age
        state = state.copy(confirmedTravelMeters = confirmed, displayedTravelMeters = max(estimate, state.displayedTravelMeters))
        return FeedResult(true, if (sample.speedKmh == 0) "Car connected · odometer only" else "Navigating · speed smoothing")
    }

    fun anchor(sample: CarSample, routeMeters: Double, scale: Double = state.scale) {
        require(state.running && sample.anchorFresh) { "A running route and fresh odometer are needed to align" }
        require(routeMeters.isFinite() && routeMeters >= 0)
        require(scale.isFinite() && scale in 0.5..1.5) { "Calibration must be between 0.5 and 1.5" }
        if (state.blockedReason != null) {
            state = state.copy(carriedMeters = state.displayedTravelMeters, confirmedTravelMeters = state.displayedTravelMeters,
                baseEpoch = sample.epoch, baseM = sample.odometerM, lastAnchorM = sample.odometerM,
                lastAnchorT = sample.odometerT, holdingForAnchor = false, blockedReason = null)
        } else accept(sample)
        state = state.copy(routeAnchorMeters = routeMeters, travelAnchorMeters = state.displayedTravelMeters, scale = scale)
    }

    fun restoreAlignment(previous: TripState) {
        require(state.running && previous.running && state.blockedReason == null &&
            state.navigationSessionId != null && state.navigationSessionId == previous.navigationSessionId) {
            "Navigation session changed since sync; align position manually"
        }
        // Keep current car epoch, gap history, and distance; undo only the route alignment.
        state = state.copy(routeAnchorMeters = previous.routeAnchorMeters, travelAnchorMeters = previous.travelAnchorMeters, scale = previous.scale)
    }

    private fun staleMessage(sample: CarSample) = when {
        !sample.connected -> "Position stale · ECU disconnected"
        sample.odometerM == null || sample.odometerT == null -> "Odometer unavailable · waiting for first sample"
        else -> "Position stale · odometer anchor is old"
    }

    private fun block(reason: String): FeedResult {
        state = state.copy(blockedReason = reason)
        return FeedResult(false, reason)
    }
}
