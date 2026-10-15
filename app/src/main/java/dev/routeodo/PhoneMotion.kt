package dev.routeodo

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.*

/** Gravity projects device gyro onto the vertical axis, independent of screen orientation. */
class PhoneMotion(context: Context) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private var gravity: FloatArray? = null
    private var timestamp = 0L
    private var yaw = 0.0
    private var receiving = false
    val ready: Boolean get() = receiving && android.os.SystemClock.elapsedRealtimeNanos() - timestamp in 0..500_000_000L
    fun start(): Boolean {
        stop()
        val gyro = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: return false
        val vertical = manager.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: return false
        val ok = manager.registerListener(this, vertical, SensorManager.SENSOR_DELAY_GAME) &&
            manager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME)
        if (!ok) stop()
        return ok
    }
    fun stop() { manager.unregisterListener(this); timestamp = 0; yaw = 0.0; gravity = null; receiving = false }
    fun consume(): Double = yaw.also { yaw = 0.0 }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_GRAVITY) { gravity = event.values.clone(); return }
        val g = gravity ?: return
        val norm = sqrt(g.sumOf { it.toDouble().pow(2) })
        if (norm < 1) return
        val dt = (event.timestamp - timestamp) / 1e9
        timestamp = event.timestamp
        if (dt !in 0.0..0.2) { receiving = false; return }
        yaw -= Math.toDegrees(event.values.indices.sumOf { event.values[it].toDouble() * g[it] } / norm * dt)
        receiving = true
    }
}
