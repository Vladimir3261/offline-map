package dev.routeodo.core

import org.junit.Assert.*
import org.junit.Test

class PositionDebugTest {
    private val origin = Coordinate(0.0, 0.0)
    private fun east(meters: Double) = Coordinate(0.0, Math.toDegrees(meters / 6_371_008.8))
    private fun fix(coordinate: Coordinate, time: Long = 10_000, accuracy: Double = 8.0) = GpsFix(coordinate, accuracy, time)

    @Test fun mergesAtFiftyMetersAndSplitsImmediatelyBeyondIt() {
        for (meters in listOf(0.0, 49.99, 50.0)) {
            val view = PositionDebug.compare(origin, fix(east(meters)), 10_000)
            assertTrue("$meters m should merge", view.merged)
            assertNull(view.separateGps)
            assertEquals(meters, view.separationMeters!!, 0.00001)
        }
        val beyond = PositionDebug.compare(origin, fix(east(50.01)), 10_000)
        assertFalse(beyond.merged)
        assertEquals(east(50.01), beyond.separateGps)
    }

    @Test fun comparesRawGpsAcrossRouteInsteadOfSnappingIt() {
        val route = Route("test", "test", listOf(origin, east(1000.0)), emptyList())
        val calculated = route.position(500.0).coordinate
        val rawGps = Coordinate(Math.toDegrees(80.0 / 6_371_008.8), calculated.longitude)
        val view = PositionDebug.compare(calculated, fix(rawGps), 10_000)
        assertFalse(view.merged)
        assertEquals(80.0, view.separationMeters!!, 0.001)
        assertEquals(rawGps, view.separateGps)
        assertEquals(calculated, view.calculated)
    }

    @Test fun expiredOrFutureGpsCannotAppearLiveOrMerged() {
        assertTrue(PositionDebug.compare(origin, fix(origin), 15_000).merged)
        for (time in listOf(15_001L, 9_999L)) {
            val view = PositionDebug.compare(origin, fix(origin), time)
            assertFalse(view.merged)
            assertNull(view.gps)
            assertNull(view.separateGps)
            assertNull(view.separationMeters)
            assertEquals(origin, view.calculated)
        }
        assertNull(PositionDebug.compare(origin, null, 10_000).gps)
    }

    @Test fun movementAndGpsRecoveryReevaluateMergeWithoutMovingTheEstimate() {
        val gps = fix(east(30.0))
        assertTrue(PositionDebug.compare(origin, gps, 10_000).merged)
        val moved = east(100.0)
        assertFalse(PositionDebug.compare(moved, gps, 11_000).merged)
        assertNull(PositionDebug.compare(moved, gps, 16_000).gps)
        val recovered = PositionDebug.compare(moved, fix(east(125.0), 16_000), 16_000)
        assertTrue(recovered.merged)
        assertEquals(moved, recovered.calculated)
        assertEquals(east(125.0), recovered.gps!!.coordinate)
    }

    @Test fun accuracyDoesNotExpandTheRequestedMergeThreshold() {
        val view = PositionDebug.compare(origin, fix(east(60.0), accuracy = 100.0), 10_000)
        assertFalse(view.merged)
        assertEquals(100.0, view.gps!!.accuracyMeters, 0.0)
    }
}
