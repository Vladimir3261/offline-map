package dev.routeodo.ui

import dev.routeodo.core.Route
import dev.routeodo.core.RoutePosition
import kotlin.math.abs
import kotlin.math.hypot

/** Camera state only. Never feeds back into the positioning engine. */
class FollowState {
    var enabled = true
        private set
    private var last: RoutePosition? = null
    private var lastMoveMs = Long.MIN_VALUE
    fun browse() { enabled = false }
    fun recenter() { enabled = true; last = null }
    fun shouldMove(position: RoutePosition, nowMs: Long): Boolean {
        if (!enabled) return false
        val previous = last
        if (previous != null) {
            if (nowMs - lastMoveMs < 650) return false
            val turn = abs((position.bearing - previous.bearing + 540) % 360 - 180)
            if (Route.distance(previous.coordinate, position.coordinate) < 1 && turn < 3) return false
        }
        last = position; lastMoveMs = nowMs
        return true
    }
}

object MarkerOverlap {
    /** Pixel footprints, not a geographic merge radius. Both source features always remain present. */
    fun visibleOverlap(dxPixels: Double, dyPixels: Double, density: Double): Boolean =
        hypot(dxPixels, dyPixels) <= 32 * density
}
