package com.circlecounter.app.location

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Phone accelerometer (prefer linear acceleration) as a GPS sanity check.
 *
 * GPS teleports while the phone is nearly still get rejected by [GpsFilter]
 * when [recentRms] stays low over the jump interval.
 */
class MotionTracker : SensorEventListener {
    data class Sample(val tMs: Long, val rms: Float)

    private val samples = ArrayDeque<Sample>(MAX_SAMPLES)
    private var sensorManager: SensorManager? = null
    private var registered = false

    @Synchronized
    fun start(sm: SensorManager) {
        if (registered) return
        sensorManager = sm
        val sensor = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            ?: return
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        registered = true
    }

    @Synchronized
    fun stop() {
        if (!registered) return
        sensorManager?.unregisterListener(this)
        registered = false
        sensorManager = null
        samples.clear()
    }

    /**
     * RMS of recent linear-accel magnitude in [fromMs, toMs].
     * Returns null if there are too few samples in the window.
     */
    @Synchronized
    fun rmsBetween(fromMs: Long, toMs: Long): Float? {
        if (samples.isEmpty()) return null
        var sumSq = 0.0
        var n = 0
        for (s in samples) {
            if (s.tMs < fromMs) continue
            if (s.tMs > toMs) break
            sumSq += s.rms * s.rms
            n++
        }
        if (n < 3) return null
        return sqrt(sumSq / n).toFloat()
    }

    /** True when the device has been nearly still for [windowMs]. */
    @Synchronized
    fun isLikelyStationary(nowMs: Long = System.currentTimeMillis(), windowMs: Long = 1_500L): Boolean {
        val rms = rmsBetween(nowMs - windowMs, nowMs) ?: return false
        return rms < STATIONARY_RMS
    }

    override fun onSensorChanged(event: SensorEvent) {
        val ax = event.values[0]
        val ay = event.values[1]
        val az = event.values[2]
        val mag = sqrt(ax * ax + ay * ay + az * az)
        val tMs = event.timestamp / 1_000_000L // ns → ms
        val wall = System.currentTimeMillis()
        // Sensor timestamp is boot time; prefer wall clock for GPS alignment.
        val sample = Sample(tMs = wall, rms = mag)
        synchronized(this) {
            samples.addLast(sample)
            while (samples.size > MAX_SAMPLES) samples.removeFirst()
            // Drop older than 15 s.
            val cutoff = wall - 15_000L
            while (samples.isNotEmpty() && samples.first().tMs < cutoff) {
                samples.removeFirst()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private const val MAX_SAMPLES = 400
        /** Below this RMS (m/s²) over a short window ⇒ effectively still. */
        const val STATIONARY_RMS = 0.55f
        /**
         * GPS jump with implied speed above this and near-still accel ⇒ reject.
         */
        const val GPS_SUSPECT_SPEED_MPS = 2.2
        const val GPS_SUSPECT_JUMP_M = 22.0
    }
}
