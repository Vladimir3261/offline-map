package dev.routeodo.data

import dev.routeodo.core.Coordinate
import dev.routeodo.core.Place
import org.json.JSONObject

object PlaceCodec {
    fun photon(raw: String): List<Place> {
        val json = JSONObject(raw)
        val features = json.getJSONArray("features")
        require(features.length() <= 100) { "Too many address results" }
        return (0 until features.length()).mapNotNull { index ->
            // Ignore individual malformed or non-point results without losing the remaining choices.
            runCatching {
                val item = features.getJSONObject(index)
                val geometry = item.getJSONObject("geometry")
                require(geometry.getString("type") == "Point")
                val xy = geometry.getJSONArray("coordinates")
                val coordinate = Coordinate(xy.getDouble(1), xy.getDouble(0))
                val p = item.getJSONObject("properties")
                fun value(key: String) = p.optString(key).takeUnless { it.isBlank() || it == "null" }
                val street = listOfNotNull(value("street"), value("housenumber")).joinToString(" ")
                val label = listOfNotNull(value("name"), street.takeIf { it.isNotBlank() },
                    value("district"), value("city") ?: value("locality"), value("state"), value("country"))
                    .distinct().joinToString(", ")
                val category = when (value("osm_key")) {
                    "shop" -> "Shop"
                    "tourism" -> "Place to visit"
                    "leisure" -> "Leisure"
                    "railway" -> "Station"
                    "amenity" -> when (value("osm_value")) {
                        "cafe", "restaurant", "fast_food", "bar", "pub" -> "Food & drink"
                        "fuel" -> "Fuel station"
                        "parking" -> "Parking"
                        else -> "Place"
                    }
                    else -> null
                }
                Place(label, coordinate, category)
            }.getOrNull()
        }.distinctBy { it.label to it.coordinate }
    }
}
