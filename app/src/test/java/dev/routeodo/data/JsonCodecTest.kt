package dev.routeodo.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class JsonCodecTest {
    @Test fun parsesAndRoundTripsRealRouteWithManeuvers() {
        val sample = File("src/main/assets/demo-route.json").readText()
        val route = JsonCodec.osrm(sample)
        val copy = JsonCodec.route(JsonCodec.route(route))
        assertTrue(copy.points.all { it.latitude in 50.2..50.7 && it.longitude in 30.2..30.9 })
        assertTrue(copy.points.size > 100)
        assertTrue(copy.maneuvers.size > 3)
        assertEquals(route.lengthMeters, copy.lengthMeters, 1e-9)
        assertTrue(copy.lengthMeters > 3000)
        assertTrue(copy.lengthMeters < 10000)
        assertEquals("Arrive at destination", copy.maneuvers.last().instruction)
    }

    @Test fun firmwarePayloadSupportsNullableSamplesAndStrictMetres() {
        val raw = """{"t":1789060085.784,"epoch":"9f3a1c6e","odometer_m":37104,"odometer_t":1789060085.364,"speed_kmh":47,"speed_t":1789060085.751,"connected":true,"clock_synced":true,"resets":0,"source":"n47d_dist_since_regen","mapping_ver":5}"""
        val sample = JsonCodec.carSample(raw)
        assertEquals(37104L, sample.odometerM)
        assertTrue(sample.anchorFresh); assertTrue(sample.speedFresh)
        val startup = JSONObject(raw).put("odometer_m", JSONObject.NULL).put("odometer_t", JSONObject.NULL)
            .put("speed_kmh", JSONObject.NULL).put("speed_t", JSONObject.NULL)
        assertNull(JsonCodec.carSample(startup.toString()).odometerM)
        assertFalse(JsonCodec.carSample(startup.toString()).anchorFresh)
        assertThrows(IllegalArgumentException::class.java) { JsonCodec.carSample(JSONObject(raw).put("odometer_m", "37104").toString()) }
        assertThrows(ArithmeticException::class.java) { JsonCodec.carSample(JSONObject(raw).put("odometer_m", 37104.5).toString()) }
    }
}
