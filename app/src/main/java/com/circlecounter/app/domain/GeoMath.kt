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

    /**
     * Average corresponding points of several similar loops into one template.
     */
    fun averagePaths(paths: List<List<GeoPoint>>, samples: Int = 64): List<GeoPoint> {
        require(paths.isNotEmpty())
        val resampled = paths.map { resampleByDistance(it, samples) }
        return List(samples) { i ->
            var lat = 0.0
            var lon = 0.0
            for (path in resampled) {
                lat += path[i].latitude
                lon += path[i].longitude
            }
            GeoPoint(lat / resampled.size, lon / resampled.size)
        }
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
