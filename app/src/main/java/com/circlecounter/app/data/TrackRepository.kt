package com.circlecounter.app.data

import android.content.Context
import com.circlecounter.app.domain.GeoMath
import com.circlecounter.app.domain.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class TrackRepository(context: Context) {
    private val file = File(context.filesDir, "circle_counter_store.json")
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val _store = MutableStateFlow(loadSync())
    val store: StateFlow<AppStore> = _store.asStateFlow()
    private val writeMutex = Mutex()
    private var compactPending = false

    private fun loadSync(): AppStore {
        if (!file.exists()) return AppStore()
        val raw = runCatching {
            json.decodeFromString<AppStore>(file.readText())
        }.getOrElse { return AppStore() }
        compactPending = raw.runs.any { it.points.isNotEmpty() }
        return raw.copy(runs = raw.runs.map { it.copy(points = emptyList()) })
    }

    /** One writer at a time. [transform] returns null to leave the file unchanged. */
    private suspend fun mutate(transform: (AppStore) -> AppStore?) {
        writeMutex.withLock {
            val next = transform(_store.value)?.let { stripPoints(it) } ?: return
            persist(next)
        }
    }

    private fun stripPoints(store: AppStore): AppStore {
        if (store.runs.all { it.points.isEmpty() }) return store
        return store.copy(runs = store.runs.map { it.copy(points = emptyList()) })
    }

    private suspend fun persist(store: AppStore) = withContext(Dispatchers.IO) {
        file.writeText(json.encodeToString(store))
        _store.value = store
    }

    /** Rewrite the store once if an older version still had full GPS polylines on disk. */
    suspend fun compactIfNeeded() {
        if (!compactPending) return
        writeMutex.withLock {
            if (!compactPending) return
            persist(stripPoints(_store.value))
            compactPending = false
        }
    }

    /** Freshest first (updatedAtMs desc). */
    fun tracksSorted(): List<KnownTrack> =
        _store.value.knownTracks.sortedByDescending { it.updatedAtMs }

    suspend fun getTrack(id: String): KnownTrack? =
        _store.value.knownTracks.find { it.id == id }

    suspend fun saveNewRing(
        name: String,
        template: List<GeoPoint>,
        manualLapLengthMeters: Double?,
        autoNamed: Boolean = false,
    ): KnownTrack {
        val now = System.currentTimeMillis()
        val computed = GeoMath.ringLengthMeters(template)
        val track = KnownTrack(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            template = template.map { StoredPoint.from(it) },
            manualLapLengthMeters = manualLapLengthMeters,
            computedLapLengthMeters = computed,
            runCount = 1,
            createdAtMs = now,
            updatedAtMs = now,
            autoNamed = autoNamed,
        )
        var saved = track
        mutate { store ->
            var list = store.knownTracks + track
            if (autoNamed) list = pruneAutoNamed(list)
            saved = list.find { it.id == track.id } ?: track
            store.copy(knownTracks = list)
        }
        return saved
    }

    /**
     * Merge this run's ring into an existing track and bump freshness.
     *
     * The stored shape carries the weight of previous runs; a ring whose length
     * disagrees with the manual lap length is not learned from, and a stored
     * shape that disagrees with it is replaced by the new ring.
     */
    suspend fun refineTrack(
        trackId: String,
        newRing: List<GeoPoint>,
        lapsUsed: Int = 1,
        manualLapLengthMeters: Double? = null,
    ): KnownTrack? {
        var result: KnownTrack? = null
        mutate { store ->
            val idx = store.knownTracks.indexOfFirst { it.id == trackId }
            if (idx < 0) return@mutate null
            val old = store.knownTracks[idx]
            val manual = manualLapLengthMeters ?: old.manualLapLengthMeters
            fun fitsManual(len: Double) =
                manual == null || len in manual * MANUAL_FIT_MIN..manual * MANUAL_FIT_MAX

            val oldRing = old.templatePoints()
            val newLen = GeoMath.ringLengthMeters(newRing)
            // A ring that disagrees with the manual length must not gain history weight.
            val merged: List<GeoPoint>? = when {
                newRing.size < 3 || !fitsManual(newLen) -> null
                oldRing.size < 3 || !fitsManual(GeoMath.ringLengthMeters(oldRing)) ->
                    GeoMath.resampleClosedRing(newRing, TEMPLATE_SAMPLES)
                else -> GeoMath.averageRings(
                    listOf(oldRing, newRing),
                    samples = TEMPLATE_SAMPLES,
                    weights = listOf(
                        old.runCount.coerceIn(1, MAX_HISTORY_WEIGHT).toDouble(),
                        (lapsUsed / 4.0).coerceIn(0.5, 2.0),
                    ),
                )
            }
            if (merged == null) {
                result = old
                return@mutate null
            }
            val updated = old.copy(
                template = merged.map { StoredPoint.from(it) },
                computedLapLengthMeters = GeoMath.ringLengthMeters(merged),
                runCount = old.runCount + 1,
                updatedAtMs = System.currentTimeMillis(),
            )
            result = updated
            val list = store.knownTracks.toMutableList()
            list[idx] = updated
            store.copy(knownTracks = list)
        }
        return result
    }

    suspend fun updateManualLength(trackId: String, meters: Double?) {
        mutate { store ->
            val list = store.knownTracks.map {
                if (it.id == trackId) it.copy(
                    manualLapLengthMeters = meters,
                    updatedAtMs = System.currentTimeMillis(),
                ) else it
            }
            store.copy(knownTracks = list)
        }
    }

    /** Rename; giving a custom name clears [KnownTrack.autoNamed]. */
    suspend fun renameTrack(trackId: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        mutate { store ->
            val list = store.knownTracks.map {
                if (it.id == trackId) it.copy(
                    name = trimmed,
                    autoNamed = false,
                    updatedAtMs = System.currentTimeMillis(),
                ) else it
            }
            store.copy(knownTracks = list)
        }
    }

    suspend fun deleteTrack(trackId: String) {
        mutate { store ->
            store.copy(knownTracks = store.knownTracks.filterNot { it.id == trackId })
        }
    }

    suspend fun addRun(run: SavedRun) {
        mutate { store ->
            val slim = run.copy(points = emptyList())
            store.copy(runs = (listOf(slim) + store.runs).take(MAX_SAVED_RUNS))
        }
    }

    suspend fun setPreferredHrSensor(address: String?, name: String?) {
        mutate { store ->
            val sensor = if (address != null) {
                PreferredHrSensor(address = address, name = name?.ifBlank { address } ?: address)
            } else {
                null
            }
            store.copy(preferredHrSensor = sensor)
        }
    }

    /** Persist global manual lap length; also stamps the selected track when [trackId] is set. */
    suspend fun setManualLapLength(meters: Double?, trackId: String? = null) {
        mutate { store ->
            val tracks = if (trackId != null) {
                store.knownTracks.map {
                    if (it.id == trackId) {
                        it.copy(
                            manualLapLengthMeters = meters,
                            updatedAtMs = System.currentTimeMillis(),
                        )
                    } else {
                        it
                    }
                }
            } else {
                store.knownTracks
            }
            store.copy(manualLapLengthMeters = meters, knownTracks = tracks)
        }
    }

    companion object {
        private const val TEMPLATE_SAMPLES = 64
        private const val MAX_HISTORY_WEIGHT = 6
        private const val MAX_SAVED_RUNS = 100
        private const val MANUAL_FIT_MIN = 0.85
        private const val MANUAL_FIT_MAX = 1.15

        private val autoNameFormat = SimpleDateFormat("yy-MM-dd_HH:mm", Locale.US)

        fun autoNameForStart(startedAtMs: Long): String =
            autoNameFormat.format(Date(startedAtMs))

        /**
         * Keep at most [KnownTrack.MAX_AUTO_NAMED] auto-named tracks;
         * drop the oldest by [KnownTrack.createdAtMs].
         */
        fun pruneAutoNamed(tracks: List<KnownTrack>): List<KnownTrack> {
            val auto = tracks.filter { it.autoNamed }.sortedBy { it.createdAtMs }
            if (auto.size <= KnownTrack.MAX_AUTO_NAMED) return tracks
            val dropIds = auto
                .take(auto.size - KnownTrack.MAX_AUTO_NAMED)
                .map { it.id }
                .toSet()
            return tracks.filterNot { it.id in dropIds }
        }
    }
}
