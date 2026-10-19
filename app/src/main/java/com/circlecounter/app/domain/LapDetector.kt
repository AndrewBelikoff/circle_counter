package com.circlecounter.app.domain

/**
 * Detects repeating route loops from GPS points.
 *
 * Gate = the place where **Start** was pressed (first GPS fix), anywhere on the ring.
 * A lap completes when the runner leaves that gate and later crosses it again
 * after covering roughly one lap length.
 *
 * New track: learn length/shape from the first returns to the Start gate;
 * after confirmation, recount from the beginning of movement.
 * Known track: use the manual lap length (or template length), but still close
 * laps on the Start gate (not on the template's stored start vertex). The stored
 * template is not modified mid-run; [runRing] builds this run's refinement.
 */
class LapDetector(
    private val config: Config = Config(),
    knownTemplate: List<GeoPoint>? = null,
    /** Real lap length entered by the user; anchors detection on a known track. */
    lapLengthHintMeters: Double? = null,
) {
    data class Config(
        val matchRadiusMeters: Double = 90.0,
        /** First return to the Start-press gate (phone GPS is often 30–50 m off). */
        val learningMatchRadiusMeters: Double = 50.0,
        val pathMatchMeanMeters: Double = 35.0,
        val pathRejectMeanMeters: Double = 60.0,
        val minLapMeters: Double = 260.0,
        val minLapExtentMeters: Double = 70.0,
        val maxLapMeters: Double = 1_500.0,
        val pathSamples: Int = 48,
        val minDistanceBeforeFirstLoopMeters: Double = 180.0,
        val lengthMinFactor: Double = 0.70,
        val lengthMaxFactor: Double = 1.60,
        val abandonFactor: Double = 2.4,
        val maxMissesBeforeAbandon: Int = 3,
        /** Laps used for template refinement must be within this band of the expected length. */
        val refineMinFactor: Double = 0.85,
        val refineMaxFactor: Double = 1.20,
        val maxRefineLaps: Int = 12,
        /** Moving this much farther than the closest approach means the gate was passed. */
        val gateExitMarginMeters: Double = 20.0,
    )

    /** Shape of the ring built from this run's clean laps. */
    data class RunRing(
        val template: List<GeoPoint>,
        val lapsUsed: Int,
        val medianLapMeters: Double,
    )

    enum class Phase {
        LEARNING,
        HYPOTHESIS_SECOND,
        HYPOTHESIS_THIRD,
        CONFIRMED,
    }

    data class Snapshot(
        val phase: Phase,
        val completedLaps: Int,
        val partialLapFraction: Double,
        val template: List<GeoPoint>?,
        val computedLapLengthMeters: Double?,
        val gpsDistanceMeters: Double,
        val points: List<GeoPoint>,
        val hypothesisRejectedCount: Int,
        val startGate: GeoPoint?,
        val lapSplits: List<LapSplit>,
    )

    private val points = mutableListOf<GeoPoint>()
    private var phase = Phase.LEARNING
    private var completedLaps = 0
    private var hypothesisRejectedCount = 0
    private val lapSplits = mutableListOf<LapSplit>()
    private val lapLengths = mutableListOf<Double>()
    /** Clean laps of a ring learned in this run, for in-run template averaging. */
    private val refineLaps = ArrayDeque<List<GeoPoint>>()

    private var template: List<GeoPoint>? = null
    /** Fixed reference: where Start was pressed. Never chase the template vertex. */
    private var startGate: GeoPoint? = null
    private var expectedLapMeters: Double? = null
    private var currentLapStartIndex = 0
    private var set1: List<GeoPoint>? = null
    private var set2: List<GeoPoint>? = null
    private var learnFromIndex = 0
    private var missStreak = 0
    private var minDistToAnchorMeters = Double.POSITIVE_INFINITY
    private var leftStartZone = false

    private val knownMode: Boolean
    private val lapLengthHint: Double? = lapLengthHintMeters
        ?.takeIf { it in config.minLapMeters..config.maxLapMeters }
    /** Known track without a manual length: expected lap may still adapt. */
    private val expectedFixed: Boolean

    init {
        val candidate = knownTemplate
            ?.takeIf { it.size >= 3 }
            ?.let { GeoMath.resampleClosedRing(it, config.pathSamples) }
        val usable = candidate != null && isPlausibleRing(candidate)
        knownMode = usable
        expectedFixed = usable && lapLengthHint != null
        if (usable) {
            template = candidate
            phase = Phase.CONFIRMED
            completedLaps = 0
            currentLapStartIndex = 0
            expectedLapMeters = lapLengthHint ?: GeoMath.ringLengthMeters(candidate!!)
            leftStartZone = false
            minDistToAnchorMeters = Double.POSITIVE_INFINITY
        }
    }

    private fun isPlausibleRing(ring: List<GeoPoint>): Boolean {
        val len = GeoMath.ringLengthMeters(ring)
        val extent = GeoMath.pathExtentMeters(ring)
        return len >= config.minLapMeters &&
            len <= config.maxLapMeters &&
            extent >= config.minLapExtentMeters
    }

    fun snapshot(): Snapshot {
        val tpl = template
        val expected = expectedLapMeters ?: tpl?.let { GeoMath.ringLengthMeters(it) }
        val measured = expected?.let { exp ->
            GeoMath.median(
                lapLengths.filter { it in exp * config.refineMinFactor..exp * config.refineMaxFactor },
            )
        }
        val currentLap =
            if (points.isEmpty()) emptyList() else points.subList(currentLapStartIndex, points.size)
        val fraction = if (expected != null && expected > 1.0 &&
            (phase == Phase.CONFIRMED || phase == Phase.HYPOTHESIS_THIRD || phase == Phase.HYPOTHESIS_SECOND)
        ) {
            (GeoMath.pathLengthMeters(currentLap) / expected).coerceIn(0.0, 0.999)
        } else {
            0.0
        }
        return Snapshot(
            phase = phase,
            completedLaps = completedLaps,
            partialLapFraction = fraction,
            template = tpl,
            computedLapLengthMeters = measured ?: expected,
            gpsDistanceMeters = GeoMath.pathLengthMeters(points),
            points = points.toList(),
            hypothesisRejectedCount = hypothesisRejectedCount,
            startGate = startGate,
            lapSplits = lapSplits.toList(),
        )
    }

    private fun recordSplit(lapNumber: Int, startIndex: Int, endIndex: Int) {
        if (endIndex < startIndex || endIndex >= points.size || startIndex >= points.size) return
        val startT = points[startIndex].timestampMs
        val endT = points[endIndex].timestampMs
        lapLengths += GeoMath.pathLengthMeters(points.subList(startIndex, endIndex + 1))
        lapSplits += LapSplit(
            lapNumber = lapNumber,
            startIndex = startIndex,
            endIndex = endIndex,
            startTimestampMs = startT,
            endTimestampMs = endT,
        )
    }

    private fun rebuildSplitsFromRecount(laps: List<Pair<Int, Int>>) {
        lapSplits.clear()
        lapLengths.clear()
        laps.forEachIndexed { idx, (start, end) ->
            recordSplit(lapNumber = idx + 1, startIndex = start, endIndex = end)
        }
    }

    fun addPoint(point: GeoPoint): Snapshot {
        if (points.isNotEmpty()) {
            val last = points.last()
            if (GeoMath.haversineMeters(last, point) < 1.5) {
                return snapshot()
            }
        }
        if (startGate == null) {
            startGate = point
            leftStartZone = false
            minDistToAnchorMeters = Double.POSITIVE_INFINITY
        }
        points += point
        updateMinDistToGate()

        when {
            knownMode || phase == Phase.CONFIRMED -> processConfirmed()
            phase == Phase.LEARNING -> processLearning()
            phase == Phase.HYPOTHESIS_SECOND -> processHypothesisSecond()
            phase == Phase.HYPOTHESIS_THIRD -> processHypothesisThird()
        }
        return snapshot()
    }

    private fun updateMinDistToGate() {
        if (points.isNotEmpty()) trackGateDistance(points.last())
    }

    private fun trackGateDistance(tip: GeoPoint) {
        if (startGate == null) return
        val d = distanceToStartGate(tip)
        if (!leftStartZone) {
            if (d > activeMatchRadius() * 1.2) {
                leftStartZone = true
                minDistToAnchorMeters = Double.POSITIVE_INFINITY
            }
            return
        }
        if (d < minDistToAnchorMeters) minDistToAnchorMeters = d
    }

    /** Start a lap at [startIndex] (may be in the past) and replay gate state up to now. */
    private fun resetLapWindow(startIndex: Int) {
        currentLapStartIndex = startIndex
        minDistToAnchorMeters = Double.POSITIVE_INFINITY
        leftStartZone = false
        for (i in startIndex + 1..points.lastIndex) trackGateDistance(points[i])
    }

    /** Distance to the Start-press gate only. */
    private fun distanceToStartGate(tip: GeoPoint): Double {
        val gate = startGate ?: return Double.POSITIVE_INFINITY
        return GeoMath.haversineMeters(tip, gate)
    }

    private fun activeMatchRadius(): Double {
        val expected = expectedLapMeters ?: return config.learningMatchRadiusMeters
        return minOf(config.matchRadiusMeters, maxOf(config.learningMatchRadiusMeters, expected * 0.15))
    }

    private fun processLearning() {
        if (startGate == null || points.size < 12) return
        if (!leftStartZone) return

        val fromIdx = maxOf(learnFromIndex, currentLapStartIndex).coerceIn(0, points.lastIndex)
        if (points.size - fromIdx < 8) return
        val lapPoints = points.subList(fromIdx, points.size).toList()
        val lapLen = GeoMath.pathLengthMeters(lapPoints)

        // Noisy GPS can inflate the path before the first real return — slide the window.
        if (lapLen > config.maxLapMeters) {
            slideLearningWindow()
            return
        }

        val tip = points.last()
        // First closure must cross the Start-press gate, not an arbitrary self-intersection.
        if (distanceToStartGate(tip) > config.learningMatchRadiusMeters) return
        if (lapLen < config.minLapMeters) return
        if (lapLen < config.minDistanceBeforeFirstLoopMeters) return
        if (GeoMath.pathExtentMeters(lapPoints) < config.minLapExtentMeters) return

        val ring = GeoMath.resampleClosedRing(lapPoints, config.pathSamples)
        if (!isPlausibleRing(ring)) return

        set1 = lapPoints
        template = ring
        expectedLapMeters = lapLen
        missStreak = 0
        lapSplits.clear()
        lapLengths.clear()
        recordSplit(lapNumber = 1, startIndex = fromIdx, endIndex = points.lastIndex)
        resetLapWindow(points.lastIndex)
        phase = Phase.HYPOTHESIS_SECOND
        completedLaps = 1
    }

    private fun slideLearningWindow() {
        var idx = points.lastIndex
        var lookback = 0.0
        while (idx > 0 && lookback < config.minLapMeters) {
            lookback += GeoMath.haversineMeters(points[idx - 1], points[idx])
            idx--
        }
        learnFromIndex = idx
        currentLapStartIndex = idx
        leftStartZone = false
        minDistToAnchorMeters = Double.POSITIVE_INFINITY
        if (points.isNotEmpty()) updateMinDistToGate()
    }

    private fun processHypothesisSecond() {
        val scan = tryCompleteLap()
        when (scan.result) {
            LapResult.TOO_EARLY -> return
            LapResult.COMPLETED -> {
                missStreak = 0
                val currentLap = points.subList(currentLapStartIndex, scan.endIndex + 1).toList()
                val lapLen = GeoMath.pathLengthMeters(currentLap)
                if (lapLen < config.minLapMeters ||
                    GeoMath.pathExtentMeters(currentLap) < config.minLapExtentMeters
                ) {
                    resetLapWindow(scan.endIndex)
                    return
                }
                set2 = currentLap
                expectedLapMeters = blendLapLength(expectedLapMeters, lapLen)
                val averaged = GeoMath.averageRings(listOfNotNull(set1, set2), config.pathSamples)
                if (isPlausibleRing(averaged)) {
                    template = averaged
                }
                phase = Phase.HYPOTHESIS_THIRD
                recountFromStartOfMovement()
            }
            LapResult.MISSED_WINDOW -> onHypothesisMiss(scan.endIndex)
        }
    }

    private fun processHypothesisThird() {
        val scan = tryCompleteLap()
        when (scan.result) {
            LapResult.TOO_EARLY -> return
            LapResult.COMPLETED -> {
                missStreak = 0
                val currentLap = points.subList(currentLapStartIndex, scan.endIndex + 1).toList()
                val lapLen = GeoMath.pathLengthMeters(currentLap)
                if (lapLen < config.minLapMeters ||
                    GeoMath.pathExtentMeters(currentLap) < config.minLapExtentMeters
                ) {
                    resetLapWindow(scan.endIndex)
                    return
                }
                val laps = listOfNotNull(set1, set2, currentLap)
                val averaged = GeoMath.averageRings(laps, config.pathSamples)
                if (isPlausibleRing(averaged)) {
                    template = averaged
                }
                expectedLapMeters = blendLapLength(expectedLapMeters, lapLen)
                refineLaps.clear()
                refineLaps.addAll(laps)
                phase = Phase.CONFIRMED
                recountFromStartOfMovement()
            }
            LapResult.MISSED_WINDOW -> onHypothesisMiss(scan.endIndex)
        }
    }

    /**
     * Re-apply Start-gate crossings from the first GPS point after Start.
     */
    private fun recountFromStartOfMovement() {
        val expected = expectedLapMeters ?: return
        val gate = startGate
        if (gate == null || points.size < 8) {
            completedLaps = 0
            resetLapWindow(0)
            return
        }

        var lapStart = 0
        val laps = mutableListOf<Pair<Int, Int>>()
        while (true) {
            val scan = scanLap(lapStart, expected)
            when (scan.result) {
                LapResult.COMPLETED -> {
                    laps += lapStart to scan.endIndex
                    lapStart = scan.endIndex
                }
                LapResult.MISSED_WINDOW -> lapStart = scan.endIndex
                LapResult.TOO_EARLY -> break
            }
        }

        completedLaps = laps.size
        rebuildSplitsFromRecount(laps)
        missStreak = 0
        resetLapWindow(lapStart)
    }

    private fun processConfirmed(scan: LapScan = tryCompleteLap()) {
        if (startGate == null) return
        when (scan.result) {
            LapResult.TOO_EARLY -> return
            LapResult.COMPLETED -> {
                missStreak = 0
                val endIdx = scan.endIndex
                val currentLap = points.subList(currentLapStartIndex, endIdx + 1).toList()
                val lapLen = GeoMath.pathLengthMeters(currentLap)
                if (lapLen < config.minLapMeters) {
                    resetLapWindow(endIdx)
                    return
                }
                val expected = expectedLapMeters
                if (expected != null && isCleanLap(currentLap, lapLen, expected)) {
                    if (!expectedFixed) {
                        expectedLapMeters = blendLapLength(expected, lapLen)
                    }
                    // A stored track keeps its template for the whole run; see runRing().
                    if (!knownMode) {
                        refineLaps.addLast(currentLap)
                        while (refineLaps.size > config.maxRefineLaps) refineLaps.removeFirst()
                        val averaged = GeoMath.averageRings(
                            refineLaps.toList(),
                            config.pathSamples,
                            robust = true,
                        )
                        if (isPlausibleRing(averaged)) template = averaged
                    }
                }
                completedLaps += 1
                recordSplit(lapNumber = completedLaps, startIndex = currentLapStartIndex, endIndex = endIdx)
                resetLapWindow(endIdx)
            }
            LapResult.MISSED_WINDOW -> {
                missStreak += 1
                resetLapWindow(scan.endIndex)
            }
        }
    }

    private fun onHypothesisMiss(restartIndex: Int) {
        missStreak += 1
        resetLapWindow(restartIndex)
        if (missStreak >= config.maxMissesBeforeAbandon) {
            abandonHypothesis()
        }
    }

    private fun isCleanLap(lap: List<GeoPoint>, lapLen: Double, expected: Double): Boolean =
        lap.size >= 8 &&
            lapLen in expected * config.refineMinFactor..expected * config.refineMaxFactor &&
            GeoMath.pathExtentMeters(lap) >= config.minLapExtentMeters

    /**
     * Ring shape from this run's clean laps (phase-aligned per-point median).
     * Null if fewer than two clean laps — not enough to refine a track.
     */
    fun runRing(): RunRing? {
        val expected = expectedLapMeters ?: return null
        val clean = lapSplits.mapNotNull { split ->
            if (split.endIndex >= points.size || split.startIndex >= split.endIndex) return@mapNotNull null
            val lap = points.subList(split.startIndex, split.endIndex + 1).toList()
            val len = GeoMath.pathLengthMeters(lap)
            if (isCleanLap(lap, len, expected)) lap to len else null
        }
        if (clean.size < 2) return null
        val ring = GeoMath.averageRings(
            clean.map { it.first },
            samples = 64,
            robust = clean.size >= 3,
        )
        if (!isPlausibleRing(ring)) return null
        return RunRing(
            template = ring,
            lapsUsed = clean.size,
            medianLapMeters = GeoMath.median(clean.map { it.second }) ?: GeoMath.ringLengthMeters(ring),
        )
    }

    private enum class LapResult { TOO_EARLY, COMPLETED, MISSED_WINDOW }

    private fun blendLapLength(previous: Double?, observed: Double): Double {
        if (previous == null) return observed
        return previous * 0.75 + observed * 0.25
    }

    /** [endIndex]: lap end for COMPLETED, restart point for MISSED_WINDOW. */
    private data class LapScan(val result: LapResult, val endIndex: Int = -1)

    private fun tryCompleteLap(final: Boolean = false): LapScan {
        val expected = expectedLapMeters ?: return LapScan(LapResult.TOO_EARLY)
        return scanLap(currentLapStartIndex, expected, final)
    }

    /** Call on Stop: credits a lap that reached the Start gate but has not moved away yet. */
    fun finish(): Snapshot {
        if (phase == Phase.CONFIRMED && startGate != null) {
            val scan = tryCompleteLap(final = true)
            if (scan.result == LapResult.COMPLETED) processConfirmed(scan)
        }
        return snapshot()
    }

    /**
     * Scan the lap that began at [start]. It ends at the closest approach to the
     * Start gate — decided only once the runner moves away again — so the next lap
     * also starts at the gate and boundaries do not drift around the ring.
     */
    private fun scanLap(start: Int, expected: Double, final: Boolean = false): LapScan {
        val gate = startGate ?: return LapScan(LapResult.TOO_EARLY)
        val radius = activeMatchRadius()
        val minLen = maxOf(config.minLapMeters, expected * config.lengthMinFactor)
        val abandonLen = expected * config.abandonFactor
        val strongLimit = radius * 1.35
        val weakLimit = maxOf(strongLimit, expected * 0.28)
        val weakFrom = expected * config.refineMinFactor
        val weakTo = expected * config.refineMaxFactor

        var traveled = 0.0
        var left = false
        var bestIdx = -1
        var bestD = Double.POSITIVE_INFINITY
        var weakIdx = -1
        var weakD = Double.POSITIVE_INFINITY
        for (i in start + 1..points.lastIndex) {
            traveled += GeoMath.haversineMeters(points[i - 1], points[i])
            val d = GeoMath.haversineMeters(points[i], gate)
            if (!left) {
                if (d > radius * 1.2) left = true else continue
            }
            if (traveled > abandonLen) return LapScan(LapResult.MISSED_WINDOW, i)
            if (traveled < minLen) continue
            if (d < bestD) {
                bestD = d
                bestIdx = i
            }
            if (traveled in weakFrom..weakTo && d < weakD) {
                weakD = d
                weakIdx = i
            }
            if (bestD <= strongLimit && d >= bestD + config.gateExitMarginMeters) {
                return LapScan(LapResult.COMPLETED, bestIdx)
            }
            if (traveled > weakTo && weakD <= weakLimit) {
                return LapScan(LapResult.COMPLETED, weakIdx)
            }
        }
        if (final && bestD <= strongLimit) return LapScan(LapResult.COMPLETED, bestIdx)
        return LapScan(LapResult.TOO_EARLY)
    }

    private fun abandonHypothesis() {
        hypothesisRejectedCount += 1
        missStreak = 0
        var idx = points.lastIndex
        var lookback = 0.0
        while (idx > 0 && lookback < 900.0) {
            lookback += GeoMath.haversineMeters(points[idx - 1], points[idx])
            idx--
        }
        learnFromIndex = idx
        phase = Phase.LEARNING
        completedLaps = 0
        lapSplits.clear()
        lapLengths.clear()
        refineLaps.clear()
        template = null
        // Keep Start gate — counting always closes there.
        expectedLapMeters = null
        set1 = null
        set2 = null
        minDistToAnchorMeters = Double.POSITIVE_INFINITY
        currentLapStartIndex = learnFromIndex
        leftStartZone = false
        if (points.isNotEmpty()) updateMinDistToGate()
    }

    fun canSaveAsRing(): Boolean = phase == Phase.CONFIRMED && completedLaps >= 3

    fun detectionFailedOnStop(): Boolean = !knownMode && phase != Phase.CONFIRMED
}
