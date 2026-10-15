package dev.routeodo.voice

import dev.routeodo.core.*
import org.junit.Assert.*
import org.junit.Test

class LocationEstimatorTest {
    private val route = Route("r", "North", listOf(Coordinate(50.44, 30.52), Coordinate(50.46, 30.52)), emptyList())
    private val landmark = Place("Market", route.position(500.0).coordinate)
    private val observation = LocationObservation(route.position(520.0).coordinate, 1000, 100.0, 0.0, route, 520.0, "session", 10.0)

    @Test fun movementDuringSpeechAndSearchIsRetained() {
        val result = LocationEstimator.estimate(landmark, observation, 250.0)
        assertEquals(650.0, result.progressMeters!!, .1)
        assertEquals(150.0, result.movementMeters, .001)
        assertEquals("session", result.sessionId)
    }
    @Test fun pastSightingUsesSuppliedTimingWithoutDemandingNewLandmark() {
        val result = LocationEstimator.estimate(landmark, observation, 150.0, 5.0)
        assertEquals(600.0, result.progressMeters!!, .1)
    }
    @Test fun unknownTimingSpeedAndOffRoutePlacesRemainUncertain() {
        assertTrue(LocationEstimator.estimate(landmark, observation.copy(speedMetersPerSecond = null), 100.0, 5.0).uncertain)
        val off = Place("Far away", Coordinate(50.45, 30.6))
        val result = LocationEstimator.estimate(off, observation, 200.0)
        assertNull(result.progressMeters); assertTrue(result.uncertain)
        assertEquals(off.coordinate, result.coordinate)
        assertThrows(IllegalArgumentException::class.java) { LocationEstimator.estimate(landmark, observation, 200.0, -1.0) }
    }
    @Test fun noRouteProducesAnAreaEstimateWithoutInventedMovementDirection() {
        val result = LocationEstimator.estimate(landmark, observation.copy(route = null, progressMeters = null), 200.0)
        assertTrue(result.uncertain); assertNull(result.progressMeters); assertEquals(landmark.coordinate, result.coordinate)
    }
}
