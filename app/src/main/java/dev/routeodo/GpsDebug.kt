package dev.routeodo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import dev.routeodo.core.Coordinate
import dev.routeodo.core.GpsFix
import dev.routeodo.core.PositionDebug

/** Foreground GNSS observer. It has no reference to the route or distance tracker. */
class GpsDebug(context: Context) {
    private val context = context.applicationContext
    private val manager = context.getSystemService(LocationManager::class.java)
    private var listener: LocationListener? = null

    fun start(update: (GpsFix?, String) -> Unit) {
        if (listener != null) return
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            update(null, "GPS debug off · tap to allow precise location"); return
        }
        if (manager == null || LocationManager.GPS_PROVIDER !in manager.allProviders) {
            update(null, "GPS hardware unavailable"); return
        }
        var newestFixNanos = -1L
        val callback = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (listener !== this || location.elapsedRealtimeNanos <= newestFixNanos) return
                val now = SystemClock.elapsedRealtimeNanos()
                val age = now - location.elapsedRealtimeNanos
                if (age < 0 || age > PositionDebug.MAX_GPS_AGE_MS * 1_000_000 || !location.hasAccuracy()) return
                val fix = runCatching { GpsFix(Coordinate(location.latitude, location.longitude),
                    location.accuracy.toDouble(), location.elapsedRealtimeNanos / 1_000_000) }.getOrNull() ?: return
                newestFixNanos = location.elapsedRealtimeNanos
                update(fix, "GPS live")
            }
            override fun onProviderDisabled(provider: String) {
                if (listener === this) update(null, "GPS disabled · tap for device settings")
            }
            override fun onProviderEnabled(provider: String) {
                if (listener === this) update(null, "Waiting for GPS fix")
            }
            @Deprecated("Legacy callback") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        listener = callback
        try {
            update(null, if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) "Waiting for GPS fix"
                else "GPS disabled · tap for device settings")
            // Keep the registration while the provider is disabled so enabling GPS resumes observation.
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, callback, Looper.getMainLooper())
        } catch (e: Exception) {
            stop()
            update(null, "GPS unavailable (${e.javaClass.simpleName}) · tap to retry")
        }
    }

    fun stop() {
        val previous = listener
        listener = null // Ignore callbacks already queued when the activity leaves the foreground.
        previous?.let { runCatching { manager?.removeUpdates(it) } }
    }
}
