package dev.routeodo.core

import org.junit.Assert.*
import org.junit.Test

class PositionDebugTest {
    private val origin = Coordinate(0.0, 0.0)
    private fun east(meters: Double) = Coordinate(0.0, Math.toDegrees(meters / 6_371_008.8))
    private fun fix(coordinate: Coordinate, time: Long = 10_000, accuracy: Double = 8.0) = GpsFix(coordinate, accuracy, time)

    @Test fun bothSourcesRemainPresentAtOverlapAndEitherSideOfTheOldFiftyMetreThreshold() {
        for (meters in listOf(0.0, 1.0, 49.99, 50.0, 50.01, 84.0)) {
            val view = PositionDebug.compare(origin, fix(east(meters)), 10_000)
            assertEquals(GpsDisplayState.LIVE, view.status)
            assertEquals(origin, view.calculated)
            assertEquals(east(meters), view.gps!!.coordinate)
            assertEquals(meters, view.separationMeters!!, 0.00001)
        }
    }
    @Test fun comparesRawGpsAcrossRouteInsteadOfSnappingIt() {
        val route = Route("test", "test", listOf(origin, east(1000.0)), emptyList())
        val calculated = route.position(500.0).coordinate
        val rawGps = Coordinate(Math.toDegrees(80.0 / 6_371_008.8), calculated.longitude)
        val view = PositionDebug.compare(calculated, fix(rawGps), 10_000)
        assertEquals(80.0, view.separationMeters!!, 0.001)
        assertEquals(rawGps, view.gps!!.coordinate)
        assertEquals(calculated, view.calculated)
    }
    @Test fun staleAndFutureFixesAreExplicitAndNeverPresentedAsCurrent() {
        assertEquals(GpsDisplayState.LIVE, PositionDebug.compare(origin, fix(origin), 15_000).status)
        for (time in listOf(15_001L, 9_999L)) {
            val view = PositionDebug.compare(origin, fix(origin), time)
            assertEquals(GpsDisplayState.STALE, view.status)
            assertNull(view.gps); assertNull(view.separationMeters)
            assertEquals(origin, view.calculated)
        }
        assertEquals(GpsDisplayState.UNAVAILABLE, PositionDebug.compare(origin, null, 10_000).status)
    }
    @Test fun movementAndGpsRecoveryRecomputeDistanceIndependently() {
        val gps = fix(east(30.0))
        assertEquals(30.0, PositionDebug.compare(origin, gps, 10_000).separationMeters!!, .001)
        val moved = east(100.0)
        assertEquals(70.0, PositionDebug.compare(moved, gps, 11_000).separationMeters!!, .001)
        assertNull(PositionDebug.compare(moved, gps, 16_000).gps)
        val recovered = PositionDebug.compare(moved, fix(east(125.0), 16_000), 16_000)
        assertEquals(25.0, recovered.separationMeters!!, .001)
        assertEquals(moved, recovered.calculated)
    }
    @Test fun reportedAccuracyNeverChangesEitherCoordinateOrSeparation() {
        val view = PositionDebug.compare(origin, fix(east(60.0), accuracy = 100.0), 10_000)
        assertEquals(100.0, view.gps!!.accuracyMeters, 0.0)
        assertEquals(60.0, view.separationMeters!!, .001)
    }
    @Test fun permissionDenialOverridesACachedFreshFix() {
        val view = PositionDebug.compare(origin, fix(east(1.0)), 10_000, permissionGranted = false)
        assertEquals(GpsDisplayState.PERMISSION_DENIED, view.status)
        assertNull(view.gps); assertNull(view.separationMeters)
    }
}
