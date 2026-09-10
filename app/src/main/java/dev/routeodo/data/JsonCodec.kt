package dev.routeodo.data

import dev.routeodo.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object JsonCodec {
    private fun integer(json: JSONObject, key: String): Long? {
        if (json.isNull(key)) return null
        val value = json.get(key)
        require(value is Number) { "$key must be an integer or null" }
        return value.toString().toBigDecimal().longValueExact().also { require(it >= 0) }
    }

    private fun optionalTime(json: JSONObject, key: String): Double? {
        if (json.isNull(key)) return null
        require(json.get(key) is Number) { "$key must be a number or null" }
        return json.getDouble(key)
    }

    fun carSample(raw: String): CarSample {
        val json = JSONObject(raw)
        require(json.get("t") is Number && json.get("epoch") is String && json.get("connected") is Boolean)
        val speed = integer(json, "speed_kmh")
        require(speed == null || speed <= Int.MAX_VALUE)
        return CarSample(json.getDouble("t"), json.getString("epoch"), integer(json, "odometer_m"),
            optionalTime(json, "odometer_t"), speed?.toInt(), optionalTime(json, "speed_t"),
            json.getBoolean("connected"), json.optBoolean("clock_synced"), json.optInt("resets"),
            json.optString("source"), json.optInt("mapping_ver"))
    }

    fun state(value: TripState): JSONObject = JSONObject().put("format", 3)
        .put("routeAnchorMeters", value.routeAnchorMeters).put("travelAnchorMeters", value.travelAnchorMeters)
        .put("scale", value.scale).put("displayedTravelMeters", value.displayedTravelMeters)
        .put("confirmedTravelMeters", value.confirmedTravelMeters).put("carriedMeters", value.carriedMeters)
        .put("baseEpoch", value.baseEpoch).put("baseM", value.baseM)
        .put("lastAnchorM", value.lastAnchorM).put("lastAnchorT", value.lastAnchorT)
        .put("holdingForAnchor", value.holdingForAnchor).put("blockedReason", value.blockedReason)
        .put("navigationSessionId", value.navigationSessionId).put("running", value.running)
        .put("gaps", JSONArray().apply { value.gaps.forEach { gap ->
            put(JSONObject().put("progressMeters", gap.progressMeters).put("fromEpoch", gap.fromEpoch).put("toEpoch", gap.toEpoch))
        } })

    fun state(json: JSONObject): TripState {
        val scale = json.getDouble("scale")
        require(scale.isFinite() && scale in 0.5..1.5)
        if (json.optInt("format") != 3) {
            val progress = json.optDouble("progressMeters", 0.0)
            require(progress.isFinite() && progress >= 0)
            return TripState(routeAnchorMeters = progress, scale = scale)
        }
        val routeAnchor = json.getDouble("routeAnchorMeters")
        val travelAnchor = json.getDouble("travelAnchorMeters")
        val displayed = json.getDouble("displayedTravelMeters")
        val confirmed = json.getDouble("confirmedTravelMeters")
        val carried = json.getDouble("carriedMeters")
        require(listOf(routeAnchor, travelAnchor, displayed, confirmed, carried).all { it.isFinite() && it >= 0 })
        require(carried <= confirmed && confirmed <= displayed && travelAnchor <= displayed)
        fun optionalText(key: String) = json.optString(key).takeIf { it.isNotBlank() && it != "null" }
        val base = integer(json, "baseM")
        val last = integer(json, "lastAnchorM")
        val lastT = optionalTime(json, "lastAnchorT")
        val epoch = optionalText("baseEpoch")
        val session = optionalText("navigationSessionId")
        val running = json.optBoolean("running")
        require(!running || session != null)
        require(base == null || epoch != null && last != null && last >= base && lastT != null && lastT.isFinite())
        val gaps = json.getJSONArray("gaps")
        require(gaps.length() <= 100)
        return TripState(routeAnchor, travelAnchor, scale, displayed, confirmed, carried, epoch, base, last, lastT,
            json.optBoolean("holdingForAnchor"), optionalText("blockedReason"), session, running,
            (0 until gaps.length()).map { i -> gaps.getJSONObject(i).let {
                val progress = it.getDouble("progressMeters")
                require(progress.isFinite() && progress >= 0)
                RouteGap(progress, it.getString("fromEpoch"), it.getString("toEpoch"))
            } })
    }

    fun route(route: Route): JSONObject = JSONObject()
        .put("schemaVersion", 1).put("id", route.id).put("name", route.name)
        .put("points", JSONArray().apply { route.points.forEach { put(JSONArray().put(it.longitude).put(it.latitude)) } })
        .put("maneuvers", JSONArray().apply {
            route.maneuvers.forEach { put(JSONObject().put("vertexIndex", it.vertexIndex).put("instruction", it.instruction)) }
        })

    fun route(json: JSONObject): Route {
        require(json.getInt("schemaVersion") == 1) { "Unsupported route package" }
        val pts = json.getJSONArray("points")
        val steps = json.getJSONArray("maneuvers")
        require(pts.length() <= 200_000) { "Route is too large" }
        return Route(json.getString("id"), json.getString("name"),
            (0 until pts.length()).map { coordinate(pts.getJSONArray(it)) },
            (0 until steps.length()).map { steps.getJSONObject(it).let { s -> Maneuver(s.getInt("vertexIndex"), s.getString("instruction")) } })
    }

    fun osrm(raw: String, name: String = "Driving route"): Route {
        val json = JSONObject(raw)
        require(json.optString("code") == "Ok") { "No driving route found between these points" }
        val result = json.getJSONArray("routes").getJSONObject(0)
        val points = mutableListOf<Coordinate>()
        val maneuvers = mutableListOf<Maneuver>()
        val legs = result.getJSONArray("legs")
        for (l in 0 until legs.length()) {
            val steps = legs.getJSONObject(l).getJSONArray("steps")
            for (s in 0 until steps.length()) {
                val step = steps.getJSONObject(s)
                val geometry = step.getJSONObject("geometry").getJSONArray("coordinates")
                require(geometry.length() > 0) { "Missing route step geometry" }
                val first = coordinate(geometry.getJSONArray(0))
                if (points.isEmpty()) points += first
                else require(Route.distance(points.last(), first) < 2.0) { "Disconnected route geometry" }
                val index = points.lastIndex
                maneuvers += Maneuver(index, instruction(step))
                for (p in 1 until geometry.length()) {
                    val point = coordinate(geometry.getJSONArray(p))
                    if (point != points.last()) points += point
                }
                require(points.size <= 200_000) { "Route is too large" }
            }
        }
        return Route(UUID.randomUUID().toString(), name, points, maneuvers)
    }

    private fun coordinate(a: JSONArray) = Coordinate(a.getDouble(1), a.getDouble(0))

    private fun instruction(step: JSONObject): String {
        val maneuver = step.getJSONObject("maneuver")
        val modifier = maneuver.optString("modifier").replace("uturn", "U-turn")
        val road = step.optString("name").ifBlank { step.optString("ref") }
        val direction = when (maneuver.getString("type")) {
            "depart" -> "Start driving"
            "arrive" -> "Arrive at destination"
            "turn" -> "Turn $modifier"
            "new name", "continue", "notification" -> if (modifier.isBlank() || modifier == "straight") "Continue" else "Continue $modifier"
            "roundabout", "rotary" -> if (maneuver.has("exit")) "Roundabout · exit ${maneuver.getInt("exit")}" else "Enter roundabout"
            "exit roundabout", "exit rotary" -> "Exit roundabout"
            "fork" -> "Keep $modifier at fork"
            "merge" -> "Merge $modifier"
            "on ramp" -> "Take ramp $modifier"
            "off ramp" -> "Take exit $modifier"
            "end of road" -> "At road end, turn $modifier"
            else -> "Continue $modifier"
        }.trim()
        return if (road.isBlank() || maneuver.getString("type") == "arrive") direction else "$direction · $road"
    }
}
