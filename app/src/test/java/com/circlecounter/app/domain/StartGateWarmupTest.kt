package com.circlecounter.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StartGateWarmupTest {
    @Test
    fun waitsForAnAccurateFix() {
        val warmup = StartGateWarmup()
        val cold = GeoPoint(55.0, 37.0, 1_000L, accuracyMeters = 40f)
        assertTrue(warmup.offer(cold).isEmpty())
        val good = GeoPoint(55.0002, 37.0, 2_000L, accuracyMeters = 12f)
        val armed = warmup.offer(good)
        assertEquals(listOf(good), armed)
    }

    @Test
    fun fallsBackToTheBestFixAfterWaiting() {
        val warmup = StartGateWarmup(maxWaitMs = 5_000L)
        val worse = GeoPoint(55.0, 37.0, 1_000L, accuracyMeters = 40f)
        val better = GeoPoint(55.0001, 37.0, 3_000L, accuracyMeters = 28f)
        assertTrue(warmup.offer(worse).isEmpty())
        assertTrue(warmup.offer(better).isEmpty())
        val armed = warmup.offer(GeoPoint(55.0002, 37.0, 7_000L, accuracyMeters = 35f))
        assertEquals(28f, armed.first().accuracyMeters)
    }
}
