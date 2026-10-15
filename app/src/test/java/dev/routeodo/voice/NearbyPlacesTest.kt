package dev.routeodo.voice

import dev.routeodo.core.Coordinate
import org.junit.Assert.*
import org.junit.Test

class NearbyPlacesTest {
    @Test fun queryCannotInjectArbitraryOverpassOrUnboundedArea() {
        val point = Coordinate(50.45, 30.52)
        assertTrue(NearbyPlaces.query("supermarket", point, 1200).contains("nwr[shop=supermarket]"))
        assertThrows(Exception::class.java) { NearbyPlaces.query("supermarket];out;", point, 1200) }
        assertThrows(Exception::class.java) { NearbyPlaces.query("shop", point, 99999) }
    }
    @Test fun unnamedShopsAndWayCentersAreUsableEvidence() {
        val places = NearbyPlaces.decode("""{"elements":[{"type":"way","id":1,"center":{"lat":50.45,"lon":30.52},"tags":{"shop":"supermarket","brand":"Market","addr:street":"Street"}},{"type":"node","id":2,"lat":50.451,"lon":30.52,"tags":{"amenity":"fuel"}}]}""")
        assertEquals(2, places.size); assertEquals("Market, Street", places[0].label)
        assertEquals("fuel", places[1].label)
        assertThrows(Exception::class.java) { NearbyPlaces.decode("""{"remark":"runtime timeout","elements":[]}""") }
    }
}
