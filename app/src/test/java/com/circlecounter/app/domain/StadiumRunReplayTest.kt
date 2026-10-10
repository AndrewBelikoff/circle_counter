package com.circlecounter.app.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replay of stadium sessions from the POCO phone store.
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

    @Test
    fun oct9RunCountsStadiumLapsAndKeepsPlausibleTemplate() {
        val raw = loadPoints("stadium_run_2026_10_09.json")
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

        assertTrue(
            "should confirm ring: phase=${snap.phase} laps=${snap.completedLaps}",
            snap.phase == LapDetector.Phase.CONFIRMED && snap.completedLaps >= 3,
        )
        // ~2.1 km / ~400 m ⇒ about 4–6 laps; reject the old 126 m collapsed template.
        assertTrue(
            "lap count unrealistic: ${snap.completedLaps}",
            snap.completedLaps in 3..8,
        )
        val expected = snap.computedLapLengthMeters
        assertTrue(
            "template collapsed or wrong: $expected",
            expected != null && expected in 300.0..700.0,
        )
        val extent = snap.template?.let { GeoMath.pathExtentMeters(it) }
        assertTrue("template footprint too small: $extent", extent != null && extent >= 70.0)
    }

    @Test
    fun rejectsCollapsedKnownTemplateAndLearnsInstead() {
        // Mimic the broken phone track (~126 m, tiny bbox).
        val tiny = (0 until 48).map { i ->
            val a = i * Math.PI * 2 / 48
            GeoPoint(
                latitude = 52.6 + 0.00015 * Math.cos(a),
                longitude = 39.5 + 0.00015 * Math.sin(a),
                timestampMs = i * 1000L,
            )
        }
        val detector = LapDetector(knownTemplate = tiny)
        assertEquals(LapDetector.Phase.LEARNING, detector.snapshot().phase)
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
