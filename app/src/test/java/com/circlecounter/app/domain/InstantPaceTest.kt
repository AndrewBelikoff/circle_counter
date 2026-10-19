package com.circlecounter.app.domain

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstantPaceTest {
    @Test
    fun prefersFusedSpeedOverPath() {
        val est = InstantPaceEstimator()
        // ~3.33 m/s ⇒ ~5:00 /km
        val p = GeoPoint(52.6, 39.5, timestampMs = 1_000L, speedMps = 3.333f)
        est.onFix(p)
        val pace = est.paceSecPerKm()
        assertNotNull(pace)
        assertTrue("pace=$pace", pace!! in 280.0..320.0)
    }

    @Test
    fun fallsBackToShortWindow() {
        val est = InstantPaceEstimator()
        var t = 0L
        // Move ~40 m in 4 s ⇒ 10 m/s ⇒ ~1:40/km — just check it produces a value
        for (i in 0..8) {
            est.onFix(
                GeoPoint(
                    latitude = 52.6 + i * 0.000045, // ~5 m north each step
                    longitude = 39.5,
                    timestampMs = t,
                ),
            )
            t += 500L
        }
        val pace = est.paceSecPerKm()
        assertNotNull(pace)
        assertTrue("pace=$pace", pace!! in 60.0..600.0)
    }
}
