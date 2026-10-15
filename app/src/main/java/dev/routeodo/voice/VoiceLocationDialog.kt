package dev.routeodo.voice

import android.app.Activity
import android.app.AlertDialog
import android.os.SystemClock
import android.text.InputType
import android.widget.*
import dev.routeodo.core.Coordinate
import dev.routeodo.core.Place
import dev.routeodo.data.Network
import dev.routeodo.data.TokenStore
import org.json.JSONObject
import java.util.concurrent.FutureTask

class VoiceLocationDialog(
    private val activity: Activity,
    private val context: () -> JSONObject,
    private val focus: () -> Coordinate,
    private val travel: () -> Double,
    private val geocoder: String,
    private val review: (LocationProposal) -> Unit
) {
    private val secrets = TokenStore(activity, "voice-openai")
    private val prefs = activity.getSharedPreferences("voice-settings", Activity.MODE_PRIVATE)
    private var session: RealtimeVoice? = null
    private var proposal: LocationProposal? = null
    private var dialog: AlertDialog? = null
    private var revision = 0

    private fun <T> onUi(block: () -> T): T {
        val task = FutureTask(block)
        activity.runOnUiThread(task)
        return task.get()
    }

    fun show() {
        val fields = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 12, 28, 12) }
        fields.addView(TextView(activity).apply { text = "Describe a shop, street or junction around you. Voice and approximate location go to OpenAI; place queries go to Photon. Internet and a billed OpenAI API key are required. No position changes until you review and apply." })
        val key = EditText(activity).apply { hint = "OpenAI API key"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; setText(secrets.read()) }
        val model = EditText(activity).apply { hint = "Realtime model"; setSingleLine(); setText(prefs.getString("model", "gpt-realtime-2.1")) }
        val state = TextView(activity).apply { text = "Microphone is off"; setPadding(0, 16, 0, 8) }
        val transcript = TextView(activity).apply { text = ""; maxLines = 6 }
        val proposed = TextView(activity)
        val apply = Button(activity).apply { text = "Review proposed location"; isEnabled = false }
        val start = Button(activity).apply { text = "Start voice chat" }
        val stop = Button(activity).apply { text = "End voice chat"; isEnabled = false }
        listOf(key, model, state, transcript, proposed, start, stop, apply).forEach(fields::addView)
        fun end() {
            revision++; session?.close(); session = null
            key.isEnabled = true; model.isEnabled = true; start.isEnabled = true; stop.isEnabled = false
            state.text = "Microphone is off"
        }
        start.setOnClickListener {
            val secret = key.text.toString().trim(); val selectedModel = model.text.toString().trim()
            if (secret.isBlank() || secret.any { it.isWhitespace() || it.code < 32 }) { key.error = "Enter a valid OpenAI API key without spaces"; return@setOnClickListener }
            if (!selectedModel.matches(Regex("[a-zA-Z0-9._-]{1,100}"))) { model.error = "Enter a Realtime model ID"; return@setOnClickListener }
            secrets.write(secret); prefs.edit().putString("model", selectedModel).apply()
            proposal = null; apply.isEnabled = false; proposed.text = ""; transcript.text = ""
            key.isEnabled = false; model.isEnabled = false; start.isEnabled = false; stop.isEnabled = true
            val current = ++revision
            val network = Network()
            val tools = LocationTools({ onUi(context) }, { onUi(focus) }, { query, point -> network.search(geocoder, query, point) },
                SystemClock::elapsedRealtime, { onUi(travel) }, { candidate -> activity.runOnUiThread {
                    if (revision == current) { proposal = candidate; proposed.text = "${candidate.place.label}\n${candidate.reason}"; apply.isEnabled = true }
                } })
            state.text = "Connecting…"
            session = RealtimeVoice(activity.getSystemService(android.media.AudioManager::class.java), secret, selectedModel, tools,
                { message -> activity.runOnUiThread { if (revision == current) state.text = message } },
                { text -> activity.runOnUiThread { if (revision == current) transcript.text = text } }).also { it.start() }
        }
        stop.setOnClickListener { end() }
        apply.setOnClickListener {
            val candidate = proposal ?: return@setOnClickListener
            end(); dialog?.dismiss(); review(candidate)
        }
        dialog = AlertDialog.Builder(activity).setTitle("Find me by voice")
            .setView(ScrollView(activity).apply { addView(fields) }).setNegativeButton("Close", null)
            .setNeutralButton("Forget key") { _, _ -> secrets.write("") }.create().also {
                it.setOnDismissListener { end() }; it.show()
            }
    }

    fun dismiss() { revision++; session?.close(); session = null; dialog?.dismiss(); dialog = null }
}
