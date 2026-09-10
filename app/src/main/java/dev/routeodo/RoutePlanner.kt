package dev.routeodo

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.*
import dev.routeodo.core.*
import dev.routeodo.data.Network
import dev.routeodo.data.SavedPlaces
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.ExecutorService

const val DEFAULT_GEOCODER = "https://photon.komoot.io"
val KYIV_CENTER = Coordinate(50.4501, 30.5234)

/** Address selection and GPS start selection never modify an existing navigation alignment. */
class RoutePlanner(
    private val activity: Activity,
    private val network: Network,
    private val workers: ExecutorService,
    private var routingUrl: String,
    private var geocodingUrl: String,
    private val initialStart: Coordinate?,
    private val initialEnd: Coordinate?,
    private val freshGps: () -> GpsFix?,
    private val requestPermission: () -> Unit,
    private val saveServices: (String, String) -> Unit,
    private val ready: (Route) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val gps = GpsSync(activity)
    private val savedPlaces = SavedPlaces(activity)
    private lateinit var dialog: AlertDialog
    private lateinit var from: EndpointInput
    private lateinit var to: EndpointInput
    private lateinit var gpsButton: Button
    private lateinit var servicesButton: Button
    private lateinit var status: TextView
    private var calculating = false
    private var gpsWaiting = false

    fun show() {
        val form = column()
        form.addView(text("Enter a street address and city, search, then select the matching result.", 14f))
        from = EndpointInput("A · Start", "e.g. Хрещатик 22, Київ", form)
        gpsButton = button("Use current GPS for A") { useGpsStart() }
        form.addView(gpsButton)
        to = EndpointInput("B · Destination", "e.g. Володимирська 60, Київ", form)
        initialStart?.let { from.choose(Place("Start selected on map", it)) }
        initialEnd?.let { to.choose(Place("Destination selected on map", it)) }
        status = text("Address search: Photon · © OpenStreetMap contributors\nRoute calculation needs internet.", 12f)
        form.addView(status)
        servicesButton = button("Search and route service settings") { services() }
        form.addView(servicesButton)
        dialog = AlertDialog.Builder(activity).setTitle("Plan a real driving route")
            .setView(ScrollView(activity).apply { addView(form) })
            .setPositiveButton("Calculate route", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { calculate() } }
        dialog.setOnDismissListener { gps.cancel() }
        dialog.show()
    }

    fun dismiss() { if (::dialog.isInitialized) dialog.dismiss() }

    fun pause() {
        gps.cancel()
        if (gpsWaiting) { gpsWaiting = false; from.detail.text = "GPS request paused · tap Use current GPS to retry"; controls() }
    }

    fun useGpsStart() {
        if (!usable() || calculating) return
        if (activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            from.detail.text = "Precise location is needed for GPS start. You can also search for an address."
            requestPermission(); return
        }
        freshGps()?.takeIf { SystemClock.elapsedRealtime() - it.elapsedRealtimeMs in 0..2000 }?.let {
            chooseGps(it); return
        }
        gpsWaiting = true; controls(); from.detail.text = "Waiting for a fresh GPS fix…"
        gps.request { location, error ->
            if (!usable()) return@request
            gpsWaiting = false; controls()
            if (location == null) from.detail.text = error ?: "GPS unavailable · search for an address instead"
            else runCatching { GpsFix(Coordinate(location.latitude, location.longitude), location.accuracy.toDouble(),
                location.elapsedRealtimeNanos / 1_000_000) }
                .onSuccess(::chooseGps).onFailure { from.detail.text = "GPS fix could not be used · try again" }
        }
    }

    fun permissionDenied() { if (usable()) from.detail.text = "GPS permission denied · enter an address or allow precise location in device settings" }

    private fun chooseGps(fix: GpsFix) {
        from.choose(Place("Current GPS position (±${fix.accuracyMeters.toInt()} m)", fix.coordinate))
    }

    private inner class EndpointInput(title: String, hint: String, parent: LinearLayout) {
        val state = EndpointSelection()
        val field = EditText(activity).apply {
            this.hint = hint; textSize = 16f; setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            contentDescription = title
        }
        val detail = text("Choose an address result or a map point", 12f)
        var searching = false
        private var choosing = false
        val searchButton = button("Search $title") { search() }
        val savedButton = button("Saved places") { saved() }
        val saveButton = button("Save address") { save() }

        init {
            parent.addView(text(title, 16f)); parent.addView(field); parent.addView(detail); parent.addView(searchButton)
            parent.addView(LinearLayout(activity).apply {
                addView(savedButton, LinearLayout.LayoutParams(0, -2, 1f))
                addView(saveButton, LinearLayout.LayoutParams(0, -2, 1f))
            })
            field.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (!choosing) { state.edit(s.toString()); detail.text = "Search and select a result" }
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }

        fun choose(place: Place) {
            choosing = true; state.choose(place); field.setText(place.label); choosing = false
            detail.text = "Selected ✓ ${place.label}"
        }

        private fun saved() {
            val places = savedPlaces.all()
            if (places.isEmpty()) { detail.text = "Select an address, then Save address as Home, Work, or any name"; return }
            AlertDialog.Builder(activity).setTitle("Saved places")
                .setItems(places.map { "${it.name}\n${it.place.label}" }.toTypedArray()) { _, index ->
                    if (!calculating) { gps.cancel(); gpsWaiting = false; choose(places[index].place); controls() }
                }.setNeutralButton("Remove a place") { _, _ ->
                    AlertDialog.Builder(activity).setTitle("Remove saved place")
                        .setItems(places.map { it.name }.toTypedArray()) { _, index -> savedPlaces.remove(places[index].name) }
                        .setNegativeButton("Cancel", null).show()
                }.setNegativeButton("Cancel", null).show()
        }

        private fun save() {
            val selected = state.place ?: run { field.error = "Select an address or GPS position first"; return }
            val name = EditText(activity).apply { hint = "Home, Work, or another name"; setSingleLine() }
            val saveDialog = AlertDialog.Builder(activity).setTitle("Save this place").setMessage(selected.label).setView(name)
                .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
            saveDialog.setOnShowListener { saveDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                runCatching { savedPlaces.save(name.text.toString(), selected) }
                    .onSuccess { detail.text = "Saved as ${name.text}"; saveDialog.dismiss() }
                    .onFailure { name.error = it.message ?: "Could not save place" }
            } }
            saveDialog.show()
        }

        private fun search() {
            if (searching || calculating) return
            val query = field.text.toString().trim()
            if (query.length !in 3..240) { field.error = "Enter a street and city (3–240 characters)"; return }
            val revision = state.revision
            val server = geocodingUrl
            searching = true; controls(); detail.text = "Searching…"
            workers.execute {
                val result = runCatching { network.search(server, query, KYIV_CENTER) }
                handler.post {
                    if (!usable()) return@post
                    searching = false; controls()
                    if (revision != state.revision || server != geocodingUrl) return@post
                    result.onSuccess { results ->
                        if (results.isEmpty()) { detail.text = "No matches · include the city or try another spelling"; return@onSuccess }
                        detail.text = "Select the matching address"
                        AlertDialog.Builder(activity).setTitle("Choose address")
                            .setItems(results.map { it.label }.toTypedArray()) { _, index ->
                                if (usable() && state.revision == revision && server == geocodingUrl && !calculating) choose(results[index])
                            }.setNegativeButton("Cancel", null).show()
                    }.onFailure { detail.text = it.message ?: "Search unavailable · check internet or service settings" }
                }
            }
        }
    }

    private fun calculate() {
        val start = from.state.place
        val end = to.state.place
        if (start == null) { from.field.error = "Search and select A, or use current GPS"; return }
        if (end == null) { to.field.error = "Search and select B"; return }
        if (Route.distance(start.coordinate, end.coordinate) <= 10) { to.field.error = "Choose a destination farther from the start"; return }
        gps.cancel(); gpsWaiting = false; calculating = true; controls()
        status.text = "Calculating a driving route…"
        workers.execute {
            val result = runCatching { network.route(routingUrl, start.coordinate, end.coordinate,
                "${start.label.substringBefore(',')} → ${end.label.substringBefore(',')}") }
            handler.post {
                if (!usable()) return@post
                calculating = false; controls()
                result.onSuccess { route -> dialog.dismiss(); ready(route) }
                    .onFailure { status.text = it.message ?: "Could not calculate route · check internet and try again" }
            }
        }
    }

    private fun services() {
        val fields = column()
        val search = EditText(activity).apply { setText(geocodingUrl); hint = "Photon base URL"; setSingleLine() }
        val routing = EditText(activity).apply { setText(routingUrl); hint = "OSRM base URL"; setSingleLine() }
        fields.addView(text("Photon search server", 14f)); fields.addView(search)
        fields.addView(text("OSRM driving route server", 14f)); fields.addView(routing)
        fields.addView(text("Default public services need no API key and are intended for light testing.", 12f))
        val settings = AlertDialog.Builder(activity).setTitle("Route services").setView(fields)
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        settings.setOnShowListener { settings.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            runCatching {
                fun base(field: EditText): String {
                    val url = field.text.toString().trim().toHttpUrl()
                    require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "Use a base URL without credentials, query, or fragment" }
                    return url.toString().trimEnd('/')
                }
                val newSearch = base(search); val newRouting = base(routing)
                geocodingUrl = newSearch; routingUrl = newRouting
                saveServices(newRouting, newSearch); settings.dismiss()
            }.onFailure { search.error = it.message ?: "Invalid service URL" }
        } }
        settings.show()
    }

    private fun controls() {
        from.field.isEnabled = !calculating && !gpsWaiting
        to.field.isEnabled = !calculating
        from.searchButton.isEnabled = !calculating && !gpsWaiting && !from.searching
        to.searchButton.isEnabled = !calculating && !to.searching
        listOf(from.savedButton, from.saveButton, to.savedButton, to.saveButton).forEach { it.isEnabled = !calculating && !gpsWaiting }
        gpsButton.isEnabled = !calculating && !gpsWaiting && !from.searching
        gpsButton.text = if (gpsWaiting) "GPS…" else "Use current GPS for A"
        servicesButton.isEnabled = !calculating && !from.searching && !to.searching
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
            isEnabled = !calculating && !gpsWaiting && !from.searching && !to.searching
            text = if (calculating) "Calculating…" else "Calculate route"
        }
    }

    private fun usable() = ::dialog.isInitialized && dialog.isShowing && !activity.isDestroyed
    private fun text(value: String, size: Float) = TextView(activity).apply { text = value; textSize = size; setTextColor(Color.rgb(23, 49, 42)); setPadding(0, dp(6), 0, dp(6)) }
    private fun button(value: String, action: () -> Unit) = Button(activity).apply { text = value; isAllCaps = false; minHeight = dp(44); setOnClickListener { action() } }
    private fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}
