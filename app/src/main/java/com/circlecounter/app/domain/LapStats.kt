package com.circlecounter.app.domain

import kotlin.math.roundToInt

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
                avgHeartRateBpm = timeWeightedBpm(hrInLap, split.startTimestampMs, split.endTimestampMs),
                maxHeartRateBpm = hrInLap.maxOfOrNull { it.value },
                avgCadenceSpm = cadenceByRange(split.startTimestampMs, split.endTimestampMs),
            )
        }
    }

    /**
     * Average bpm weighted by how long each sample stayed current.
     * A value that holds for 10 s counts more than a one-second spike.
     */
    fun timeWeightedBpm(samples: List<TimedSample>, fromMs: Long, toMs: Long): Int? {
        val relevant = samples.filter { it.tMs in fromMs..toMs }
        if (relevant.isEmpty()) return null
        if (relevant.size == 1) return relevant[0].value
        var acc = 0.0
        var weight = 0.0
        for (i in 0 until relevant.lastIndex) {
            val dt = (relevant[i + 1].tMs - relevant[i].tMs).toDouble().coerceIn(0.0, 15_000.0)
            acc += relevant[i].value * dt
            weight += dt
        }
        val tail = (toMs - relevant.last().tMs).toDouble().coerceIn(0.0, 15_000.0)
            .takeIf { it >= 1.0 } ?: 1_000.0
        acc += relevant.last().value * tail
        weight += tail
        if (weight < 1.0) return relevant.last().value
        return (acc / weight).roundToInt()
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
