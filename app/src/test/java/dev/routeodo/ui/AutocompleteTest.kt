package dev.routeodo.ui

import dev.routeodo.core.*
import org.junit.Assert.*
import org.junit.Test

class AutocompleteTest {
    private val focus = Coordinate(50.45, 30.52)
    private val place = Place("Сільпо, Київ", focus, "Shop")
    private class Harness {
        var now = 0L
        data class Job(val at: Long, val run: () -> Unit, var canceled: Boolean = false)
        val jobs = mutableListOf<Job>()
        data class Request(val query: String, val done: (Result<List<Place>>) -> Unit, var canceled: Boolean = false)
        val requests = mutableListOf<Request>()
        val updates = mutableListOf<Suggestions>()
        val search = Autocomplete({ now }, { delay, work ->
            val job = Job(now + delay, work); jobs += job; CancelWork { job.canceled = true }
        }, { query, _, done ->
            val call = Request(query, done); requests += call; CancelWork { call.canceled = true }
        }, updates::add)
        fun advance(ms: Long) {
            val end = now + ms
            while (true) {
                val job = jobs.filter { !it.canceled && it.at <= end }.minByOrNull { it.at } ?: break
                jobs.remove(job); now = job.at; job.run()
            }
            now = end
        }
    }
    @Test fun rapidTypingProducesOnlyTheLatestRequestAfterDebounce() {
        val h = Harness()
        h.search.search("Si", focus); h.advance(200)
        h.search.search("Sil", focus); h.advance(200)
        h.search.search("Silpo", focus); h.advance(399)
        assertTrue(h.requests.isEmpty())
        h.advance(1)
        assertEquals(listOf("Silpo"), h.requests.map { it.query })
        h.requests.last().done(Result.success(listOf(place)))
        assertEquals(listOf(place), h.updates.last().places)
        assertFalse(h.updates.last().loading)
    }
    @Test fun staleResponsesCannotReplaceNewQueryEvenWhenCancellationRaces() {
        val h = Harness()
        h.search.search("Old", focus); h.advance(400)
        h.search.search("New", focus)
        assertTrue(h.requests.first().canceled)
        h.requests.first().done(Result.success(listOf(place)))
        assertEquals("New", h.updates.last().query)
        assertTrue(h.updates.last().loading)
        h.advance(1099); assertEquals(1, h.requests.size)
        h.advance(1); assertEquals(2, h.requests.size)
    }
    @Test fun clearingOrClosingCancelsWorkAndRejectsLateResults() {
        val h = Harness()
        h.search.search("Kyiv", focus); h.advance(400)
        h.search.clear(); val count = h.updates.size
        h.requests.first().done(Result.success(listOf(place)))
        assertEquals(count, h.updates.size)
        h.search.search("Shop", focus); h.search.close(); h.advance(5000)
        assertEquals(1, h.requests.size)
        val closedCount = h.updates.size
        h.search.search("Ignored", focus); assertEquals(closedCount, h.updates.size)
    }
    @Test fun emptyAndSingleCharacterQueriesDoNotCallTheService() {
        val h = Harness()
        h.search.search("  ", focus); h.search.search("a", focus); h.advance(2000)
        assertTrue(h.requests.isEmpty()); assertFalse(h.updates.last().loading)
    }
    @Test fun invalidServiceConfigurationBecomesRecoverableSearchError() {
        var callback: (() -> Unit)? = null
        var result: Suggestions? = null
        val search = Autocomplete({ 0 }, { _, work -> callback = work; CancelWork {} },
            { _, _, _ -> error("Invalid URL") }, { result = it })
        search.search("Kyiv", focus); callback!!()
        assertNotNull(result!!.error); assertFalse(result!!.loading)
    }
}
