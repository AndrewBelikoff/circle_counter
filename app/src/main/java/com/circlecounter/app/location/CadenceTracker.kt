package com.circlecounter.app.location

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Steps/min from [Sensor.TYPE_STEP_COUNTER] (preferred) or [Sensor.TYPE_STEP_DETECTOR].
 */
class CadenceTracker : SensorEventListener {
    data class Sample(val tMs: Long, val cumulativeSteps: Long)

    private val samples = ArrayDeque<Sample>(MAX_SAMPLES)
    private var sensorManager: SensorManager? = null
    private var registered = false
    private var usingCounter = false
    private var detectorSteps = 0L
    private var counterBase: Long? = null

    @Synchronized
    fun start(sm: SensorManager) {
        if (registered) return
        sensorManager = sm
        val counter = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        val detector = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        when {
            counter != null -> {
                usingCounter = true
                sm.registerListener(this, counter, SensorManager.SENSOR_DELAY_UI)
                registered = true
            }
            detector != null -> {
                usingCounter = false
                sm.registerListener(this, detector, SensorManager.SENSOR_DELAY_UI)
                registered = true
            }
        }
    }

    @Synchronized
    fun stop() {
        if (!registered) return
        sensorManager?.unregisterListener(this)
        registered = false
        sensorManager = null
        samples.clear()
        detectorSteps = 0L
        counterBase = null
    }

    /** Rolling cadence (steps/min) over the last [windowMs]. */
    @Synchronized
    fun cadenceSpm(nowMs: Long = System.currentTimeMillis(), windowMs: Long = 12_000L): Int? {
        if (samples.size < 2) return null
        val from = nowMs - windowMs
        val inWindow = samples.filter { it.tMs >= from }
        if (inWindow.size < 2) return null
        val steps = inWindow.last().cumulativeSteps - inWindow.first().cumulativeSteps
        val dt = inWindow.last().tMs - inWindow.first().tMs
        if (dt < 2_000L || steps < 0) return null
        return ((steps * 60_000.0) / dt).toInt().coerceIn(0, 250).takeIf { it > 0 }
    }

    /** Average cadence between two wall-clock times. */
    @Synchronized
    fun averageCadenceSpm(fromMs: Long, toMs: Long): Int? {
        if (toMs <= fromMs || samples.size < 2) return null
        val before = samples.lastOrNull { it.tMs <= fromMs } ?: samples.firstOrNull { it.tMs >= fromMs }
        val after = samples.lastOrNull { it.tMs <= toMs } ?: return null
        if (before == null) return null
        val steps = after.cumulativeSteps - before.cumulativeSteps
        val dt = after.tMs - before.tMs
        if (dt < 1_000L || steps < 0) return null
        return ((steps * 60_000.0) / dt).toInt().coerceIn(0, 250).takeIf { it >= 0 }
    }

    @Synchronized
    fun snapshotSamples(): List<Sample> = samples.toList()

    override fun onSensorChanged(event: SensorEvent) {
        val wall = System.currentTimeMillis()
        val steps: Long = if (usingCounter) {
            val raw = event.values[0].toLong()
            if (counterBase == null) counterBase = raw
            raw - (counterBase ?: raw)
        } else {
            detectorSteps += 1
            detectorSteps
        }
        synchronized(this) {
            samples.addLast(Sample(wall, steps))
            while (samples.size > MAX_SAMPLES) samples.removeFirst()
            val cutoff = wall - 2 * 60 * 60 * 1000L
            while (samples.isNotEmpty() && samples.first().tMs < cutoff) {
                samples.removeFirst()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private const val MAX_SAMPLES = 8_000
    }
}
