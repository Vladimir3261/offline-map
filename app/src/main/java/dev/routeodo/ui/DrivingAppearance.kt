package dev.routeodo.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color

enum class AppearanceMode { SYSTEM, DAY, NIGHT;
    companion object { fun decode(value: String?) = entries.firstOrNull { it.name == value } ?: SYSTEM }
    fun isNight(systemNight: Boolean) = this == NIGHT || this == SYSTEM && systemNight
}

/** Preference access is injectable so persistence and unknown-value migration can be tested without Android. */
class AppearanceSettings(private val read: () -> String?, private val write: (String) -> Unit) {
    constructor(context: Context) : this(
        { context.getSharedPreferences("driving-ui", Context.MODE_PRIVATE).getString("appearance", null) },
        { context.getSharedPreferences("driving-ui", Context.MODE_PRIVATE).edit().putString("appearance", it).apply() })
    var mode: AppearanceMode
        get() = AppearanceMode.decode(read())
        set(value) = write(value.name)
    fun night(context: Context) = mode.isNight(context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
}

data class DrivingPalette(val night: Boolean) {
    val panel = Color.parseColor(if (night) "#202936" else "#FFFFFF")
    val background = Color.parseColor(if (night) "#141D29" else "#EEF0ED")
    val text = Color.parseColor(if (night) "#F3F6FC" else "#172338")
    val secondary = Color.parseColor(if (night) "#AAB9CD" else "#59677D")
    val soft = Color.parseColor(if (night) "#303D51" else "#EDF2FA")
    val accent = Color.parseColor(if (night) "#91ACFF" else "#285EE8")
    val warning = Color.parseColor(if (night) "#FFAA90" else "#B73F2F")
}
