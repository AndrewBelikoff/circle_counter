package com.circlecounter.app.domain

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object GeoMath {
    private const val EARTH_RADIUS_M = 6_371_000.0

    fun haversineMeters(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
    }

    fun pathLengthMeters(points: List<GeoPoint>): Double {
        if (points.size < 2) return 0.0
        var sum = 0.0
        for (i in 1 until points.size) {
            sum += haversineMeters(points[i - 1], points[i])
        }
        return sum
    }

    /**
     * Spatial span of a path (bbox diagonal). Used to reject collapsed "loops"
     * whose GPS arc length is long from zig-zag but the ring is tiny.
     */
    fun pathExtentMeters(points: List<GeoPoint>): Double {
        if (points.isEmpty()) return 0.0
        var minLat = points.first().latitude
        var maxLat = minLat
        var minLon = points.first().longitude
        var maxLon = minLon
        for (p in points) {
            if (p.latitude < minLat) minLat = p.latitude
            if (p.latitude > maxLat) maxLat = p.latitude
            if (p.longitude < minLon) minLon = p.longitude
            if (p.longitude > maxLon) maxLon = p.longitude
        }
        return haversineMeters(
            GeoPoint(minLat, minLon),
            GeoPoint(maxLat, maxLon),
        )
    }

    /**
     * Resample a polyline to [count] points evenly spaced by arc length.
     */
    fun resampleByDistance(points: List<GeoPoint>, count: Int): List<GeoPoint> {
        require(count >= 2)
        if (points.isEmpty()) return emptyList()
        if (points.size == 1) return List(count) { points.first() }

        val segmentLengths = DoubleArray(points.size - 1)
        var total = 0.0
        for (i in 1 until points.size) {
            val d = haversineMeters(points[i - 1], points[i])
            segmentLengths[i - 1] = d
            total += d
        }
        if (total <= 1e-3) return List(count) { points.first() }

        val result = ArrayList<GeoPoint>(count)
        for (i in 0 until count) {
            val target = total * i / (count - 1)
            result += pointAtDistance(points, segmentLengths, target)
        }
        return result
    }

    private fun pointAtDistance(
        points: List<GeoPoint>,
        segmentLengths: DoubleArray,
        target: Double,
    ): GeoPoint {
        if (target <= 0) return points.first()
        var remaining = target
        for (i in segmentLengths.indices) {
            val seg = segmentLengths[i]
            if (remaining <= seg || i == segmentLengths.lastIndex) {
                val t = if (seg < 1e-9) 0.0 else (remaining / seg).coerceIn(0.0, 1.0)
                val a = points[i]
                val b = points[i + 1]
                return GeoPoint(
                    latitude = a.latitude + (b.latitude - a.latitude) * t,
                    longitude = a.longitude + (b.longitude - a.longitude) * t,
                    timestampMs = a.timestampMs + ((b.timestampMs - a.timestampMs) * t).toLong(),
                )
            }
            remaining -= seg
        }
        return points.last()
    }

    /**
     * Mean point-to-point distance after resampling both paths to the same count.
     */
    fun meanPathDistanceMeters(a: List<GeoPoint>, b: List<GeoPoint>, samples: Int = 64): Double {
        if (a.isEmpty() || b.isEmpty()) return Double.POSITIVE_INFINITY
        val ra = resampleByDistance(a, samples)
        val rb = resampleByDistance(b, samples)
        var sum = 0.0
        for (i in ra.indices) {
            sum += haversineMeters(ra[i], rb[i])
        }
        return sum / ra.size
    }

    /** Length of a closed ring stored as an open polyline (includes last → first). */
    fun ringLengthMeters(ring: List<GeoPoint>): Double {
        if (ring.size < 2) return 0.0
        return pathLengthMeters(ring) + haversineMeters(ring.last(), ring.first())
    }

    /**
     * Resample a loop to [count] points evenly spaced along the closed ring
     * (the last → first segment is part of the ring, no duplicated vertex).
     */
    fun resampleClosedRing(points: List<GeoPoint>, count: Int): List<GeoPoint> {
        require(count >= 3)
        if (points.size < 2) return resampleByDistance(points, count)
        val closed = points + points.first()
        val segmentLengths = DoubleArray(closed.size - 1)
        var total = 0.0
        for (i in 1 until closed.size) {
            val d = haversineMeters(closed[i - 1], closed[i])
            segmentLengths[i - 1] = d
            total += d
        }
        if (total <= 1e-3) return List(count) { points.first() }
        return List(count) { i -> pointAtDistance(closed, segmentLengths, total * i / count) }
    }

    /**
     * Rotate/reverse [ring] so that index i lies at the same place of the loop as
     * [reference][i]. Both must be closed-ring resamples of equal size.
     * Without this, laps started at different places average toward the centroid
     * and the ring shrinks.
     */
    fun alignRing(reference: List<GeoPoint>, ring: List<GeoPoint>): List<GeoPoint> {
        val n = reference.size
        require(ring.size == n && n >= 3)
        val lat0 = reference.sumOf { it.latitude } / n
        val kx = 111_320.0 * cos(Math.toRadians(lat0))
        val ky = 111_320.0
        val rx = DoubleArray(n) { reference[it].longitude * kx }
        val ry = DoubleArray(n) { reference[it].latitude * ky }

        var best = ring
        var bestCost = Double.POSITIVE_INFINITY
        for (candidate in listOf(ring, ring.reversed())) {
            val cx = DoubleArray(n) { candidate[it].longitude * kx }
            val cy = DoubleArray(n) { candidate[it].latitude * ky }
            for (shift in 0 until n) {
                var cost = 0.0
                for (i in 0 until n) {
                    val j = (i + shift) % n
                    val dx = rx[i] - cx[j]
                    val dy = ry[i] - cy[j]
                    cost += dx * dx + dy * dy
                    if (cost >= bestCost) break
                }
                if (cost < bestCost) {
                    bestCost = cost
                    best = List(n) { candidate[(it + shift) % n] }
                }
            }
        }
        return best
    }

    /**
     * Phase-aligned average of several laps of the same ring.
     * [weights] defaults to equal; [robust] uses a per-point weighted median
     * (resists single GPS-drifted laps).
     */
    fun averageRings(
        paths: List<List<GeoPoint>>,
        samples: Int = 64,
        weights: List<Double>? = null,
        robust: Boolean = false,
    ): List<GeoPoint> {
        require(paths.isNotEmpty())
        require(weights == null || weights.size == paths.size)
        val w = weights ?: List(paths.size) { 1.0 }
        val rings = paths.map { resampleClosedRing(it, samples) }
        if (rings.size == 1) return rings.first()

        var reference = rings[w.indices.maxBy { w[it] }]
        var result = reference
        // Second pass re-aligns to the consensus so the first lap does not bias the shape.
        repeat(2) {
            val aligned = rings.map { alignRing(reference, it) }
            result = List(samples) { i ->
                if (robust && aligned.size >= 3) {
                    GeoPoint(
                        latitude = weightedMedian(aligned.map { it[i].latitude }, w),
                        longitude = weightedMedian(aligned.map { it[i].longitude }, w),
                    )
                } else {
                    val sw = w.sum()
                    GeoPoint(
                        latitude = aligned.indices.sumOf { aligned[it][i].latitude * w[it] } / sw,
                        longitude = aligned.indices.sumOf { aligned[it][i].longitude * w[it] } / sw,
                    )
                }
            }
            reference = result
        }
        return result
    }

    private fun weightedMedian(values: List<Double>, weights: List<Double>): Double {
        val order = values.indices.sortedBy { values[it] }
        val half = weights.sum() / 2
        var acc = 0.0
        for (idx in order) {
            acc += weights[idx]
            if (acc >= half) return values[idx]
        }
        return values[order.last()]
    }

    /** Value at the middle of a sorted list; null if empty. */
    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
    }

    /**
     * Progress along [template] for [currentLap] (0..1+). Uses nearest-template matching
     * by distance traveled ratio, refined by proximity.
     */
    fun lapProgressFraction(currentLap: List<GeoPoint>, template: List<GeoPoint>): Double {
        if (template.size < 2 || currentLap.isEmpty()) return 0.0
        val templateLen = pathLengthMeters(template).coerceAtLeast(1.0)
        val traveled = pathLengthMeters(currentLap)
        val byDistance = (traveled / templateLen).coerceAtLeast(0.0)

        val tip = currentLap.last()
        val resampled = resampleByDistance(template, 64)
        var bestIdx = 0
        var bestDist = Double.POSITIVE_INFINITY
        for (i in resampled.indices) {
            val d = haversineMeters(tip, resampled[i])
            if (d < bestDist) {
                bestDist = d
                bestIdx = i
            }
        }
        val byShape = bestIdx.toDouble() / (resampled.size - 1)
        // Prefer distance-based progress; blend when near the template.
        return if (bestDist < 40.0) (byDistance * 0.55 + byShape * 0.45) else byDistance
    }
}
