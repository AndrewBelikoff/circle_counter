package com.circlecounter.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class GeoMathRingTest {

    @Test
    fun averagingLapsStartedAtDifferentPlacesKeepsRingLength() {
        val base = oval(phase = 0.0)
        val reference = GeoMath.ringLengthMeters(GeoMath.resampleClosedRing(base, 64))
        val laps = listOf(base, oval(phase = 0.33), oval(phase = 0.5), oval(phase = 0.8))
        val averaged = GeoMath.averageRings(laps, samples = 64)
        assertEquals(reference, GeoMath.ringLengthMeters(averaged), reference * 0.02)
    }

    @Test
    fun averagingOppositeDirectionLapsKeepsRingLength() {
        val base = oval(phase = 0.0)
        val reference = GeoMath.ringLengthMeters(GeoMath.resampleClosedRing(base, 64))
        val averaged = GeoMath.averageRings(listOf(base, oval(phase = 0.25).reversed()), samples = 64)
        assertEquals(reference, GeoMath.ringLengthMeters(averaged), reference * 0.02)
    }

    @Test
    fun repeatedRefinementDoesNotShrink() {
        var template = oval(phase = 0.0)
        val reference = GeoMath.ringLengthMeters(GeoMath.resampleClosedRing(template, 64))
        repeat(30) { i ->
            template = GeoMath.averageRings(listOf(template, oval(phase = (i * 0.37) % 1.0)), samples = 64)
        }
        assertEquals(reference, GeoMath.ringLengthMeters(template), reference * 0.02)
    }

    /** ~400 m oval starting at fraction [phase] of the loop. */
    private fun oval(phase: Double, points: Int = 80): List<GeoPoint> {
        val lat0 = 52.6
        val mLat = 111_320.0
        val mLon = 111_320.0 * cos(Math.toRadians(lat0))
        return (0 until points).map { i ->
            val a = 2 * Math.PI * (i.toDouble() / points + phase)
            GeoPoint(
                latitude = lat0 + 54.0 * sin(a) / mLat,
                longitude = 39.5 + 73.0 * cos(a) / mLon,
                timestampMs = i * 1000L,
            )
        }
    }
}
