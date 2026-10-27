package com.circlecounter.app.ui

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.circlecounter.app.CircleCounterApp
import com.circlecounter.app.data.GpxExporter
import com.circlecounter.app.data.KnownTrack
import com.circlecounter.app.data.RunLogStore
import com.circlecounter.app.data.SavedRun
import com.circlecounter.app.data.StoredPoint
import com.circlecounter.app.data.TrackRepository
import com.circlecounter.app.domain.DistanceBreakdown
import com.circlecounter.app.domain.DistanceCalculator
import com.circlecounter.app.domain.DistanceFormatter
import com.circlecounter.app.domain.GeoPoint
import com.circlecounter.app.domain.GpsFilter
import com.circlecounter.app.domain.InstantPaceEstimator
import com.circlecounter.app.domain.LapDetector
import com.circlecounter.app.domain.LapStat
import com.circlecounter.app.domain.LapStatsCalculator
import com.circlecounter.app.domain.PaceCalculator
import com.circlecounter.app.domain.StartGateWarmup
import com.circlecounter.app.domain.TimedSample
import com.circlecounter.app.hr.HeartRateManager
import com.circlecounter.app.hr.HeartRateState
import com.circlecounter.app.hr.HrConnectionState
import com.circlecounter.app.hr.HrDevice
import com.circlecounter.app.location.LocationTrackingService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

enum class GpsQuality {
    /** Fix present and accuracy is usable for lap tracking. */
    GOOD,
    /** Fix present but noisy / coarse. */
    POOR,
    /** No recent fix. */
    ABSENT,
}

data class RunUiState(
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val selectedTrack: KnownTrack? = null,
    val phase: LapDetector.Phase = LapDetector.Phase.LEARNING,
    val completedLaps: Int = 0,
    val elapsedMs: Long = 0L,
    val distances: DistanceBreakdown = DistanceBreakdown(0.0, null, null, 0, 0.0),
    val paceGps: String = "—:—/км",
    val paceManual: String = "—:—/км",
    val paceComputed: String = "—:—/км",
    /** Near-term pace `m:ss` (fused speed / ~4 s window), not whole-run average. */
    val paceMetricText: String = "—:—",
    /** Live cadence, steps/min. */
    val cadenceSpm: Int? = null,
    val autoCountActive: Boolean = false,
    val statusText: String = "Выберите трек или начните новый",
    val finished: FinishedRun? = null,
    val knownTracks: List<KnownTrack> = emptyList(),
    val manualLapLengthInput: String = "",
    val heartRate: HeartRateState = HeartRateState(),
    val avgHeartRateBpm: Int? = null,
    val maxHeartRateBpm: Int? = null,
    val gpsQuality: GpsQuality = GpsQuality.ABSENT,
    val gpsAccuracyMeters: Float? = null,
)

data class FinishedRun(
    val run: SavedRun,
    val detectionFailed: Boolean,
    /** New unnamed run with confirmed laps — user must choose add / save / cancel. */
    val needsTrackDecision: Boolean,
    val ringTemplate: List<GeoPoint>?,
    val gpx: String,
    val lapStats: List<LapStat> = emptyList(),
    val decisionDone: Boolean = false,
    val decisionMessage: String? = null,
)

class RunViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = (application as CircleCounterApp).trackRepository
    private val runLogStore = RunLogStore(application)
    private val heartRateManager = HeartRateManager(application, viewModelScope)

    private val _ui = MutableStateFlow(RunUiState())
    val ui: StateFlow<RunUiState> = _ui.asStateFlow()

    private var detector: LapDetector? = null
    private var gpsFilter: GpsFilter? = null
    private var pointsJob: Job? = null
    private var tickerJob: Job? = null
    private var startedAtMs = 0L
    private var accumulatedElapsedMs = 0L
    private var segmentStartedAtMs = 0L
    private var lastSnapshot: LapDetector.Snapshot? = null
    private var hrMax = 0
    private var lastGpsAtMs = 0L
    private val instantPace = InstantPaceEstimator()
    private val gateWarmup = StartGateWarmup()
    private val rawLogPoints = mutableListOf<GeoPoint>()
    private val hrTimeline = mutableListOf<TimedSample>()
    private var manualLengthHydrated = false

    companion object {
        private const val GPS_STALE_MS = 4_000L
        private const val GPS_GOOD_ACCURACY_M = 18f
        private const val GPS_POOR_ACCURACY_M = 45f
    }

    init {
        viewModelScope.launch { repo.compactIfNeeded() }
        viewModelScope.launch {
            repo.store.collect { store ->
                val preferred = store.preferredHrSensor
                heartRateManager.setPreferred(preferred?.address, preferred?.name)
                _ui.update { ui ->
                    val hydrated = if (!manualLengthHydrated && !ui.isRunning) {
                        manualLengthHydrated = true
                        formatManualInput(
                            ui.selectedTrack?.manualLapLengthMeters
                                ?: store.manualLapLengthMeters,
                        )
                    } else {
                        ui.manualLapLengthInput
                    }
                    ui.copy(
                        knownTracks = store.knownTracks.sortedByDescending { t -> t.updatedAtMs },
                        manualLapLengthInput = hydrated,
                        selectedTrack = ui.selectedTrack?.id?.let { id ->
                            store.knownTracks.find { it.id == id } ?: ui.selectedTrack
                        },
                    )
                }
            }
        }
        viewModelScope.launch {
            heartRateManager.state.collect { hr ->
                _ui.update { it.copy(heartRate = hr) }
            }
        }
        viewModelScope.launch {
            heartRateManager.samples.collect { sample ->
                if (!_ui.value.isRunning || _ui.value.isPaused) return@collect
                if (sample.bpm > hrMax) hrMax = sample.bpm
                hrTimeline += TimedSample(sample.tMs, sample.bpm)
                val avg = LapStatsCalculator.timeWeightedBpm(hrTimeline, startedAtMs, sample.tMs)
                _ui.update {
                    it.copy(
                        avgHeartRateBpm = avg,
                        maxHeartRateBpm = hrMax.takeIf { m -> m > 0 },
                    )
                }
            }
        }
    }

    override fun onCleared() {
        heartRateManager.release()
        super.onCleared()
    }

    fun startHrScan() = heartRateManager.startScan()

    fun stopHrScan() = heartRateManager.stopScan()

    fun selectHrDevice(device: HrDevice) {
        viewModelScope.launch {
            repo.setPreferredHrSensor(device.address, device.name)
        }
        heartRateManager.selectAndConnect(device)
    }

    fun clearHrDevice() {
        viewModelScope.launch { repo.setPreferredHrSensor(null, null) }
        heartRateManager.disconnect(clearPreferred = true)
    }

    fun connectPreferredHr() = heartRateManager.connectPreferred()

    fun selectTrack(track: KnownTrack?) {
        if (_ui.value.isRunning) return
        val global = repo.store.value.manualLapLengthMeters
        val length = track?.manualLapLengthMeters ?: global
        _ui.update {
            it.copy(
                selectedTrack = track,
                manualLapLengthInput = formatManualInput(length),
                statusText = if (track == null) {
                    "Новый трек — круг = возврат к точке Старта"
                } else {
                    "Трек «${track.name}» — круг = возврат к точке Старта"
                },
            )
        }
    }

    fun setManualLapLengthInput(value: String) {
        val filtered = value.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' }
        _ui.update { it.copy(manualLapLengthInput = filtered) }
        val trimmed = filtered.trim()
        val meters = parseManualLength(filtered)
        // "4" and "40" are not lengths yet — don't wipe the saved 400 while typing.
        if (trimmed.isNotEmpty() && meters == null) return
        viewModelScope.launch {
            repo.setManualLapLength(meters, trackId = _ui.value.selectedTrack?.id)
        }
    }

    private fun formatManualInput(meters: Double?): String {
        if (meters == null) return ""
        return if (meters % 1.0 == 0.0) meters.toInt().toString() else meters.toString()
    }

    fun start() {
        if (_ui.value.isRunning) return
        val track = _ui.value.selectedTrack
        val manualHint = parseManualLength()
            ?: track?.manualLapLengthMeters
            ?: repo.store.value.manualLapLengthMeters
        detector = LapDetector(
            knownTemplate = track?.templatePoints(),
            lapLengthHintMeters = manualHint,
        )
        gpsFilter = GpsFilter(
            motionRms = { from, to -> LocationTrackingService.sharedMotion?.rmsBetween(from, to) },
        )
        instantPace.reset()
        gateWarmup.reset()
        rawLogPoints.clear()
        hrTimeline.clear()
        startedAtMs = System.currentTimeMillis()
        accumulatedElapsedMs = 0L
        segmentStartedAtMs = startedAtMs
        lastSnapshot = detector?.snapshot()
        hrMax = 0

        val app = getApplication<Application>()
        ContextCompat.startForegroundService(
            app,
            Intent(app, LocationTrackingService::class.java),
        )

        // Keep / reconnect HW9 for the whole run.
        if (_ui.value.heartRate.deviceAddress != null &&
            _ui.value.heartRate.connection != HrConnectionState.CONNECTED &&
            _ui.value.heartRate.connection != HrConnectionState.CONNECTING
        ) {
            heartRateManager.connectPreferred()
        }

        pointsJob?.cancel()
        pointsJob = viewModelScope.launch {
            LocationTrackingService.points.collect { point ->
                onPoint(point)
            }
        }

        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            while (isActive) {
                refreshTimeOnly()
                delay(250)
            }
        }

        lastGpsAtMs = 0L
        _ui.update {
            it.copy(
                isRunning = true,
                isPaused = false,
                finished = null,
                avgHeartRateBpm = null,
                maxHeartRateBpm = null,
                statusText = if (track == null) "Запись нового трека…" else "Запись «${track.name}»…",
                autoCountActive = track != null,
                gpsQuality = GpsQuality.ABSENT,
                gpsAccuracyMeters = null,
                paceMetricText = "—:—",
                cadenceSpm = null,
            )
        }
        publishFromSnapshot(lastSnapshot ?: return)
    }

    fun pause() {
        if (!_ui.value.isRunning || _ui.value.isPaused) return
        accumulatedElapsedMs += System.currentTimeMillis() - segmentStartedAtMs
        _ui.update { it.copy(isPaused = true, statusText = "Пауза") }
    }

    fun resume() {
        if (!_ui.value.isRunning || !_ui.value.isPaused) return
        segmentStartedAtMs = System.currentTimeMillis()
        _ui.update { it.copy(isPaused = false, statusText = "Запись…") }
    }

    fun stop() {
        if (!_ui.value.isRunning) return
        val elapsed = currentElapsedMs()
        pointsJob?.cancel()
        tickerJob?.cancel()
        stopLocationService()

        // Flush warmup and the filter's held point into the detector.
        val filter = gpsFilter
        if (filter != null) {
            for (raw in gateWarmup.flush()) {
                for (accepted in filter.offer(raw)) onFilteredPoint(accepted)
            }
            filter.flush().forEach { onFilteredPoint(it) }
        }

        val snap = detector?.finish() ?: lastSnapshot
        val det = detector
        val track = _ui.value.selectedTrack
        val manualLen = parseManualLength()
            ?: track?.manualLapLengthMeters
            ?: repo.store.value.manualLapLengthMeters
        val auto = snap?.let { it.phase == LapDetector.Phase.CONFIRMED } == true
        val distances = if (snap != null) {
            DistanceCalculator.breakdown(snap, manualLen, auto)
        } else {
            DistanceBreakdown(0.0, null, null, 0, 0.0)
        }

        val detectionFailed = det?.detectionFailedOnStop() == true
        val lapsConfirmed = det?.canSaveAsRing() == true ||
            (track != null && snap?.phase == LapDetector.Phase.CONFIRMED)
        // Persist filtered points (not raw GPS junk).
        val points = snap?.points.orEmpty()
        val runRing = det?.runRing()
        val template = runRing?.template ?: snap?.template
        val splits = snap?.lapSplits.orEmpty()
        val cadence = LocationTrackingService.sharedCadence
        val lapStats = LapStatsCalculator.build(
            splits = splits,
            points = points,
            manualLapLengthMeters = manualLen,
            heartSamples = hrTimeline.toList(),
            cadenceByRange = { from, to -> cadence?.averageCadenceSpm(from, to) },
        )

        val avgHr = LapStatsCalculator.timeWeightedBpm(
            hrTimeline,
            startedAtMs,
            System.currentTimeMillis(),
        )
        val maxHr = hrMax.takeIf { it > 0 }

        val run = SavedRun(
            id = UUID.randomUUID().toString(),
            trackId = track?.id,
            trackName = track?.name,
            startedAtMs = startedAtMs,
            endedAtMs = System.currentTimeMillis(),
            elapsedMs = elapsed,
            completedLaps = snap?.completedLaps ?: 0,
            lapsConfirmed = lapsConfirmed,
            gpsDistanceMeters = distances.gpsMeters,
            manualDistanceMeters = distances.manualMeters,
            computedDistanceMeters = distances.computedMeters,
            points = points.map { StoredPoint.from(it) },
            avgHeartRateBpm = avgHr,
            maxHeartRateBpm = maxHr,
        )

        val gpx = GpxExporter.toGpx(
            points = points,
            name = track?.name ?: "Новый трек",
            startedAtMs = startedAtMs,
        )

        // Unnamed start + confirmed laps → user decides; known start → auto-refine.
        val needsDecision = track == null && lapsConfirmed && template != null

        val logPoints = rawLogPoints.toList()
        val logHeart = hrTimeline.toList()
        viewModelScope.launch {
            repo.addRun(run)
            runCatching {
                runLogStore.save(
                    startedAtMs = startedAtMs,
                    endedAtMs = run.endedAtMs,
                    elapsedMs = elapsed,
                    completedLaps = run.completedLaps,
                    lapsConfirmed = lapsConfirmed,
                    trackName = track?.name,
                    points = logPoints.ifEmpty { points },
                    heartSamples = logHeart,
                )
            }
            if (track != null && lapsConfirmed && runRing != null) {
                repo.refineTrack(
                    trackId = track.id,
                    newRing = runRing.template,
                    lapsUsed = runRing.lapsUsed,
                    manualLapLengthMeters = manualLen,
                )
                val manual = parseManualLength()
                if (manual != null) {
                    repo.updateManualLength(track.id, manual)
                }
            }
        }

        detector = null
        gpsFilter = null
        rawLogPoints.clear()
        instantPace.reset()
        _ui.update {
            it.copy(
                isRunning = false,
                isPaused = false,
                finished = FinishedRun(
                    run = run,
                    detectionFailed = detectionFailed && track == null,
                    needsTrackDecision = needsDecision,
                    ringTemplate = template,
                    gpx = gpx,
                    lapStats = lapStats,
                ),
                statusText = when {
                    needsDecision -> "Круги найдены — сохраните трек"
                    detectionFailed && track == null -> "Не удалось определить круги"
                    else -> "Тренировка завершена"
                },
                elapsedMs = elapsed,
                distances = distances,
            )
        }
    }

    fun addFinishedToExisting(trackId: String) {
        val finished = _ui.value.finished ?: return
        if (!finished.needsTrackDecision || finished.decisionDone) return
        val template = finished.ringTemplate ?: return
        viewModelScope.launch {
            val updated = repo.refineTrack(
                trackId = trackId,
                newRing = template,
                manualLapLengthMeters = parseManualLength(),
            )
            val manual = parseManualLength()
            if (manual != null) {
                repo.updateManualLength(trackId, manual)
            }
            _ui.update {
                it.copy(
                    selectedTrack = updated,
                    finished = finished.copy(
                        needsTrackDecision = false,
                        decisionDone = true,
                        decisionMessage = updated?.let { t -> "Добавлено к «${t.name}»" }
                            ?: "Трек обновлён",
                    ),
                    statusText = updated?.let { t -> "Добавлено к «${t.name}»" } ?: "Готово",
                )
            }
        }
    }

    fun saveFinishedAsNew(name: String) {
        val finished = _ui.value.finished ?: return
        if (!finished.needsTrackDecision || finished.decisionDone) return
        val template = finished.ringTemplate ?: return
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val manual = parseManualLength()
            val track = repo.saveNewRing(
                name = trimmed,
                template = template,
                manualLapLengthMeters = manual,
                autoNamed = false,
            )
            _ui.update {
                it.copy(
                    selectedTrack = track,
                    finished = finished.copy(
                        needsTrackDecision = false,
                        decisionDone = true,
                        decisionMessage = "Сохранено как «${track.name}»",
                    ),
                    statusText = "Сохранено как «${track.name}»",
                )
            }
        }
    }

    fun cancelFinishedTrackDecision() {
        val finished = _ui.value.finished ?: return
        if (!finished.needsTrackDecision || finished.decisionDone) return
        val template = finished.ringTemplate ?: return
        viewModelScope.launch {
            val autoName = TrackRepository.autoNameForStart(finished.run.startedAtMs)
            val manual = parseManualLength()
            val track = repo.saveNewRing(
                name = autoName,
                template = template,
                manualLapLengthMeters = manual,
                autoNamed = true,
            )
            _ui.update {
                it.copy(
                    selectedTrack = null,
                    finished = finished.copy(
                        needsTrackDecision = false,
                        decisionDone = true,
                        decisionMessage = "Сохранено как «${track.name}» (авто)",
                    ),
                    statusText = "Сохранено как «${track.name}»",
                )
            }
        }
    }

    fun clearFinished() {
        val finished = _ui.value.finished ?: return
        // Must resolve add/save/cancel before leaving when laps were found on a new track.
        if (finished.needsTrackDecision && !finished.decisionDone) return
        _ui.update { it.copy(finished = null) }
    }

    private fun onPoint(point: GeoPoint) {
        lastGpsAtMs = System.currentTimeMillis()
        rawLogPoints += point
        instantPace.onFix(point)
        _ui.update {
            it.copy(
                gpsQuality = classifyGps(point.accuracyMeters, fresh = true),
                gpsAccuracyMeters = point.accuracyMeters,
                paceMetricText = instantPace.paceMetricText(),
            )
        }
        val filter = gpsFilter ?: return
        for (raw in gateWarmup.offer(point)) {
            for (accepted in filter.offer(raw)) {
                onFilteredPoint(accepted)
            }
        }
    }

    private fun classifyGps(accuracyMeters: Float?, fresh: Boolean): GpsQuality {
        if (!fresh) return GpsQuality.ABSENT
        val acc = accuracyMeters ?: return GpsQuality.POOR
        return when {
            acc <= GPS_GOOD_ACCURACY_M -> GpsQuality.GOOD
            acc <= GPS_POOR_ACCURACY_M -> GpsQuality.POOR
            else -> GpsQuality.ABSENT
        }
    }

    private fun onFilteredPoint(point: GeoPoint) {
        val det = detector ?: return
        val snap = det.addPoint(point)
        lastSnapshot = snap
        publishFromSnapshot(snap)
    }

    private fun publishFromSnapshot(snap: LapDetector.Snapshot) {
        val track = _ui.value.selectedTrack
        val manualLen = parseManualLength()
            ?: track?.manualLapLengthMeters
            ?: repo.store.value.manualLapLengthMeters
        val auto = snap.phase == LapDetector.Phase.CONFIRMED
        val distances = DistanceCalculator.breakdown(snap, manualLen, auto)
        val elapsed = currentElapsedMs()

        val status = when (snap.phase) {
            LapDetector.Phase.LEARNING -> "Ищем повторение маршрута…"
            LapDetector.Phase.HYPOTHESIS_SECOND -> "Гипотеза: идёт 2-й круг"
            LapDetector.Phase.HYPOTHESIS_THIRD -> "Гипотеза: идёт 3-й круг"
            LapDetector.Phase.CONFIRMED -> "Круги подтверждены"
        }

        _ui.update {
            it.copy(
                phase = snap.phase,
                completedLaps = snap.completedLaps,
                elapsedMs = elapsed,
                distances = distances,
                autoCountActive = auto,
                paceGps = PaceCalculator.formatPace(
                    PaceCalculator.paceSecPerKm(distances.gpsMeters, elapsed)
                ),
                paceManual = PaceCalculator.formatPace(
                    distances.manualMeters?.let { m -> PaceCalculator.paceSecPerKm(m, elapsed) }
                ),
                paceComputed = PaceCalculator.formatPace(
                    distances.computedMeters?.let { m -> PaceCalculator.paceSecPerKm(m, elapsed) }
                ),
                paceMetricText = instantPace.paceMetricText(),
                cadenceSpm = LocationTrackingService.sharedCadence?.cadenceSpm(),
                statusText = if (it.isPaused) "Пауза" else status,
            )
        }
    }

    private fun refreshTimeOnly() {
        if (!_ui.value.isRunning || _ui.value.isPaused) return
        val elapsed = currentElapsedMs()
        val d = _ui.value.distances
        val gpsFresh = lastGpsAtMs > 0L &&
            System.currentTimeMillis() - lastGpsAtMs <= GPS_STALE_MS
        _ui.update {
            it.copy(
                elapsedMs = elapsed,
                paceGps = PaceCalculator.formatPace(PaceCalculator.paceSecPerKm(d.gpsMeters, elapsed)),
                paceManual = PaceCalculator.formatPace(
                    d.manualMeters?.let { m -> PaceCalculator.paceSecPerKm(m, elapsed) }
                ),
                paceComputed = PaceCalculator.formatPace(
                    d.computedMeters?.let { m -> PaceCalculator.paceSecPerKm(m, elapsed) }
                ),
                paceMetricText = instantPace.paceMetricText(),
                cadenceSpm = LocationTrackingService.sharedCadence?.cadenceSpm(),
                gpsQuality = classifyGps(it.gpsAccuracyMeters, fresh = gpsFresh),
            )
        }
    }

    private fun currentElapsedMs(): Long {
        if (!_ui.value.isRunning) return _ui.value.elapsedMs
        return if (_ui.value.isPaused) {
            accumulatedElapsedMs
        } else {
            accumulatedElapsedMs + (System.currentTimeMillis() - segmentStartedAtMs)
        }
    }

    private fun parseManualLength(rawInput: String = _ui.value.manualLapLengthInput): Double? {
        val raw = rawInput.replace(',', '.').trim()
        if (raw.isEmpty()) return null
        return raw.toDoubleOrNull()?.takeIf { it in 50.0..100_000.0 }
    }

    private fun stopLocationService() {
        val app = getApplication<Application>()
        app.stopService(
            Intent(app, LocationTrackingService::class.java).apply {
                action = LocationTrackingService.ACTION_STOP
            },
        )
    }

    fun formatElapsed(): String = DistanceFormatter.formatElapsed(_ui.value.elapsedMs)
}
