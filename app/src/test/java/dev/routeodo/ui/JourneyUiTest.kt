package dev.routeodo.ui

import dev.routeodo.core.*
import dev.routeodo.data.RouteDetails
import org.junit.Assert.*
import org.junit.Test

class JourneyUiTest {
    private val route = Route("r", "Example", listOf(Coordinate(50.4, 30.5), Coordinate(50.5, 30.5)), emptyList())
    private fun view(selected: Boolean = true, running: Boolean = false, starting: Boolean = false,
                     simulation: Boolean = false, picking: Boolean? = null) = JourneyUi.present(selected, running, starting, simulation, picking, route, null) { "${it.toInt()} m" }
    @Test fun freshInstallExploresAndPlannedRouteRequiresExplicitStart() {
        assertEquals(JourneyPhase.EXPLORE, view(selected = false).phase)
        assertEquals(JourneyPhase.READY, view().phase)
        assertFalse(view().guiding)
        assertEquals(JourneyPhase.STARTING, view(running = true, starting = true).phase)
        assertEquals(JourneyPhase.NAVIGATING, view(running = true).phase)
    }
    @Test fun simulationAndMapSelectionAreExplicit() {
        assertEquals(JourneyPhase.SIMULATION, view(simulation = true).phase)
        assertEquals(JourneyPhase.PICK_START, view(running = true, picking = true).phase)
        assertEquals(JourneyPhase.PICK_DESTINATION, view(picking = false).phase)
        assertFalse(view(running = true, picking = true).guiding)
    }
    @Test fun previewMetadataSurvivesPersistenceWithoutChangingGeometry() {
        val details = RouteDetails(Place("Station, Kyiv", route.points.first()), Place("Сільпо, Kyiv", route.points.last(), "Shop"), 451.0)
        assertEquals(details, RouteDetails.read(details.json()))
        val view = JourneyUi.present(true, false, false, false, null, route, details) { "1.5 km" }
        assertEquals("Сільпо", view.title); assertEquals("8 min · 1.5 km", view.summary); assertEquals("From Station", view.origin)
    }
}
