package dev.routeodo.data

import dev.routeodo.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OsmRoadGraphTest {
    private val bounds = RoadCoverage(49.99, 29.99, 50.01, 30.01)
    private fun node(id: Long, lat: Double = 50.0, lon: Double = 30.0, tags: Map<String, String> = emptyMap()) =
        JSONObject().put("type", "node").put("id", id).put("lat", lat).put("lon", lon).put("tags", JSONObject(tags))
    private fun way(id: Long, vararg nodes: Long, tags: Map<String, String> = emptyMap()) =
        JSONObject().put("type", "way").put("id", id).put("nodes", JSONArray(nodes.toList()))
            .put("tags", JSONObject(mapOf("highway" to "residential") + tags))
    private fun restriction(kind: String, from: Long = 10, to: Long = 20, via: Long = 2, viaType: String = "node", conditional: Boolean = false) =
        JSONObject().put("type", "relation").put("id", 100).put("tags", JSONObject(mapOf("type" to "restriction",
            (if (conditional) "restriction:conditional" else "restriction") to kind)))
            .put("members", JSONArray(listOf(
                JSONObject().put("type", "way").put("ref", from).put("role", "from"),
                JSONObject().put("type", viaType).put("ref", via).put("role", "via"),
                JSONObject().put("type", "way").put("ref", to).put("role", "to"))))
    private fun graph(vararg items: JSONObject) = OsmRoadGraph.read(JSONObject().put("elements", JSONArray(items.toList())).toString(), bounds, 1000)
    private fun junction(vararg extras: JSONObject) = graph(node(1, 49.999), node(2), node(3, 50.0, 30.001), node(4, 50.001),
        way(10, 1, 2), way(20, 2, 3), way(30, 2, 4), *extras)
    private fun RoadGraph.index(lat: Double, lon: Double = 30.0) = nodes.indexOf(Coordinate(lat, lon))

    @Test fun publicRoadsExcludeFootwaysAndPrivateRoadsButAllowCarOverride() {
        val graph = graph(node(1), node(2, 50.001), node(3, 50.002), node(4, 50.003), node(5, 50.004),
            way(10, 1, 2), way(20, 2, 3, tags = mapOf("highway" to "footway")),
            way(30, 3, 4, tags = mapOf("access" to "private")),
            way(40, 4, 5, tags = mapOf("access" to "no", "motorcar" to "yes")))
        assertEquals(4, graph.edges.size)
        assertEquals(-1, graph.index(50.002))
    }
    @Test fun oneWaysReverseWaysAndRoundaboutsKeepDirections() {
        for ((tags, forward, backward) in listOf(
            Triple(mapOf("oneway" to "yes"), true, false),
            Triple(mapOf("oneway" to "-1"), false, true),
            Triple(mapOf("junction" to "roundabout"), true, false),
            Triple(mapOf("highway" to "motorway"), true, false),
            Triple(mapOf("junction" to "roundabout", "oneway" to "no"), true, true))) {
            val graph = graph(node(1), node(2, 50.001), way(10, 1, 2, tags = tags))
            assertEquals(forward, RoadEdge(0, 1) in graph.edges)
            assertEquals(backward, RoadEdge(1, 0) in graph.edges)
        }
    }
    @Test fun equalCoordinatesNeverConnectBridgeAndRoad() {
        val graph = graph(node(1, 49.999), node(2), node(3), node(4, 50.0, 30.001), way(10, 1, 2), way(20, 3, 4))
        assertEquals(4, graph.nodes.size)
        assertNull(graph.route(RoadHypothesis(RoadEdge(0,1), 0.0, 0.0), Coordinate(50.0, 30.001)))
    }
    @Test fun noRightTurnIsUsedByPredictionAndRouting() {
        val graph = junction(restriction("no_right_turn"))
        val incoming = RoadEdge(graph.index(49.999), graph.index(50.0))
        val right = RoadEdge(graph.index(50.0), graph.index(50.0, 30.001))
        assertFalse(right in graph.successors(incoming))
        val predictor = RoadPredictor(graph, Coordinate(49.999,30.0), 0.0)
        predictor.advance(graph.length(incoming) + 10, 90.0)
        assertNotEquals(right, predictor.best?.edge)
        val route = graph.route(RoadHypothesis(incoming, 0.0, 0.0), Coordinate(50.0, 30.001))!!
        assertTrue(route.points.contains(Coordinate(50.001, 30.0)))
    }
    @Test fun onlyTurnBlocksAllOtherExits() {
        val graph = junction(restriction("only_right_turn"))
        val incoming = RoadEdge(graph.index(49.999), graph.index(50.0))
        assertEquals(listOf(RoadEdge(graph.index(50.0), graph.index(50.0,30.001))), graph.successors(incoming))
    }
    @Test fun noUturnOnSameWayStillAllowsStraight() {
        val graph = graph(node(1,49.999),node(2),node(3,50.001),way(10,1,2,3), restriction("no_u_turn",to=10))
        val incoming = RoadEdge(0,1)
        assertFalse(RoadEdge(1,0) in graph.successors(incoming))
        assertTrue(RoadEdge(1,2) in graph.successors(incoming))
    }
    @Test fun conditionalBansApplyConservativelyAndViaWayOnlyRestrictionStopsAllExits() {
        val conditional = junction(restriction("no_right_turn @ (Mo-Fr)", conditional = true))
        assertEquals(1, conditional.blockedTurns.size)
        val via = junction(restriction("only_right_turn", via = 20, viaType = "way", to = 30))
        assertTrue(via.successors(RoadEdge(via.index(49.999),via.index(50.0))).isEmpty())
    }
    @Test(expected = IllegalArgumentException::class) fun incompleteResponseIsRejected() {
        graph(node(1),way(10,1,2))
    }
    @Test(expected = IllegalArgumentException::class) fun overpassPartialResultIsRejected() {
        OsmRoadGraph.read("""{"remark":"runtime error: Query timed out", "elements":[]}""",bounds,1000)
    }
    @Test fun graphCodecRetainsBoundsAgeAndRestrictions() {
        val graph = junction(restriction("no_right_turn"))
        val restored = RoadGraphCodec.read(RoadGraphCodec.write(graph))
        assertEquals(graph.edges, restored.edges)
        assertEquals(graph.blockedTurns, restored.blockedTurns)
        assertEquals(bounds, restored.coverage)
        assertEquals(1000L, restored.downloadedAtMillis)
    }
}
