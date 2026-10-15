package dev.routeodo.voice

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.widget.*
import dev.routeodo.data.Network
import dev.routeodo.data.TokenStore
import org.json.JSONObject
import java.util.concurrent.FutureTask

/** Everyday map interaction. Credentials are configured once, outside the press-to-talk path. */
class VoiceLocationController(
    private val activity: Activity,
    private val context: () -> JSONObject,
    private val observation: () -> LocationObservation,
    private val geocoder: () -> String,
    private val update: (VoicePresentation) -> Unit,
    private val review: (LocationProposal) -> Unit
) {
    private val secrets = TokenStore(activity, "voice-openai")
    private val prefs = activity.getSharedPreferences("voice-settings", Activity.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val network = Network()
    private val nearby = NearbyPlaces()
    private var session: RealtimeVoice? = null
    private var tools: LocationTools? = null
    private var proposal: LocationProposal? = null
    private var observed: LocationObservation? = null
    private var revision = 0
    private var holding = false
    private var message = "Удерживайте, чтобы сказать, что видите"
    private val timeout = Runnable { release(false) }
    private val expire = Runnable { proposal = null; publish() }
    private val idle = Runnable { endSession(); message = "Удерживайте, чтобы сказать, что видите"; publish() }

    private fun <T> onUi(block: () -> T): T {
        val task = FutureTask(block); activity.runOnUiThread(task); return task.get()
    }
    private fun publish() { update(VoicePresentation(message, holding, proposal)) }

    fun press() {
        if (holding) return
        handler.removeCallbacks(idle); handler.removeCallbacks(expire)
        val key = secrets.read()
        if (key.isBlank()) { settings(); return }
        observed = observation(); proposal = null
        if (session == null) {
            val current = ++revision
            tools = LocationTools({ onUi(context) }, { onUi { observation().coordinate } },
                { query, point -> network.search(geocoder(), query, point, timeoutMillis = 5000) }, SystemClock::elapsedRealtime,
                { onUi { observation().travelMeters } }, { candidate -> activity.runOnUiThread {
                    if (current == revision && observed?.atMillis == candidate.observationAtMillis) { proposal = candidate; handler.removeCallbacks(expire); handler.postDelayed(expire, 60000); publish() }
                } }, nearby::search)
            session = RealtimeVoice(activity.getSystemService(android.media.AudioManager::class.java), key,
                prefs.getString("model", "gpt-realtime-2.1") ?: "gpt-realtime-2.1", tools!!,
                { text -> activity.runOnUiThread { if (current == revision) { message = text; publish() } } },
                { text -> activity.runOnUiThread { if (current == revision) { message = text; publish() } } },
                { activity.runOnUiThread { if (current == revision) { session = null; holding = false; handler.removeCallbacks(timeout); publish() } } })
            try { session!!.start() } catch (_: Exception) {
                endSession(); message = "Микрофон недоступен. Попробуйте ещё раз."; publish(); return
            }
        }
        tools!!.beginReport(observed!!)
        holding = true; message = "Слушаю… Отпустите кнопку, чтобы найти место"; publish()
        try { session!!.beginReport(tools!!.execute("location_context", JSONObject())) } catch (_: Exception) {
            endSession(); message = "Не удалось включить микрофон"; publish(); return
        }
        handler.postDelayed(timeout, 30000)
    }

    fun release(cancel: Boolean) {
        if (!holding) return
        holding = false; handler.removeCallbacks(timeout)
        session?.endReport(cancel)
        publish(); handler.postDelayed(idle, 90000)
    }
    fun reviewEstimate() { proposal?.let { release(true); review(it) } }
    fun close() { endSession(); proposal = null; message = "Удерживайте, чтобы сказать, что видите"; publish() }
    private fun endSession() {
        revision++; holding = false; handler.removeCallbacks(timeout); handler.removeCallbacks(idle); handler.removeCallbacks(expire)
        session?.close(); session = null; tools = null
    }

    fun settings() {
        close()
        val fields = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 12, 28, 12) }
        fields.addView(TextView(activity).apply {
            text = "Говорите, удерживая кнопку на карте. Ответы — по-русски. Голос и примерное местоположение отправляются в OpenAI; поиск — в Photon и OpenStreetMap/Overpass. Нужны интернет и оплачиваемый API-ключ OpenAI."
        })
        val key = EditText(activity).apply { hint = "OpenAI API key"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; setText(secrets.read()) }
        val model = EditText(activity).apply { hint = "Модель Realtime"; setSingleLine(); setText(prefs.getString("model", "gpt-realtime-2.1")) }
        fields.addView(key); fields.addView(model)
        val dialog = AlertDialog.Builder(activity).setTitle("Голосовое определение места").setView(fields)
            .setPositiveButton("Сохранить", null).setNegativeButton("Отмена", null)
            .setNeutralButton("Удалить ключ") { _, _ -> secrets.write("") }.create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val secret = key.text.toString().trim(); val selected = model.text.toString().trim()
            if (secret.isBlank() || secret.any { it.isWhitespace() || it.code < 32 }) { key.error = "Введите корректный API-ключ"; return@setOnClickListener }
            if (!selected.matches(Regex("[a-zA-Z0-9._-]{1,100}"))) { model.error = "Введите ID модели Realtime"; return@setOnClickListener }
            secrets.write(secret); prefs.edit().putString("model", selected).apply(); dialog.dismiss()
            message = "Готово. Удерживайте кнопку на карте и говорите"; publish()
        } }; dialog.show()
    }
}

data class VoicePresentation(val message: String, val recording: Boolean = false, val proposal: LocationProposal? = null)
