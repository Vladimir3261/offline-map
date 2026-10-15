package dev.routeodo.voice

import dev.routeodo.core.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Fixed, bounded OSM category queries; no arbitrary query language supplied by the model. */
class NearbyPlaces {
    private val client = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    private val cache = linkedMapOf<String, Pair<Long, List<Place>>>()
    fun search(category: String, focus: Coordinate, radius: Int): List<Place> {
        val query = query(category, focus, radius)
        cache[query]?.takeIf { System.nanoTime() / 1000000 - it.first < 300000 }?.let { return it.second }
        val request = Request.Builder().url("https://overpass-api.de/api/interpreter")
            .header("User-Agent", "RouteOdo Android location assistant")
            .post(FormBody.Builder().add("data", query).build()).build()
        val results = client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Nearby search unavailable" }
            val source = response.body?.source() ?: error("Empty nearby search")
            check(!source.request(512 * 1024 + 1L)) { "Nearby response too large" }
            decode(source.readUtf8()).sortedBy { Route.distance(focus, it.coordinate) }.take(30)
        }
        cache[query] = System.nanoTime() / 1000000 to results
        if (cache.size > 16) cache.remove(cache.keys.first())
        return results
    }
    companion object {
        val categories = linkedMapOf("supermarket" to "shop=supermarket", "shop" to "shop", "fuel" to "amenity=fuel",
            "pharmacy" to "amenity=pharmacy", "cafe" to "amenity=cafe", "restaurant" to "amenity=restaurant",
            "bank" to "amenity=bank", "school" to "amenity=school", "hospital" to "amenity=hospital",
            "parking" to "amenity=parking", "bus_stop" to "highway=bus_stop")
        fun query(category: String, focus: Coordinate, radius: Int): String {
            val tag = categories[category] ?: error("Unsupported place category")
            require(radius in 100..3000)
            return "[out:json][timeout:4];nwr[$tag](around:$radius,${focus.latitude},${focus.longitude});out center 100;"
        }
        fun decode(raw: String): List<Place> {
            val root = JSONObject(raw)
            check(!root.has("remark")) { "Nearby search incomplete" }
            val array = root.getJSONArray("elements")
            require(array.length() <= 100)
            return (0 until array.length()).mapNotNull { i -> runCatching {
                val element = array.getJSONObject(i); val tags = element.getJSONObject("tags")
                val point = element.optJSONObject("center") ?: element
                val category = tags.optString("shop").ifBlank { tags.optString("amenity").ifBlank { tags.optString("highway") } }
                val name = tags.optString("name").ifBlank { tags.optString("brand").ifBlank { category.replace('_', ' ') } }
                val address = listOf(tags.optString("addr:street"), tags.optString("addr:housenumber")).filter { it.isNotBlank() }.joinToString(" ")
                Place(listOf(name, address).filter { it.isNotBlank() }.joinToString(", "),
                    Coordinate(point.getDouble("lat"), point.getDouble("lon")), category)
            }.getOrNull() }
        }
    }
}
