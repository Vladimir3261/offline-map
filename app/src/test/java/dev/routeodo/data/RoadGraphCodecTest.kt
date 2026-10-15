package dev.routeodo.data

import org.junit.Assert.*
import org.junit.Test

class RoadGraphCodecTest {
    @Test fun longitudeComesFirstAndEdgesStayDirected() {
        val graph = RoadGraphCodec.read("""{"version":1,"nodes":[[30,50],[30,50.001]],"edges":[[0,1]]}""")
        assertEquals(50.0, graph.nodes.first().latitude, 0.0)
        assertTrue(graph.outgoing[1].isNullOrEmpty())
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsUnknownVersion() {
        RoadGraphCodec.read("""{"version":2,"nodes":[],"edges":[]}""")
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsMissingNode() {
        RoadGraphCodec.read("""{"version":1,"nodes":[[30,50],[30,50.001]],"edges":[[0,2]]}""")
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsZeroLengthRoad() {
        RoadGraphCodec.read("""{"version":1,"nodes":[[30,50],[30,50]],"edges":[[0,1]]}""")
    }
}
