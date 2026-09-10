package dev.routeodo.data

import dev.routeodo.core.CarSample
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class CarFeedTest {
    private val payload = """{"t":100.5,"epoch":"a","odometer_m":1000,"odometer_t":100,"speed_kmh":36,"speed_t":100.5,"connected":true,"clock_synced":true,"resets":0,"mapping_ver":5}"""

    private class Scheduler : FeedScheduler {
        data class Job(val at: Long, val task: Runnable)
        var now = 0L
        private val jobs = mutableListOf<Job>()
        override fun post(task: Runnable) = postDelayed(task, 0)
        @Synchronized override fun postDelayed(task: Runnable, delayMs: Long) { jobs += Job(now + delayMs, task) }
        @Synchronized override fun removeCallbacks(task: Runnable) { jobs.removeAll { it.task === task } }
        @Synchronized fun hasDelay(delay: Long) = jobs.any { it.at == now + delay }
        fun advance(ms: Long) { now += ms; drain() }
        fun drain() {
            while (true) {
                val job = synchronized(this) { jobs.filter { it.at <= now }.minByOrNull { it.at }?.also(jobs::remove) } ?: return
                job.task.run()
            }
        }
        fun await(condition: () -> Boolean) {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!condition() && System.nanoTime() < deadline) { drain(); Thread.sleep(5) }
            drain(); assertTrue("Timed out waiting for transport", condition())
        }
    }

    @Test fun unauthorizedResponseStopsRetriesIncludingOrdinaryResume() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            val scheduler = Scheduler()
            val issues = mutableListOf<CarFeedIssue>()
            val feed = CarFeed(null, { _, _ -> fail("Unauthorized sample") }, { issue, _ -> issues += issue }, scheduler, { scheduler.now })
            try {
                feed.start(server.url("/api/odometer").toString(), "", false)
                scheduler.await { CarFeedIssue.AUTH in issues }
                scheduler.advance(60_000)
                feed.start(server.url("/api/odometer").toString(), "", false)
                scheduler.advance(60_000)
                assertEquals(1, server.requestCount)
            } finally { feed.close() }
        }
    }

    @Test fun unavailableStreamFallsBackToFiveHundredMillisecondPolling() {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = if (request.path!!.endsWith("/stream")) MockResponse().setResponseCode(404)
                    else MockResponse().setBody(payload)
            }
            val scheduler = Scheduler()
            val samples = mutableListOf<CarSample>()
            val feed = CarFeed(null, { value, _ -> samples += value }, { _, _ -> }, scheduler, { scheduler.now })
            try {
                feed.start(server.url("/api/odometer").toString(), "", false)
                scheduler.await { scheduler.hasDelay(500) }
                assertEquals(1, samples.size)
                assertEquals(2, server.requestCount)
                scheduler.advance(499); assertEquals(2, server.requestCount)
                scheduler.advance(1); scheduler.await { samples.size == 2 }
                assertEquals(3, server.requestCount)
                assertEquals("no-cache, no-store", server.takeRequest().getHeader("Cache-Control"))
            } finally { feed.close() }
        }
    }

    @Test fun sseDataAndKeepaliveAreHandledWithoutCreatingAnotherSession() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(payload))
            server.enqueue(MockResponse().addHeader("Content-Type", "text/event-stream")
                .setBody(": keepalive\n\ndata: $payload\n\n"))
            val scheduler = Scheduler()
            val sources = mutableListOf<String>()
            val epochs = mutableListOf<String>()
            val feed = CarFeed(null, { value, source -> sources += source; epochs += value.epoch }, { _, _ -> }, scheduler, { scheduler.now })
            try {
                feed.start(server.url("/api/odometer").toString(), "", false)
                scheduler.await { sources.size == 2 }
                assertTrue(sources.last().contains("live stream"))
                assertEquals(listOf("a", "a"), epochs)
                assertEquals(2, server.requestCount)
            } finally { feed.close() }
        }
    }

    @Test fun lanFailureFallsBackToPublicEndpointAndDoesNotHammerLan() {
        MockWebServer().use { lan -> MockWebServer().use { remote ->
            lan.enqueue(MockResponse().setResponseCode(502))
            remote.enqueue(MockResponse().setBody(payload))
            remote.enqueue(MockResponse().setResponseCode(404))
            remote.enqueue(MockResponse().setBody(payload))
            val scheduler = Scheduler()
            val sources = mutableListOf<String>()
            val feed = CarFeed(null, { _, source -> sources += source }, { _, _ -> }, scheduler, { scheduler.now }, lan.url("/api/odometer").toString())
            try {
                feed.start(remote.url("/api/odometer").toString(), "", true)
                scheduler.await { scheduler.hasDelay(500) }
                assertEquals(1, lan.requestCount)
                scheduler.advance(500); scheduler.await { sources.size == 2 }
                assertEquals(1, lan.requestCount)
                assertTrue(sources.all { it.startsWith("Internet") })
            } finally { feed.close() }
        } }
    }

    @Test fun missingCarChannelIsReportedAndBearerCannotLeakToOtherHttpHosts() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            val scheduler = Scheduler()
            val issues = mutableListOf<CarFeedIssue>()
            val feed = CarFeed(null, { _, _ -> }, { issue, _ -> issues += issue }, scheduler, { scheduler.now })
            try {
                feed.start(server.url("/api/odometer").toString(), "", false)
                scheduler.await { CarFeedIssue.UNAVAILABLE in issues }
                feed.start(server.url("/api/odometer").toString(), "secret", false)
                assertEquals(CarFeedIssue.INVALID, issues.last())
                assertEquals(1, server.requestCount)
            } finally { feed.close() }
        }
    }
}
