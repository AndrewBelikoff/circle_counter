package com.circlecounter.app.domain

/**
 * Detects repeating route loops from GPS points.
 *
 * New track flow:
 * 1. At Start the runner is already on the ring — the whole recording is the route.
 * 2. Coordinates start repeating → hypothesis "lap 2" (uncertain).
 * 3. Two matching passes → hypothesis "lap 3 started".
 * 4. Third pass confirms the pattern → **recount from the beginning of movement**
 *    with that pattern (so the UI shows lap 3, not "lap 1 just confirmed").
 * 5. Soft mismatch does not wipe confirmation; only a clear failed window
 *    abandons an unconfirmed hypothesis.
 *
 * Known track: template provided → count from the first lap.
 */
class LapDetector(
    private val config: Config = Config(),
    knownTemplate: List<GeoPoint>? = null,
) {
    data class Config(
        /**
         * Re-approach tolerance after a lap length is known.
         * Phone GPS on a stadium is often tens of metres off.
         */
        val matchRadiusMeters: Double = 90.0,
        /**
         * First-loop discovery radius. Must stay well below half the ring diameter
         * (~150 m on a 400 m track) or the detector closes false short loops.
         */
        val learningMatchRadiusMeters: Double = 35.0,
        /** Soft path check only (not used to zero the counter). */
        val pathMatchMeanMeters: Double = 35.0,
        val pathRejectMeanMeters: Double = 60.0,
        val minLapMeters: Double = 260.0,
        /**
         * Minimum bbox diagonal of a discovered lap. Rejects zig-zag "loops"
         * that have enough arc length but sit in a tiny footprint (false rings).
         */
        val minLapExtentMeters: Double = 70.0,
        /**
         * Upper bound for a single discovered lap. Keeps phone GPS teleports
         * from forming multi-kilometre false rings on a 400 m track.
         */
        val maxLapMeters: Double = 1_500.0,
        val pathSamples: Int = 48,
        val minDistanceBeforeFirstLoopMeters: Double = 180.0,
        /** Acceptable length window vs expected lap. */
        val lengthMinFactor: Double = 0.70,
        val lengthMaxFactor: Double = 1.60,
        /** How far past expected length before a hypothesis is abandoned. */
        val abandonFactor: Double = 2.4,
        /** Missed lap windows allowed before abandoning hypothesis. */
        val maxMissesBeforeAbandon: Int = 3,
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
    )

    private val points = mutableListOf<GeoPoint>()
    private var phase = Phase.LEARNING
    private var completedLaps = 0
    private var hypothesisRejectedCount = 0

    private var template: List<GeoPoint>? = null
    private var anchor: GeoPoint? = null
    private var expectedLapMeters: Double? = null
    private var currentLapStartIndex = 0
    private var set1: List<GeoPoint>? = null
    private var set2: List<GeoPoint>? = null
    /** After reject, do not re-use points before this index for a new first closure. */
    private var learnFromIndex = 0
    private var missStreak = 0
    /** Closest approach to the start zone during the current lap (after leaving it). */
    private var minDistToAnchorMeters = Double.POSITIVE_INFINITY
    /** Must leave the start zone before a new lap can be completed. */
    private var leftStartZone = false

    private val knownMode: Boolean

    init {
        val candidate = knownTemplate
            ?.takeIf { it.size >= 2 }
            ?.let { GeoMath.resampleByDistance(it, config.pathSamples) }
        val usable = candidate != null && isPlausibleRing(candidate)
        knownMode = usable
        if (usable) {
            template = candidate
            phase = Phase.CONFIRMED
            completedLaps = 0
            currentLapStartIndex = 0
            anchor = candidate!!.first()
            expectedLapMeters = GeoMath.pathLengthMeters(candidate)
            leftStartZone = false
            minDistToAnchorMeters = Double.POSITIVE_INFINITY
        }
    }

    private fun isPlausibleRing(ring: List<GeoPoint>): Boolean {
        val len = GeoMath.pathLengthMeters(ring)
        val extent = GeoMath.pathExtentMeters(ring)
        return len >= config.minLapMeters &&
            len <= config.maxLapMeters &&
            extent >= config.minLapExtentMeters
    }

    fun snapshot(): Snapshot {
        val tpl = template
        val expected = expectedLapMeters ?: tpl?.let { GeoMath.pathLengthMeters(it) }
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
            computedLapLengthMeters = expected,
            gpsDistanceMeters = GeoMath.pathLengthMeters(points),
            points = points.toList(),
            hypothesisRejectedCount = hypothesisRejectedCount,
        )
    }

    fun addPoint(point: GeoPoint): Snapshot {
        if (points.isNotEmpty()) {
            val last = points.last()
            if (GeoMath.haversineMeters(last, point) < 1.5) {
                return snapshot()
            }
        }
        points += point
        updateMinDistToAnchor()

        when {
            knownMode || phase == Phase.CONFIRMED -> processConfirmed()
            phase == Phase.LEARNING -> processLearning()
            phase == Phase.HYPOTHESIS_SECOND -> processHypothesisSecond()
            phase == Phase.HYPOTHESIS_THIRD -> processHypothesisThird()
        }
        return snapshot()
    }

    private fun updateMinDistToAnchor() {
        if (phase == Phase.LEARNING) return
        val tip = points.last()
        val d = distanceToStartZone(tip)
        if (!leftStartZone) {
            if (d > activeMatchRadius() * 1.2) {
                leftStartZone = true
                // Reset — we only care about the closest approach on the way *back*.
                minDistToAnchorMeters = Double.POSITIVE_INFINITY
            }
            return
        }
        if (d < minDistToAnchorMeters) minDistToAnchorMeters = d
    }

    private fun resetLapWindow(startIndex: Int) {
        currentLapStartIndex = startIndex
        minDistToAnchorMeters = Double.POSITIVE_INFINITY
        leftStartZone = false
        if (points.isNotEmpty()) updateMinDistToAnchor()
    }

    /**
     * Distance to the finish/start reference. Keep the zone tight so the runner
     * can actually "leave" it; a wide template prefix made leftStartZone stuck.
     */
    private fun distanceToStartZone(tip: GeoPoint): Double {
        var best = Double.POSITIVE_INFINITY
        anchor?.let { best = minOf(best, GeoMath.haversineMeters(tip, it)) }
        val tpl = template
        if (tpl != null && tpl.isNotEmpty()) {
            val zoneCount = (tpl.size * 0.08).toInt().coerceIn(1, 4)
            for (i in 0 until zoneCount) {
                best = minOf(best, GeoMath.haversineMeters(tip, tpl[i]))
            }
        }
        return best
    }

    private fun activeMatchRadius(): Double {
        val expected = expectedLapMeters ?: return config.learningMatchRadiusMeters
        return minOf(config.matchRadiusMeters, maxOf(config.learningMatchRadiusMeters, expected * 0.15))
    }

    private fun nearStartZone(tip: GeoPoint): Boolean =
        distanceToStartZone(tip) <= activeMatchRadius()

    private fun approachedStartZone(): Boolean =
        minDistToAnchorMeters <= activeMatchRadius() * 1.35

    private fun processLearning() {
        if (points.size < 12) return
        val total = GeoMath.pathLengthMeters(points)
        if (total < config.minDistanceBeforeFirstLoopMeters) return

        val closure = findLoopClosure(searchFromIndex = learnFromIndex) ?: return
        val lapPoints = points.subList(closure.startIndex, points.size).toList()
        val lapLen = GeoMath.pathLengthMeters(lapPoints)
        if (lapLen < config.minLapMeters || lapLen > config.maxLapMeters) return
        if (GeoMath.pathExtentMeters(lapPoints) < config.minLapExtentMeters) return

        val ring = GeoMath.resampleByDistance(lapPoints, config.pathSamples)
        if (!isPlausibleRing(ring)) return

        set1 = lapPoints
        template = ring
        // Closing tip is the practical start/finish for the next lap.
        anchor = points.last()
        expectedLapMeters = lapLen
        missStreak = 0
        resetLapWindow(points.lastIndex)
        phase = Phase.HYPOTHESIS_SECOND
        completedLaps = 1
    }

    private fun processHypothesisSecond() {
        when (val result = tryCompleteLap()) {
            LapResult.TOO_EARLY -> return
            LapResult.COMPLETED -> {
                missStreak = 0
                val currentLap = points.subList(currentLapStartIndex, points.size).toList()
                val lapLen = GeoMath.pathLengthMeters(currentLap)
                if (lapLen < config.minLapMeters) {
                    resetLapWindow(points.lastIndex)
                    return
                }
                if (GeoMath.pathExtentMeters(currentLap) < config.minLapExtentMeters) {
                    resetLapWindow(points.lastIndex)
                    return
                }
                set2 = currentLap
                expectedLapMeters = blendLapLength(expectedLapMeters, lapLen)
                val averaged = GeoMath.averagePaths(listOfNotNull(set1, set2), config.pathSamples)
                if (isPlausibleRing(averaged)) {
                    template = averaged
                    anchor = averaged.first()
                }
                phase = Phase.HYPOTHESIS_THIRD
                // Pattern is usable: credit all laps since Start, not only "2".
                recountFromStartOfMovement()
            }
            LapResult.MISSED_WINDOW -> onHypothesisMiss()
        }
    }

    private fun processHypothesisThird() {
        when (val result = tryCompleteLap()) {
            LapResult.TOO_EARLY -> return
            LapResult.COMPLETED -> {
                missStreak = 0
                val currentLap = points.subList(currentLapStartIndex, points.size).toList()
                val lapLen = GeoMath.pathLengthMeters(currentLap)
                if (lapLen < config.minLapMeters) {
                    resetLapWindow(points.lastIndex)
                    return
                }
                if (GeoMath.pathExtentMeters(currentLap) < config.minLapExtentMeters) {
                    resetLapWindow(points.lastIndex)
                    return
                }
                val averaged = GeoMath.averagePaths(
                    listOfNotNull(set1, set2, currentLap),
                    config.pathSamples,
                )
                if (isPlausibleRing(averaged)) {
                    template = averaged
                    expectedLapMeters = blendLapLength(expectedLapMeters, lapLen)
                    anchor = averaged.first()
                } else {
                    expectedLapMeters = blendLapLength(expectedLapMeters, lapLen)
                }
                phase = Phase.CONFIRMED
                // Re-apply confirmed pattern from the first GPS point after Start.
                recountFromStartOfMovement()
            }
            LapResult.MISSED_WINDOW -> onHypothesisMiss()
        }
    }

    /**
     * Walk the full recording from the first point (runner was already on the ring
     * at Start) and set [completedLaps] / current lap window from the known pattern.
     */
    private fun recountFromStartOfMovement() {
        val expected = expectedLapMeters ?: return
        if (points.size < 8) {
            completedLaps = 0
            resetLapWindow(0)
            return
        }

        var lapStart = 0
        var laps = 0
        var leftZone = false
        var minDist = Double.POSITIVE_INFINITY
        val radius = activeMatchRadius()
        val minLen = maxOf(config.minLapMeters, expected * config.lengthMinFactor)
        val maxLen = expected * config.lengthMaxFactor
        val abandonLen = expected * config.abandonFactor
        val weakLimit = maxOf(radius * 1.35, expected * 0.28)

        // Prefix path lengths for O(n) recount.
        val cum = DoubleArray(points.size)
        for (i in 1 until points.size) {
            cum[i] = cum[i - 1] + GeoMath.haversineMeters(points[i - 1], points[i])
        }

        for (i in 1 until points.size) {
            val tip = points[i]
            val traveled = cum[i] - cum[lapStart]
            val d = distanceToStartZone(tip)

            if (!leftZone) {
                if (d > radius * 1.2) {
                    leftZone = true
                    minDist = Double.POSITIVE_INFINITY
                }
            } else if (d < minDist) {
                minDist = d
            }

            if (traveled < minLen || !leftZone) continue

            val nearNow = d <= radius
            val approached = minDist <= radius * 1.35
            val complete =
                ((nearNow || (approached && traveled >= expected * 0.90)) && traveled <= abandonLen) ||
                    (traveled >= expected * 0.90 && traveled <= maxLen && minDist <= weakLimit)

            if (complete) {
                laps += 1
                lapStart = i
                leftZone = false
                minDist = Double.POSITIVE_INFINITY
            } else if (traveled > abandonLen) {
                lapStart = i
                leftZone = false
                minDist = Double.POSITIVE_INFINITY
            }
        }

        completedLaps = laps
        currentLapStartIndex = lapStart
        leftStartZone = leftZone
        minDistToAnchorMeters = minDist
        missStreak = 0
        // Recompute leave/approach state for the open lap tip.
        if (points.isNotEmpty()) {
            updateMinDistToAnchor()
        }
    }

    private fun processConfirmed() {
        when (tryCompleteLap()) {
            LapResult.TOO_EARLY -> return
            LapResult.COMPLETED -> {
                missStreak = 0
                val currentLap = points.subList(currentLapStartIndex, points.size).toList()
                val lapLen = GeoMath.pathLengthMeters(currentLap)
                if (lapLen < config.minLapMeters) {
                    resetLapWindow(points.lastIndex)
                    return
                }
                val tpl = template
                val expected = expectedLapMeters
                if (tpl != null && currentLap.size >= 4 && expected != null &&
                    lapLen in expected * 0.75..expected * 1.25 &&
                    GeoMath.pathExtentMeters(currentLap) >= config.minLapExtentMeters
                ) {
                    // Gentle refine only when the new lap agrees with the estimate.
                    val averaged = GeoMath.averagePaths(listOf(tpl, currentLap), config.pathSamples)
                    if (isPlausibleRing(averaged)) {
                        template = averaged
                        expectedLapMeters = blendLapLength(expected, lapLen)
                    }
                }
                // Keep confirmed anchor stable — do not chase GPS drift each lap.
                completedLaps += 1
                resetLapWindow(points.lastIndex)
            }
            LapResult.MISSED_WINDOW -> {
                // Stay confirmed; slide window and keep counting on later approaches.
                missStreak += 1
                resetLapWindow(points.lastIndex)
            }
        }
    }

    private fun onHypothesisMiss() {
        missStreak += 1
        resetLapWindow(points.lastIndex)
        if (missStreak >= config.maxMissesBeforeAbandon) {
            abandonHypothesis()
        }
    }

    private enum class LapResult { TOO_EARLY, COMPLETED, MISSED_WINDOW }

    private fun blendLapLength(previous: Double?, observed: Double): Double {
        if (previous == null) return observed
        // Slow adaptation — do not let one noisy short lap collapse the estimate.
        return previous * 0.75 + observed * 0.25
    }

    private fun tryCompleteLap(): LapResult {
        val expected = expectedLapMeters ?: return LapResult.TOO_EARLY
        if (currentLapStartIndex >= points.lastIndex) return LapResult.TOO_EARLY

        val currentLap = points.subList(currentLapStartIndex, points.size).toList()
        if (currentLap.size < 3) return LapResult.TOO_EARLY

        val traveled = GeoMath.pathLengthMeters(currentLap)
        val minLen = maxOf(config.minLapMeters, expected * config.lengthMinFactor)
        val abandonLen = expected * config.abandonFactor

        if (traveled < minLen) return LapResult.TOO_EARLY
        if (!leftStartZone) return LapResult.TOO_EARLY

        val tip = currentLap.last()
        val nearNow = nearStartZone(tip)
        val approached = approachedStartZone()

        // Prefer counting when we are in the start zone now; also allow a lap if we
        // dipped into the zone earlier in this window and have covered ~one lap.
        if (nearNow || (approached && traveled >= expected * 0.90)) {
            if (traveled <= abandonLen) return LapResult.COMPLETED
        }

        // After two spatially matched laps, allow a wider re-approach for confirmation.
        val radius = activeMatchRadius()
        if (phase == Phase.HYPOTHESIS_THIRD) {
            val weakApproachLimit = maxOf(radius * 1.75, expected * 0.25)
            if (traveled >= expected * 0.90 &&
                traveled <= expected * config.lengthMaxFactor &&
                minDistToAnchorMeters <= weakApproachLimit
            ) {
                return LapResult.COMPLETED
            }
        }

        // Once confirmed, still require a weak re-approach so a straight cool-down
        // jog does not mint dozens of fake laps.
        if (phase == Phase.CONFIRMED) {
            val weakApproachLimit = maxOf(radius * 1.25, expected * 0.30)
            if (traveled >= expected * 0.90 &&
                traveled <= expected * config.lengthMaxFactor &&
                (nearStartZone(tip) || minDistToAnchorMeters <= weakApproachLimit)
            ) {
                return LapResult.COMPLETED
            }
        }

        if (traveled > abandonLen) return LapResult.MISSED_WINDOW
        return LapResult.TOO_EARLY
    }

    private fun abandonHypothesis() {
        hypothesisRejectedCount += 1
        missStreak = 0
        // Keep ~900 m of recent path available for rediscovery (≈2 stadium laps).
        var idx = points.lastIndex
        var lookback = 0.0
        while (idx > 0 && lookback < 900.0) {
            lookback += GeoMath.haversineMeters(points[idx - 1], points[idx])
            idx--
        }
        learnFromIndex = idx
        phase = Phase.LEARNING
        completedLaps = 0
        template = null
        anchor = null
        expectedLapMeters = null
        set1 = null
        set2 = null
        minDistToAnchorMeters = Double.POSITIVE_INFINITY
        currentLapStartIndex = learnFromIndex
    }

    private data class Closure(val startIndex: Int, val lapLen: Double)

    private fun findLoopClosure(searchFromIndex: Int): Closure? {
        if (points.size < 8) return null
        val tip = points.last()
        val tipCum = cumulativeDistanceTo(points.lastIndex)

        // Prefer lap length near 400 m (stadium), then any valid length.
        var best: Closure? = null
        var bestScore = Double.POSITIVE_INFINITY
        var cum = if (searchFromIndex <= 0) 0.0 else cumulativeDistanceTo(searchFromIndex)

        for (i in searchFromIndex until points.lastIndex) {
            if (i > searchFromIndex) {
                cum += GeoMath.haversineMeters(points[i - 1], points[i])
            }
            val lapLen = tipCum - cum
            if (lapLen < config.minLapMeters || lapLen > config.maxLapMeters) continue
            if (GeoMath.haversineMeters(points[i], tip) > config.learningMatchRadiusMeters) continue
            if (points.size - i < 8) continue
            val extent = GeoMath.pathExtentMeters(points.subList(i, points.size))
            if (extent < config.minLapExtentMeters) continue

            // Score: distance from classic 400 m track, slight penalty for very long loops.
            val score = kotlin.math.abs(lapLen - 400.0) + if (lapLen > 800) (lapLen - 800) * 0.15 else 0.0
            if (score < bestScore) {
                bestScore = score
                best = Closure(i, lapLen)
            }
        }
        return best
    }

    private fun cumulativeDistanceTo(index: Int): Double {
        var sum = 0.0
        for (i in 1..index) {
            sum += GeoMath.haversineMeters(points[i - 1], points[i])
        }
        return sum
    }

    fun canSaveAsRing(): Boolean = phase == Phase.CONFIRMED && completedLaps >= 3

    fun detectionFailedOnStop(): Boolean = !knownMode && phase != Phase.CONFIRMED
}
