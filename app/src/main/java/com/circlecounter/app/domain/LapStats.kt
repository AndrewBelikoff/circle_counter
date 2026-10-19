package com.circlecounter.app.domain

/** Boundary of a completed lap inside the GPS point list. */
data class LapSplit(
    val lapNumber: Int,
    val startIndex: Int,
    val endIndex: Int,
    val startTimestampMs: Long,
    val endTimestampMs: Long,
)

/**
 * One completed lap for the post-run stats screen.
 */
data class LapStat(
    val lapNumber: Int,
    val elapsedMs: Long,
    val gpsDistanceMeters: Double,
    val manualDistanceMeters: Double?,
    /** km/h from GPS path length / lap time. */
    val speedGpsKmh: Double?,
    /** km/h from manual lap length / lap time. */
    val speedManualKmh: Double?,
    val avgHeartRateBpm: Int?,
    val maxHeartRateBpm: Int?,
    val avgCadenceSpm: Int?,
)

data class TimedSample(val tMs: Long, val value: Int)

object LapStatsCalculator {
    fun build(
        splits: List<LapSplit>,
        points: List<GeoPoint>,
        manualLapLengthMeters: Double?,
        heartSamples: List<TimedSample>,
        cadenceByRange: (fromMs: Long, toMs: Long) -> Int?,
    ): List<LapStat> {
        if (splits.isEmpty() || points.isEmpty()) return emptyList()
        return splits.map { split ->
            val slice = points.subList(
                split.startIndex.coerceIn(0, points.lastIndex),
                (split.endIndex + 1).coerceIn(1, points.size),
            )
            val gpsDist = GeoMath.pathLengthMeters(slice)
            val elapsed = (split.endTimestampMs - split.startTimestampMs).coerceAtLeast(1L)
            val hrInLap = heartSamples.filter { it.tMs in split.startTimestampMs..split.endTimestampMs }
            LapStat(
                lapNumber = split.lapNumber,
                elapsedMs = elapsed,
                gpsDistanceMeters = gpsDist,
                manualDistanceMeters = manualLapLengthMeters,
                speedGpsKmh = speedKmh(gpsDist, elapsed),
                speedManualKmh = manualLapLengthMeters?.let { speedKmh(it, elapsed) },
                avgHeartRateBpm = hrInLap.takeIf { it.isNotEmpty() }?.map { it.value }?.average()?.toInt(),
                maxHeartRateBpm = hrInLap.maxOfOrNull { it.value },
                avgCadenceSpm = cadenceByRange(split.startTimestampMs, split.endTimestampMs),
            )
        }
    }

    fun speedKmh(meters: Double, elapsedMs: Long): Double? {
        if (meters < 1.0 || elapsedMs < 500L) return null
        return meters / 1000.0 / (elapsedMs / 3_600_000.0)
    }

    fun formatSpeedKmh(kmh: Double?): String {
        if (kmh == null || kmh.isNaN() || kmh.isInfinite()) return "—"
        return if (kmh >= 10.0) "%.0f км/ч".format(kmh) else "%.1f км/ч".format(kmh)
    }
}
