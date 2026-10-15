package dev.routeodo.data

import dev.routeodo.core.*
import org.json.JSONObject

/** Presentation metadata; it never changes odometer progress or route geometry. */
data class RouteDetails(val start: Place, val destination: Place, val durationSeconds: Double? = null) {
    fun json(): JSONObject = JSONObject().put("start", encode(start)).put("destination", encode(destination))
        .put("durationSeconds", durationSeconds)

    companion object {
        private fun encode(place: Place) = JSONObject().put("label", place.label)
            .put("lat", place.coordinate.latitude).put("lon", place.coordinate.longitude).put("category", place.category)
        private fun decode(json: JSONObject) = Place(json.getString("label"), Coordinate(json.getDouble("lat"), json.getDouble("lon")),
            json.optString("category").takeIf { it.isNotBlank() && it != "null" })
        fun read(json: JSONObject) = RouteDetails(decode(json.getJSONObject("start")), decode(json.getJSONObject("destination")),
            json.optDouble("durationSeconds").takeIf { it.isFinite() && it >= 0 })
    }
}

data class PlannedRoute(val route: Route, val details: RouteDetails)
