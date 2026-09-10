package dev.routeodo.core

import org.junit.Assert.*
import org.junit.Test

class RouteTest {
    private val a = Coordinate(0.0, 0.0)
    private val b = Coordinate(0.0, 0.01)
    private val c = Coordinate(0.01, 0.01)
    private fun route(points: List<Coordinate> = listOf(a, b, c)) = Route("test", "Test", points, listOf(Maneuver(1, "Turn left"), Maneuver(points.lastIndex, "Arrive")))

    @Test fun advancesByDistanceAroundCorner() {
        val r = route()
        assertEquals(1111.95, r.cumulative[1], 0.02)
        val first = r.position(r.cumulative[1] / 2)
        assertEquals(0.005, first.coordinate.longitude, 1e-8)
        assertEquals(0.0, first.coordinate.latitude, 1e-8)
        val second = r.position(r.cumulative[1] * 1.5)
        assertEquals(0.01, second.coordinate.longitude, 1e-8)
        assertEquals(0.005, second.coordinate.latitude, 1e-8)
        assertEquals("Turn left", r.nextTurn(200.0).instruction)
        assertEquals("Arrive", r.nextTurn(r.cumulative[1]).instruction)
    }

    @Test fun crossingsAndDuplicatesKeepOrderedProgress() {
        val r = route(listOf(a, a, b, a, c, c))
        assertEquals(a, r.position(0.0).coordinate)
        assertEquals(3, r.position(r.cumulative[3] + 1).segment)
        assertEquals(c, r.position(r.lengthMeters + 100).coordinate)
        assertEquals("Route complete", r.nextTurn(r.lengthMeters).instruction)
    }

    @Test fun gpsProjectionReturnsDistinctOccurrencesOnLoop() {
        val r = route(listOf(a, b, a, c))
        val candidates = r.project(a)
        assertTrue(candidates.any { it.progressMeters < 1 })
        assertTrue(candidates.any { kotlin.math.abs(it.progressMeters - r.cumulative[2]) < 1 })
    }

    @Test fun projectsGpsOntoSegmentWithLateralDistance() {
        val r = route()
        val projection = r.project(Coordinate(0.0001, 0.005)).first()
        assertEquals(r.cumulative[1] / 2, projection.progressMeters, 0.02)
        assertEquals(11.1195, projection.distanceFromRouteMeters, 0.01)
    }

    @Test fun interpolationUsesShortestLongitudeAcrossDateLine() {
        val r = Route("date", "Date", listOf(Coordinate(0.0, 179.99), Coordinate(0.0, -179.99)), emptyList())
        assertEquals(180.0, kotlin.math.abs(r.position(r.lengthMeters / 2).coordinate.longitude), 1e-6)
    }
}
