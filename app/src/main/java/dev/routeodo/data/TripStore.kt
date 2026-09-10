package dev.routeodo.data

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

class TripStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "trip.json"))
    @Synchronized fun read(): JSONObject? = runCatching { JSONObject(String(file.readFully(), Charsets.UTF_8)) }.getOrNull()
    @Synchronized fun write(json: JSONObject) {
        val stream = file.startWrite()
        try {
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }
}
