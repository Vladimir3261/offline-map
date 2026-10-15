package dev.routeodo

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.*
import dev.routeodo.core.*
import dev.routeodo.data.*
import dev.routeodo.ui.*
import java.util.concurrent.ExecutorService
import kotlin.math.roundToInt

const val DEFAULT_GEOCODER = "https://photon.komoot.io"
val KYIV_CENTER = Coordinate(50.4501, 30.5234)

/** A destination-first search flow. Draft edits never mutate the current navigation session. */
class RoutePlanner(
    private val activity: Activity,
    private val network: Network,
    private val workers: ExecutorService,
    private val routingUrl: String,
    private val geocodingUrl: String,
    private val initialStart: Place?,
    private val initialEnd: Place?,
    private val focus: Coordinate,
    private val palette: DrivingPalette,
    private val freshGps: () -> GpsFix?,
    private val requestPermission: () -> Unit,
    private val pickOnMap: (Boolean, Place?, Place?) -> Unit,
    private val closed: () -> Unit,
    private val ready: (PlannedRoute) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val gps = GpsSync(activity)
    private val savedPlaces = SavedPlaces(activity)
    private lateinit var dialog: Dialog
    private lateinit var from: EndpointInput
    private lateinit var to: EndpointInput
    private lateinit var active: EndpointInput
    private lateinit var results: LinearLayout
    private lateinit var status: TextView
    private lateinit var action: Button
    private lateinit var gpsButton: Button
    private lateinit var originButton: Button
    private lateinit var mapButton: Button
    private var calculating = false
    private var gpsWaiting = false
    private var compactSearch = false
    private var visibleResults = Suggestions("")
    private val autocomplete = Autocomplete(SystemClock::elapsedRealtime, { delay, work ->
        val task = Runnable(work); handler.postDelayed(task, delay); CancelWork { handler.removeCallbacks(task) }
    }, { query, near, complete ->
        val cancel = network.searchAsync(geocodingUrl, query, near) { result -> handler.post { complete(result) } }
        CancelWork(cancel)
    }, { suggestions ->
        if (usable()) { visibleResults = suggestions; renderSuggestions() }
    })

    fun show() {
        dialog = Dialog(activity, if (palette.night) R.style.AppThemeNight else R.style.AppTheme)
        val root = column().apply { setBackgroundColor(palette.background) }
        val header = column().apply { setPadding(dp(16), dp(8), dp(16), dp(10)) }
        val title = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        title.addView(button("‹", "Back to map") { dialog.dismiss() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        title.addView(text("Where to?", 23f, true), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
        title.addView(button("⇅", "Swap start and destination") {
            if (!calculating) {
                cancelGps(); val a = from.state.place; val b = to.state.place
                from.setPlace(b); to.setPlace(a); active = to; showChoices(); updateAction()
            }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(title)
        from = EndpointInput("Starting point", "Choose starting point", header)
        to = EndpointInput("Destination", "Where to? Address or place", header)
        active = to
        val quick = LinearLayout(activity)
        gpsButton = button("Your location") { useGpsStart() }
        originButton = button("Choose starting point", "Change starting point") {
            if (!calculating) { from.row.visibility = View.VISIBLE; active = from; from.field.requestFocus(); showChoices(); keyboard(from.field) }
        }.apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        mapButton = button("Choose on map") {
            val choosingStart = active === from
            val a = from.state.place; val b = to.state.place
            dialog.dismiss(); pickOnMap(choosingStart, a, b)
        }
        quick.addView(gpsButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(8) })
        quick.addView(originButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(8) })
        quick.addView(mapButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(quick, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        status = text("", 13f).apply { setPadding(dp(4), dp(12), dp(4), 0) }
        header.addView(status)
        root.addView(header)
        results = column().apply { setPadding(dp(16), 0, dp(16), dp(12)) }
        val resultPane = column()
        resultPane.addView(ScrollView(activity).apply { isFillViewport = true; addView(results) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val footer = column().apply { setPadding(dp(16), dp(8), dp(16), dp(8)); setBackgroundColor(palette.panel) }
        action = button("Preview route") { calculate() }.apply { setTextColor(android.graphics.Color.WHITE); background = rounded(palette.accent, dp(18).toFloat()) }
        footer.addView(action, LinearLayout.LayoutParams(-1, dp(56)))
        footer.addView(text("Search by Photon · © OpenStreetMap contributors", 11f).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(4)) })
        resultPane.addView(footer)
        root.addView(resultPane, LinearLayout.LayoutParams(-1, 0, 1f))
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                dialog.window?.insetsController?.setSystemBarsAppearance(if (palette.night) 0 else mask, mask)
            } else view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            val landscape = activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            val keyboardVisible = Build.VERSION.SDK_INT >= 30 && insets.isVisible(WindowInsets.Type.ime())
            val compact = landscape && (keyboardVisible || view.height - view.paddingTop - view.paddingBottom in 1..dp(350))
            if (compactSearch != compact) {
                compactSearch = compact
                root.orientation = if (compact) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                val availableWidth = activity.resources.displayMetrics.widthPixels - view.paddingLeft - view.paddingRight
                header.layoutParams = if (compact) LinearLayout.LayoutParams((availableWidth * .36).toInt().coerceIn(dp(220), dp(360)), -1)
                    else LinearLayout.LayoutParams(-1, -2)
                resultPane.layoutParams = if (compact) LinearLayout.LayoutParams(0, -1, 1f) else LinearLayout.LayoutParams(-1, 0, 1f)
                footer.setPadding(dp(16), dp(if (compact) 0 else 8), dp(16), dp(if (compact) 0 else 8))
            }
            title.visibility = if (compactSearch) View.GONE else View.VISIBLE
            quick.visibility = if (compactSearch) View.GONE else View.VISIBLE
            updateAction()
            insets
        }
        dialog.setContentView(root)
        if (Build.VERSION.SDK_INT >= 30) dialog.window?.setDecorFitsSystemWindows(false)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.setOnDismissListener { autocomplete.close(); gps.cancel(); handler.removeCallbacksAndMessages(null); closed() }
        dialog.show(); dialog.window?.setLayout(-1, -1); root.requestApplyInsets()
        from.setPlace(initialStart ?: freshGps()?.let { Place("Your location", it.coordinate) })
        to.setPlace(initialEnd)
        to.field.requestFocus(); active = to; showChoices(); updateAction()
        handler.postDelayed({ if (usable() && to.state.place == null) keyboard(to.field) }, 250)
    }

    fun dismiss() { if (::dialog.isInitialized) dialog.dismiss() }
    fun pause() { cancelGps(); autocomplete.clear() }
    fun resume() { if (usable()) showChoices() }
    fun permissionDenied() { if (usable()) status.text = "Location permission is off. Search for a starting point or choose it on the map." }

    fun useGpsStart() {
        if (!usable() || calculating) return
        if (gpsWaiting) { cancelGps(); status.text = "Choose a starting point"; return }
        if (activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) { requestPermission(); return }
        freshGps()?.takeIf { SystemClock.elapsedRealtime() - it.elapsedRealtimeMs in 0..2000 }?.let { chooseGps(it); return }
        gpsWaiting = true; gpsButton.text = "Cancel GPS"; updateAction()
        val version = from.state.revision
        status.text = "Finding your location… You can still search for a destination."
        gps.request { location, _ ->
            if (!usable() || !gpsWaiting || from.state.revision != version) return@request
            cancelGps()
            if (location == null) status.text = "GPS is unavailable. Search for a starting point or choose it on the map."
            else runCatching { GpsFix(Coordinate(location.latitude, location.longitude), location.accuracy.toDouble(), location.elapsedRealtimeNanos / 1_000_000) }
                .onSuccess(::chooseGps).onFailure { status.text = "Location unavailable. Choose a starting point." }
        }
    }

    private fun chooseGps(fix: GpsFix) {
        cancelGps(); from.setPlace(Place("Your location", fix.coordinate)); active = to
        to.field.requestFocus(); showChoices(); updateAction()
        if (to.state.place != null) calculate()
    }
    private fun cancelGps() { gps.cancel(); gpsWaiting = false; if (::gpsButton.isInitialized) gpsButton.text = "Your location"; if (::action.isInitialized) updateAction() }

    private inner class EndpointInput(title: String, hint: String, parent: LinearLayout) {
        val state = EndpointSelection()
        val row = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        private var choosing = false
        val field = EditText(activity).apply {
            this.hint = hint; contentDescription = title; textSize = 16f; setTextColor(palette.text); setHintTextColor(palette.secondary)
            setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(14), dp(8), dp(10), dp(8)); background = rounded(palette.soft, dp(14).toFloat())
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setOnEditorActionListener { _, _, _ -> showChoices(); true }
        }
        init {
            row.addView(field, LinearLayout.LayoutParams(0, dp(52), 1f))
            row.addView(button("×", "Clear $title") {
                if (!calculating) { cancelGps(); setPlace(null); field.requestFocus(); active = this; showChoices(); keyboard(field) }
            }, LinearLayout.LayoutParams(dp(48), dp(52)))
            parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            field.setOnFocusChangeListener { _, focused ->
                if (focused && ::results.isInitialized && !choosing) { active = this; showChoices(); if (state.place != null) field.selectAll() }
            }
            field.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun afterTextChanged(s: Editable?) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (!choosing && ::results.isInitialized) {
                        if (this@EndpointInput === from) cancelGps()
                        state.edit(s.toString()); active = this@EndpointInput; showChoices(); updateAction()
                    }
                }
            })
        }
        fun setPlace(place: Place?) {
            choosing = true
            if (place == null) { state.edit(""); field.setText("") } else { state.choose(place); field.setText(place.label) }
            choosing = false
        }
    }

    private fun showChoices() {
        if (!usable() || calculating) return
        autocomplete.clear()
        if (active.state.place == null) autocomplete.search(active.field.text.toString(), from.state.place?.coordinate ?: focus)
        else { visibleResults = Suggestions(""); renderSuggestions() }
    }

    private fun renderSuggestions() {
        if (!usable() || calculating) return
        results.removeAllViews()
        val query = active.field.text.toString().trim().takeIf { active.state.place == null }.orEmpty()
        val saved = savedPlaces.all().filter { query.isBlank() || it.name.contains(query, true) || it.place.label.contains(query, true) }
        if (saved.isNotEmpty()) {
            results.addView(text("Saved places", 13f, true).apply { setPadding(dp(4), dp(10), 0, dp(6)) })
            saved.forEach { item -> placeRow(item.place, item.name, saved = true, choose = { choose(item.place) }, longPress = {
                AlertDialog.Builder(activity).setTitle(item.name).setItems(arrayOf("Remove saved place")) { _, _ -> savedPlaces.remove(item.name); renderSuggestions() }.show()
            }) }
        }
        when {
            active.state.place != null -> {
                status.text = if (from.state.place != null && to.state.place != null) "Ready to preview your drive" else "Choose ${if (active === to) "a starting point" else "a destination"}"
                results.addView(text("Selected ${if (active === from) "starting point" else "destination"}", 13f, true).apply { setPadding(0, dp(16), 0, dp(8)) })
                placeRow(active.state.place!!, active.state.place!!.title, choose = { savePlace(active.state.place!!) })
            }
            query.length < 2 -> {
                status.text = if (active === from) "Where will you start?" else "Search addresses, shops, cafés and other places"
                if (saved.isEmpty()) results.addView(text("Your saved places will appear here. Tap ☆ beside a result to save Home, Work or another favourite.", 15f).apply { setPadding(dp(8), dp(24), dp(8), 0) })
            }
            visibleResults.loading -> { status.text = "Searching nearby…" }
            visibleResults.error != null -> {
                status.text = visibleResults.error
                results.addView(button("Try again") { showChoices() })
            }
            else -> {
                status.text = if (visibleResults.places.isEmpty()) "No matches. Try the place name and city." else "Choose ${if (active === from) "your starting point" else "your destination"}"
                val savedCoordinates = saved.map { it.place.coordinate }.toSet()
                visibleResults.places.filterNot { it.coordinate in savedCoordinates }.forEach { place -> placeRow(place, place.title, choose = { choose(place) }) }
            }
        }
        updateAction()
    }

    private fun placeRow(place: Place, title: String, saved: Boolean = false, choose: () -> Unit, longPress: (() -> Unit)? = null) {
        val row = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(12), dp(4), dp(12)); background = rounded(palette.panel, dp(16).toFloat()) }
        row.addView(text(if (saved) "★" else "↗", 22f, true).apply { setTextColor(palette.accent); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(36), dp(44)))
        val info = column().apply { setPadding(dp(10), 0, dp(6), 0) }
        info.addView(text(title, 16f, true).apply { maxLines = 2 })
        info.addView(text(listOfNotNull(place.category, place.subtitle.takeIf { it.isNotBlank() }).joinToString(" · "), 13f).apply { maxLines = 2; setPadding(0, dp(4), 0, 0) })
        val meters = Route.distance(from.state.place?.coordinate ?: focus, place.coordinate)
        info.addView(text(if (meters < 1000) "${meters.roundToInt()} m away" else "${String.format(java.util.Locale.getDefault(), "%.1f", meters / 1000)} km away", 12f).apply { setPadding(0, dp(4), 0, 0) })
        row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        row.isClickable = true; row.isFocusable = true; row.contentDescription = "$title. ${place.subtitle}"
        row.setOnClickListener { choose() }
        if (longPress != null) row.setOnLongClickListener { longPress(); true }
        if (!saved) row.addView(button("☆", "Save ${place.title}") { savePlace(place) }, LinearLayout.LayoutParams(dp(48), dp(48)))
        results.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }

    private fun choose(place: Place) {
        if (calculating) return
        autocomplete.clear(); cancelGps(); active.setPlace(place)
        if (from.state.place != null && to.state.place != null) calculate()
        else {
            active = if (from.state.place == null) from else to
            if (active === from) from.row.visibility = View.VISIBLE
            active.field.requestFocus(); showChoices(); keyboard(active.field)
        }
    }

    private fun savePlace(place: Place) {
        val name = EditText(activity).apply { hint = "Home, Work or another name"; setSingleLine(); setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val save = AlertDialog.Builder(activity).setTitle("Save place").setMessage(place.label).setView(name)
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        save.setOnShowListener { save.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            runCatching { savedPlaces.save(name.text.toString(), place) }.onSuccess { save.dismiss(); renderSuggestions() }
                .onFailure { name.error = it.message }
        } }; save.show()
    }

    private fun calculate() {
        val a = from.state.place ?: return
        val b = to.state.place ?: return
        if (calculating) return
        if (Route.distance(a.coordinate, b.coordinate) <= 10) { status.text = "Choose a destination farther from the start."; return }
        cancelGps(); autocomplete.clear(); calculating = true; updateAction()
        (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(to.field.windowToken, 0)
        status.text = "Finding a driving route…"
        workers.execute {
            val result = runCatching { network.planRoute(routingUrl, a, b) }
            handler.post {
                if (!usable()) return@post
                calculating = false; updateAction()
                result.onSuccess { dialog.dismiss(); ready(it) }.onFailure { status.text = "Couldn't find a route. Check your connection or change the places, then try again." }
            }
        }
    }

    private fun updateAction() {
        if (!::action.isInitialized) return
        action.isEnabled = !calculating && !gpsWaiting && from.state.place != null && to.state.place != null
        action.visibility = if (!compactSearch && from.state.place != null && to.state.place != null) View.VISIBLE else View.GONE
        action.alpha = if (action.isEnabled) 1f else .5f
        action.text = if (calculating) "Finding route…" else "Preview route"
        // Reapplying EditText.setEnabled restarts its input connection on Android.
        // Leave focused fields alone while each keystroke refreshes suggestions.
        for (field in listOf(from.field, to.field)) if (field.isEnabled == calculating) field.isEnabled = !calculating
        gpsButton.isEnabled = !calculating; mapButton.isEnabled = !calculating
        val choosingStart = active === from
        from.row.visibility = if (choosingStart) View.VISIBLE else View.GONE
        to.row.visibility = if (compactSearch && choosingStart) View.GONE else View.VISIBLE
        gpsButton.visibility = if (choosingStart) View.VISIBLE else View.GONE
        originButton.visibility = if (choosingStart) View.GONE else View.VISIBLE
        originButton.updateText(from.state.place?.let { "From: ${it.title}" } ?: "Choose starting point")
    }
    private fun usable() = ::dialog.isInitialized && dialog.isShowing && !activity.isDestroyed
    private fun keyboard(view: View) { (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(view, InputMethodManager.SHOW_IMPLICIT) }
    private fun dp(value: Int) = activity.dp(value)
    private fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private fun text(value: String, size: Float, bold: Boolean = false) = TextView(activity).font(size, if (bold) palette.text else palette.secondary, bold).apply { text = value }
    private fun button(value: String, description: String = value, click: () -> Unit) = Button(activity).apply {
        text = value; contentDescription = description; textSize = 14f; isAllCaps = false; minHeight = dp(48)
        setPadding(dp(8), dp(4), dp(8), dp(4)); setTextColor(palette.accent); background = rounded(palette.soft, dp(14).toFloat())
        stateListAnimator = null; setOnClickListener { click() }
    }
}
