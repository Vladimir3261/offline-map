package dev.routeodo.core

import org.junit.Assert.*
import org.junit.Test

class RoadPredictionTest {
    private val nodes = listOf(Coordinate(50.0, 30.0), Coordinate(50.001, 30.0), Coordinate(50.002, 30.0), Coordinate(50.001, 30.002), Coordinate(50.001, 29.998))
    private fun graph() = RoadGraph(nodes, listOf(RoadEdge(0,1), RoadEdge(1,2), RoadEdge(1,3), RoadEdge(1,4), RoadEdge(3,1), RoadEdge(1,0)))
    @Test fun rightTurnFollowsConnectedRoadAndReroutesOffline() {
        val graph = graph()
        val predictor = RoadPredictor(graph, nodes[0], 0.0)
        val result = predictor.advance(graph.length(RoadEdge(0,1)) + 20, 90.0)!!
        assertEquals(RoadEdge(1,3), predictor.best!!.edge)
        assertTrue(result.confidence > 0.8)
        val route = graph.route(predictor.best!!, nodes[4])!!
        assertTrue(route.maneuvers.any { it.instruction == "Turn around" })
        assertEquals(nodes[4], route.points.last())
    }
    @Test fun measuredTurnCanCorrectSmallOdometerShortfallAtJunction() {
        val graph = graph(); val predictor = RoadPredictor(graph, nodes[0], 0.0)
        val estimate = predictor.advance(graph.length(RoadEdge(0,1)) - 15, 90.0)!!
        assertEquals(RoadEdge(1,3), predictor.best!!.edge)
        assertTrue(estimate.confidence > 0.8)
        assertEquals(0.0, predictor.best!!.meters, 0.0001)
    }
    @Test fun leftAndReverseAreDistinctFromRight() {
        for ((yaw, target) in listOf(-90.0 to 4, 180.0 to 0)) {
            val graph = graph(); val predictor = RoadPredictor(graph, nodes[0], 0.0)
            predictor.advance(graph.length(RoadEdge(0,1)) + 20, yaw)
            assertEquals(RoadEdge(1,target), predictor.best!!.edge)
        }
    }
    @Test fun indistinguishableForkIsUncertain() {
        val graph = RoadGraph(nodes + Coordinate(50.002, 30.00001), listOf(RoadEdge(0,1), RoadEdge(1,2), RoadEdge(1,5)))
        val predictor = RoadPredictor(graph, nodes[0], 0.0)
        assertTrue(predictor.advance(graph.length(RoadEdge(0,1)) + 20, 0.0)!!.confidence < 0.8)
    }
    @Test fun neverJumpsToUnconnectedRoadOrAgainstOneWay() {
        val graph = RoadGraph(nodes, listOf(RoadEdge(0,1), RoadEdge(3,4)))
        val predictor = RoadPredictor(graph, nodes[0], 0.0)
        assertNull(predictor.advance(graph.length(RoadEdge(0,1)) + 20, 90.0))
        assertNull(graph.route(RoadHypothesis(RoadEdge(0,1), 20.0, 0.0), nodes[0]))
    }
    @Test fun largeDistanceGapInvalidatesEstimate() {
        assertNull(RoadPredictor(graph(), nodes[0], 0.0).advance(501.0, 0.0))
    }
    @Test fun manyRotationsWrapInEitherDirection() {
        assertEquals(90.0, signedAngle(0.0, -3510.0), 0.0001)
        assertEquals(-90.0, signedAngle(3600.0, -90.0), 0.0001)
    }
    @Test fun repeatedStationarySamplesDoNotReduceConfidence() {
        val graph = graph(); val predictor = RoadPredictor(graph, nodes[0], 0.0)
        val initial = predictor.advance(10.0, 10.0)!!
        repeat(100) { assertEquals(initial.confidence, predictor.advance(0.0, 0.0)!!.confidence, 0.00001) }
    }
}
