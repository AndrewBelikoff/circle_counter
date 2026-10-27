package com.circlecounter.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class LapStatsTest {
    @Test
    fun heartAverageWeightsTimeNotSampleCount() {
        val samples = listOf(
            TimedSample(0L, 100),
            TimedSample(10_000L, 180),
            TimedSample(11_000L, 180),
        )
        val avg = LapStatsCalculator.timeWeightedBpm(samples, 0L, 11_000L)
        // 10 s at 100 and about 2 s at 180. An unweighted mean of the three samples would be 153.
        assertEquals(113, avg)
    }
}
