package dev.routeodo.data

import dev.routeodo.core.Coordinate
import dev.routeodo.core.Route
import dev.routeodo.core.Place
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class Network(private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private val searchCache = linkedMapOf<String, Pair<Long, List<Place>>>()
    private var lastSearchAt: Long? = null

    @Synchronized
    fun search(baseUrl: String, query: String, focus: Coordinate, timeoutMillis: Long? = null): List<Place> {
        val url = searchUrl(baseUrl, query, focus)
        val now = clockMillis()
        cached(url)?.let { return it }
        require(lastSearchAt?.let { now - it >= 1_100 } != false) { "Please wait a moment before searching again" }
        lastSearchAt = now
        val results = PlaceCodec.photon(get(url, "", 512 * 1024, timeoutMillis))
        remember(url, results)
        return results
    }

    private fun searchUrl(baseUrl: String, query: String, focus: Coordinate): String {
        require(query.trim().length in 2..240) { "Enter at least 2 characters" }
        return baseUrl.trimEnd('/').toHttpUrl().newBuilder().addPathSegments("api/")
            .addQueryParameter("q", query.trim()).addQueryParameter("limit", "15")
            .addQueryParameter("lat", focus.latitude.toString()).addQueryParameter("lon", focus.longitude.toString()).build().toString()
    }

    private fun cached(url: String): List<Place>? = synchronized(searchCache) {
        searchCache[url]?.takeIf { clockMillis() - it.first in 0..600_000 }?.second
    }
    private fun remember(url: String, results: List<Place>) = synchronized(searchCache) {
        searchCache[url] = clockMillis() to results
        if (searchCache.size > 64) searchCache.remove(searchCache.keys.first())
    }

    /** Cancellation belongs to the search UI; credentials are never attached to place requests. */
    fun searchAsync(baseUrl: String, query: String, focus: Coordinate, complete: (Result<List<Place>>) -> Unit): () -> Unit {
        val url = searchUrl(baseUrl, query, focus)
        cached(url)?.let { complete(Result.success(it)); return {} }
        val call = client.newCall(request(url, ""))
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { complete(Result.failure(e)) }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching { response.use { PlaceCodec.photon(read(it, 512 * 1024)) } }
                if (!call.isCanceled()) result.onSuccess { remember(url, it) }
                complete(result)
            }
        })
        return { call.cancel() }
    }

    fun planRoute(baseUrl: String, start: Place, end: Place): PlannedRoute {
        val raw = routeResponse(baseUrl, start.coordinate, end.coordinate)
        val route = JsonCodec.osrm(raw, "${start.title} → ${end.title}")
        val seconds = JSONObject(raw).getJSONArray("routes").getJSONObject(0).optDouble("duration")
            .takeIf { it.isFinite() && it >= 0 }
        return PlannedRoute(route, RouteDetails(start, end, seconds))
    }

    fun route(baseUrl: String, start: Coordinate, end: Coordinate, name: String = "Driving route"): Route {
        return JsonCodec.osrm(routeResponse(baseUrl, start, end), name)
    }

    private fun routeResponse(baseUrl: String, start: Coordinate, end: Coordinate): String {
        val coordinates = "${start.longitude},${start.latitude};${end.longitude},${end.latitude}"
        val url = baseUrl.trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegments("route/v1/driving/$coordinates")
            .addQueryParameter("steps", "true").addQueryParameter("geometries", "geojson")
            .addQueryParameter("overview", "full").build()
        return get(url.toString(), "", 20 * 1024 * 1024)
    }

    private fun get(url: String, token: String, maxBytes: Long, timeoutMillis: Long? = null): String {
        val call = client.newCall(request(url, token))
        timeoutMillis?.let { require(it in 100..15000); call.timeout().timeout(it, TimeUnit.MILLISECONDS) }
        return call.execute().use { read(it, maxBytes) }
    }

    private fun request(url: String, token: String): Request {
        val endpoint = url.toHttpUrl()
        require(endpoint.username.isEmpty() && endpoint.password.isEmpty()) { "Use the bearer token field for authentication" }
        require(token.isBlank() || endpoint.isHttps) { "Bearer tokens require HTTPS" }
        return Request.Builder().url(endpoint).header("User-Agent", "RouteOdo/0.5 Android")
            .header("Accept", "application/json").header("Cache-Control", "no-cache, no-store")
            .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }.build()
    }

    private fun read(response: Response, maxBytes: Long): String {
            require(response.isSuccessful) { "Server returned HTTP ${response.code}" }
            val body = response.body ?: error("Server returned an empty response")
            require(body.contentLength() <= maxBytes) { "Response too large" }
            val source = body.source()
            require(!source.request(maxBytes + 1)) { "Response too large" }
            return source.readUtf8()
    }
}
