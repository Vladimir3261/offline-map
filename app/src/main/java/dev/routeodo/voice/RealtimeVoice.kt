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

/** Personal BYOK prototype: key is sent only to api.openai.com over TLS. No auto reconnect. */
class RealtimeVoice(
    private val audioManager: AudioManager,
    private val key: String,
    private val model: String,
    private val tools: LocationTools,
    private val status: (String) -> Unit,
    private val transcript: (String) -> Unit
) : AutoCloseable {
    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.SECONDS).connectTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    private val jobs = Executors.newSingleThreadExecutor()
    private val closed = AtomicBoolean(false)
    @Volatile private var socket: WebSocket? = null
    private var audio: VoiceAudio? = null
    private val audioLock = Any()
    private var ready = false
    private val seen = mutableSetOf<String>()

    fun start() {
        val url = "https://api.openai.com/v1/realtime".toHttpUrl().newBuilder().addQueryParameter("model", model).build()
        socket = client.newWebSocket(Request.Builder().url(url).header("Authorization", "Bearer $key").build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (closed.get()) { webSocket.cancel(); return }
                socket = webSocket
                val pcm = JSONObject().put("type", "audio/pcm").put("rate", 24000)
                val session = JSONObject().put("type", "realtime").put("instructions", LocationTools.instructions)
                    .put("output_modalities", JSONArray().put("audio")).put("tools", LocationTools.definitions())
                    .put("tool_choice", "auto").put("max_output_tokens", 800)
                    .put("audio", JSONObject().put("input", JSONObject().put("format", pcm)
                        .put("turn_detection", JSONObject().put("type", "server_vad").put("silence_duration_ms", 700)
                            .put("create_response", true).put("interrupt_response", true)))
                        .put("output", JSONObject().put("format", pcm).put("voice", "marin")))
                send(JSONObject().put("type", "session.update").put("session", session))
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (closed.get()) return
                try { event(JSONObject(text)) } catch (_: Exception) { fail("Voice session could not process a response. Start again.") }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                fail(if (response != null) "Voice connection returned HTTP ${response.code}. Check API key, model access and billing."
                    else "Voice connection lost. Check internet and start again.")
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { fail("Voice session ended.") }
        })
    }

    private fun event(event: JSONObject) {
        when (event.optString("type")) {
            "session.updated" -> if (!ready) {
                ready = true
                synchronized(audioLock) {
                if (closed.get()) return
                audio = VoiceAudio(audioManager, { bytes ->
                    if ((socket?.queueSize() ?: 0) > 256000) fail("Voice network is too slow. Start again when connected.")
                    else send(JSONObject().put("type", "input_audio_buffer.append").put("audio", Base64.encodeToString(bytes, Base64.NO_WRAP)))
                }, { fail("Voice audio unavailable. Check microphone or audio connection.") })
                audio!!.start()
                }
                status("Listening · tell me a nearby landmark or street")
                send(JSONObject().put("type", "response.create"))
            }
            "response.output_audio.delta" -> audio?.enqueue(event.getString("item_id"), Base64.decode(event.getString("delta"), Base64.NO_WRAP))
            "response.output_audio_transcript.done" -> transcript(event.optString("transcript").take(4000))
            "input_audio_buffer.speech_started" -> {
                audio?.interrupt()?.let { (id, millis) ->
                    send(JSONObject().put("type", "conversation.item.truncate").put("item_id", id).put("content_index", 0).put("audio_end_ms", millis))
                }
                status("Listening…")
            }
            "response.done" -> {
                val response = event.getJSONObject("response")
                if (response.optString("status") == "failed") { fail("Voice response failed. Check model access, billing and try again."); return }
                val output = response.optJSONArray("output") ?: return
                val calls = (0 until output.length()).map { output.getJSONObject(it) }
                    .filter { it.optString("type") == "function_call" && seen.add(it.getString("call_id")) }
                if (calls.isNotEmpty()) jobs.execute {
                    for (call in calls) {
                        if (closed.get()) return@execute
                        status("Checking nearby places…")
                        val result = runCatching { tools.execute(call.getString("name"), JSONObject(call.getString("arguments"))) }
                            .getOrElse { JSONObject().put("error", if (it is IllegalArgumentException) it.message else "Search unavailable. Ask again or use another clue.") }
                        if (closed.get()) return@execute
                        send(JSONObject().put("type", "conversation.item.create").put("item", JSONObject()
                            .put("type", "function_call_output").put("call_id", call.getString("call_id")).put("output", result.toString())))
                    }
                    send(JSONObject().put("type", "response.create"))
                    status("Listening · voice chat active")
                }
            }
            "error" -> {
                // A user interruption can finish a response before its truncate reaches the server.
                val code = event.optJSONObject("error")?.optString("code")
                if (code != "response_cancel_not_active" && code != "conversation_already_has_active_response")
                    fail("Voice service rejected a request. Check model support, API access and start again.")
            }
        }
    }

    private fun send(event: JSONObject) { if (!closed.get()) socket?.send(event.toString()) }
    private fun fail(message: String) { if (!closed.get()) { status(message); close() } }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        socket?.cancel(); socket = null
        // Audio threads may report a failure themselves; clean up elsewhere to avoid joining self.
        Thread({ synchronized(audioLock) { audio?.close(); audio = null } }, "voice-cleanup").start()
        jobs.shutdownNow(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
    }
}
