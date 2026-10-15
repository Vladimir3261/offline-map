package dev.routeodo.voice

import org.junit.Assert.*
import org.junit.Test

class PushToTalkBufferTest {
    @Test fun audioOutsidePressIsNeverSent() {
        val buffer = PushToTalkBuffer()
        buffer.append(ByteArray(5000))
        assertNull(buffer.finish(false))
        buffer.begin(); buffer.append(ByteArray(6000) { 7 }); val result = buffer.finish(false)
        assertEquals(6000, result!!.size)
        buffer.append(ByteArray(5000)); assertNull(buffer.finish(false))
    }
    @Test fun cancelAndNewPressDiscardPreviousAudio() {
        val buffer = PushToTalkBuffer()
        buffer.begin(); buffer.append(ByteArray(6000)); assertNull(buffer.finish(true))
        buffer.begin(); buffer.append(ByteArray(6000)); buffer.begin()
        buffer.append(ByteArray(5000) { 2 }); assertEquals(5000, buffer.finish(false)!!.size)
    }
    @Test fun shortTapDoesNotCommitAndLongReportsAreBounded() {
        val buffer = PushToTalkBuffer()
        buffer.begin(); buffer.append(ByteArray(1000)); assertNull(buffer.finish(false))
        buffer.begin()
        assertThrows(IllegalStateException::class.java) { buffer.append(ByteArray(1440001)) }
    }
}
