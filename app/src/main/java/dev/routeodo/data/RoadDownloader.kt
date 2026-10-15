package dev.routeodo.data

import dev.routeodo.core.*
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One cancellable download; never receives or attaches car/OpenAI credentials. */
class RoadDownloader(
    private val endpoint: String = "https://overpass-api.de/api/interpreter",
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build(),
) {
    @Volatile private var cancelled = false
    @Volatile private var call: Call? = null
    fun cancel() { cancelled = true; call?.cancel() }
    fun fetch(route: Route): RoadGraph {
        val bounds = RoadCoverage.around(route)
        if (cancelled) throw IOException("Download cancelled")
        val query = query(bounds)
        val request = Request.Builder().url(endpoint).header("User-Agent", "RouteOdo/0.5 Android")
            .post(FormBody.Builder().add("data", query).build()).build()
        val requestCall = client.newCall(request)
        call = requestCall
        if (cancelled) requestCall.cancel()
        val raw = requestCall.execute().use { response ->
            require(response.isSuccessful) { if (response.code == 429 || response.code == 504)
                "Road service is busy. Please retry later." else "Road download failed (HTTP ${response.code})." }
            val body = response.body ?: error("Road service returned an empty response")
            val limit = 20L * 1024 * 1024
            require(body.contentLength() <= limit) { "Road area is too large. Select a shorter route." }
            val source = body.source()
            require(!source.request(limit + 1)) { "Road area is too large. Select a shorter route." }
            source.readUtf8()
        }
        if (cancelled) throw IOException("Download cancelled")
        val graph = OsmRoadGraph.read(raw, bounds, System.currentTimeMillis())
        val start = route.position(0.0)
        val seed = RoadPredictor(graph, start.coordinate, start.bearing).best
        require(seed != null && graph.route(seed, route.points.last()) != null) {
            "Downloaded roads cannot connect this route's start and destination. Try different endpoints."
        }
        if (cancelled) throw IOException("Download cancelled")
        return graph
    }
    companion object {
        fun query(bounds: RoadCoverage): String {
            val box = "${bounds.south},${bounds.west},${bounds.north},${bounds.east}"
            val classes = OsmRoadGraph.highways.joinToString("|")
            return """[out:json][timeout:30][maxsize:67108864];
                way["highway"~"^($classes)$"]($box)->.roads;
                (.roads;rel(bw.roads)["type"~"^restriction(:.*)?$"];);
                out body; >; out body qt;
            """.trimIndent()
        }
    }
}
