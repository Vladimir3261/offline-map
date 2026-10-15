package dev.routeodo.data

import android.content.Context
import dev.routeodo.core.Coordinate
import dev.routeodo.core.Place
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class SavedPlace(val name: String, val place: Place)

object SavedPlaceCodec {
    fun read(raw: String): List<SavedPlace> {
        val array = JSONArray(raw)
        require(array.length() <= 100)
        return (0 until array.length()).mapNotNull { i -> runCatching {
            val p = array.getJSONObject(i)
            val name = p.getString("name")
            require(name.isNotBlank() && name.length <= 60)
            SavedPlace(name, Place(p.getString("address"), Coordinate(p.getDouble("lat"), p.getDouble("lon")),
                p.optString("category").takeIf { it.isNotBlank() && it != "null" }))
        }.getOrNull() }
    }

    fun write(places: List<SavedPlace>): String = JSONArray().apply {
        places.forEach { put(JSONObject().put("name", it.name).put("address", it.place.label)
            .put("lat", it.place.coordinate.latitude).put("lon", it.place.coordinate.longitude).put("category", it.place.category)) }
    }.toString()
}

class SavedPlaces(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("saved_places", Context.MODE_PRIVATE)
    fun all(): List<SavedPlace> = runCatching { SavedPlaceCodec.read(preferences.getString("places", "[]") ?: "[]") }.getOrDefault(emptyList())
    fun save(name: String, place: Place) {
        val trimmed = name.trim()
        require(trimmed.length in 1..60) { "Use a name between 1 and 60 characters" }
        val list = all().filterNot { it.name.lowercase(Locale.ROOT) == trimmed.lowercase(Locale.ROOT) } + SavedPlace(trimmed, place)
        require(list.size <= 100) { "Remove a saved place before adding more" }
        preferences.edit().putString("places", SavedPlaceCodec.write(list)).apply()
    }
    fun remove(name: String) { preferences.edit().putString("places", SavedPlaceCodec.write(all().filterNot { it.name == name })).apply() }
}
