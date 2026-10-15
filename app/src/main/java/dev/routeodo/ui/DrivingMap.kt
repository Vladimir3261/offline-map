package dev.routeodo.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import dev.routeodo.R
import dev.routeodo.core.*
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.layers.*
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.*
import kotlin.math.*

/** Map rendering and camera only; calculated and raw-GPS sources never replace one another. */
class DrivingMap(private val context: Context, private val map: MapLibreMap, private val follow: FollowState, onGesture: () -> Unit) {
    private var route: Route? = null
    private var renderedProgress = Double.NaN
    private var comparison: PositionDebug? = null
    private var accuracyFix: GpsFix? = null
    private var overlap: Boolean? = null
    private var styleGeneration = 0
    private var night = false
    init {
        map.addOnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) { follow.browse(); onGesture() }
        }
        map.addOnCameraMoveListener { updateOverlap() }
    }

    fun loadStyle(dark: Boolean, ready: () -> Unit) {
        night = dark
        val generation = ++styleGeneration
        val json = context.assets.open("styles/routeodo-${if (dark) "night" else "day"}.json").bufferedReader().use { it.readText() }
        map.setStyle(Style.Builder().fromJson(json)) { style ->
            if (generation != styleGeneration) return@setStyle
            route = null; accuracyFix = null; overlap = null; renderedProgress = Double.NaN
            setup(style); ready()
        }
    }

    fun close() { styleGeneration++ }

    private fun setup(style: Style) {
        fun source(id: String) = style.addSource(GeoJsonSource(id, empty()))
        listOf("route", "route-start", "route-travelled", "route-gaps", "gps-accuracy", "gps-connector", "gps-position", "position", "destination", "voice-estimate").forEach(::source)
        style.addLayer(LineLayer("route-outline", "route").withProperties(lineColor("#233A70"), lineWidth(12f), lineCap("round"), lineJoin("round")))
        style.addLayer(LineLayer("route-active", "route").withProperties(lineColor(if (night) "#6A8EFF" else "#4D78FF"), lineWidth(8f), lineCap("round"), lineJoin("round")))
        style.addLayer(LineLayer("route-travelled-line", "route-travelled").withProperties(lineColor("#7989A8"), lineWidth(8f), lineCap("round"), lineJoin("round")))
        style.addLayer(CircleLayer("route-gap-dots", "route-gaps").withProperties(circleRadius(6f), circleColor("#F04E3E"), circleStrokeColor(Color.WHITE), circleStrokeWidth(2f)))
        style.addLayer(CircleLayer("destination-dot", "destination").withProperties(circleRadius(7f), circleColor(Color.WHITE), circleStrokeColor("#233A70"), circleStrokeWidth(3f)))
        style.addLayer(CircleLayer("start-dot", "route-start").withProperties(circleRadius(6f), circleColor("#4D78FF"), circleStrokeColor(Color.WHITE), circleStrokeWidth(3f)))
        style.addLayer(FillLayer("gps-accuracy-fill", "gps-accuracy").withProperties(fillColor("#4285F4"), fillOpacity(.12f)))
        style.addLayer(LineLayer("gps-accuracy-outline", "gps-accuracy").withProperties(lineColor("#4285F4"), lineWidth(1f), lineOpacity(.45f)))
        style.addLayer(LineLayer("gps-comparison-line", "gps-connector").withProperties(lineColor(if (night) "#B2C3E5" else "#61759A"), lineWidth(1.5f), lineOpacity(.6f), lineDasharray(arrayOf(2f, 3f))))
        style.addLayer(CircleLayer("gps-ring", "gps-position").withProperties(circleRadius(14f), circleColor("#4285F4"), circleOpacity(.08f), circleStrokeColor("#4285F4"), circleStrokeWidth(2.5f)))
        style.addLayer(CircleLayer("gps-dot", "gps-position").withProperties(circleRadius(7f), circleColor("#4285F4"), circleStrokeColor(Color.WHITE), circleStrokeWidth(2.5f)))
        style.addLayer(CircleLayer("voice-estimate-dot", "voice-estimate").withProperties(circleRadius(11f),
            circleColor("#9654D8"), circleStrokeColor(Color.WHITE), circleStrokeWidth(3f)))
        val drawable = context.getDrawable(R.drawable.ic_vehicle)!!
        val size = context.dp(52)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bitmap.density = context.resources.displayMetrics.densityDpi
        drawable.setBounds(0, 0, size, size); drawable.draw(Canvas(bitmap))
        style.addImage("routeodo-chevron", bitmap)
        style.addLayer(SymbolLayer("calculated-chevron", "position").withProperties(
            iconImage("routeodo-chevron"), iconRotate(get("bearing")), iconRotationAlignment("map"),
            iconPitchAlignment("viewport"), iconAllowOverlap(true), iconIgnorePlacement(true)))
    }

    fun voiceEstimate(coordinate: Coordinate?) { put("voice-estimate", coordinate?.let(::point)) }

    fun renderExplore(gps: GpsFix?) {
        route = null; comparison = null; renderedProgress = Double.NaN; overlap = null
        listOf("route", "route-start", "route-travelled", "route-gaps", "position", "destination", "gps-connector").forEach { put(it, null) }
        put("gps-position", gps?.coordinate?.let(::point))
        put("gps-accuracy", gps?.let(::accuracyCircle)); accuracyFix = gps
        map.style?.getLayerAs<CircleLayer>("gps-ring")?.setProperties(circleRadius(14f))
    }

    fun render(currentRoute: Route, progress: Double, position: RoutePosition, debug: PositionDebug, gaps: List<RouteGap>, nowMs: Long, navigating: Boolean = true) {
        val style = map.style ?: return
        if (style.getSource("position") == null) return
        if (route !== currentRoute) {
            route = currentRoute; renderedProgress = Double.NaN
            put("route", LineString.fromLngLats(currentRoute.points.map(::point)))
            put("destination", point(currentRoute.points.last()))
        }
        if (renderedProgress != progress) {
            renderedProgress = progress
            val passed = currentRoute.points.take(position.segment + 1).map(::point) + point(position.coordinate)
            put("route-travelled", if (progress > 0 && passed.size >= 2) LineString.fromLngLats(passed) else null)
        }
        val feature = Feature.fromGeometry(point(position.coordinate)).apply { addNumberProperty("bearing", position.bearing) }
        if (navigating) style.getSourceAs<GeoJsonSource>("position")?.setGeoJson(feature) else put("position", null)
        put("route-start", if (navigating) null else point(currentRoute.points.first()))
        style.getSourceAs<GeoJsonSource>("route-gaps")?.setGeoJson(FeatureCollection.fromFeatures(gaps.map { Feature.fromGeometry(point(currentRoute.position(it.progressMeters).coordinate)) }))
        comparison = if (navigating) debug else null
        put("gps-position", debug.gps?.coordinate?.let(::point))
        if (debug.gps != accuracyFix) { accuracyFix = debug.gps; put("gps-accuracy", debug.gps?.let(::accuracyCircle)) }
        if (navigating) updateOverlap(force = true) else {
            put("gps-connector", null)
            style.getLayerAs<CircleLayer>("gps-ring")?.setProperties(circleRadius(14f))
        }
        if (navigating && follow.shouldMove(position, nowMs)) {
            map.easeCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder()
                .target(LatLng(position.coordinate.latitude, position.coordinate.longitude)).zoom(16.6)
                .tilt(48.0).bearing(position.bearing).build()), 600, false)
        }
    }

    fun showBoth(debug: PositionDebug, padding: IntArray) {
        follow.browse()
        // Explicit bounds padding replaces, rather than inherits, the map's follow padding.
        // Include the accuracy area as well as both marker centers.
        val coordinates = mutableListOf(point(debug.calculated))
        debug.gps?.let { fix ->
            coordinates += point(fix.coordinate)
            coordinates += accuracyCircle(fix).coordinates().flatten()
        }
        map.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(map.cameraPosition).tilt(0.0).bearing(0.0).build()))
        if (debug.gps == null || debug.separationMeters!! < 1 && debug.gps.accuracyMeters < 1) {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(debug.calculated.latitude, debug.calculated.longitude), 16.0))
        } else {
            val bounds = LatLngBounds.Builder().includes(coordinates.map { LatLng(it.latitude(), it.longitude()) }).build()
            val margin = context.dp(40)
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, padding[0] + margin, padding[1] + margin,
                padding[2] + margin, padding[3] + margin))
        }
    }

    private fun updateOverlap(force: Boolean = false) {
        val view = comparison ?: return
        val gps = view.gps
        val overlapping = if (gps == null) false else {
            val a = map.projection.toScreenLocation(LatLng(view.calculated.latitude, view.calculated.longitude))
            val b = map.projection.toScreenLocation(LatLng(gps.coordinate.latitude, gps.coordinate.longitude))
            MarkerOverlap.visibleOverlap((a.x - b.x).toDouble(), (a.y - b.y).toDouble(), context.resources.displayMetrics.density.toDouble())
        }
        if (!force && overlapping == overlap) return
        overlap = overlapping
        map.style?.getLayerAs<CircleLayer>("gps-ring")?.setProperties(circleRadius(if (overlapping) 30f else 14f))
        put("gps-connector", if (gps != null && !overlapping) LineString.fromLngLats(listOf(point(view.calculated), point(gps.coordinate))) else null)
    }

    private fun put(id: String, geometry: Geometry?) {
        map.style?.getSourceAs<GeoJsonSource>(id)?.setGeoJson(if (geometry == null) empty() else FeatureCollection.fromFeature(Feature.fromGeometry(geometry)))
    }
    private fun point(c: Coordinate) = Point.fromLngLat(c.longitude, c.latitude)
    private fun empty() = FeatureCollection.fromFeatures(emptyList<Feature>())
    private fun accuracyCircle(fix: GpsFix): Polygon {
        val lat = Math.toRadians(fix.coordinate.latitude)
        val lon = Math.toRadians(fix.coordinate.longitude)
        val radius = fix.accuracyMeters / 6_371_008.8
        val ring = (0..64).map { index ->
            val bearing = 2 * PI * index / 64
            val y = asin((sin(lat) * cos(radius) + cos(lat) * sin(radius) * cos(bearing)).coerceIn(-1.0, 1.0))
            val x = lon + atan2(sin(bearing) * sin(radius) * cos(lat), cos(radius) - sin(lat) * sin(y))
            Point.fromLngLat((Math.toDegrees(x) + 540) % 360 - 180, Math.toDegrees(y).coerceIn(-85.0, 85.0))
        }
        return Polygon.fromLngLats(listOf(ring))
    }
}
