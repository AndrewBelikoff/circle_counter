package com.circlecounter.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class LapDetectorTest {

    @Test
    fun discoversAndConfirmsLapsOnRepeatingOval() {
        val detector = LapDetector()
        var snap = detector.snapshot()
        // Plenty of laps — with a wide GPS match radius the first closure may span ~2 ovals.
        val track = generateOvalLaps(laps = 10, pointsPerLap = 80, radiusMeters = 63.7)
        for (p in track) {
            snap = detector.addPoint(p)
        }
        assertEquals(LapDetector.Phase.CONFIRMED, snap.phase)
        assertTrue("expected >= 3 laps, got ${snap.completedLaps}", snap.completedLaps >= 3)
        assertTrue(detector.canSaveAsRing())
        assertFalse(detector.detectionFailedOnStop())
    }

    @Test
    fun recountsFromStartOncePatternIsClear() {
        val detector = LapDetector()
        var snap = detector.snapshot()
        val track = generateOvalLaps(laps = 5, pointsPerLap = 80, radiusMeters = 63.7)
        for (p in track) {
            snap = detector.addPoint(p)
        }
        assertEquals(LapDetector.Phase.CONFIRMED, snap.phase)
        // Pattern confirmed on lap 3, but recount from Start must credit the whole run.
        assertTrue(
            "recount from start should yield ~5 laps, got ${snap.completedLaps}",
            snap.completedLaps >= 4,
        )
    }

    @Test
    fun rejectsWhenThirdLapDiverges() {
        val detector = LapDetector()
        val twoLaps = generateOvalLaps(laps = 2, pointsPerLap = 80, radiusMeters = 63.7)
        var snap = detector.snapshot()
        for (p in twoLaps) {
            snap = detector.addPoint(p)
        }
        // After two matching laps we should be in HYPOTHESIS_THIRD or already matching second.
        assertTrue(
            "phase=${snap.phase}",
            snap.phase == LapDetector.Phase.HYPOTHESIS_SECOND ||
                snap.phase == LapDetector.Phase.HYPOTHESIS_THIRD ||
                snap.phase == LapDetector.Phase.CONFIRMED,
        )

        // Send a straight line away — third lap does not match.
        val last = twoLaps.last()
        for (i in 1..120) {
            val p = GeoPoint(
                latitude = last.latitude + i * 0.00025,
                longitude = last.longitude,
                timestampMs = last.timestampMs + i * 1000L,
            )
            snap = detector.addPoint(p)
        }
        // Diverging straight away must not keep minting laps.
        assertTrue(
            "diverging run scored too many laps: phase=${snap.phase} laps=${snap.completedLaps}",
            snap.completedLaps <= 4,
        )
        // Full recording kept.
        assertTrue(snap.points.size > twoLaps.size)
    }

    @Test
    fun closesLapsAtStartPressLocationNotTemplateOrigin() {
        // Start mid-lap: first point is halfway around the oval, not the template vertex.
        val full = generateOvalLaps(laps = 6, pointsPerLap = 80, radiusMeters = 63.7)
        val offset = 40
        val session = full.drop(offset).take(80 * 5)
        val template = generateOvalLaps(laps = 1, pointsPerLap = 80, radiusMeters = 63.7)
        val detector = LapDetector(knownTemplate = template)
        var snap = detector.snapshot()
        for (p in session) {
            snap = detector.addPoint(p)
        }
        assertEquals(LapDetector.Phase.CONFIRMED, snap.phase)
        assertTrue("gate should be first GPS after Start", snap.startGate != null)
        assertTrue("laps=${snap.completedLaps}", snap.completedLaps >= 3)
        val gate = snap.startGate!!
        val tplStart = template.first()
        val gateShift = GeoMath.haversineMeters(gate, tplStart)
        assertTrue("start mid-track gate shift should be meaningful: $gateShift", gateShift > 40.0)
    }

    @Test
    fun knownTrackCountsFromFirstLap() {
        val templateLap = generateOvalLaps(laps = 1, pointsPerLap = 80, radiusMeters = 63.7)
        val detector = LapDetector(knownTemplate = templateLap)
        var snap = detector.snapshot()
        assertEquals(LapDetector.Phase.CONFIRMED, snap.phase)

        val run = generateOvalLaps(laps = 2, pointsPerLap = 80, radiusMeters = 63.7)
        for (p in run) {
            snap = detector.addPoint(p)
        }
        snap = detector.finish()
        assertTrue("expected >= 2 laps, got ${snap.completedLaps}", snap.completedLaps >= 2)
    }

    @Test
    fun distanceBreakdownIncludesTailWhenAutoActive() {
        val templateLap = generateOvalLaps(laps = 1, pointsPerLap = 80, radiusMeters = 63.7)
        val detector = LapDetector(knownTemplate = templateLap)
        val oneAndHalf = generateOvalLaps(laps = 1, pointsPerLap = 80, radiusMeters = 63.7) +
            generateOvalLaps(laps = 1, pointsPerLap = 40, radiusMeters = 63.7)
        var snap = detector.snapshot()
        for (p in oneAndHalf) {
            snap = detector.addPoint(p)
        }
        val breakdown = DistanceCalculator.breakdown(
            snapshot = snap,
            manualLapLengthMeters = 400.0,
            autoCountActive = true,
        )
        assertTrue(breakdown.manualMeters != null)
        // At least one full manual lap worth when a lap completed.
        if (snap.completedLaps >= 1) {
            assertTrue(breakdown.manualMeters!! >= 400.0)
        }
    }

    @Test
    fun manualLengthPreventsLockingAShortLoop() {
        val detector = LapDetector(lapLengthHintMeters = 400.0)
        var snap = detector.snapshot()
        val short = generateOvalLaps(laps = 5, pointsPerLap = 80, radiusMeters = 45.0)
        for (p in short) snap = detector.addPoint(p)
        assertEquals(LapDetector.Phase.LEARNING, snap.phase)
        assertEquals(0, snap.completedLaps)
    }

    @Test
    fun infieldShortcutDoesNotCloseLapOnKnownTrack() {
        val template = generateOvalLaps(laps = 1, pointsPerLap = 80, radiusMeters = 63.7)
        val ringLen = GeoMath.ringLengthMeters(GeoMath.resampleClosedRing(template, 48))
        val shortcut = infieldShortcut(ringLen)
        val detector = LapDetector(knownTemplate = template)
        var snap = detector.snapshot()
        var t = 1_700_000_000_000L
        repeat(3) {
            for (p in shortcut) {
                t += 1000L
                snap = detector.addPoint(p.copy(timestampMs = t))
            }
        }
        snap = detector.finish()
        assertEquals(0, snap.completedLaps)
    }

    /**
     * Approximate 400m oval around a Moscow-ish coordinate.
     */
    private fun generateOvalLaps(
        laps: Int,
        pointsPerLap: Int,
        radiusMeters: Double,
        centerLat: Double = 55.7558,
        centerLon: Double = 37.6173,
    ): List<GeoPoint> {
        val metersPerDegLat = 111_320.0
        val metersPerDegLon = 111_320.0 * cos(Math.toRadians(centerLat))
        val out = ArrayList<GeoPoint>(laps * pointsPerLap)
        var t = 1_700_000_000_000L
        for (lap in 0 until laps) {
            for (i in 0 until pointsPerLap) {
                val angle = 2 * Math.PI * i / pointsPerLap
                // Slightly elongated oval.
                val east = radiusMeters * 1.15 * cos(angle)
                val north = radiusMeters * 0.85 * sin(angle)
                out += GeoPoint(
                    latitude = centerLat + north / metersPerDegLat,
                    longitude = centerLon + east / metersPerDegLon,
                    timestampMs = t,
                )
                t += 1000L
            }
        }
        return out
    }

    /** Stay near the oval center long enough to look like a lap, then return to the start point. */
    private fun infieldShortcut(targetMeters: Double): List<GeoPoint> {
        val centerLat = 55.7558
        val centerLon = 37.6173
        val start = generateOvalLaps(laps = 1, pointsPerLap = 80, radiusMeters = 63.7).first()
        val mLat = 111_320.0
        val mLon = 111_320.0 * cos(Math.toRadians(centerLat))
        val out = ArrayList<GeoPoint>()
        out += start
        var traveled = 0.0
        var i = 0
        var prev = start
        while (traveled < targetMeters * 0.95) {
            val angle = 2 * Math.PI * i / 24.0
            val p = GeoPoint(
                latitude = centerLat + 5.0 * sin(angle) / mLat,
                longitude = centerLon + 5.0 * cos(angle) / mLon,
            )
            traveled += GeoMath.haversineMeters(prev, p)
            out += p
            prev = p
            i++
        }
        out += start
        return out
    }
}
