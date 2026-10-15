package dev.routeodo.data

import dev.routeodo.core.*
import org.json.JSONObject

/** Converts OSM topology to a passenger-car graph; coordinates alone never create a junction. */
object OsmRoadGraph {
    val highways = setOf("motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link",
        "secondary", "secondary_link", "tertiary", "tertiary_link", "unclassified", "residential", "living_street", "service")
    private val modes = listOf("motorcar", "motor_vehicle", "vehicle", "access")
    private val permitted = setOf("yes", "designated", "permissive", "official")
    private fun tags(element: JSONObject): Map<String, String> = element.optJSONObject("tags")?.let { obj ->
        obj.keys().asSequence().associateWith { obj.getString(it).lowercase() }
    }.orEmpty()
    private fun access(tags: Map<String, String>, direction: String? = null): Boolean {
        val value = modes.firstNotNullOfOrNull { mode -> direction?.let { tags["$mode:$it"] } ?: tags[mode] }
        return value == null || value in permitted
    }
    private fun conditional(tags: Map<String, String>) = tags.keys.any { key ->
        key.endsWith(":conditional") && (key.startsWith("oneway") || modes.any { key.startsWith("$it:") })
    }
    private data class Way(val id: Long, val nodes: List<Long>, val tags: Map<String, String>)

    fun read(raw: String, coverage: RoadCoverage, downloadedAtMillis: Long): RoadGraph {
        val json = JSONObject(raw)
        require(!json.has("remark")) { "Road service returned an incomplete result. Please retry later." }
        val elements = json.getJSONArray("elements")
        require(elements.length() <= 300000) { "Too many roads. Select a shorter route." }
        val osmNodes = mutableMapOf<Long, Coordinate>()
        val blockedNodes = mutableSetOf<Long>()
        val ways = linkedMapOf<Long, Way>()
        val relations = linkedMapOf<Long, JSONObject>()
        for (i in 0 until elements.length()) {
            val element = elements.getJSONObject(i)
            val id = element.getLong("id")
            val tags = tags(element)
            when (element.getString("type")) {
                "node" -> {
                    osmNodes[id] = Coordinate(element.getDouble("lat"), element.getDouble("lon"))
                    val motorPermission = listOf("motorcar", "motor_vehicle", "vehicle").firstNotNullOfOrNull { tags[it] } in permitted
                    val barrier = tags["barrier"]
                    if (!access(tags) || conditional(tags) ||
                        (!motorPermission && barrier != null && barrier !in setOf("no", "entrance", "cattle_grid", "toll_booth"))) blockedNodes += id
                }
                "way" -> if (tags["highway"] in highways && tags["area"] != "yes" && access(tags) && !conditional(tags)) {
                    val nodes = element.getJSONArray("nodes")
                    ways[id] = Way(id, (0 until nodes.length()).map { nodes.getLong(it) }, tags)
                }
                "relation" -> if (tags["type"]?.startsWith("restriction") == true) relations[id] = element
            }
        }
        val coordinates = mutableListOf<Coordinate>()
        val indices = mutableMapOf<Long, Int>()
        fun index(id: Long): Int = indices.getOrPut(id) { coordinates.add(osmNodes.getValue(id)); coordinates.lastIndex }
        val edges = linkedSetOf<RoadEdge>()
        val byWay = mutableMapOf<Long, MutableSet<RoadEdge>>()
        for (way in ways.values) {
            // Missing geometry means a truncated service response, not permission to invent a link.
            require(way.nodes.all { it in osmNodes }) { "Road geometry is incomplete. Please retry." }
            val oneway = listOf("oneway:motorcar", "oneway:motor_vehicle", "oneway:vehicle", "oneway")
                .firstNotNullOfOrNull { way.tags[it] }
                ?: if (way.tags["junction"] == "roundabout" || way.tags["highway"] == "motorway") "yes" else "no"
            if (oneway !in setOf("yes", "true", "1", "-1", "no", "false", "0")) continue
            for ((from, to) in way.nodes.zipWithNext()) {
                if (from in blockedNodes || to in blockedNodes || Route.distance(osmNodes.getValue(from), osmNodes.getValue(to)) <= 0.1) continue
                val a = index(from); val b = index(to)
                fun add(edge: RoadEdge) { edges += edge; byWay.getOrPut(way.id) { linkedSetOf() } += edge }
                if (oneway != "-1" && access(way.tags, "forward")) add(RoadEdge(a, b))
                if (oneway !in setOf("yes", "true", "1") && access(way.tags, "backward")) add(RoadEdge(b, a))
                require(coordinates.size <= 50000 && edges.size <= 100000) { "Too many roads. Select a shorter route." }
            }
        }
        val blocked = linkedSetOf<RoadTurn>()
        val outgoing = edges.groupBy { it.from }
        for (relation in relations.values) {
            val tags = tags(relation)
            if (tags["except"].orEmpty().split(';').any { it.trim() in setOf("motorcar", "motor_vehicle", "vehicle") }) continue
            val type = tags.getValue("type")
            val restrictions = listOf(
                listOf("restriction:motorcar", "restriction:motor_vehicle", "restriction:vehicle", "restriction"),
                listOf("restriction:motorcar:conditional", "restriction:motor_vehicle:conditional", "restriction:vehicle:conditional", "restriction:conditional")
            ).mapNotNull { keys -> keys.firstNotNullOfOrNull { tags[it] } }
            if (restrictions.isEmpty()) continue
            if (type !in setOf("restriction", "restriction:motorcar", "restriction:motor_vehicle", "restriction:vehicle")) continue
            val members = relation.getJSONArray("members")
            val from = mutableSetOf<Long>(); val to = mutableSetOf<Long>(); val viaNodes = mutableSetOf<Long>(); val viaWays = mutableSetOf<Long>()
            for (i in 0 until members.length()) {
                val member = members.getJSONObject(i); val ref = member.getLong("ref")
                when (member.getString("role") to member.getString("type")) {
                    "from" to "way" -> from += ref
                    "to" to "way" -> to += ref
                    "via" to "node" -> viaNodes += ref
                    "via" to "way" -> viaWays += ref
                }
            }
            val incoming = from.flatMap { byWay[it].orEmpty() }
            val destination = to.flatMap { byWay[it].orEmpty() }.toSet()
            for (restriction in restrictions) {
                if (viaNodes.size == 1 && viaWays.isEmpty()) {
                    val via = indices[viaNodes.single()] ?: continue
                    for (edge in incoming.filter { it.to == via }) {
                        for (next in outgoing[via].orEmpty()) {
                            val onTargetWay = next in destination
                            val kind = restriction.substringBefore('@').trim()
                            val matches = when {
                                from == to && kind.endsWith("u_turn") -> onTargetWay && next.to == edge.from
                                from == to && kind.endsWith("straight_on") -> onTargetWay && next.to != edge.from
                                else -> onTargetWay
                            }
                            if (if (restriction.startsWith("only_")) !matches else if (restriction.startsWith("no_")) matches else true)
                                blocked += RoadTurn(edge, next)
                        }
                    }
                } else {
                    // Via-way/unknown forms need path history. Conservatively block entry to those via
                    // ways (or every exit if unresolved), rather than silently dropping the restriction.
                    val viaEdges = viaWays.flatMap { byWay[it].orEmpty() }.toSet()
                    for (edge in incoming) for (next in outgoing[edge.to].orEmpty()) {
                        if (!restriction.startsWith("no_") || viaEdges.isEmpty() || next in viaEdges) blocked += RoadTurn(edge, next)
                    }
                }
            }
            require(blocked.size <= 200000) { "Too many road restrictions. Select a shorter route." }
        }
        // Apply explicit way-level U-turn bans too.
        for (way in ways.values.filter { it.tags["u_turn"] == "no" || it.tags["no_u_turn"] == "yes" }) {
            for (edge in byWay[way.id].orEmpty()) {
                val reverse = RoadEdge(edge.to, edge.from)
                if (reverse in edges) blocked += RoadTurn(edge, reverse)
            }
        }
        require(edges.isNotEmpty()) { "No public drivable roads were found for this route." }
        return RoadGraph(coordinates, edges.toList(), blocked, coverage, downloadedAtMillis)
    }
}
