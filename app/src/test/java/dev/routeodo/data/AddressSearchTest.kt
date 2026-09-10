package dev.routeodo.data

import dev.routeodo.core.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class AddressSearchTest {
    private fun fixture() = javaClass.getResource("/photon-kyiv.json")!!.readText()

    @Test fun realUkrainianAddressKeepsStreetNumberAndCoordinateOrder() {
        val result = PlaceCodec.photon(fixture()).first()
        assertTrue(result.label.contains("Хрещатик 22"))
        assertTrue(result.label.contains("Київ"))
        assertEquals(50.4498465, result.coordinate.latitude, 1e-7)
        assertEquals(30.5230925, result.coordinate.longitude, 1e-7)
    }

    @Test fun editingAnAddressInvalidatesItsSelectionAndOldSearchResults() {
        val endpoint = EndpointSelection()
        endpoint.edit("Kyiv")
        val request = endpoint.revision
        endpoint.choose(Place("Home", Coordinate(50.45, 30.52)))
        assertNotEquals(request, endpoint.revision)
        endpoint.edit("Another street")
        assertNull(endpoint.place)
        endpoint.edit("Kyiv")
        assertNotEquals(request, endpoint.revision)
    }

    @Test fun savedPlacesRoundTripWithoutGeocodingOrInternet() {
        val home = SavedPlace("Дім", Place("Хрещатик 22, Київ", Coordinate(50.4498465, 30.5230925)))
        assertEquals(listOf(home), SavedPlaceCodec.read(SavedPlaceCodec.write(listOf(home))))
    }

    @Test fun searchEscapesTheQueryBiasesKyivAndCachesRepeatedSearches() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(fixture()))
            val network = Network { 10_000 }
            val query = "Хрещатик 22, Київ & A/B"
            val focus = Coordinate(50.4501, 30.5234)
            val places = network.search(server.url("/").toString(), query, focus)
            val request = server.takeRequest()
            assertEquals(query, request.requestUrl!!.queryParameter("q"))
            assertEquals("50.4501", request.requestUrl!!.queryParameter("lat"))
            assertEquals("30.5234", request.requestUrl!!.queryParameter("lon"))
            assertEquals("/api/", request.requestUrl!!.encodedPath)
            assertEquals(places, network.search(server.url("/").toString(), query, focus))
            assertEquals(1, server.requestCount)
            assertThrows(IllegalArgumentException::class.java) { network.search(server.url("/").toString(), "Another address", focus) }
        }
    }

}
