package dev.routeodo

import android.content.Context
import dev.routeodo.core.Route
import org.json.JSONObject
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.*
import org.maplibre.geojson.MultiPolygon
import org.maplibre.geojson.Point
import kotlin.math.*

// Preserve the existing resource manifest and pack identities for offline upgrades.
// Bundled driving styles use a subset of these tiles and font stacks.
const val OFFLINE_RESOURCE_STYLE = "https://tiles.openfreemap.org/styles/liberty"
val KYIV_BOUNDS: LatLngBounds get() = LatLngBounds.from(50.62, 30.83, 50.21, 30.24)

/** One independently managed pack. Route packs and the Kyiv area share MapLibre's tile database. */
class OfflineMaps(context: Context, private val update: (String) -> Unit) {
    private val manager = OfflineManager.getInstance(context.applicationContext)
    private val pixelRatio = context.resources.displayMetrics.density
    private var key = ""
    private var name = ""
    private var definition: (() -> OfflineRegionDefinition)? = null
    private var active: OfflineRegion? = null
    private var disposed = false
    private var operationPending = false
    private var generation = 0
    private var downloadRequested = false
    var statusText = "Not downloaded"
        private set
    var ready = false
        private set

    fun select(route: Route) = select(route.id, route.name) {
        OfflineGeometryRegionDefinition(OFFLINE_RESOURCE_STYLE, corridor(route), 0.0, 16.0, pixelRatio)
    }

    fun selectKyiv() = select("kyiv-area-z15-v1", "Kyiv area") {
        OfflineTilePyramidRegionDefinition(OFFLINE_RESOURCE_STYLE, KYIV_BOUNDS, 0.0, 15.0, pixelRatio)
    }

    private fun select(packKey: String, packName: String, buildDefinition: () -> OfflineRegionDefinition) {
        key = packKey; name = packName; definition = buildDefinition; downloadRequested = false
        val selected = ++generation
        active?.setDownloadState(OfflineRegion.STATE_INACTIVE)
        active?.setObserver(null)
        active = null; ready = false; operationPending = true
        emit("Checking saved map…")
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                if (disposed || selected != generation) return
                operationPending = false
                active = offlineRegions?.firstOrNull { region ->
                    runCatching { JSONObject(String(region.metadata, Charsets.UTF_8)).let {
                        it.optString("packKey", it.optString("routeId")) == key
                    } }.getOrDefault(false)
                }
                active?.let { observe(it); status(it) } ?: emit("Not downloaded")
            }
            override fun onError(error: String) {
                if (disposed || selected != generation) return
                operationPending = false; emit("Map storage unavailable")
            }
        })
    }

    fun download() {
        if (disposed) return
        if (operationPending) { emit("Map storage is busy · try again shortly"); return }
        downloadRequested = true
        active?.let { observe(it); it.setDownloadState(OfflineRegion.STATE_ACTIVE); emit("Checking / resuming download…"); return }
        val spec = definition?.invoke() ?: return
        val selected = generation
        val metadata = JSONObject().put("packKey", key).put("name", name).toString().toByteArray(Charsets.UTF_8)
        operationPending = true; emit("Preparing download…")
        manager.createOfflineRegion(spec, metadata, object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(offlineRegion: OfflineRegion) {
                if (disposed || selected != generation) return
                operationPending = false; active = offlineRegion
                observe(offlineRegion)
                if (downloadRequested) offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE) else status(offlineRegion)
            }
            override fun onError(error: String) {
                if (disposed || selected != generation) return
                operationPending = false; emit("Download could not start")
            }
        })
    }

    fun refresh() {
        if (operationPending || disposed) return
        val region = active ?: run { download(); return }
        downloadRequested = true
        operationPending = true
        region.setDownloadState(OfflineRegion.STATE_INACTIVE)
        emit("Checking for map updates…")
        region.invalidate(object : OfflineRegion.OfflineRegionInvalidateCallback {
            override fun onInvalidate() {
                if (disposed || active !== region) return
                operationPending = false
                observe(region)
                if (downloadRequested) region.setDownloadState(OfflineRegion.STATE_ACTIVE) else status(region)
            }
            override fun onError(error: String) {
                if (disposed || active !== region) return
                operationPending = false; emit("Could not refresh · saved map retained")
            }
        })
    }

    fun pause() {
        downloadRequested = false
        val region = active ?: return
        region.setDownloadState(OfflineRegion.STATE_INACTIVE)
        if (!ready) emit("Download paused")
    }

    fun deleteCurrent() {
        if (operationPending) return
        val region = active ?: return
        operationPending = true
        region.setDownloadState(OfflineRegion.STATE_INACTIVE); region.setObserver(null)
        region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() {
                if (disposed || active !== region) return
                active = null; operationPending = false; ready = false; emit("Not downloaded")
            }
            override fun onError(error: String) {
                if (disposed || active !== region) return
                operationPending = false; observe(region); emit("Could not remove saved map")
            }
        })
    }

    fun close() { disposed = true; downloadRequested = false; active?.setDownloadState(OfflineRegion.STATE_INACTIVE); active?.setObserver(null) }

    private fun status(region: OfflineRegion) {
        region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
            override fun onStatus(status: OfflineRegionStatus?) { if (!disposed && active === region) status?.let { render(it) } }
            override fun onError(error: String?) { if (!disposed && active === region) emit("Map status unavailable") }
        })
    }

    private fun observe(region: OfflineRegion) {
        region.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) {
                if (disposed || active !== region || operationPending) return
                if (status.isComplete && status.downloadState != OfflineRegion.STATE_INACTIVE) region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                if (status.completedResourceSize > 1_000_000_000L && !status.isComplete) {
                    if (status.downloadState != OfflineRegion.STATE_INACTIVE) region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                    emit("Paused at 1 GB · choose a smaller area")
                } else render(status)
            }
            override fun onError(error: OfflineRegionError) { if (!disposed && active === region) emit("Download waiting for network / map server") }
            override fun mapboxTileCountLimitExceeded(limit: Long) {
                if (disposed || active !== region) return
                region.setDownloadState(OfflineRegion.STATE_INACTIVE); emit("Map tile limit reached")
            }
        })
    }

    private fun render(status: OfflineRegionStatus) {
        if (disposed) return
        ready = status.isComplete
        val megabytes = status.completedResourceSize / 1_000_000
        emit(if (ready) "Ready offline · $megabytes MB" else {
            val progress = if (status.isRequiredResourceCountPrecise && status.requiredResourceCount > 0)
                "${status.completedResourceCount * 100 / status.requiredResourceCount}%" else "${status.completedResourceCount} resources"
            "${if (status.downloadState == OfflineRegion.STATE_ACTIVE) "Downloading" else "Paused"} · $progress · $megabytes MB"
        })
    }

    private fun emit(value: String) { if (!disposed) { statusText = value; update(value) } }

    private fun corridor(route: Route): MultiPolygon {
        require(route.points.maxOf { it.longitude } - route.points.minOf { it.longitude } < 180) {
            "Offline downloads across the date line are not supported yet"
        }
        // Union of overlapping rectangles follows the route with roughly 750 m of context.
        // Group by path length so curves remain inside each rectangle.
        val polygons = mutableListOf<List<List<Point>>>()
        var start = 0
        while (start < route.points.lastIndex) {
            var end = start + 1
            while (end < route.points.lastIndex && route.cumulative[end] - route.cumulative[start] < 700) end++
            val section = route.points.subList(start, end + 1)
            val latPad = 750.0 / 111_195
            val centerLat = section.map { it.latitude }.average()
            val lonPad = latPad / cos(Math.toRadians(centerLat)).coerceAtLeast(0.08)
            val west = (section.minOf { it.longitude } - lonPad).coerceAtLeast(-180.0)
            val east = (section.maxOf { it.longitude } + lonPad).coerceAtMost(180.0)
            val south = (section.minOf { it.latitude } - latPad).coerceAtLeast(-85.0)
            val north = (section.maxOf { it.latitude } + latPad).coerceAtMost(85.0)
            polygons += listOf(listOf(Point.fromLngLat(west, south), Point.fromLngLat(east, south),
                Point.fromLngLat(east, north), Point.fromLngLat(west, north), Point.fromLngLat(west, south)))
            start = end
        }
        return MultiPolygon.fromLngLats(polygons)
    }
}
