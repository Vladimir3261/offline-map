package dev.routeodo.ui

import dev.routeodo.core.Coordinate
import dev.routeodo.core.Place

fun interface CancelWork { fun cancel() }
data class Suggestions(val query: String, val loading: Boolean = false, val places: List<Place> = emptyList(), val error: String? = null)

/** Runs on the UI thread. Only the latest query may publish, even after cancellation races. */
class Autocomplete(
    private val clock: () -> Long,
    private val later: (Long, () -> Unit) -> CancelWork,
    private val request: (String, Coordinate, (Result<List<Place>>) -> Unit) -> CancelWork,
    private val publish: (Suggestions) -> Unit,
) {
    private var revision = 0L
    private var pending: CancelWork? = null
    private var active: CancelWork? = null
    private var nextRequestAt = 0L
    private var closed = false

    fun search(text: String, focus: Coordinate) {
        if (closed) return
        val query = text.trim()
        val version = ++revision
        pending?.cancel(); active?.cancel()
        pending = null; active = null
        if (query.length !in 2..240) { publish(Suggestions(query)); return }
        publish(Suggestions(query, loading = true))
        // Photon supports typeahead. Coalesce typing and keep public-server traffic below one request/second.
        pending = later(maxOf(400, nextRequestAt - clock())) {
            if (closed || version != revision) return@later
            pending = null
            nextRequestAt = clock() + 1100
            val completion: (Result<List<Place>>) -> Unit = { result ->
                if (!closed && version == revision) {
                    publish(result.fold({ Suggestions(query, places = it) }, {
                        Suggestions(query, error = "Search unavailable. Check your connection and try again.")
                    }))
                }
            }
            try { active = request(query, focus, completion) } catch (error: Exception) { completion(Result.failure(error)) }
        }
    }

    fun clear() { revision++; pending?.cancel(); active?.cancel(); pending = null; active = null }
    fun close() { clear(); closed = true }
}
