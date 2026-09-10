package dev.routeodo.data

import dev.routeodo.core.Coordinate
import dev.routeodo.core.Route
import dev.routeodo.core.Place
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class Network(private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private val searchCache = linkedMapOf<String, Pair<Long, List<Place>>>()
    private var lastSearchAt: Long? = null

    @Synchronized
    fun search(baseUrl: String, query: String, focus: Coordinate): List<Place> {
        require(query.trim().length in 3..240) { "Enter at least 3 characters, including the city" }
        val url = baseUrl.trimEnd('/').toHttpUrl().newBuilder().addPathSegments("api/")
            .addQueryParameter("q", query.trim()).addQueryParameter("limit", "6")
            .addQueryParameter("lat", focus.latitude.toString()).addQueryParameter("lon", focus.longitude.toString()).build().toString()
        val now = clockMillis()
        searchCache[url]?.let { (at, results) -> if (now - at in 0..600_000) return results }
        require(lastSearchAt?.let { now - it >= 1_100 } != false) { "Please wait a moment before searching again" }
        lastSearchAt = now
        val results = PlaceCodec.photon(get(url, "", 512 * 1024))
        searchCache[url] = now to results
        if (searchCache.size > 32) searchCache.remove(searchCache.keys.first())
        return results
    }


    fun route(baseUrl: String, start: Coordinate, end: Coordinate, name: String = "Driving route"): Route {
        val coordinates = "${start.longitude},${start.latitude};${end.longitude},${end.latitude}"
        val url = baseUrl.trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegments("route/v1/driving/$coordinates")
            .addQueryParameter("steps", "true").addQueryParameter("geometries", "geojson")
            .addQueryParameter("overview", "full").build()
        return JsonCodec.osrm(get(url.toString(), "", 20 * 1024 * 1024), name)
    }

    private fun get(url: String, token: String, maxBytes: Long): String {
        val endpoint = url.toHttpUrl()
        require(endpoint.username.isEmpty() && endpoint.password.isEmpty()) { "Use the bearer token field for authentication" }
        require(token.isBlank() || endpoint.isHttps) { "Bearer tokens require HTTPS" }
        val request = Request.Builder().url(endpoint).header("User-Agent", "RouteOdo/0.3 Android")
            .header("Accept", "application/json").header("Cache-Control", "no-cache, no-store")
            .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }.build()
        return client.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "Server returned HTTP ${response.code}" }
            val body = response.body ?: error("Server returned an empty response")
            require(body.contentLength() <= maxBytes) { "Response too large" }
            val source = body.source()
            require(!source.request(maxBytes + 1)) { "Response too large" }
            source.readUtf8()
        }
    }
}
