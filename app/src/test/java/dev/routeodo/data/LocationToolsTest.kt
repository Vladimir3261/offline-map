package dev.routeodo.data

import dev.routeodo.core.*
import dev.routeodo.voice.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LocationToolsTest {
    private val center = Coordinate(50.45, 30.52)
    private val nearby = Place("Shop, Kyiv", Coordinate(50.451, 30.52))
    private var now = 1000L
    private var proposal: LocationProposal? = null
    private val tools = LocationTools({ JSONObject().put("travel_m", 42) }, { center },
        { _, _ -> listOf(nearby, Place("Shop, far away", Coordinate(49.0, 30.0))) }, { now }, { 42.0 }, { proposal = it })

    @Test fun searchFiltersFarBranchesAndIssuesSessionEvidence() {
        val result = tools.execute("search_places", JSONObject().put("query", "Shop"))
        assertEquals(1, result.getJSONArray("places").length())
        assertNull(proposal)
        val id = result.getJSONArray("places").getJSONObject(0).getString("id")
        val response = tools.execute("propose_location", args(id))
        assertEquals("estimate_ready", response.getString("status"))
        assertEquals(nearby, proposal!!.place)
        assertEquals(42.0, proposal!!.travelMeters, 0.0)
    }

    @Test fun fabricatedAndExpiredEvidenceCannotBeProposed() {
        assertThrows(Exception::class.java) { tools.execute("propose_location", args("invented")) }
        tools.execute("search_places", JSONObject().put("query", "Shop"))
        now += 120001
        assertThrows(Exception::class.java) { tools.execute("propose_location", args("p1")) }
        assertNull(proposal)
    }

    @Test fun pastSightingProducesBestEstimateWithoutDemandingCurrentLandmark() {
        tools.execute("search_places", JSONObject().put("query", "Shop"))
        tools.execute("propose_location", args("p1").put("seconds_ago", 5))
        assertNotNull(proposal)
        assertTrue(proposal!!.estimate!!.uncertain)
        assertTrue(proposal!!.confidence <= 55)
    }

    @Test fun eachReportHasBoundedSearchAndCannotReuseOldEvidence() {
        tools.execute("search_places", JSONObject().put("query", "Shop"))
        tools.execute("search_places", JSONObject().put("query", "Shop again"))
        assertThrows(Exception::class.java) { tools.execute("search_places", JSONObject().put("query", "More")) }
        tools.beginReport(LocationObservation(center, now, 42.0, null, null, null, null))
        assertThrows(Exception::class.java) { tools.execute("propose_location", args("p1")) }
        tools.execute("search_places", JSONObject().put("query", "New report"))
    }

    @Test fun genericSupermarketOnRightUsesCategorySearchAndExposesSide() {
        val local = LocationTools({ JSONObject() }, { center }, { _, _ -> emptyList() }, { now }, { 0.0 }, { proposal = it },
            { category, _, _ -> assertEquals("supermarket", category); listOf(Place("Market", Coordinate(50.45, 30.521))) })
        local.beginReport(LocationObservation(center, now, 0.0, 0.0, null, null, null))
        val result = local.execute("nearby_places", JSONObject().put("category", "supermarket"))
        val place = result.getJSONArray("places").getJSONObject(0)
        assertEquals("right", place.getString("side"))
        local.execute("propose_location", args(place.getString("id")))
        assertNotNull(proposal)
    }

    @Test fun contextStaysFreshAndArgumentsAreBounded() {
        assertEquals(42, tools.execute("location_context", JSONObject()).getInt("travel_m"))
        assertThrows(Exception::class.java) { tools.execute("search_places", JSONObject().put("query", "Shop").put("radius_m", -1)) }
        assertThrows(Exception::class.java) { tools.execute("search_places", JSONObject().put("query", "x")) }
        assertThrows(Exception::class.java) { tools.execute("arbitrary_network_request", JSONObject()) }
    }
    private fun args(id: String) = JSONObject().put("place_id", id).put("reason", "Best match from the reported clue")
        .put("confidence", 75)
}
