package dev.routeodo.data

import dev.routeodo.core.*
import org.json.JSONObject

object RoadGraphCodec {
    fun read(raw: String): RoadGraph {
        val json = JSONObject(raw)
        require(json.getInt("version") == 1)
        val nodes = json.getJSONArray("nodes")
        val edges = json.getJSONArray("edges")
        require(nodes.length() in 2..50000 && edges.length() in 1..100000)
        return RoadGraph((0 until nodes.length()).map { i ->
            val p = nodes.getJSONArray(i); Coordinate(p.getDouble(1), p.getDouble(0))
        }, (0 until edges.length()).map { i ->
            val e = edges.getJSONArray(i); RoadEdge(e.getInt(0), e.getInt(1))
        })
    }
}
