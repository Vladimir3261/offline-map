package dev.routeodo.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.MotionEvent
import dev.routeodo.voice.VoicePresentation
import android.widget.*
import dev.routeodo.core.GpsDisplayState
import dev.routeodo.data.SavedPlace
import org.maplibre.android.maps.MapView

internal fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()
internal fun TextView.updateText(value: CharSequence) { if (!android.text.TextUtils.equals(text, value)) text = value }
internal fun rounded(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }
internal fun TextView.font(size: Float, color: Int, bold: Boolean = false): TextView = apply {
    textSize = size; setTextColor(color); typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL); includeFontPadding = false
}

data class DrivingState(
    val maneuverDistance: String, val instruction: String, val remaining: String,
    val speed: String, val source: String, val connection: String, val notice: String,
    val comparison: String, val accuracy: String, val gpsState: GpsDisplayState,
    val preview: Boolean, val warning: Boolean, val following: Boolean,
)

/** Explore → preview → drive. Technical controls live outside this map surface. */
class DrivingUi(context: Context, mapView: MapView, menu: () -> Unit, search: () -> Unit,
    edit: () -> Unit, private val primary: () -> Unit, recenter: () -> Unit, compare: () -> Unit,
    private val mapInsets: (Int, Int, Int, Int) -> Unit,
    private val voicePress: () -> Unit, private val voiceRelease: (Boolean) -> Unit,
    voiceSettings: () -> Unit, voiceStop: () -> Unit, voiceReview: () -> Unit,
) : FrameLayout(context) {
    private var palette = DrivingPalette(false)
    private val top = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val searchBar = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val searchText = TextView(context).font(19f, palette.text, true).apply {
        text = "⌕   Where to?"; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), 0, 0, 0)
        isClickable = true; isFocusable = true; contentDescription = "Search for an address or place"; setOnClickListener { search() }
    }
    private val searchMenu = button("⋯", "Menu", menu)
    private val maneuver = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(12), dp(6), dp(12)) }
    private val turn = TurnIcon(context)
    private val distance = TextView(context).font(32f, palette.text, true)
    private val instruction = TextView(context).font(16f, palette.text, true).apply { maxLines = 2 }
    private val navMenu = button("⋯", "Menu", menu)
    private val bottom = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(16), dp(18), dp(14)) }
    private val title = TextView(context).font(20f, palette.text, true).apply { maxLines = 2 }
    private val summary = TextView(context).font(27f, palette.text, true).apply { setPadding(0, dp(6), 0, dp(5)) }
    private val subtitle = TextView(context).font(13f, palette.secondary).apply { maxLines = 2 }
    private val actions = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(14), 0, 0) }
    private val editButton = button("Edit route", "Edit route", edit)
    private val primaryButton = button("Start", "Start route", primary)
    private val navRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val speed = TextView(context).font(32f, palette.text, true).apply { gravity = Gravity.CENTER }
    private val speedUnit = TextView(context).font(11f, palette.secondary).apply { text = "km/h"; gravity = Gravity.CENTER }
    private val navInfo = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(8), 0) }
    private val remaining = TextView(context).font(22f, palette.text, true)
    private val arrival = TextView(context).font(13f, palette.secondary).apply { maxLines = 1; setPadding(0, dp(5), 0, 0) }
    private val stop = button("Stop", "Stop route", primary)
    private val comparison = TextView(context).font(12f, palette.secondary).apply {
        minHeight = dp(48); gravity = Gravity.CENTER_VERTICAL; maxLines = 2; isClickable = true; isFocusable = true; setOnClickListener { compare() }
    }
    private val shortcuts = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val shortcutScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(shortcuts) }
    private val followButton = button("➤", "Recenter", recenter).apply { textSize = 26f }
    private val notice = TextView(context).font(12f, palette.warning, true).apply { setPadding(dp(12), dp(10), dp(12), dp(10)); maxLines = 2 }
    private val voicePanel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(4), dp(12), dp(10)) }
    private val voiceRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, 0) }
    private var touchPress = false
    private var voiceRecording = false
    private val voiceButton = button("●  Удерживайте и говорите", "Удерживайте, чтобы сообщить ориентир", {}).apply {
        textSize = 16f
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { touchPress = true; parent.requestDisallowInterceptTouchEvent(true); voicePress(); true }
                MotionEvent.ACTION_MOVE -> {
                    if (touchPress && (event.x < -dp(24) || event.x > width + dp(24) || event.y < -dp(24) || event.y > height + dp(24))) {
                        touchPress = false; voiceRelease(true)
                    }; true
                }
                MotionEvent.ACTION_UP -> {
                    if (touchPress) { voiceRelease(false); view.performClick() }; touchPress = false; true
                }
                MotionEvent.ACTION_CANCEL -> { if (touchPress) voiceRelease(true); touchPress = false; true }
                else -> true
            }
        }
        // TalkBack / keyboard activation toggles capture without requiring a sustained press.
        setOnClickListener { if (!touchPress) { if (voiceRecording) voiceRelease(false) else voicePress() } }
    }
    private val voiceConfig = button("⚙", "Настройки голосового определения места", voiceSettings)
    private val voiceClose = button("×", "Остановить голосовое определение места", voiceStop)
    private val voiceMessage = TextView(context).font(12f, palette.secondary).apply {
        text = "Удерживайте, чтобы сказать, что видите"; maxLines = 2; setPadding(0, dp(6), 0, 0)
    }
    private val voiceResult = TextView(context).font(15f, palette.accent, true).apply {
        visibility = GONE; maxLines = 2; minHeight = dp(48); gravity = Gravity.CENTER_VERTICAL
        isClickable = true; isFocusable = true; setOnClickListener { voiceReview() }
    }
    private var safeLeft = 0; private var safeTop = 0; private var safeRight = 0; private var safeBottom = 0
    private var lastInsets = emptyList<Int>()
    private var phase = JourneyPhase.EXPLORE

    init {
        addView(mapView, LayoutParams(-1, -1))
        searchBar.addView(searchText, LinearLayout.LayoutParams(0, dp(60), 1f))
        searchBar.addView(searchMenu, LinearLayout.LayoutParams(dp(56), dp(56)))
        top.addView(searchBar)
        maneuver.addView(turn, LinearLayout.LayoutParams(dp(58), dp(62)))
        val words = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(2), 0); addView(distance); addView(instruction) }
        maneuver.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        maneuver.addView(navMenu, LinearLayout.LayoutParams(dp(48), dp(48)))
        top.addView(maneuver)
        addView(top, LayoutParams(-1, -2, Gravity.TOP or Gravity.START))
        bottom.addView(title); bottom.addView(summary); bottom.addView(subtitle)
        bottom.addView(shortcutScroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        actions.addView(editButton, LinearLayout.LayoutParams(0, dp(54), 1f).apply { rightMargin = dp(10) })
        actions.addView(primaryButton, LinearLayout.LayoutParams(0, dp(54), 1.3f)); bottom.addView(actions)
        val speedColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; addView(speed); addView(speedUnit) }
        navRow.addView(speedColumn, LinearLayout.LayoutParams(dp(60), -2))
        navInfo.addView(remaining); navInfo.addView(arrival)
        navRow.addView(navInfo, LinearLayout.LayoutParams(0, -2, 1f))
        navRow.addView(stop, LinearLayout.LayoutParams(dp(78), dp(54)))
        bottom.addView(navRow); bottom.addView(comparison)
        voiceRow.addView(voiceButton, LinearLayout.LayoutParams(0, dp(60), 1f))
        voiceRow.addView(voiceConfig, LinearLayout.LayoutParams(dp(48), dp(56)).apply { leftMargin = dp(4) })
        voiceRow.addView(voiceClose, LinearLayout.LayoutParams(dp(48), dp(56)))
        voicePanel.addView(voiceRow); voicePanel.addView(voiceMessage); voicePanel.addView(voiceResult)
        addView(bottom, LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.START))
        addView(voicePanel, LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.END))
        addView(notice, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
        addView(followButton, LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.END))
        setOnApplyWindowInsetsListener { _, insets ->
            safeLeft = insets.systemWindowInsetLeft; safeTop = insets.systemWindowInsetTop
            safeRight = insets.systemWindowInsetRight; safeBottom = insets.systemWindowInsetBottom
            arrange(); insets
        }
        applyAppearance(palette)
    }

    fun savedPlaces(places: List<SavedPlace>, open: (SavedPlace?) -> Unit) {
        shortcuts.removeAllViews()
        places.sortedBy { if (it.name.equals("Home", true)) 0 else if (it.name.equals("Work", true)) 1 else 2 }.take(8).forEach { item ->
            shortcuts.addView(button("★  ${item.name}", "Route to ${item.name}") { open(item) }, LinearLayout.LayoutParams(-2, dp(48)).apply { rightMargin = dp(8) })
        }
        shortcuts.addView(button(if (places.isEmpty()) "☆  Saved places" else "All places", "Saved places") { open(null) }, LinearLayout.LayoutParams(-2, dp(48)))
    }

    fun applyAppearance(value: DrivingPalette) {
        palette = value
        searchBar.background = rounded(value.panel, dp(24).toFloat()); maneuver.background = rounded(value.panel, dp(22).toFloat())
        bottom.background = rounded(value.panel, dp(24).toFloat())
        voicePanel.background = rounded(value.panel, dp(24).toFloat())
        for (v in listOf(searchText, distance, instruction, title, summary, remaining, speed)) v.setTextColor(value.text)
        for (v in listOf(subtitle, arrival, speedUnit, comparison)) v.setTextColor(value.secondary)
        for (v in listOf(searchMenu, navMenu, editButton, followButton, voiceConfig, voiceClose)) { v.background = rounded(value.soft, dp(20).toFloat()); v.setTextColor(value.accent) }
        primaryButton.background = rounded(value.accent, dp(18).toFloat()); primaryButton.setTextColor(if (value.night) Color.parseColor("#14213C") else Color.WHITE)
        voiceButton.background = rounded(value.accent, dp(18).toFloat()); voiceButton.setTextColor(if (value.night) Color.parseColor("#14213C") else Color.WHITE)
        voiceMessage.setTextColor(value.secondary); voiceResult.setTextColor(value.accent)
        stop.background = rounded(if (value.night) Color.parseColor("#4D3030") else Color.parseColor("#FCECEA"), dp(18).toFloat()); stop.setTextColor(value.warning)
        turn.background = rounded(Color.parseColor(if (value.night) "#3559B1" else "#285EE8"), dp(16).toFloat())
    }

    fun voice(value: VoicePresentation) {
        voiceRecording = value.recording
        voiceButton.updateText(if (value.recording) "●  Говорите…" else "●  Удерживайте и говорите")
        voiceButton.contentDescription = if (value.recording) "Запись. Отпустите для отправки" else "Удерживайте, чтобы сообщить ориентир"
        voiceButton.isActivated = value.recording
        voiceMessage.updateText(value.message)
        val result = value.proposal
        voiceResult.visibility = if (result == null) GONE else VISIBLE
        if (result != null) voiceResult.updateText("${result.confidence}% · ${result.place.label}  ›")
        voiceResult.contentDescription = result?.let { "Лучшее совпадение: ${it.place.label}. Уверенность ${it.confidence} процентов. Проверить оценку" }
        arrange()
    }

    fun render(state: DrivingState, journey: JourneyUi) {
        phase = journey.phase
        val guiding = journey.guiding
        searchBar.visibility = if (guiding) GONE else VISIBLE
        maneuver.visibility = if (guiding) VISIBLE else GONE
        distance.updateText(state.maneuverDistance); instruction.updateText(state.instruction); turn.instruction = state.instruction
        val exploring = phase == JourneyPhase.EXPLORE
        for (v in listOf(title, summary, subtitle, actions)) v.visibility = if (guiding) GONE else VISIBLE
        summary.visibility = if (guiding || exploring || journey.picking) GONE else VISIBLE
        title.updateText(if (exploring) "Where are we going?" else if (journey.picking) "Choose ${if (phase == JourneyPhase.PICK_START) "a starting point" else "a destination"}" else journey.title)
        summary.updateText(journey.summary)
        subtitle.updateText(if (exploring) "Search for an address, a shop or a saved place" else if (journey.picking) "Long-press the map to place your pin" else journey.origin)
        shortcutScroll.visibility = if (exploring) VISIBLE else GONE
        actions.visibility = if (guiding || exploring) GONE else VISIBLE
        editButton.visibility = if (journey.picking) GONE else VISIBLE
        primaryButton.updateText(if (journey.picking) "Cancel" else "Start")
        primaryButton.contentDescription = if (journey.picking) "Cancel map selection" else "Start route"
        navRow.visibility = if (guiding) VISIBLE else GONE
        comparison.visibility = if (guiding) VISIBLE else GONE
        speed.updateText(state.speed); remaining.updateText(state.remaining.removeSuffix(" remaining"))
        arrival.updateText(if (phase == JourneyPhase.SIMULATION) "Simulated drive" else if (phase == JourneyPhase.STARTING) "Waiting for car…" else if (state.warning) state.connection else "To ${journey.title}")
        stop.updateText(if (phase == JourneyPhase.STARTING) "Cancel" else if (phase == JourneyPhase.SIMULATION) "Exit" else "Stop")
        stop.contentDescription = if (phase == JourneyPhase.SIMULATION) "Exit simulation" else if (phase == JourneyPhase.STARTING) "Cancel start" else "Stop route"
        val gpsText = state.comparison.replace("GPS ↔ ODO:", "GPS:")
        comparison.updateText(if (state.gpsState == GpsDisplayState.LIVE) "$gpsText apart · ${state.accuracy.removePrefix("GPS accuracy: ")}" else gpsText)
        comparison.setTextColor(if (state.gpsState == GpsDisplayState.STALE) palette.warning else palette.secondary)
        val comparisonDescription = "${state.comparison}. ${state.accuracy}. Position details"
        if (comparison.contentDescription != comparisonDescription) comparison.contentDescription = comparisonDescription
        val banner = if (phase == JourneyPhase.SIMULATION) "SIMULATION · no vehicle movement"
            else if (!state.preview && (guiding || state.warning)) state.notice.replace("Services → API", "Menu → Car connection") else ""
        notice.updateText(banner); notice.visibility = if (banner.isEmpty()) GONE else VISIBLE
        notice.background = rounded(if (phase == JourneyPhase.SIMULATION) Color.parseColor(if (palette.night) "#362D4D" else "#F0E9FC") else palette.panel, dp(14).toFloat())
        notice.setTextColor(if (phase == JourneyPhase.SIMULATION) Color.parseColor(if (palette.night) "#D6C8FF" else "#643AA8") else palette.warning)
        val description = if (state.following && guiding) "Following calculated position. Recenter" else "Recenter map"
        if (followButton.contentDescription != description) followButton.contentDescription = description
        arrange()
    }

    private fun dp(value: Int) = context.dp(value)
    private fun button(caption: String, description: String, click: () -> Unit) = Button(context).apply {
        text = caption; contentDescription = description; isAllCaps = false; textSize = 16f; minWidth = 0; minimumWidth = 0
        minHeight = dp(48); setPadding(dp(12), dp(6), dp(12), dp(6)); stateListAnimator = null
        background = RippleDrawable(ColorStateList.valueOf(0x224285F4), rounded(palette.soft, dp(18).toFloat()), null)
        setTextColor(palette.accent); setOnClickListener { click() }
    }
    override fun onMeasure(w: Int, h: Int) { arrange(MeasureSpec.getSize(w), MeasureSpec.getSize(h)); super.onMeasure(w, h) }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) { super.onLayout(changed, l, t, r, b); arrange() }
    private fun arrange(w: Int = width, h: Int = height) {
        if (w == 0 || h == 0) return
        val wide = w > h * 1.15
        val available = w - safeLeft - safeRight - dp(28)
        val card = if (wide) minOf(dp(390), (available * .45).toInt()) else minOf(dp(500), available)
        fun place(view: View, width: Int, height: Int, gravity: Int, left: Int, top: Int, right: Int, bottom: Int) {
            val old = view.layoutParams as LayoutParams
            if (old.width == width && old.height == height && old.gravity == gravity && old.leftMargin == left && old.topMargin == top && old.rightMargin == right && old.bottomMargin == bottom) return
            view.layoutParams = LayoutParams(width, height, gravity).apply { setMargins(left, top, right, bottom) }
        }
        place(top, card, -2, Gravity.TOP or Gravity.START, safeLeft + dp(14), safeTop + dp(12), 0, 0)
        val bottomWidth = if (wide) minOf(dp(500), card) else card
        place(bottom, bottomWidth, -2, Gravity.BOTTOM or Gravity.START, safeLeft + dp(14), 0, 0, safeBottom + dp(12))
        val voiceWidth = if (wide) minOf(dp(390), available - card - dp(14)) else card
        place(voicePanel, voiceWidth, -2, Gravity.BOTTOM or Gravity.END, 0, 0, safeRight + dp(14),
            safeBottom + if (wide) dp(12) else bottom.height + dp(20))
        place(followButton, dp(56), dp(56), Gravity.BOTTOM or Gravity.END, 0, 0, safeRight + dp(14),
            safeBottom + voicePanel.height + if (wide) dp(24) else bottom.height + dp(32))
        place(notice, if (wide) card else maxOf(dp(100), card - dp(70)), -2, Gravity.BOTTOM or Gravity.START, safeLeft + dp(14), 0, 0, safeBottom + if (wide) bottom.height + dp(26) else bottom.height + voicePanel.height + dp(32))
        val padding = if (wide) listOf(safeLeft + card + dp(30), safeTop + dp(24), safeRight + dp(24), safeBottom + voicePanel.height + dp(30))
            else listOf(safeLeft + dp(24), safeTop + top.height + dp(32), safeRight + dp(24), safeBottom + bottom.height + voicePanel.height + dp(46))
        if (padding != lastInsets) { lastInsets = padding; mapInsets(padding[0], padding[1], padding[2], padding[3]) }
    }
}
private class TurnIcon(context: Context) : View(context) {
    var instruction = ""; set(value) { if (field != value) { field = value; invalidate() } }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 3.5f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    override fun onDraw(canvas: Canvas) {
        canvas.save(); canvas.translate(width * .16f, height * .18f); canvas.scale(width * .68f / 32, height * .64f / 32)
        val words = instruction.lowercase()
        val path = Path()
        when {
            "arriv" in words || "complete" in words -> { path.moveTo(7f, 28f); path.lineTo(7f, 5f); path.lineTo(26f, 5f); path.lineTo(23f, 17f); path.lineTo(7f, 17f) }
            "roundabout" in words || "rotary" in words -> { canvas.drawArc(6f, 6f, 26f, 26f, 90f, 290f, false, paint); path.moveTo(21f, 4f); path.lineTo(27f, 9f); path.lineTo(20f, 12f) }
            "uturn" in words || "u-turn" in words -> { path.moveTo(7f, 28f); path.lineTo(7f, 11f); path.cubicTo(7f, -1f, 25f, -1f, 25f, 11f); path.lineTo(25f, 22f); path.moveTo(19f, 16f); path.lineTo(25f, 22f); path.lineTo(31f, 16f) }
            "left" in words || "right" in words -> {
                if ("left" in words) { canvas.translate(32f, 0f); canvas.scale(-1f, 1f) }
                path.moveTo(8f, 29f); path.lineTo(8f, 17f); path.quadTo(8f, 10f, 15f, 10f); path.lineTo(27f, 10f)
                path.moveTo(20f, 3f); path.lineTo(27f, 10f); path.lineTo(20f, 17f)
            }
            else -> { path.moveTo(16f, 29f); path.lineTo(16f, 3f); path.moveTo(7f, 12f); path.lineTo(16f, 3f); path.lineTo(25f, 12f) }
        }
        canvas.drawPath(path, paint); canvas.restore()
    }
}
