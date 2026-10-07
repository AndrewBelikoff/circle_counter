package com.circlecounter.app.domain

import org.junit.Assert.assertTrue
import org.junit.Test

class GpsFilterTest {
    @Test
    fun dropsSpikeReturn() {
        val filter = GpsFilter()
        val t0 = 1_000_000L
        val points = listOf(
            GeoPoint(55.0, 37.0, t0),
            GeoPoint(55.0, 37.00005, t0 + 2_000),
            GeoPoint(55.002, 37.00005, t0 + 4_000), // ~220 m spike
            GeoPoint(55.0, 37.00010, t0 + 6_000), // return near path
            GeoPoint(55.0, 37.00015, t0 + 8_000),
            GeoPoint(55.0, 37.00020, t0 + 10_000),
        )
        for (p in points) filter.offer(p)
        filter.flush()
        val accepted = filter.acceptedPoints
        assertTrue("spike kept: $accepted", accepted.none { it.latitude >= 55.001 })
        assertTrue("too few kept: ${accepted.size}", accepted.size >= 3)
    }

    @Test
    fun dropsSuperhumanSegmentOnFlush() {
        val filter = GpsFilter()
        filter.offer(GeoPoint(55.0, 37.0, 1_000))
        // ~1110 m north in 1 second — impossible
        filter.offer(GeoPoint(55.01, 37.0, 2_000))
        filter.flush()
        val accepted = filter.acceptedPoints
        assertTrue(accepted.size == 1)
        assertTrue(accepted.first().latitude == 55.0)
    }
}
