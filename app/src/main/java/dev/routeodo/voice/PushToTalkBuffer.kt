package dev.routeodo.voice

import java.io.ByteArrayOutputStream

/** No audio is accepted outside a deliberate press. Buffers the first report during connection setup. */
class PushToTalkBuffer {
    private var recording = false
    private val bytes = ByteArrayOutputStream()
    @Synchronized fun begin() { bytes.reset(); recording = true }
    @Synchronized fun append(value: ByteArray) {
        if (!recording) return
        check(bytes.size() + value.size <= 24000 * 2 * 30) { "Report exceeds 30 seconds" }
        bytes.write(value)
    }
    @Synchronized fun finish(cancel: Boolean): ByteArray? {
        recording = false
        val result = if (!cancel && bytes.size() >= 4800) bytes.toByteArray() else null
        bytes.reset()
        return result
    }
}
