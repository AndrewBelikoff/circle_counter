package com.circlecounter.app.domain

/**
 * Rejects GPS glitches and lightly smooths the path before lap detection.
 *
 * Glitch rules:
 * - Speed much above world-record sprint (~10 m/s for 100 m) ⇒ drop.
 * - Spike-and-return (far from both neighbours, neighbours close) ⇒ drop.
 * - Large jump in a short time ⇒ drop.
 * - Reported accuracy worse than threshold ⇒ drop.
 * - Large/fast GPS step while accelerometer says the phone is still ⇒ drop.
 *
 * Smoothing (EMA on lat/lon) reduces zig-zag inflation from phone GPS
 * without using pace/time to invent coordinates.
 */
class GpsFilter(
    private val config: Config = Config(),
    /**
     * Optional accelerometer RMS for [fromMs, toMs] (wall clock).
     * Null return = no motion data (skip the check).
     */
    private val motionRms: ((fromMs: Long, toMs: Long) -> Float?)? = null,
) {
    data class Config(
        /** ~10 s / 100 m; slightly above for residual GPS jitter. */
        val maxSpeedMetersPerSec: Double = 9.0,
        val maxJumpMeters: Double = 55.0,
        val maxJumpTimeSec: Double = 15.0,
        val spikeJumpMeters: Double = 40.0,
        val spikeBridgeMeters: Double = 35.0,
        val maxAccuracyMeters: Float = 45f,
        val minMoveMeters: Double = 1.5,
        /**
         * EMA weight for the latest accepted fix (0..1).
         * Too low (e.g. 0.28) shrinks loops toward the centroid and delays closure;
         * 0.7 trims zig-zag length while keeping ring geometry.
         */
        val emaAlpha: Double = 0.70,
        /** Accel RMS below this ⇒ treat as still (m/s²). */
        val stationaryRms: Float = 0.55f,
        val motionSuspectSpeedMps: Double = 2.2,
        val motionSuspectJumpM: Double = 22.0,
    )

    private val pending = ArrayDeque<GeoPoint>()
    private val acceptedRaw = mutableListOf<GeoPoint>()
    private val acceptedSmoothed = mutableListOf<GeoPoint>()
    private var emaLat: Double? = null
    private var emaLon: Double? = null

    /** Smoothed points fed to the lap detector / GPX. */
    val acceptedPoints: List<GeoPoint> get() = acceptedSmoothed.toList()

    fun offer(raw: GeoPoint): List<GeoPoint> {
        val accuracy = raw.accuracyMeters
        if (accuracy != null && accuracy > config.maxAccuracyMeters) {
            return emptyList()
        }

        pending.addLast(raw)
        val emitted = mutableListOf<GeoPoint>()

        while (pending.size >= 2) {
            val candidate = pending.first()
            val next = pending.elementAt(1)

            if (acceptedRaw.isEmpty()) {
                pending.removeFirst()
                emitAccepted(candidate, emitted)
                continue
            }

            val prev = acceptedRaw.last()
            if (isSpikeReturn(prev, candidate, next)) {
                pending.removeFirst()
                continue
            }
            if (!isPlausibleStep(prev, candidate)) {
                pending.removeFirst()
                continue
            }
            if (GeoMath.haversineMeters(prev, candidate) < config.minMoveMeters) {
                pending.removeFirst()
                continue
            }

            pending.removeFirst()
            emitAccepted(candidate, emitted)
        }
        return emitted
    }

    fun flush(): List<GeoPoint> {
        val emitted = mutableListOf<GeoPoint>()
        while (pending.isNotEmpty()) {
            val candidate = pending.removeFirst()
            if (acceptedRaw.isEmpty()) {
                emitAccepted(candidate, emitted)
                continue
            }
            val prev = acceptedRaw.last()
            if (!isPlausibleStep(prev, candidate)) continue
            if (GeoMath.haversineMeters(prev, candidate) < config.minMoveMeters) continue
            emitAccepted(candidate, emitted)
        }
        return emitted
    }

    fun reset() {
        pending.clear()
        acceptedRaw.clear()
        acceptedSmoothed.clear()
        emaLat = null
        emaLon = null
    }

    private fun emitAccepted(candidate: GeoPoint, emitted: MutableList<GeoPoint>) {
        acceptedRaw += candidate
        val alpha = config.emaAlpha
        val lat: Double
        val lon: Double
        if (emaLat == null || emaLon == null) {
            lat = candidate.latitude
            lon = candidate.longitude
        } else {
            lat = alpha * candidate.latitude + (1 - alpha) * emaLat!!
            lon = alpha * candidate.longitude + (1 - alpha) * emaLon!!
        }
        emaLat = lat
        emaLon = lon
        val smoothed = GeoPoint(
            latitude = lat,
            longitude = lon,
            timestampMs = candidate.timestampMs,
            accuracyMeters = candidate.accuracyMeters,
        )
        if (acceptedSmoothed.isNotEmpty()) {
            val prev = acceptedSmoothed.last()
            if (GeoMath.haversineMeters(prev, smoothed) < config.minMoveMeters) {
                return
            }
        }
        acceptedSmoothed += smoothed
        emitted += smoothed
    }

    private fun isSpikeReturn(prev: GeoPoint, mid: GeoPoint, next: GeoPoint): Boolean {
        val dPrev = GeoMath.haversineMeters(prev, mid)
        val dNext = GeoMath.haversineMeters(mid, next)
        val bridge = GeoMath.haversineMeters(prev, next)
        if (dPrev < config.spikeJumpMeters || dNext < config.spikeJumpMeters) return false
        if (bridge > config.spikeBridgeMeters) return false
        return bridge < 0.5 * minOf(dPrev, dNext)
    }

    private fun isPlausibleStep(prev: GeoPoint, next: GeoPoint): Boolean {
        val d = GeoMath.haversineMeters(prev, next)
        val dtSec = ((next.timestampMs - prev.timestampMs).coerceAtLeast(1L)) / 1000.0
        if (d / dtSec > config.maxSpeedMetersPerSec) return false
        if (d > config.maxJumpMeters && dtSec < config.maxJumpTimeSec) return false
        if (!passesMotionCheck(prev, next, d, dtSec)) return false
        return true
    }

    private fun passesMotionCheck(
        prev: GeoPoint,
        next: GeoPoint,
        distanceM: Double,
        dtSec: Double,
    ): Boolean {
        val hint = motionRms ?: return true
        val from = minOf(prev.timestampMs, next.timestampMs)
        val to = maxOf(prev.timestampMs, next.timestampMs)
        // Widen slightly — sensor samples use wall clock.
        val rms = hint(from - 200L, to + 200L) ?: return true
        val speed = if (dtSec > 0) distanceM / dtSec else 0.0
        val suspectJump = distanceM >= config.motionSuspectJumpM ||
            speed >= config.motionSuspectSpeedMps
        if (suspectJump && rms < config.stationaryRms) {
            return false
        }
        return true
    }
}
