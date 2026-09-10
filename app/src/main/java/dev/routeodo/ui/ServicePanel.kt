package dev.routeodo.ui

import android.app.Dialog
import android.os.Build
import android.graphics.Color
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.view.WindowInsetsController
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*

data class ServiceState(val routeName: String, val live: Boolean, val running: Boolean, val starting: Boolean,
    val gpsPending: Boolean, val undoAvailable: Boolean, val previewRunning: Boolean, val fraction: Int,
    val feed: String, val maps: String, val progress: String, val appearance: String)

data class ServiceActions(val route: () -> Unit, val api: () -> Unit, val retry: () -> Unit,
    val startStop: () -> Unit, val align: () -> Unit, val sync: () -> Unit, val undo: () -> Unit,
    val maps: () -> Unit, val positions: () -> Unit, val apiDetails: () -> Unit, val appearance: () -> Unit,
    val preview: () -> Unit, val drive: () -> Unit, val step: (Int) -> Unit,
    val seek: (Int) -> Unit, val seekStart: () -> Unit, val seekStop: () -> Unit)

/** Local service screen. It delegates every navigation/configuration operation to the existing flow. */
class ServicePanel(context: Context, private val palette: DrivingPalette, private val actions: ServiceActions) :
    Dialog(context, if (palette.night) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar) {
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(24)) }
    private val routeName = text(17f, true)
    private val feed = text(13f)
    private val maps = text(13f)
    private val start = button("Start route") { exit(actions.startStop) }
    private val sync = button("GPS Sync") { exit(actions.sync) }
    private val undo = button("Undo Sync") { exit(actions.undo) }
    private val appearance = button("Appearance") { exit(actions.appearance) }
    private val preview = button("Enter Preview") { actions.preview() }
    private val previewControls = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val drive = button("Drive") { actions.drive() }
    private val previewProgress = text(13f)
    private val slider = SeekBar(context).apply { max = 10_000; contentDescription = "Preview route progress" }
    init {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(palette.background) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            if (Build.VERSION.SDK_INT >= 30) {
                val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                window?.insetsController?.setSystemBarsAppearance(if (palette.night) 0 else mask, mask)
            }
            insets
        }
        val header = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(10), dp(20), dp(8)) }
        header.addView(button("‹  Back to map") { dismiss() })
        header.addView(text(28f, true).apply { text = "Services"; setPadding(0, dp(14), 0, dp(4)) })
        header.addView(text(13f).apply { text = "RouteOdo · tools for this drive" })
        root.addView(header)
        root.addView(ScrollView(context).apply { isFillViewport = true; addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        if (Build.VERSION.SDK_INT >= 30) window?.setDecorFitsSystemWindows(false)
        window?.statusBarColor = Color.TRANSPARENT
        window?.navigationBarColor = Color.TRANSPARENT
        window?.setLayout(-1, -1)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        section("ROUTE & NAVIGATION")
        content.addView(routeName)
        action("Route & saved places", "Plan addresses or GPS start · import / export · fit route", actions.route)
        content.addView(text(12f).apply { text = "Long-press the map to set a start, destination, or alignment point."; setPadding(0, dp(4), 0, dp(6)) })
        content.addView(start)
        pair(sync, undo)
        action("Align & calibration", "Set metres along route and vehicle distance multiplier", actions.align)
        section("CAR CONNECTION")
        content.addView(feed)
        action("API & connection", "Endpoint · encrypted bearer token · LAN-first", actions.api)
        action("Retry car connection", "Reconnect without resetting navigation progress", actions.retry)
        action("API diagnostics", "Latest anchor, speed, epoch and connection details", actions.apiDetails)
        section("MAPS & POSITION")
        content.addView(maps)
        action("Offline & online maps", "Route / Kyiv packs · pause, resume, refresh, remove", actions.maps)
        action("Position details / Show both", "Raw GPS · odometer position · separation · GPS settings", actions.positions)
        content.addView(appearance)
        section("PREVIEW")
        content.addView(text(13f).apply { text = "Simulated movement is separate from the real car feed." })
        content.addView(preview)
        previewControls.addView(previewProgress); previewControls.addView(slider)
        val controls = LinearLayout(context)
        controls.addView(button("−100 m") { actions.step(-100) }, LinearLayout.LayoutParams(0, dp(52), 1f))
        controls.addView(drive, LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(dp(6), 0, dp(6), 0) })
        controls.addView(button("+100 m") { actions.step(100) }, LinearLayout.LayoutParams(0, dp(52), 1f))
        previewControls.addView(controls); content.addView(previewControls)
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) actions.seek(progress) }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { actions.seekStart() }
            override fun onStopTrackingTouch(seekBar: SeekBar?) { actions.seekStop() }
        })
    }
    override fun onStart() { super.onStart(); window?.setLayout(-1, -1); window?.decorView?.requestApplyInsets() }
    private fun dp(n: Int) = context.dp(n)
    private fun text(size: Float, bold: Boolean = false) = TextView(context).font(size, if (bold) palette.text else palette.secondary, bold)
    private fun button(caption: String, action: () -> Unit) = Button(context).apply {
        text = caption; isAllCaps = false; textSize = 15f; gravity = Gravity.CENTER
        minHeight = dp(52); minimumHeight = dp(52); setPadding(dp(12), dp(8), dp(12), dp(8)); setTextColor(palette.accent)
        background = RippleDrawable(ColorStateList.valueOf(0x334285F4), rounded(palette.soft, dp(14).toFloat()), null)
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(palette.secondary, palette.accent)))
        elevation = 0f; stateListAnimator = null
        setOnClickListener { action() }
    }
    private fun section(title: String) { content.addView(text(12f, true).apply { text = title; setTextColor(palette.secondary); letterSpacing = .12f; setPadding(0, dp(24), 0, dp(12)) }) }
    private fun pair(a: View, b: View) {
        content.addView(LinearLayout(context).apply {
            addView(a, LinearLayout.LayoutParams(0, dp(54), 1f).apply { rightMargin = dp(6) })
            addView(b, LinearLayout.LayoutParams(0, dp(54), 1f))
        })
    }
    private fun action(title: String, detail: String, callback: () -> Unit) {
        content.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(palette.panel, dp(14).toFloat()); isClickable = true; isFocusable = true; contentDescription = title
            addView(text(16f, true).apply { text = title })
            addView(text(12f).apply { text = detail; setPadding(0, dp(5), 0, 0) })
            setOnClickListener { exit(callback) }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }
    private fun exit(action: () -> Unit) { dismiss(); action() }
    fun render(state: ServiceState) {
        routeName.updateText(state.routeName); feed.updateText(state.feed); maps.updateText(state.maps)
        start.updateText(if (state.starting) "Cancel start" else if (state.live && state.running) "Stop route" else "Start route")
        sync.updateText(if (state.gpsPending) "Getting GPS…" else "GPS Sync"); sync.isEnabled = !state.gpsPending && (!state.live || state.running)
        undo.isEnabled = state.undoAvailable
        preview.updateText(if (state.live) "Enter Preview" else "PREVIEW MODE · simulated position")
        previewControls.visibility = if (state.live) View.GONE else View.VISIBLE
        drive.updateText(if (state.previewRunning) "Pause" else "Drive")
        if (slider.progress != state.fraction) slider.progress = state.fraction
        previewProgress.updateText(state.progress)
        appearance.updateText("Appearance · ${state.appearance}")
    }
}
