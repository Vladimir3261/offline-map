package dev.routeodo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Independent one-shot request for manual alignment; does not stop the foreground debug observer. */
class GpsSync(context: Context) {
    private val context = context.applicationContext
    private val manager = context.getSystemService(LocationManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var listener: LocationListener? = null
    private var timeout: Runnable? = null

    fun request(result: (Location?, String?) -> Unit) {
        cancel()
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            result(null, "Precise location permission is needed for GPS Sync"); return
        }
        if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            result(null, "Enable device location, then try GPS Sync"); return
        }
        val callback = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (listener !== this) return
                val ageMs = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
                if (ageMs !in 0..2000 || !location.hasAccuracy()) return
                cancel()
                result(location, null)
            }
            override fun onProviderDisabled(provider: String) {
                if (listener === this) { cancel(); result(null, "GPS was disabled") }
            }
            override fun onProviderEnabled(provider: String) = Unit
            @Deprecated("Legacy callback") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        listener = callback
        timeout = Runnable { cancel(); result(null, "No fresh GPS fix in 25 seconds. Distance tracking continues.") }
        try {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, callback, Looper.getMainLooper())
            handler.postDelayed(timeout!!, 25_000)
        } catch (e: Exception) { cancel(); result(null, "Could not request GPS: ${e.javaClass.simpleName}") }
    }

    fun cancel() {
        listener?.let { manager.removeUpdates(it) }
        listener = null
        timeout?.let { handler.removeCallbacks(it) }
        timeout = null
    }
}
