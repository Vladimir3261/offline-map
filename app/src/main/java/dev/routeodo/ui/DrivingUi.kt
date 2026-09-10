package dev.routeodo.ui

import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.*
import dev.routeodo.core.GpsDisplayState
import org.maplibre.android.maps.MapView

internal fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()
internal fun TextView.updateText(value: CharSequence) { if (!android.text.TextUtils.equals(text, value)) text = value }
internal fun rounded(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }
internal fun TextView.font(size: Float, color: Int, bold: Boolean = false): TextView = apply {
    textSize = size; setTextColor(color); typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
    includeFontPadding = false
}

data class DrivingState(
    val maneuverDistance: String, val instruction: String, val remaining: String,
    val speed: String, val source: String, val connection: String, val notice: String,
    val comparison: String, val accuracy: String, val gpsState: GpsDisplayState,
    val preview: Boolean, val warning: Boolean, val following: Boolean,
)

/** Map-first surface. No route, odometer, credential or persistence responsibilities. */
class DrivingUi(context: Context, mapView: MapView, private val services: () -> Unit,
    private val recenter: () -> Unit, compare: () -> Unit,
    private val mapInsets: (Int, Int, Int, Int) -> Unit,
) : FrameLayout(context) {
    private var palette = DrivingPalette(false)
    private val top = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val maneuver = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(14), dp(16), dp(14)); elevation = dp(7).toFloat() }
    private val turn = TurnIcon(context)
    private val distance = TextView(context).font(34f, palette.text, true)
    private val street = TextView(context).font(17f, palette.text, true).apply { maxLines = 2 }
    private val comparisonCard = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(10)); elevation = dp(4).toFloat() }
    private val separation = TextView(context).font(15f, palette.accent, true)
    private val accuracy = TextView(context).font(12f, palette.secondary).apply { setPadding(0, dp(4), 0, 0) }
    private val bottom = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(14), dp(12), dp(14)); elevation = dp(9).toFloat() }
    private val speed = TextView(context).font(35f, palette.text, true).apply { gravity = Gravity.CENTER }
    private val units = TextView(context).font(11f, palette.secondary).apply { text = "km/h"; gravity = Gravity.CENTER }
    private val source = TextView(context).font(13f, palette.accent, true)
    private val connection = TextView(context).font(12f, palette.secondary).apply { maxLines = 2; setPadding(0, dp(4), 0, dp(4)) }
    private val remaining = TextView(context).font(15f, palette.text, true)
    private val serviceButton = control("Services", false, services)
    private val followButton = control("Recenter", true, recenter)
    private val notice = TextView(context).font(12f, palette.secondary, true).apply { setPadding(dp(12), dp(9), dp(12), dp(9)); maxLines = 2; elevation = dp(4).toFloat() }
    private var safeLeft = 0; private var safeTop = 0; private var safeRight = 0; private var safeBottom = 0
    private var lastInsets = emptyList<Int>()

    init {
        addView(mapView, LayoutParams(-1, -1))
        val wording = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, 0, 0) }
        wording.addView(distance); wording.addView(street)
        maneuver.addView(turn, LinearLayout.LayoutParams(dp(64), dp(68)))
        maneuver.addView(wording, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(maneuver, LinearLayout.LayoutParams(-1, -2))
        comparisonCard.addView(separation); comparisonCard.addView(accuracy)
        comparisonCard.isClickable = true; comparisonCard.isFocusable = true
        comparisonCard.contentDescription = "GPS and odometer comparison. Details and Show both"
        comparisonCard.setOnClickListener { compare() }
        top.addView(comparisonCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        addView(top, LayoutParams(-1, -2, Gravity.TOP or Gravity.START))
        val speedColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        speedColumn.addView(speed); speedColumn.addView(units)
        bottom.addView(speedColumn, LinearLayout.LayoutParams(dp(68), -2))
        val info = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(8), 0) }
        info.addView(source); info.addView(connection); info.addView(remaining)
        bottom.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        bottom.addView(serviceButton, LinearLayout.LayoutParams(dp(68), dp(64)))
        addView(bottom, LayoutParams(-1, -2, Gravity.BOTTOM))
        addView(notice, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
        addView(followButton, LayoutParams(dp(68), dp(64), Gravity.BOTTOM or Gravity.END))
        setOnApplyWindowInsetsListener { _, insets ->
            safeLeft = insets.systemWindowInsetLeft; safeTop = insets.systemWindowInsetTop
            safeRight = insets.systemWindowInsetRight; safeBottom = insets.systemWindowInsetBottom
            arrange(); insets
        }
        applyAppearance(palette)
    }

    private fun dp(n: Int) = context.dp(n)
    private fun control(label: String, arrow: Boolean, action: () -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isClickable = true; isFocusable = true
        contentDescription = label; setOnClickListener { action() }; elevation = if (arrow) dp(5).toFloat() else 0f
        addView(ControlIcon(context, arrow), LinearLayout.LayoutParams(dp(26), dp(26)))
        addView(TextView(context).font(10f, palette.accent, true).apply { text = label; gravity = Gravity.CENTER; setPadding(0, dp(3), 0, 0) })
    }

    fun applyAppearance(value: DrivingPalette) {
        palette = value
        maneuver.background = rounded(value.panel, dp(22).toFloat())
        comparisonCard.background = rounded(value.panel, dp(18).toFloat())
        comparisonCard.foreground = RippleDrawable(ColorStateList.valueOf(0x224285F4), null, rounded(Color.WHITE, dp(18).toFloat()))
        bottom.background = rounded(value.panel, dp(24).toFloat())
        for (view in listOf(distance, street, speed, remaining)) view.setTextColor(value.text)
        for (view in listOf(units, connection, accuracy)) view.setTextColor(value.secondary)
        for (button in listOf(serviceButton, followButton)) {
            button.background = RippleDrawable(ColorStateList.valueOf(0x334285F4), rounded(value.soft, dp(18).toFloat()), null)
            (button.getChildAt(0) as ControlIcon).color = value.accent
            (button.getChildAt(1) as TextView).setTextColor(value.accent)
        }
        turn.background = rounded(Color.parseColor(if (value.night) "#3559B1" else "#285EE8"), dp(18).toFloat())
    }

    fun render(state: DrivingState) {
        distance.updateText(state.maneuverDistance); street.updateText(state.instruction)
        turn.instruction = state.instruction
        speed.updateText(state.speed); source.updateText(state.source); connection.updateText(state.connection); remaining.updateText(state.remaining)
        source.setTextColor(if (state.warning) palette.warning else palette.accent)
        separation.updateText(state.comparison); accuracy.updateText(state.accuracy)
        separation.setTextColor(if (state.gpsState == GpsDisplayState.STALE) palette.warning else palette.accent)
        val description = "${state.comparison}. ${state.accuracy}. Tap for details and Show both"
        if (comparisonCard.contentDescription != description) comparisonCard.contentDescription = description
        notice.updateText(state.notice)
        notice.visibility = if (state.notice.isEmpty()) GONE else VISIBLE
        notice.setTextColor(if (state.preview) Color.parseColor(if (palette.night) "#D6C8FF" else "#643AA8") else palette.warning)
        notice.background = rounded(if (state.preview) Color.parseColor(if (palette.night) "#362D4D" else "#F0E9FC") else palette.panel, dp(14).toFloat())
        val caption = if (state.following) "Following" else "Recenter"
        (followButton.getChildAt(1) as TextView).updateText(caption)
        val followDescription = if (state.following) "Following calculated position. Recenter" else "Recenter on calculated position"
        if (followButton.contentDescription != followDescription) followButton.contentDescription = followDescription
        arrange()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        arrange(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); arrange() }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) { super.onLayout(changed, l, t, r, b); arrange() }

    private fun arrange(surfaceWidth: Int = width, surfaceHeight: Int = height) {
        if (surfaceWidth == 0 || surfaceHeight == 0) return
        val wide = surfaceWidth > surfaceHeight * 1.15
        val available = surfaceWidth - safeLeft - safeRight - dp(28)
        val cardWidth = if (wide) minOf(dp(370), (available * .45).toInt()) else minOf(dp(500), available)
        fun place(view: View, w: Int, gravity: Int, left: Int, topMargin: Int, right: Int, bottomMargin: Int) {
            val old = view.layoutParams as LayoutParams
            if (old.width == w && old.gravity == gravity && old.leftMargin == left && old.topMargin == topMargin && old.rightMargin == right && old.bottomMargin == bottomMargin) return
            view.layoutParams = LayoutParams(w, if (view === followButton) dp(64) else -2, gravity).apply {
                setMargins(left, topMargin, right, bottomMargin)
            }
        }
        place(top, cardWidth, Gravity.TOP or Gravity.START, safeLeft + dp(14), safeTop + dp(12), 0, 0)
        val bottomWidth = if (wide) minOf(dp(570), available - dp(82)) else minOf(dp(500), available)
        place(bottom, bottomWidth, Gravity.BOTTOM or Gravity.START, safeLeft + dp(14), 0, 0, safeBottom + dp(12))
        place(followButton, dp(68), Gravity.BOTTOM or Gravity.END, 0, 0, safeRight + dp(14), safeBottom + if (wide) dp(12) else bottom.height + dp(26))
        place(notice, if (wide) cardWidth else maxOf(dp(100), cardWidth - dp(82)), Gravity.BOTTOM or Gravity.START, safeLeft + dp(14), 0, 0, safeBottom + bottom.height + dp(26))
        val padding = if (wide) listOf(safeLeft + cardWidth + dp(32), safeTop + dp(24), safeRight + dp(28), safeBottom + bottom.height + dp(32))
            else listOf(safeLeft + dp(24), safeTop + top.height + dp(35), safeRight + dp(24), safeBottom + bottom.height + dp(42))
        if (padding != lastInsets) { lastInsets = padding; mapInsets(padding[0], padding[1], padding[2], padding[3]) }
    }
}

private class ControlIcon(context: Context, private val arrow: Boolean) : View(context) {
    var color = Color.BLUE; set(value) { field = value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        canvas.save(); canvas.scale(width / 24f, height / 24f)
        paint.color = color; paint.style = Paint.Style.FILL
        if (arrow) canvas.drawPath(Path().apply { moveTo(12f, 2f); lineTo(21f, 21f); lineTo(12f, 16f); lineTo(3f, 21f); close() }, paint)
        else for (x in listOf(5f, 15f)) for (y in listOf(5f, 15f)) canvas.drawRoundRect(x, y, x + 5, y + 5, 1.5f, 1.5f, paint)
        canvas.restore()
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
