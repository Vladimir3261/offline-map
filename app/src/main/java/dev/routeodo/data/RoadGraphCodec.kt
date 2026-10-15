package dev.routeodo.data

import dev.routeodo.core.*
import org.json.JSONArray
import org.json.JSONObject

object RoadGraphCodec {
    fun read(raw: String): RoadGraph {
        val json = JSONObject(raw)
        require(json.getInt("version") == 1)
        val nodes = json.getJSONArray("nodes")
        val edges = json.getJSONArray("edges")
        val turns = json.optJSONArray("blockedTurns") ?: JSONArray()
        require(nodes.length() in 2..50000 && edges.length() in 1..100000 && turns.length() <= 200000)
        val bounds = json.optJSONObject("coverage")?.let {
            RoadCoverage(it.getDouble("south"), it.getDouble("west"), it.getDouble("north"), it.getDouble("east"))
        }
        return RoadGraph((0 until nodes.length()).map { i ->
            val p = nodes.getJSONArray(i); Coordinate(p.getDouble(1), p.getDouble(0))
        }, (0 until edges.length()).map { i ->
            val e = edges.getJSONArray(i); RoadEdge(e.getInt(0), e.getInt(1))
        }, (0 until turns.length()).map { i ->
            val t = turns.getJSONArray(i)
            RoadTurn(RoadEdge(t.getInt(0), t.getInt(1)), RoadEdge(t.getInt(1), t.getInt(2)))
        }.toSet(), bounds, json.optLong("downloadedAtMillis", 0).takeIf { it > 0 })
    }

    fun write(graph: RoadGraph): String = JSONObject().put("version", 1)
        .put("nodes", JSONArray(graph.nodes.map { JSONArray(listOf(it.longitude, it.latitude)) }))
        .put("edges", JSONArray(graph.edges.map { JSONArray(listOf(it.from, it.to)) }))
        .put("blockedTurns", JSONArray(graph.blockedTurns.map { JSONArray(listOf(it.incoming.from, it.incoming.to, it.outgoing.to)) }))
        .put("coverage", graph.coverage?.let { JSONObject().put("south", it.south).put("west", it.west).put("north", it.north).put("east", it.east) })
        .put("downloadedAtMillis", graph.downloadedAtMillis).toString()
}
