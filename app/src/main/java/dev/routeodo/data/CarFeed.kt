package dev.routeodo.data

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dev.routeodo.core.CarSample
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.TimeUnit

const val PUBLIC_ODOMETER_URL = "https://f10.skyspacex.com/api/odometer"
const val LAN_ODOMETER_URL = "http://192.168.100.106:8088/api/odometer"

enum class CarFeedIssue { NONE, AUTH, UNAVAILABLE, NETWORK, INVALID }

interface FeedScheduler {
    fun post(task: Runnable)
    fun postDelayed(task: Runnable, delayMs: Long)
    fun removeCallbacks(task: Runnable)
}

class AndroidFeedScheduler : FeedScheduler {
    private val handler = Handler(Looper.getMainLooper())
    override fun post(task: Runnable) { handler.post(task) }
    override fun postDelayed(task: Runnable, delayMs: Long) { handler.postDelayed(task, delayMs) }
    override fun removeCallbacks(task: Runnable) { handler.removeCallbacks(task) }
}

/** Foreground transport. All state and delivery is serialized on the main looper. */
class CarFeed(
    context: Context?,
    private val sample: (CarSample, String) -> Unit,
    private val status: (CarFeedIssue, String) -> Unit,
    private val handler: FeedScheduler = AndroidFeedScheduler(),
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val lanUrl: String = LAN_ODOMETER_URL,
) {
    private val connectivity = context?.applicationContext?.getSystemService(ConnectivityManager::class.java)
    private val client = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    private val lanClient = client.newBuilder().connectTimeout(1, TimeUnit.SECONDS).readTimeout(1, TimeUnit.SECONDS)
        .callTimeout(1, TimeUnit.SECONDS).build()
    private val streamClient = client.newBuilder().readTimeout(20, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build()
    private var generation = 0
    private var running = false
    private var call: Call? = null
    private var stream: EventSource? = null
    private var remote = PUBLIC_ODOMETER_URL
    private var endpoint = PUBLIC_ODOMETER_URL
    private var token = ""
    private var lanFirst = true
    private var authRejected = false
    private var nextStreamAttempt = 0L
    private var nextLanAttempt = 0L
    private var lastDataAt = 0L
    private var pollTask: Runnable? = null
    private var watchdog: Runnable? = null

    fun start(url: String, bearer: String, tryLan: Boolean, retryAuth: Boolean = false) {
        val credentialsChanged = remote != url || token != bearer
        stop()
        remote = url; token = bearer; lanFirst = tryLan
        if (retryAuth || credentialsChanged) authRejected = false
        if (authRejected) { status(CarFeedIssue.AUTH, "Authentication failed · update token or press Start route to retry"); return }
        running = true; nextStreamAttempt = 0; nextLanAttempt = 0
        val current = generation
        status(CarFeedIssue.NONE, "Connecting to car…")
        if (lanFirst) fetch(lanUrl, current, probe = true) else fetch(remote, current)
    }

    fun stop() {
        running = false; generation++
        call?.cancel(); call = null
        stream?.cancel(); stream = null
        pollTask?.let(handler::removeCallbacks); pollTask = null
        watchdog?.let(handler::removeCallbacks); watchdog = null
    }

    fun close() {
        stop()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun valid(current: Int) = running && current == generation

    private fun fetch(url: String, current: Int, probe: Boolean = false) {
        if (!valid(current)) return
        val request = runCatching { request(url, false) }.getOrElse {
            running = false; status(CarFeedIssue.INVALID, "Invalid odometer URL · use HTTPS or the configured car LAN address"); return
        }
        val pending = networkClient(url, false).newCall(request)
        call = pending
        pending.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                handler.post { if (valid(current)) failed(url, current, probe, 0) }
            }
            override fun onResponse(call: Call, response: Response) {
                val code = response.code
                val result = response.use { runCatching {
                    require(code == 200)
                    val body = it.body ?: error("Empty response")
                    require(body.contentLength() <= 65536 && !body.source().request(65537))
                    JsonCodec.carSample(body.string())
                } }
                handler.post {
                    if (!valid(current)) return@post
                    this@CarFeed.call = null
                    result.onSuccess {
                        endpoint = url; lastDataAt = clock()
                        status(CarFeedIssue.NONE, "")
                        sample(it, "${if (url == lanUrl) "LAN" else "Internet"} · polling")
                        if (lastDataAt >= nextStreamAttempt) openStream(current) else schedulePoll(current)
                    }.onFailure { failed(url, current, probe, code) }
                }
            }
        })
    }

    private fun failed(url: String, current: Int, probe: Boolean, code: Int) {
        call = null
        if (code == 401 || code == 403) { unauthorized(); return }
        if (probe || url == lanUrl) {
            nextLanAttempt = clock() + 30_000
            endpoint = remote
            if (url != remote) { fetch(remote, current); return }
        }
        endpoint = url
        status(when (code) { 503 -> CarFeedIssue.UNAVAILABLE; 200 -> CarFeedIssue.INVALID; else -> CarFeedIssue.NETWORK },
            when (code) { 503 -> "Odometer unavailable"; 200 -> "Invalid odometer response"; else -> "Car connection unavailable" })
        schedulePoll(current)
    }

    private fun schedulePoll(current: Int) {
        if (!valid(current)) return
        pollTask?.let(handler::removeCallbacks)
        pollTask = Runnable {
            if (!valid(current)) return@Runnable
            if (lanFirst && endpoint != lanUrl && clock() >= nextLanAttempt)
                fetch(lanUrl, current, probe = true)
            else fetch(endpoint, current)
        }.also { handler.postDelayed(it, 500) }
    }

    private fun openStream(current: Int) {
        if (!valid(current)) return
        nextStreamAttempt = clock() + 30_000
        val url = endpoint.trimEnd('/') + "/stream"
        val req = runCatching { request(url, true) }.getOrElse { schedulePoll(current); return }
        stream = EventSources.createFactory(networkClient(url, true)).newEventSource(req, object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                handler.post { if (valid(current) && stream === eventSource) watchStream(current, eventSource) }
            }
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                val parsed = runCatching { require(data.length <= 65536); JsonCodec.carSample(data) }
                handler.post {
                    if (!valid(current) || stream !== eventSource) return@post
                    parsed.onSuccess {
                        lastDataAt = clock(); status(CarFeedIssue.NONE, "")
                        sample(it, "${if (endpoint == lanUrl) "LAN" else "Internet"} · live stream")
                    }.onFailure { streamEnded(current, eventSource, 0) }
                }
            }
            override fun onClosed(eventSource: EventSource) { handler.post { streamEnded(current, eventSource, 0) } }
            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                handler.post { streamEnded(current, eventSource, response?.code ?: 0) }
            }
        })
        // Also bound stream setup: a server may accept the socket without sending SSE headers.
        stream?.let { watchStream(current, it) }
    }

    private fun watchStream(current: Int, expected: EventSource) {
        watchdog?.let(handler::removeCallbacks)
        watchdog = Runnable {
            if (!valid(current) || stream !== expected) return@Runnable
            if (clock() - lastDataAt >= 10_000) streamEnded(current, expected, 0)
            else watchStream(current, expected)
        }.also { handler.postDelayed(it, 1000) }
    }

    private fun streamEnded(current: Int, expected: EventSource, code: Int) {
        if (!valid(current) || stream !== expected) return
        stream = null; expected.cancel()
        watchdog?.let(handler::removeCallbacks); watchdog = null
        if (code == 401 || code == 403) { unauthorized(); return }
        if (code == 503) status(CarFeedIssue.UNAVAILABLE, "Odometer unavailable")
        // Probe the LAN again after losing a public stream; ordinary polling retries it at most every 30 s.
        if (endpoint != lanUrl && code == 0) nextLanAttempt = 0
        schedulePoll(current)
    }

    private fun unauthorized() {
        authRejected = true; stop()
        status(CarFeedIssue.AUTH, "Authentication failed (401/403) · update bearer token, then retry")
    }

    private fun networkClient(url: String, streaming: Boolean): OkHttpClient {
        val local = url.startsWith("$lanUrl/") || url == lanUrl
        val base = if (streaming) streamClient else if (local) lanClient else client
        if (!local) return base
        // Android may keep mobile data as default on an internet-less Pi hotspot. Bind LAN traffic to its Wi-Fi.
        val connectivity = connectivity ?: return base
        val wifi = connectivity.allNetworks.firstOrNull { network ->
            connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                connectivity.getLinkProperties(network)?.linkAddresses?.any { it.address.hostAddress?.startsWith("192.168.100.") == true } == true
        }
        return if (wifi == null) base else base.newBuilder().socketFactory(wifi.socketFactory).build()
    }

    private fun request(url: String, streaming: Boolean): Request {
        val endpoint = url.toHttpUrl()
        require(endpoint.username.isEmpty() && endpoint.password.isEmpty() && endpoint.query == null && endpoint.fragment == null)
        val permittedLan = endpoint.host == "192.168.100.106" && endpoint.port == 8088 &&
            endpoint.encodedPath in setOf("/api/odometer", "/api/odometer/stream")
        require(endpoint.isHttps || permittedLan || token.isBlank())
        return Request.Builder().url(endpoint).header("User-Agent", "RouteOdo/0.3 Android")
            .header("Accept", if (streaming) "text/event-stream" else "application/json")
            .header("Cache-Control", "no-cache, no-store")
            .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }.build()
    }
}
