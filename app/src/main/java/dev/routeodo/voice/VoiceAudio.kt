package dev.routeodo.voice

import android.annotation.SuppressLint
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/** 24 kHz mono signed little-endian PCM. Neither recording nor playback touches disk. */
class VoiceAudio(private val manager: AudioManager, private val input: (ByteArray) -> Unit, private val failed: () -> Unit) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private data class Chunk(val id: String, val bytes: ByteArray, val epoch: Int)
    private val queue = ArrayBlockingQueue<Chunk>(100)
    private val lock = Any()
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var echo: AcousticEchoCanceler? = null
    private var epoch = 0
    private var item = ""
    private var itemStart = 0L
    private var written = 0L
    private var capture: Thread? = null
    private val capturing = AtomicBoolean(false)
    private var playback: Thread? = null
    companion object {
        private val modeLock = Any()
        private var modeOwner: VoiceAudio? = null
        private var originalMode: Int? = null
    }
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener { change ->
            if (change < 0 && running.get()) failed()
        }.build()

    @SuppressLint("MissingPermission")
    fun start() {
        check(manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "Audio is in use" }
        synchronized(modeLock) {
            if (modeOwner == null) originalMode = manager.mode
            modeOwner = this
            manager.mode = AudioManager.MODE_IN_COMMUNICATION
        }
        val format = AudioFormat.Builder().setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
        record = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 24000, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(9600, AudioRecord.getMinBufferSize(24000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)))
        check(record!!.state == AudioRecord.STATE_INITIALIZED) { "Microphone unavailable" }
        track = AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(9600, AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)))
            .setTransferMode(AudioTrack.MODE_STREAM).build()
        if (AcousticEchoCanceler.isAvailable()) echo = AcousticEchoCanceler.create(record!!.audioSessionId)?.apply { enabled = true }
        track!!.play(); running.set(true)
        playback = Thread({
            try {
                while (running.get()) {
                    val (id, bytes, version) = queue.take()
                    var offset = 0
                    while (offset < bytes.size && running.get()) {
                        val n = synchronized(lock) {
                            if (epoch != version) -1 else {
                                if (item != id) { item = id; itemStart = written }
                                track!!.write(bytes, offset, minOf(2400, bytes.size - offset), AudioTrack.WRITE_NON_BLOCKING)
                                    .also { if (it > 0) written += it / 2 }
                            }
                        }
                        if (n < 0) break
                        offset += n
                        if (n == 0) Thread.sleep(10)
                    }
                }
            } catch (_: InterruptedException) { /* closed */ }
            catch (_: Exception) { if (running.get()) failed() }
        }, "voice-speaker").apply { start() }
    }

    fun beginCapture() {
        check(running.get())
        if (!capturing.compareAndSet(false, true)) return
        record!!.startRecording()
        capture = Thread({
            try {
                val bytes = ByteArray(2400)
                while (running.get() && capturing.get()) {
                    val n = record!!.read(bytes, 0, bytes.size)
                    if (!capturing.get() || !running.get()) break
                    check(n > 0)
                    input(bytes.copyOf(n))
                }
            } catch (_: Exception) { if (running.get() && capturing.get()) failed() }
        }, "voice-mic").apply { start() }
    }

    fun endCapture() {
        capturing.set(false)
        runCatching { record?.stop() }
        if (capture !== Thread.currentThread()) capture?.join(500)
        capture = null
    }

    fun enqueue(id: String, pcm: ByteArray) {
        val accepted = synchronized(lock) {
            running.get() && pcm.size <= 240000 && pcm.size % 2 == 0 && queue.offer(Chunk(id, pcm, epoch))
        }
        if (!accepted && running.get()) failed()
    }

    fun interrupt(): Pair<String, Long>? = synchronized(lock) {
        val played = track?.playbackHeadPosition?.toLong()?.and(0xffffffffL) ?: 0
        val result = item.takeIf { it.isNotBlank() }?.let { it to ((played - itemStart).coerceAtLeast(0) * 1000 / 24000) }
        epoch++; queue.clear(); track?.pause(); track?.flush(); track?.play()
        item = ""; itemStart = 0; written = 0
        result
    }

    override fun close() {
        running.set(false)
        endCapture()
        playback?.interrupt()
        playback?.join(500)
        synchronized(lock) {
            record?.release(); record = null; track?.release(); track = null; echo?.release(); echo = null; queue.clear()
            synchronized(modeLock) {
                if (modeOwner === this) { originalMode?.let { manager.mode = it }; originalMode = null; modeOwner = null }
            }
            manager.abandonAudioFocusRequest(focus)
        }
    }
}
