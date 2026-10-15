package dev.routeodo.data

import dev.routeodo.core.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.URLDecoder

class RoadDownloaderTest {
    private val route = Route("test", "", listOf(Coordinate(50.0,30.0), Coordinate(50.001,30.0)),emptyList())
    private val osm = """{"elements":[{"type":"node","id":1,"lat":50.0,"lon":30.0},{"type":"node","id":2,"lat":50.001,"lon":30.0},{"type":"way","id":3,"nodes":[1,2],"tags":{"highway":"residential"}}]}"""
    @Test fun downloadsConvertsAndValidatesWithoutCredentials() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(osm))
            val graph = RoadDownloader(server.url("/api/interpreter").toString()).fetch(route)
            assertEquals(2,graph.edges.size)
            assertTrue(graph.coverage!!.contains(route))
            val request = server.takeRequest()
            assertEquals("POST",request.method)
            assertNull(request.getHeader("Authorization"))
            val query = URLDecoder.decode(request.body.readUtf8(),"UTF-8")
            assertTrue(query.contains("rel(bw.roads)"))
            assertTrue(query.contains("out body"))
        }
    }
    @Test fun busyServiceFallsBackOnceToAnotherProvider() {
        MockWebServer().use { first -> MockWebServer().use { second ->
            first.enqueue(MockResponse().setResponseCode(429))
            second.enqueue(MockResponse().setBody(osm))
            val graph = RoadDownloader(first.url("/").toString(), fallbackEndpoint = second.url("/").toString()).fetch(route)
            assertEquals(2,graph.edges.size)
            assertEquals(1,first.requestCount)
            assertEquals(1,second.requestCount)
        } }
    }
    @Test fun serviceFailureIsReportedWithoutGraph() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429))
            val result = runCatching { RoadDownloader(server.url("/").toString()).fetch(route) }
            assertTrue(result.exceptionOrNull()!!.message!!.contains("busy"))
        }
    }
    @Test fun cancellationStopsActiveRequestWithoutTryingMirror() {
        MockWebServer().use { first -> MockWebServer().use { second ->
            first.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            val downloader = RoadDownloader(first.url("/").toString(), fallbackEndpoint = second.url("/").toString())
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
            try {
                val future = executor.submit<RoadGraph> { downloader.fetch(route) }
                assertNotNull(first.takeRequest(3, java.util.concurrent.TimeUnit.SECONDS))
                downloader.cancel()
                val failure = runCatching { future.get(3, java.util.concurrent.TimeUnit.SECONDS) }.exceptionOrNull()
                assertTrue(failure is java.util.concurrent.ExecutionException && failure.cause is IOException)
                assertEquals(0, second.requestCount)
            } finally { downloader.cancel(); executor.shutdownNow() }
        } }
    }
    @Test fun oversizedResponseIsRejectedBeforeParsing() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}").setHeader("Content-Length", 21 * 1024 * 1024))
            val failure = runCatching { RoadDownloader(server.url("/").toString()).fetch(route) }.exceptionOrNull()
            assertTrue(failure!!.message!!.contains("too large"))
        }
    }
    @Test fun cancelledDownloadDoesNotStartRequest() {
        MockWebServer().use { server ->
            val downloader = RoadDownloader(server.url("/").toString())
            downloader.cancel()
            assertTrue(runCatching { downloader.fetch(route) }.exceptionOrNull() is IOException)
            assertEquals(0,server.requestCount)
        }
    }
    @Test fun rejectsRouteOutsideGraph() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(osm))
            val elsewhere = Route("x","",listOf(Coordinate(51.0,30.0),Coordinate(51.001,30.0)),emptyList())
            assertTrue(runCatching { RoadDownloader(server.url("/").toString()).fetch(elsewhere) }.isFailure)
        }
    }
    @Test fun boundsIncludeMarginAndRejectHugeOrDatelineAreas() {
        val bounds = RoadCoverage.around(route)
        assertTrue(bounds.contains(route))
        assertTrue(bounds.south < 50 && bounds.north > 50.001)
        for ((a,b) in listOf(Coordinate(50.0,30.0) to Coordinate(53.0,33.0),Coordinate(50.0,179.9) to Coordinate(50.0,-179.9))) {
            assertTrue(runCatching { RoadCoverage.around(Route("large","",listOf(a,b),emptyList())) }.isFailure)
        }
    }
}
