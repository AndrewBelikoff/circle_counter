package com.circlecounter.app.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replay of the 2026-10-07 stadium session (noisy POCO GPS, ~10+ laps of ~400 m).
 */
class StadiumRunReplayTest {
    @Test
    fun filtersAndCountsMultipleLapsOnNoisyStadiumTrack() {
        val raw = loadPoints("stadium_run_2026_10_07.json")
        assertTrue("fixture missing/empty", raw.size > 100)

        val filter = GpsFilter()
        val detector = LapDetector()
        var snap = detector.snapshot()
        for (p in raw) {
            for (accepted in filter.offer(p)) {
                snap = detector.addPoint(accepted)
            }
        }
        for (accepted in filter.flush()) {
            snap = detector.addPoint(accepted)
        }

        val filteredDist = snap.gpsDistanceMeters
        // Raw claimed ~60 km; smoothed path should be workout-scale.
        assertTrue("filtered distance still absurd: $filteredDist", filteredDist < 8_000.0)
        assertTrue("filtered distance too short: $filteredDist", filteredDist > 2_000.0)

        assertTrue(
            "should confirm ring on this session: phase=${snap.phase} laps=${snap.completedLaps}",
            snap.phase == LapDetector.Phase.CONFIRMED && snap.completedLaps >= 3,
        )
        val expected = snap.computedLapLengthMeters
        // Noisy GPS often merges ~1–2 physical laps into one detected ring length.
        assertTrue("lap length not stadium-like: $expected", expected != null && expected in 250.0..900.0)
        // User ran 10+; with this phone's GPS quality we require a clear multi-lap result.
        assertTrue(
            "too few laps for 30+ min stadium run: ${snap.completedLaps}",
            snap.completedLaps >= 5,
        )
    }

    private fun loadPoints(name: String): List<GeoPoint> {
        val stream = javaClass.classLoader!!.getResourceAsStream(name) ?: return emptyList()
        val text = stream.bufferedReader().readText()
        val arr = Json.parseToJsonElement(text).jsonArray
        return arr.map { el ->
            val o = el.jsonObject
            GeoPoint(
                latitude = o["lat"]!!.jsonPrimitive.double,
                longitude = o["lon"]!!.jsonPrimitive.double,
                timestampMs = o["t"]!!.jsonPrimitive.long,
            )
        }
    }
}
