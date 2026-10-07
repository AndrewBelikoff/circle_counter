package com.circlecounter.app.domain

data class DistanceBreakdown(
    /** Sum of GPS segments. */
    val gpsMeters: Double,
    /** completedLaps * manualLength + tail (null if manual length unset). */
    val manualMeters: Double?,
    /** completedLaps * computedLapLength + tail (null if no template). */
    val computedMeters: Double?,
    val completedLaps: Int,
    val partialLapFraction: Double,
)

object DistanceCalculator {
    fun breakdown(
        snapshot: LapDetector.Snapshot,
        manualLapLengthMeters: Double?,
        autoCountActive: Boolean,
    ): DistanceBreakdown {
        val gps = snapshot.gpsDistanceMeters
        if (!autoCountActive) {
            return DistanceBreakdown(
                gpsMeters = gps,
                manualMeters = null,
                computedMeters = null,
                completedLaps = snapshot.completedLaps,
                partialLapFraction = 0.0,
            )
        }

        val fraction = snapshot.partialLapFraction.coerceIn(0.0, 0.999)
        val laps = snapshot.completedLaps.toDouble()

        val manual = manualLapLengthMeters?.let { len ->
            laps * len + fraction * len
        }

        val computedLen = snapshot.computedLapLengthMeters
        val computed = computedLen?.let { len ->
            laps * len + fraction * len
        }

        return DistanceBreakdown(
            gpsMeters = gps,
            manualMeters = manual,
            computedMeters = computed,
            completedLaps = snapshot.completedLaps,
            partialLapFraction = fraction,
        )
    }
}

object PaceCalculator {
    /**
     * Pace in seconds per kilometer. Null if distance or time insufficient.
     */
    fun paceSecPerKm(distanceMeters: Double, elapsedMs: Long): Double? {
        if (distanceMeters < 20.0 || elapsedMs < 1_000L) return null
        val km = distanceMeters / 1000.0
        val sec = elapsedMs / 1000.0
        return sec / km
    }

    fun formatPace(secPerKm: Double?): String {
        if (secPerKm == null || secPerKm.isNaN() || secPerKm.isInfinite() || secPerKm > 3600) {
            return "—:—/км"
        }
        val total = secPerKm.toInt().coerceAtLeast(0)
        val min = total / 60
        val sec = total % 60
        return "%d:%02d/км".format(min, sec)
    }
}

object DistanceFormatter {
    fun formatKmM(meters: Double?): String {
        if (meters == null) return "—"
        val m = meters.coerceAtLeast(0.0)
        val km = (m / 1000.0).toInt()
        val rem = (m - km * 1000).toInt()
        return if (km > 0) "%d км %d м".format(km, rem) else "%d м".format(rem)
    }

    fun formatElapsed(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
