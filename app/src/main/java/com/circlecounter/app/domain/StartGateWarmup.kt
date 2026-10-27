package com.circlecounter.app.domain

/**
 * The first GPS fix after Start is often a cold-start outlier.
 * Hold points until one is accurate enough, then begin the path there.
 * If nothing accurate arrives, use the best fix seen so far.
 */
class StartGateWarmup(
    private val goodAccuracyMeters: Float = 25f,
    private val maxWaitMs: Long = 12_000L,
    private val maxPoints: Int = 15,
) {
    private val buffered = mutableListOf<GeoPoint>()
    private var armed = false

    fun reset() {
        buffered.clear()
        armed = false
    }

    /** Points that should enter the lap path. Empty while still waiting. */
    fun offer(point: GeoPoint): List<GeoPoint> {
        if (armed) return listOf(point)
        buffered += point
        val accurate = point.accuracyMeters != null && point.accuracyMeters <= goodAccuracyMeters
        val waited = point.timestampMs - buffered.first().timestampMs >= maxWaitMs
        if (!accurate && !waited && buffered.size < maxPoints) return emptyList()
        return arm()
    }

    /** On Stop, don't drop a warmup that never saw a good fix. */
    fun flush(): List<GeoPoint> {
        if (armed || buffered.isEmpty()) return emptyList()
        return arm()
    }

    private fun arm(): List<GeoPoint> {
        armed = true
        val chosen = buffered.minWith(
            compareBy<GeoPoint> { it.accuracyMeters ?: Float.MAX_VALUE }
                .thenByDescending { it.timestampMs },
        )
        val rest = buffered.filter { it.timestampMs > chosen.timestampMs }
        buffered.clear()
        return listOf(chosen) + rest
    }
}
