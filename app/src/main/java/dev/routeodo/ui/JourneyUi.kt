package dev.routeodo.ui

import dev.routeodo.core.Route
import dev.routeodo.data.RouteDetails
import kotlin.math.ceil

enum class JourneyPhase { EXPLORE, READY, STARTING, NAVIGATING, SIMULATION, PICK_START, PICK_DESTINATION }

data class JourneyUi(val phase: JourneyPhase, val title: String, val summary: String, val origin: String) {
    val guiding get() = phase in listOf(JourneyPhase.STARTING, JourneyPhase.NAVIGATING, JourneyPhase.SIMULATION)
    val picking get() = phase == JourneyPhase.PICK_START || phase == JourneyPhase.PICK_DESTINATION
    companion object {
        fun present(selected: Boolean, running: Boolean, starting: Boolean, simulation: Boolean, pickingStart: Boolean?, route: Route,
                    details: RouteDetails?, format: (Double) -> String): JourneyUi {
            val phase = when {
                pickingStart != null -> if (pickingStart) JourneyPhase.PICK_START else JourneyPhase.PICK_DESTINATION
                !selected -> JourneyPhase.EXPLORE
                simulation -> JourneyPhase.SIMULATION
                starting -> JourneyPhase.STARTING
                running -> JourneyPhase.NAVIGATING
                else -> JourneyPhase.READY
            }
            val duration = details?.durationSeconds?.let { "${maxOf(1, ceil(it / 60).toInt())} min · " }.orEmpty()
            return JourneyUi(phase, details?.destination?.title ?: route.name.substringAfter(" → "),
                duration + format(route.lengthMeters), details?.start?.title?.let { "From $it" } ?: "Review the route before starting")
        }
    }
}
