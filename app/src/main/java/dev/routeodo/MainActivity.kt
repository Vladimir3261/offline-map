package dev.routeodo

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Network
import org.maplibre.android.offline.OfflineManager
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import dev.routeodo.core.*
import dev.routeodo.data.*
import dev.routeodo.ui.*
import android.content.res.Configuration
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.FeatureCollection
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.*

class MainActivity : Activity() {
    private var palette = DrivingPalette(false)
    private val ink get() = palette.text
    private val green get() = palette.accent
    private val paper get() = palette.background
    private val muted get() = palette.secondary
    private val handler = Handler(Looper.getMainLooper())
    private val workers = Executors.newFixedThreadPool(2)
    private val disk = Executors.newSingleThreadExecutor()
    private val network = dev.routeodo.data.Network()
    private lateinit var carFeed: CarFeed
    private var carIssue = CarFeedIssue.NONE
    private var carIssueMessage = ""
    private var transport = ""
    private var connectionStartedAt = 0L
    private var tryLanFirst = true
    private var observedNetwork: Network? = null
    private var connectivityCallback: ConnectivityManager.NetworkCallback? = null
    private lateinit var store: TripStore
    private lateinit var tokenStore: TokenStore
    private lateinit var gps: GpsSync
    private lateinit var gpsDebug: GpsDebug
    private var gpsFix: GpsFix? = null
    private var gpsDebugMessage = "Waiting for GPS fix"
    private lateinit var offline: OfflineMaps
    private lateinit var kyivOffline: OfflineMaps
    private var planner: RoutePlanner? = null
    private var mapsStatusUpdate: (() -> Unit)? = null
    private var refreshingMap = false
    private var geocodingUrl = DEFAULT_GEOCODER
    private var starting = false
    private lateinit var mapView: MapView
    private var map: MapLibreMap? = null
    private lateinit var route: Route
    private var tracker = TripTracker()
    private var live = false
    private var simMeters = 0.0
    private var simRunning = false
    private val followState = FollowState()
    private var follow: Boolean
        get() = followState.enabled
        set(value) { if (value) followState.recenter() else followState.browse() }
    private lateinit var appearance: AppearanceSettings
    private lateinit var drivingUi: DrivingUi
    private var drivingMap: DrivingMap? = null
    private var servicePanel: ServicePanel? = null
    private var mapSummary = "Checking saved maps…"
    private var currentFeedText = ""
    private var mapPadding = intArrayOf(0, 0, 0, 0)
    private var saveError: String? = null
    private var active = false
    private var polling = false
    private var generation = 0
    private var apiUrl = PUBLIC_ODOMETER_URL
    private var bearerToken = ""
    private var routingUrl = "https://router.project-osrm.org"
    private var lastAcceptedAt = 0L
    private var latest: CarSample? = null
    private var latestAt = 0L
    private var feedMessage = "Ready to simulate"
    private var undoTrip: TripState? = null
    private var undoSimBefore: Double? = null
    private var undoSimAfter: Double? = null
    private var selectedStart: Coordinate? = null
    private var selectedEnd: Coordinate? = null
    private var gpsPending = false
    private val progressMeters: Double get() = if (live) tracker.state.progressMeters else simMeters

    override fun onCreate(savedInstanceState: Bundle?) {
        appearance = AppearanceSettings(this)
        palette = DrivingPalette(appearance.night(this))
        setTheme(if (palette.night) R.style.AppThemeNight else R.style.AppTheme)
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        else window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        MapLibre.getInstance(this)
        store = TripStore(this)
        tokenStore = TokenStore(this)
        gps = GpsSync(this)
        gpsDebug = GpsDebug(this)
        bearerToken = tokenStore.read()
        val preferences = getSharedPreferences("settings", MODE_PRIVATE)
        apiUrl = preferences.getString("apiUrl", PUBLIC_ODOMETER_URL) ?: PUBLIC_ODOMETER_URL
        tryLanFirst = preferences.getBoolean("tryLanFirst", true)
        routingUrl = preferences.getString("routingUrl", routingUrl) ?: routingUrl
        geocodingUrl = preferences.getString("geocodingUrl", geocodingUrl) ?: geocodingUrl
        restore()
        buildUi(savedInstanceState)
        carFeed = CarFeed(this, sample = { sample, source ->
            if (active && live) {
                latest = sample; latestAt = SystemClock.elapsedRealtime(); lastAcceptedAt = latestAt; transport = source
                val before = tracker.state
                val result = tracker.accept(sample)
                if (tracker.state != before) schedulePersist()
                if (tracker.state.baseM != null) starting = false
                feedMessage = result.message
                render()
            }
        }, status = { issue, message ->
            val changed = carIssue != issue || carIssueMessage != message
            carIssue = issue; carIssueMessage = message
            if (issue == CarFeedIssue.AUTH && starting) { starting = false; tracker.stop(); persist() }
            if (active && changed) render()
        })
        val manager = getSystemService(ConnectivityManager::class.java)
        connectivityCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                handler.post {
                    val changed = observedNetwork != null && observedNetwork != network
                    observedNetwork = network
                    if (changed && active && live) connectCar()
                }
            }
        }.also { manager.registerDefaultNetworkCallback(it) }
        offline = OfflineMaps(this) { renderMapStatus(); mapsStatusUpdate?.invoke() }
        kyivOffline = OfflineMaps(this) { renderMapStatus(); mapsStatusUpdate?.invoke() }
        offline.select(route)
        kyivOffline.selectKyiv()
        mapView.getMapAsync { ready ->
            map = ready
            ready.uiSettings.isLogoEnabled = false
            ready.uiSettings.setCompassImage(getDrawable(if (palette.night) R.drawable.ic_compass_night else R.drawable.ic_compass_day)!!)
            ready.uiSettings.setCompassMargins(0, dp(210), dp(16), 0)
            // The SDK labels both compass clicks and our follow transitions as API animations.
            // Observe compass touch directly; map gestures are handled by DrivingMap.
            mapView.findViewWithTag<View>("compassView")?.setOnTouchListener { _, event ->
                if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) { follow = false; render() }
                false
            }
            ready.setPadding(mapPadding[0], mapPadding[1], mapPadding[2], mapPadding[3])
            drivingMap = DrivingMap(this, ready, followState) { render() }
            ready.addOnMapLongClickListener { p -> selectPoint(Coordinate(p.latitude, p.longitude)); true }
            loadMapStyle(true)
        }
        render()
    }

    private fun demoRoute(): Route {
        val demo = JsonCodec.osrm(assets.open("demo-route.json").bufferedReader().use { it.readText() }, "Kyiv · station to Podil")
        return Route("kyiv-demo-v1", demo.name, demo.points, demo.maneuvers)
    }

    private fun restore() {
        route = demoRoute()
        val saved = store.read() ?: return
        runCatching {
            val restoredRoute = JsonCodec.route(saved.getJSONObject("route"))
            if (restoredRoute.name == "Berlin · demo route") return
            val restoredTracker = TripTracker(JsonCodec.state(saved.getJSONObject("tracker")))
            val restoredSim = saved.getDouble("simMeters")
            require(restoredSim.isFinite() && restoredSim >= 0)
            route = restoredRoute
            tracker = restoredTracker
            simMeters = restoredSim
            live = saved.optBoolean("live") && apiUrl.isNotBlank()
            undoTrip = saved.optJSONObject("undoTrip")?.let { JsonCodec.state(it) }?.takeIf { it.navigationSessionId != null }
            undoSimBefore = saved.optDouble("undoSimBefore").takeIf { it.isFinite() }
            undoSimAfter = saved.optDouble("undoSimAfter").takeIf { it.isFinite() }
            feedMessage = if (live) "Reconnecting to distance API…" else "Ready to simulate"
        }.onFailure { feedMessage = "Saved trip could not be read · loaded demo" }
    }

    private var saveScheduled = false
    private val saveTask = Runnable { saveScheduled = false; persist() }

    private fun schedulePersist() {
        if (saveScheduled) return
        saveScheduled = true
        handler.postDelayed(saveTask, 1000)
    }

    private fun persist() {
        handler.removeCallbacks(saveTask); saveScheduled = false
        // Stream updates can arrive ten times a second. Coalesce them and serialize off the UI thread.
        val savedRoute = route
        val savedTrip = tracker.state
        val savedLive = live
        val savedSim = simMeters
        val savedUndo = undoTrip
        val savedUndoBefore = undoSimBefore
        val savedUndoAfter = undoSimAfter
        disk.execute { runCatching {
            val json = JSONObject().put("route", JsonCodec.route(savedRoute)).put("tracker", JsonCodec.state(savedTrip))
                .put("live", savedLive).put("simMeters", savedSim).put("undoTrip", savedUndo?.let { JsonCodec.state(it) })
                .put("undoSimBefore", savedUndoBefore).put("undoSimAfter", savedUndoAfter)
            store.write(json)
        }.onFailure {
            handler.post { if (!isDestroyed) { saveError = getString(R.string.save_failed); render() } }
        } }
    }

    private fun buildUi(savedInstanceState: Bundle?) {
        mapView = MapView(this)
        mapView.onCreate(savedInstanceState)
        drivingUi = DrivingUi(this, mapView, services = { showServices() }, recenter = {
            follow = true; render()
        }, compare = { debugMenu() }, mapInsets = { left, top, right, bottom ->
            mapPadding = intArrayOf(left, top, right, bottom)
            map?.setPadding(left, top, right, bottom)
            map?.uiSettings?.setAttributionMargins(dp(8), 0, 0, dp(8))
        })
        setContentView(drivingUi)
        applyAppearance(reloadMap = false)
    }

    private fun drawRoute() { render() }

    private fun fitRoute() {
        map?.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(map!!.cameraPosition).tilt(0.0).bearing(0.0).build()))
        val bounds = LatLngBounds.Builder().includes(route.points.map { LatLng(it.latitude, it.longitude) }).build()
        map?.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, mapPadding[0] + dp(38), mapPadding[1] + dp(38), mapPadding[2] + dp(38), mapPadding[3] + dp(38)))
    }

    private fun render() {
        if (!::drivingUi.isInitialized) return
        val now = SystemClock.elapsedRealtime()
        val progress = progressMeters
        val debug = positionDebug()
        val presentation = DrivingPresenter.present(DrivingInputs(route, progress, tracker.state, debug, now,
            live, simRunning, starting, latest, latestAt, connectionStartedAt, carIssue, carIssueMessage,
            transport, feedMessage, follow, saveError), ::formatDistance)
        currentFeedText = presentation.feedText
        drivingUi.render(presentation.screen)
        drivingMap?.render(route, progress, route.position(progress), debug, if (live) tracker.state.gaps else emptyList(), now)
        renderMapStatus()
    }

    private fun positionDebug() = PositionDebug.compare(route.position(progressMeters).coordinate, gpsFix,
        SystemClock.elapsedRealtime(), checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)

    private fun refreshServices() {
        servicePanel?.takeIf { it.isShowing }?.render(ServiceState(route.name, live, tracker.state.running, starting,
            gpsPending, if (live) undoTrip != null else undoSimBefore != null, simRunning,
            (progressMeters / route.lengthMeters * 10_000).toInt().coerceIn(0, 10_000), currentFeedText, mapSummary,
            "${formatDistance(progressMeters)} travelled · ${formatDistance(route.lengthMeters)} total", appearance.mode.name.lowercase().replaceFirstChar { it.uppercase() }))
    }

    private fun showServices() {
        servicePanel?.dismiss()
        servicePanel = ServicePanel(this, palette, ServiceActions(
            route = { routeMenu() }, api = { feedSettings() }, retry = { if (live) connectCar(retryAuth = true) else feedSettings() },
            startStop = { if (live && tracker.state.running) stopRoute() else startRoute() },
            align = { alignDialog() }, sync = { startGpsSync() }, undo = { undoSync() }, maps = { mapsMenu() },
            positions = { debugMenu() }, apiDetails = { apiDebugMenu() }, appearance = { appearanceMenu() },
            preview = { if (live) enterPreview() }, drive = { simRunning = !simRunning; follow = true; render() },
            step = { delta -> simMeters = (simMeters + delta).coerceAtLeast(0.0); if (delta < 0) clearUndo(); render(); persist() },
            seek = { value -> simMeters = route.lengthMeters * value / 10_000.0; clearUndo(); render() },
            seekStart = { simRunning = false }, seekStop = { persist() })).also { it.show() }
        refreshServices()
    }

    private fun enterPreview() {
        if (live) simMeters = tracker.state.progressMeters
        live = false; tracker.stop(); carFeed.stop(); starting = false; generation++; polling = false
        simRunning = false; clearUndo(); persist(); render()
    }

    private fun appearanceMenu() {
        AlertDialog.Builder(this).setTitle("Map appearance")
            .setSingleChoiceItems(arrayOf("System", "Day", "Night"), appearance.mode.ordinal) { dialog, index ->
                appearance.mode = AppearanceMode.entries[index]; applyAppearance(); dialog.dismiss()
            }.setNegativeButton("Back to map", null).show()
    }

    private fun applyAppearance(reloadMap: Boolean = true) {
        palette = DrivingPalette(appearance.night(this))
        setTheme(if (palette.night) R.style.AppThemeNight else R.style.AppTheme)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 30) {
            val bars = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (palette.night) 0 else bars, bars)
        } else window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or if (palette.night) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        map?.uiSettings?.setCompassImage(getDrawable(if (palette.night) R.drawable.ic_compass_night else R.drawable.ic_compass_day)!!)
        drivingUi.applyAppearance(palette)
        if (reloadMap) loadMapStyle(false)
        render()
    }

    private fun apiDebugMenu() {
        val sample = latest
        val text = buildString {
            append(currentFeedText)
            append("\n\nTransport: ").append(transport.ifBlank { "Not connected" })
            append("\nNavigation: ").append(if (tracker.state.running) "Running" else "Stopped")
            append("\nRoute distance: ").append(formatDistance(progressMeters))
            append("\nCalibration: ").append(tracker.state.scale)
            append("\nDistance gaps: ").append(tracker.state.gaps.size)
            if (sample != null) {
                append("\n\nOdometer: ").append(sample.odometerM ?: "No sample").append(" m")
                append("\nSpeed: ").append(sample.speedKmh ?: "Unknown").append(" km/h")
                append("\nEpoch: ").append(sample.epoch)
                append("\nServer time: ").append(sample.t)
                append("\nAnchor age: ").append(sample.odometerT?.let { String.format(Locale.US, "%.2f s", sample.t - it) } ?: "Unknown")
                append("\nSpeed age: ").append(sample.speedT?.let { String.format(Locale.US, "%.2f s", sample.t - it) } ?: "Unknown")
                append("\nECU connected: ").append(sample.connected)
                append("\nClock synced: ").append(sample.clockSynced)
                append("\nResets: ").append(sample.resets)
                append("\nSource: ").append(sample.source)
                append("\nMapping version: ").append(sample.mappingVersion)
            }
        }
        AlertDialog.Builder(this).setTitle("Car / navigation diagnostics").setMessage(text)
            .setPositiveButton("Back to map", null).setNeutralButton("Retry connection") { _, _ -> if (live) connectCar(retryAuth = true) else feedSettings() }.show()
    }

    private fun startGpsDebug() {
        if (!active) return
        gpsDebug.start { fix, message ->
            if (active) { gpsFix = fix; gpsDebugMessage = message; render() }
        }
    }

    private fun requestGpsPermission(code: Int) {
        getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("gpsPermissionRequested", true).apply()
        requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), code)
    }

    private fun debugMenu() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            AlertDialog.Builder(this).setTitle("Live GPS debug")
                .setMessage("Allow precise location while using the app to compare GPS with the distance marker. Only GPS Sync changes route alignment. If permission was previously blocked, enable it in app settings.")
                .setPositiveButton("Allow location") { _, _ -> requestGpsPermission(21) }
                .setNeutralButton("App settings") { _, _ -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
                .setNegativeButton("Cancel", null).show()
            return
        }
        val view = positionDebug()
        fun coordinates(c: Coordinate) = String.format(Locale.US, "%.6f, %.6f", c.latitude, c.longitude)
        val details = "Calculated: ${coordinates(view.calculated)}\n" + (view.gps?.let {
            "GPS: ${coordinates(it.coordinate)}\nSeparation: ${String.format(Locale.getDefault(), "%.1f", view.separationMeters)} m\nGPS accuracy: ±${it.accuracyMeters.roundToInt()} m"
        } ?: if (gpsFix != null) "GPS fix is stale." else gpsDebugMessage) +
            "\n\nThe chevron follows the route. The blue dot and accuracy circle stay at the raw GPS coordinate. A blue ring remains visible when their screen positions overlap. Displaying GPS never changes distance progress."
        val dialog = AlertDialog.Builder(this).setTitle("GPS / calculated position").setMessage(details)
            .setNeutralButton("Location settings") { _, _ -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
            .setNegativeButton("Close", null)
        if (view.gps != null) dialog.setPositiveButton("Show both") { _, _ -> showDebugPositions() }
        else dialog.setPositiveButton("Retry GPS") { _, _ -> gpsDebug.stop(); gpsFix = null; startGpsDebug() }
        dialog.show()
    }

    private fun showDebugPositions() {
        val view = positionDebug()
        follow = false
        render()
        drivingMap?.showBoth(view, mapPadding)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            if (!live && simRunning) {
                simMeters += 50_000.0 / 3600
                if (simMeters >= route.lengthMeters) { simMeters = route.lengthMeters; simRunning = false }
                persist()
            }
            render()
            handler.postDelayed(this, 1000)
        }
    }

    private fun vehicleScale(url: String = apiUrl): Double = getSharedPreferences("settings", MODE_PRIVATE)
        .getString("scale:$url", "1.0")?.toDoubleOrNull()?.takeIf { it in 0.5..1.5 } ?: 1.0

    private fun connectCar(retryAuth: Boolean = false) {
        if (!active || !live) return
        latest = null; latestAt = 0; connectionStartedAt = SystemClock.elapsedRealtime()
        carFeed.start(apiUrl, bearerToken, tryLanFirst, retryAuth)
    }

    private fun feedSettings() {
        val fields = column()
        val url = input("Distance API URL", apiUrl, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val token = input("Bearer token (optional)", bearerToken, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        fields.addView(label("Live stream when available, otherwise poll every 500 ms. Start route sets the first odometer anchor.", 14f, muted))
        val lan = CheckBox(this).apply { text = "Try car LAN first (1 s timeout)"; isChecked = tryLanFirst }
        fields.addView(lan)
        fields.addView(label(LAN_ODOMETER_URL, 12f, muted))
        fields.addView(url); fields.addView(token)
        val dialog = AlertDialog.Builder(this).setTitle("Odometer API").setView(fields)
            .setPositiveButton("Use API", null).setNeutralButton("Preview") { _, _ -> enterPreview()
            }.setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            runCatching {
                val endpoint = url.text.toString().trim().toHttpUrl()
                require(endpoint.username.isEmpty() && endpoint.password.isEmpty() && endpoint.query == null && endpoint.fragment == null) { "Use a plain endpoint URL and the token field for authentication" }
                val secret = token.text.toString().trim()
                require(secret.isBlank() || endpoint.isHttps || endpoint.toString().trimEnd('/') == LAN_ODOMETER_URL) { "Bearer tokens require HTTPS except for the car LAN endpoint" }
                tokenStore.write(secret)
                if (!live || apiUrl != endpoint.toString()) {
                    tracker = TripTracker(TripState(routeAnchorMeters = progressMeters, scale = vehicleScale(endpoint.toString())))
                    clearUndo()
                }
                apiUrl = endpoint.toString(); bearerToken = secret; tryLanFirst = lan.isChecked; live = true; simRunning = false; starting = false
                latest = null; lastAcceptedAt = SystemClock.elapsedRealtime(); generation++; polling = false
                feedMessage = "Connecting to odometer API · press Start route to begin"
                getSharedPreferences("settings", MODE_PRIVATE).edit().putString("apiUrl", apiUrl).putBoolean("tryLanFirst", tryLanFirst).apply()
                persist(); render(); connectCar(retryAuth = true); dialog.dismiss()
            }.onFailure { url.error = it.message ?: "Invalid API settings" }
        } }
        dialog.show()
    }

    private fun routeMenu() {
        AlertDialog.Builder(this).setTitle(route.name).setItems(arrayOf("Plan route / saved places", "Fit route on map", "Start route from zero", "Load Kyiv example", "Import route JSON", "Export route JSON")) { _, which ->
            when (which) {
                0 -> planRoute()
                1 -> { follow = false; fitRoute(); render() }
                2 -> startRoute()
                3 -> replaceRoute(demoRoute())
                4 -> startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json"), 11)
                5 -> startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/json").putExtra(Intent.EXTRA_TITLE, "routeodo-route.json"), 12)
            }
        }.show()
    }

    private fun planRoute() {
        planner?.dismiss()
        planner = RoutePlanner(this, network, workers, routingUrl, geocodingUrl, selectedStart, selectedEnd,
            freshGps = { positionDebug().gps }, requestPermission = { requestGpsPermission(22) },
            saveServices = { routing, geocoding ->
                routingUrl = routing; geocodingUrl = geocoding
                getSharedPreferences("settings", MODE_PRIVATE).edit().putString("routingUrl", routing)
                    .putString("geocodingUrl", geocoding).apply()
            }, ready = { newRoute ->
                replaceRoute(newRoute)
                AlertDialog.Builder(this).setTitle("Route ready · ${formatDistance(newRoute.lengthMeters)}")
                    .setMessage("Review the route on the map. When you are at the start, press Start route to set the odometer baseline. Maps lets you save this route or the Kyiv area for offline use.")
                    .setPositiveButton("View route", null).setNeutralButton("Odometer API") { _, _ -> feedSettings() }.show()
            }).also { it.show() }
    }

    private fun startRoute() {
        if (apiUrl.isBlank() || bearerToken.isBlank() && apiUrl == PUBLIC_ODOMETER_URL) { feedSettings(); return }
        live = true; simRunning = false; starting = true; clearUndo()
        tracker = TripTracker(TripState(scale = vehicleScale()))
        tracker.start()
        follow = true; persist(); render()
        // Restart transport so no pre-start queued response can become the new baseline.
        connectCar(retryAuth = true)
    }

    private fun stopRoute() {
        tracker.stop(); starting = false; generation++; polling = false; clearUndo()
        feedMessage = "Navigation stopped · press Start route for a new baseline"
        persist(); render()
    }

    private fun replaceRoute(newRoute: Route) {
        route = newRoute; tracker = TripTracker(TripState(scale = vehicleScale())); simMeters = 0.0; simRunning = false; starting = false
        clearUndo(); latest = null; lastAcceptedAt = 0; generation++; polling = false
        selectedStart = null; selectedEnd = null; follow = false
        offline.select(route); drawRoute(); fitRoute(); render(); persist()
    }

    private fun selectPoint(point: Coordinate) {
        AlertDialog.Builder(this).setTitle(String.format(Locale.US, "%.5f, %.5f", point.latitude, point.longitude))
            .setItems(arrayOf("Set route start", "Set destination", "Align marker here")) { _, which ->
                when (which) {
                    0 -> { selectedStart = point; toast("Start selected · choose destination, then Route") }
                    1 -> { selectedEnd = point; planRoute() }
                    2 -> {
                        val projection = route.project(point).first()
                        alignTo(projection.progressMeters)
                    }
                }
            }.show()
    }

    private fun alignDialog() {
        val fields = column()
        val progress = input("Metres from route start", String.format(Locale.US, "%.1f", progressMeters), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val scale = input("Distance calibration (1.0 = unchanged)", tracker.state.scale.toString(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        fields.addView(progress); fields.addView(scale)
        val dialog = AlertDialog.Builder(this).setTitle("Align route position").setView(fields).setPositiveButton("Align", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            runCatching {
                val meters = progress.text.toString().toDouble()
                val factor = scale.text.toString().toDouble()
                require(meters.isFinite() && meters in 0.0..route.lengthMeters) { "Position must be within this route" }
                require(factor.isFinite() && factor in 0.5..1.5) { "Calibration must be between 0.5 and 1.5" }
                if (alignTo(meters, factor)) dialog.dismiss()
            }.onFailure { progress.error = it.message ?: "Invalid alignment" }
        } }
        dialog.show()
    }

    private fun alignTo(meters: Double, scale: Double = tracker.state.scale): Boolean {
        if (live) {
            val sample = freshSample() ?: return false
            tracker.anchor(sample, meters, scale)
            getSharedPreferences("settings", MODE_PRIVATE).edit().putString("scale:$apiUrl", scale.toString()).apply()
        } else simMeters = meters
        clearUndo(); follow = true; persist(); render()
        return true
    }

    private fun freshSample(): CarSample? {
        if (!tracker.state.running) { notice("Start route first", "Press Start route to establish an odometer baseline before aligning."); return null }
        val sample = latest
        if (sample == null || SystemClock.elapsedRealtime() - latestAt >= 10_000 || !sample.anchorFresh) {
            notice("Waiting for distance", "A fresh API measurement is needed to align the route. Try again when the feed resumes.")
            return null
        }
        return sample
    }

    private fun hasInternet(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java)
        return manager.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }

    private fun renderMapStatus() {
        if (!::offline.isInitialized || !::kyivOffline.isInitialized || isDestroyed) return
        val connection = if (hasInternet()) "Online map" else "No internet"
        mapSummary = "$connection · Route: ${offline.statusText} · Kyiv: ${kyivOffline.statusText}"
        refreshServices()
    }

    private fun loadMapStyle(fit: Boolean) {
        drivingMap?.loadStyle(palette.night) {
            if (!isDestroyed) {
                if (fit && !follow) fitRoute()
                else if (follow) followState.recenter()
                render()
            }
        }
    }

    private fun refreshOnlineMap() {
        if (refreshingMap) return
        if (!hasInternet()) { notice("Offline", "Connect to the internet to check for map updates. Downloaded maps remain available."); return }
        refreshingMap = true
        OfflineManager.getInstance(this).invalidateAmbientCache(object : OfflineManager.FileSourceCallback {
            override fun onSuccess() { refreshingMap = false; if (!isDestroyed) { loadMapStyle(false); toast("Checking online map for updates") } }
            override fun onError(message: String) { refreshingMap = false; if (!isDestroyed) notice("Map refresh", message) }
        })
    }

    private fun mapsMenu() {
        val fields = column()
        fields.addView(label("Maps load automatically when internet is available. Download an area for reliable offline coverage; ordinary browsing cache is temporary.", 14f, muted))
        val routeStatus = label("", 13f, ink, true)
        val cityStatus = label("", 13f, ink, true)
        fun action(block: () -> Unit) { runCatching(block).onFailure { notice("Map download", it.message ?: "Could not start") } }
        fun pack(title: String, detail: TextView, pack: OfflineMaps) {
            fields.addView(label(title, 16f, ink, true)); fields.addView(detail)
            val buttons = row()
            buttons.addWeighted(button("Download") { action { pack.download() } })
            buttons.addWeighted(button("Pause") { pack.pause() })
            buttons.addWeighted(button("Refresh") { if (hasInternet()) action { pack.refresh() } else notice("Offline", "Connect to refresh this saved map") })
            fields.addView(buttons)
            fields.addView(button("Remove saved $title map") { pack.deleteCurrent() })
        }
        pack("Route · nearby streets, zoom 0–16", routeStatus, offline)
        pack("Kyiv area · zoom 0–15", cityStatus, kyivOffline)
        fields.addView(button("Show Kyiv coverage on map") {
            follow = false; map?.animateCamera(CameraUpdateFactory.newLatLngBounds(KYIV_BOUNDS, dp(30)))
        })
        fields.addView(label("Downloads include map style, labels and tiles, with a 1 GB cap per pack. Download only the route for a smaller pack. Address search and new route calculation still need internet.", 12f, muted))
        val dialog = AlertDialog.Builder(this).setTitle("Online and offline maps").setView(ScrollView(this).apply { addView(fields) })
            .setPositiveButton("Done", null).setNeutralButton("Refresh online map") { _, _ -> refreshOnlineMap() }.create()
        mapsStatusUpdate = { routeStatus.text = offline.statusText; cityStatus.text = kyivOffline.statusText }
        mapsStatusUpdate?.invoke()
        dialog.setOnDismissListener { mapsStatusUpdate = null }
        dialog.show()
    }

    private fun startGpsSync() {
        if (live && freshSample() == null) return
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestGpsPermission(20)
            return
        }
        gpsPending = true; render()
        gps.request { fix, error ->
            if (!active) return@request
            gpsPending = false; render()
            if (fix == null) notice("GPS Sync", error ?: "No GPS fix") else previewGps(fix)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 20 || requestCode == 21 || requestCode == 22) {
            startGpsDebug()
            if (requestCode == 22) {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) planner?.useGpsStart()
                else planner?.permissionDenied()
            }
            if (requestCode == 20) {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) startGpsSync()
                else notice("GPS Sync unavailable", "Precise location permission is needed for GPS debug and manual GPS Sync. Distance tracking remains available.")
            }
        }
    }

    private fun previewGps(fix: Location) {
        if (live && freshSample() == null) return
        val coordinate = runCatching { Coordinate(fix.latitude, fix.longitude) }.getOrElse {
            notice("GPS Sync", "This GPS fix is outside the map's supported latitude range."); return
        }
        val candidates = route.project(coordinate)
        val nearest = candidates.first().distanceFromRouteMeters
        val choices = candidates.filter { it.distanceFromRouteMeters <= nearest + max(10.0, fix.accuracy.toDouble()) }.take(5)
        val capturedProgress = progressMeters
        val capturedRoute = route.id
        val capturedLive = live
        val capturedSession = tracker.state.navigationSessionId
        val capturedAt = SystemClock.elapsedRealtime()
        var selected = 0
        val labels = choices.map { "${formatDistance(it.progressMeters)} from start · ${it.distanceFromRouteMeters.roundToInt()} m off route" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("GPS Sync · accuracy ±${fix.accuracy.roundToInt()} m")
            .setSingleChoiceItems(labels, 0) { _, choice -> selected = choice }
            .setPositiveButton("Apply sync") { _, _ ->
                if (capturedRoute != route.id || capturedLive != live || SystemClock.elapsedRealtime() - capturedAt > 60_000) {
                    notice("Sync expired", "The route or mode changed, or this fix is over a minute old. Request GPS Sync again.")
                    return@setPositiveButton
                }
                val target = (choices[selected].progressMeters + progressMeters - capturedProgress).coerceAtLeast(0.0)
                if (live) {
                    val sample = freshSample() ?: return@setPositiveButton
                    if (tracker.state.navigationSessionId != capturedSession || tracker.state.blockedReason != null || tracker.state.baseM == null) {
                        notice("Distance alignment needed", "The navigation session or odometer changed. Use Align, then try GPS Sync again.")
                        return@setPositiveButton
                    }
                    undoTrip = tracker.state
                    tracker.anchor(sample, target)
                } else {
                    undoSimBefore = simMeters; undoSimAfter = target; simMeters = target
                }
                follow = true; persist(); render(); toast("GPS alignment applied · Undo Sync is available")
            }.setNegativeButton("Cancel", null).show()
    }

    private fun undoSync() {
        runCatching {
            if (live) {
                val previous = undoTrip ?: return
                tracker.restoreAlignment(previous)
            } else {
                val before = undoSimBefore ?: return
                val after = undoSimAfter ?: return
                simMeters = (before + simMeters - after).coerceAtLeast(0.0)
            }
            clearUndo(); follow = true; persist(); render(); toast("Previous alignment restored")
        }.onFailure { notice("Could not undo sync", it.message ?: "Align the route manually") }
    }

    private fun clearUndo() { undoTrip = null; undoSimBefore = null; undoSimAfter = null }

    @Deprecated("Legacy document result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        workers.execute {
            val result = runCatching {
                if (requestCode == 11) {
                    val raw = contentResolver.openInputStream(uri)!!.use { stream ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= 20 * 1024 * 1024) { "Route file is too large" }
                            output.write(buffer, 0, count)
                        }
                        output.toString("UTF-8")
                    }
                    val json = JSONObject(raw)
                    if (json.has("routes")) JsonCodec.osrm(raw, "Imported driving route") else JsonCodec.route(json)
                } else {
                    val json = JsonCodec.route(route).toString(2)
                    contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use { it.write(json) }
                    null
                }
            }
            handler.post { if (!isDestroyed) result.onSuccess { if (it != null) replaceRoute(it) else toast("Route exported") }
                .onFailure { notice("Route file", it.message ?: "Could not read or write route") } }
        }
    }

    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() {
        super.onResume(); mapView.onResume(); active = true
        if (live) connectCar()
        handler.removeCallbacks(tick); handler.post(tick)
        startGpsDebug()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            !getSharedPreferences("settings", MODE_PRIVATE).getBoolean("gpsPermissionRequested", false)) requestGpsPermission(21)
    }
    override fun onPause() {
        active = false; starting = false; planner?.pause(); generation++; polling = false; gps.cancel(); gpsPending = false; simRunning = false
        gpsDebug.stop(); gpsFix = null; carFeed.stop()
        handler.removeCallbacks(tick); persist(); mapView.onPause(); super.onPause()
    }
    override fun onStop() { mapView.onStop(); super.onStop() }
    override fun onDestroy() {
        connectivityCallback?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        servicePanel?.dismiss(); drivingMap?.close(); carFeed.close(); planner?.dismiss(); offline.close(); kyivOffline.close(); gps.cancel(); gpsDebug.stop(); mapView.onDestroy(); workers.shutdownNow(); disk.shutdown(); super.onDestroy() }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (palette.night != appearance.night(this)) applyAppearance()
    }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState) }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value; textSize = 12f; isAllCaps = false; setTextColor(ink); minWidth = 0; minimumWidth = 0
        minHeight = dp(44); minimumHeight = dp(44); setPadding(dp(3), 0, dp(3), 0)
        background = GradientDrawable().apply { setColor(palette.soft); cornerRadius = dp(10).toFloat() }
        setOnClickListener { action() }
    }
    private fun row() = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(6), dp(10), dp(6)) }
    private fun LinearLayout.addWeighted(view: View) { addView(view, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(3), 0, dp(3), 0) }) }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(10), dp(22), dp(8)) }
    private fun input(hintText: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(this).apply {
        hint = hintText; setText(value); inputType = type; textSize = 15f; setSingleLine(); minHeight = dp(54)
        contentDescription = hintText
    }
    private fun notice(title: String, message: String) { if (!isFinishing) AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show() }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun formatDistance(meters: Double): String = if (meters < 1000) "${meters.roundToInt()} m" else String.format(Locale.getDefault(), "%.1f km", meters / 1000)
}
