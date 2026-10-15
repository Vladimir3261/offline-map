package dev.routeodo.core

import kotlin.math.*

/** Bounds of the OSM query, not a claim that every road in the area is mapped. */
data class RoadCoverage(val south: Double, val west: Double, val north: Double, val east: Double) {
    init {
        require(listOf(south, west, north, east).all { it.isFinite() })
        require(south >= -85 && north <= 85 && south < north && west >= -180 && east <= 180 && west < east)
    }
    fun contains(point: Coordinate) = point.latitude in south..north && point.longitude in west..east
    fun contains(route: Route) = route.points.all(::contains)
    companion object {
        fun around(route: Route): RoadCoverage {
            val latPad = 750.0 / 111_195
            val latitude = route.points.map { it.latitude }.average()
            val lonPad = latPad / cos(Math.toRadians(latitude))
            val south = route.points.minOf { it.latitude } - latPad
            val north = route.points.maxOf { it.latitude } + latPad
            val west = route.points.minOf { it.longitude } - lonPad
            val east = route.points.maxOf { it.longitude } + lonPad
            require(south >= -85 && north <= 85 && west >= -180 && east <= 180 && east - west < 180) {
                "This route crosses unsupported download bounds"
            }
            val width = Route.distance(Coordinate(latitude, west), Coordinate(latitude, east))
            val height = Route.distance(Coordinate(south, route.points.first().longitude), Coordinate(north, route.points.first().longitude))
            require(width * height <= 600_000_000 && max(width, height) <= 150_000) {
                "Navigation area is too large. Select a shorter route (up to 600 km²)."
            }
            return RoadCoverage(south, west, north, east)
        }
    }
}
