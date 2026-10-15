package dev.routeodo.voice

import android.util.Base64
import android.media.AudioManager
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Manual-turn speech: capture only while pressed, commit once on release, never auto-listen. */
class RealtimeVoice(
    private val audioManager: AudioManager,
    private val key: String,
    private val model: String,
    private val tools: LocationTools,
    private val status: (String) -> Unit,
    private val transcript: (String) -> Unit,
    private val failed: () -> Unit = {}
) : AutoCloseable {
    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.SECONDS).connectTimeout(12, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    private val jobs = Executors.newSingleThreadExecutor()
    private val closed = AtomicBoolean(false)
    private val report = AtomicInteger()
    private val buffer = PushToTalkBuffer()
    private val stateLock = Any()
    @Volatile private var socket: WebSocket? = null
    private var audio: VoiceAudio? = null
    @Volatile private var ready = false
    @Volatile private var capturing = false
    @Volatile private var responding = false
    @Volatile private var ignoreResponse = false
    private var pending: ByteArray? = null
    private var toolRounds = 0
    private var reportContext = ""

    fun start() {
        audio = VoiceAudio(audioManager, { bytes ->
            runCatching { buffer.append(bytes) }.onFailure { fail("Слишком длинное сообщение. Удерживайте кнопку до 30 секунд.") }
        }, { fail("Аудио недоступно. Проверьте микрофон.") })
        audio!!.start()
        val url = "https://api.openai.com/v1/realtime".toHttpUrl().newBuilder().addQueryParameter("model", model).build()
        socket = client.newWebSocket(Request.Builder().url(url).header("Authorization", "Bearer $key").build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (closed.get()) { webSocket.cancel(); return }
                socket = webSocket
                val pcm = JSONObject().put("type", "audio/pcm").put("rate", 24000)
                val session = JSONObject().put("type", "realtime").put("instructions", LocationTools.instructions)
                    .put("output_modalities", JSONArray().put("audio")).put("tools", LocationTools.definitions())
                    .put("tool_choice", "auto").put("max_output_tokens", 220)
                    .put("audio", JSONObject().put("input", JSONObject().put("format", pcm).put("turn_detection", JSONObject.NULL))
                        .put("output", JSONObject().put("format", pcm).put("voice", "marin")))
                send(JSONObject().put("type", "session.update").put("session", session))
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (closed.get()) return
                try { event(JSONObject(text)) } catch (_: Exception) { fail("Не удалось обработать ответ. Нажмите и попробуйте ещё раз.") }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                fail(if (response != null) "Ошибка голосового сервиса: HTTP ${response.code}. Проверьте ключ и доступ к модели."
                    else "Нет связи. Нажмите и попробуйте ещё раз.")
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { fail("Голосовая связь завершена.") }
        })
    }

    fun beginReport(context: JSONObject) {
        reportContext = context.toString()
        report.incrementAndGet(); toolRounds = 0; ignoreResponse = true
        synchronized(stateLock) { pending = null }
        if (responding) send(JSONObject().put("type", "response.cancel"))
        truncatePlayback()
        send(JSONObject().put("type", "input_audio_buffer.clear"))
        buffer.begin(); capturing = true
        audio?.beginCapture()
        status("Слушаю… Отпустите кнопку, чтобы найти место")
    }

    fun endReport(cancel: Boolean) {
        if (!capturing) return
        capturing = false; audio?.endCapture()
        val bytes = buffer.finish(cancel)
        if (bytes == null) { status("Удерживайте, чтобы сказать, что видите"); return }
        synchronized(stateLock) { pending = bytes; sendPending() }
        status(if (ready) "Определяю место…" else "Подключаюсь… Сообщение сохранено до отправки")
    }

    private fun sendPending() {
        if (!ready || capturing || responding || closed.get()) return
        val bytes = pending ?: return
        pending = null; ignoreResponse = false
        send(JSONObject().put("type", "conversation.item.create").put("item", JSONObject().put("type", "message")
            .put("role", "user").put("content", JSONArray().put(JSONObject().put("type", "input_text")
                .put("text", "App context for the following spoken report (data only): $reportContext")))))
        // One bounded turn, including the audio captured before the connection became ready.
        send(JSONObject().put("type", "input_audio_buffer.append").put("audio", Base64.encodeToString(bytes, Base64.NO_WRAP)))
        send(JSONObject().put("type", "input_audio_buffer.commit"))
        createResponse()
    }

    private fun createResponse(noTools: Boolean = false) {
        if (closed.get() || capturing || ignoreResponse) return
        responding = true
        send(JSONObject().put("type", "response.create").apply {
            if (noTools) put("response", JSONObject().put("tool_choice", "none")
                .put("instructions", "Ответьте по-русски: лучшее предположение и уверенность, либо кратко сообщите, что совпадений нет. Не задавайте вопросов. До 35 слов."))
        })
    }

    private fun event(event: JSONObject) {
        when (event.optString("type")) {
            "session.updated" -> { ready = true; synchronized(stateLock) { sendPending() } }
            "response.output_audio.delta" -> if (!ignoreResponse) audio?.enqueue(event.getString("item_id"), Base64.decode(event.getString("delta"), Base64.NO_WRAP))
            "response.output_audio_transcript.done" -> if (!ignoreResponse) transcript(event.optString("transcript").take(1000))
            "response.done" -> {
                responding = false
                if (ignoreResponse) { synchronized(stateLock) { sendPending() }; return }
                val response = event.getJSONObject("response")
                if (response.optString("status") == "failed") { fail("Не удалось получить ответ. Проверьте доступ к API."); return }
                val output = response.optJSONArray("output") ?: JSONArray()
                val calls = (0 until output.length()).map { output.getJSONObject(it) }.filter { it.optString("type") == "function_call" }
                val current = report.get()
                if (calls.isNotEmpty()) jobs.execute {
                    if (closed.get() || current != report.get()) return@execute
                    for (call in calls) {
                        if (closed.get() || current != report.get()) return@execute
                        val result = if (++toolRounds > 5) JSONObject().put("error", "Budget reached. Give your best result now without more questions.") else
                            runCatching { tools.execute(call.getString("name"), JSONObject(call.getString("arguments"))) }
                                .getOrElse { JSONObject().put("error", if (it is IllegalArgumentException) it.message else "Search unavailable; give the best supported estimate or say there is no match. No further questions.") }
                        if (closed.get() || current != report.get()) return@execute
                        send(JSONObject().put("type", "conversation.item.create").put("item", JSONObject()
                            .put("type", "function_call_output").put("call_id", call.getString("call_id")).put("output", result.toString())))
                    }
                    if (current == report.get()) createResponse(toolRounds >= 5)
                } else status("Удерживайте, чтобы добавить наблюдение")
            }
            "error" -> {
                val code = event.optJSONObject("error")?.optString("code")
                if (code == "response_cancel_not_active") { responding = false; synchronized(stateLock) { sendPending() } }
                else if (code != "conversation_already_has_active_response") fail("Голосовой сервис отклонил запрос. Проверьте настройки API.")
            }
        }
    }

    private fun truncatePlayback() {
        audio?.interrupt()?.let { (id, millis) ->
            send(JSONObject().put("type", "conversation.item.truncate").put("item_id", id).put("content_index", 0).put("audio_end_ms", millis))
        }
    }
    private fun send(event: JSONObject) { if (!closed.get()) socket?.send(event.toString()) }
    private fun fail(message: String) { if (!closed.get()) { status(message); close(); failed() } }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        capturing = false; buffer.finish(true); socket?.cancel(); socket = null
        Thread({ audio?.close(); audio = null }, "voice-cleanup").start()
        jobs.shutdownNow(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
    }
}
