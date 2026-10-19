package com.circlecounter.app.domain

/**
 * Near-term pace (seconds per km) for the run screen.
 *
 * Prefers fused [GeoPoint.speedMps] (phone GNSS stack), falls back to a short
 * filtered-path window (~4 s). Whole-run GPS polyline is never used.
 */
class InstantPaceEstimator(
    private val windowMs: Long = 4_000L,
    private val emaAlpha: Double = 0.35,
) {
    private val recent = ArrayDeque<GeoPoint>()
    private var emaPaceSecPerKm: Double? = null

    fun reset() {
        recent.clear()
        emaPaceSecPerKm = null
    }

    /** Call with every raw (or lightly accepted) fix that has a usable timestamp. */
    fun onFix(point: GeoPoint) {
        recent.addLast(point)
        val cutoff = point.timestampMs - windowMs - 1_000L
        while (recent.isNotEmpty() && recent.first().timestampMs < cutoff) {
            recent.removeFirst()
        }

        val fromSpeed = PaceCalculator.paceSecPerKmFromSpeedMps(point.speedMps)
        val fromWindow = windowPaceSecPerKm()
        val sample = fromSpeed ?: fromWindow ?: return
        emaPaceSecPerKm = when (val prev = emaPaceSecPerKm) {
            null -> sample
            else -> prev * (1 - emaAlpha) + sample * emaAlpha
        }
    }

    fun paceSecPerKm(): Double? = emaPaceSecPerKm

    fun paceMetricText(): String =
        PaceCalculator.formatPaceCore(emaPaceSecPerKm) ?: "—:—"

    private fun windowPaceSecPerKm(): Double? {
        if (recent.size < 3) return null
        val first = recent.first()
        val last = recent.last()
        val dt = last.timestampMs - first.timestampMs
        if (dt < 1_500L) return null
        var dist = 0.0
        val pts = recent.toList()
        for (i in 1 until pts.size) {
            dist += GeoMath.haversineMeters(pts[i - 1], pts[i])
        }
        if (dist < 8.0) return null
        return PaceCalculator.paceSecPerKm(dist, dt)
    }
}
