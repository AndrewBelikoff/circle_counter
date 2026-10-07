package com.circlecounter.app.data

import android.content.Context
import com.circlecounter.app.domain.GeoMath
import com.circlecounter.app.domain.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private fun loadSync(): AppStore {
        if (!file.exists()) return AppStore()
        return runCatching {
            json.decodeFromString<AppStore>(file.readText())
        }.getOrElse { AppStore() }
    }

    private suspend fun persist(store: AppStore) = withContext(Dispatchers.IO) {
        file.writeText(json.encodeToString(store))
        _store.value = store
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
        val computed = GeoMath.pathLengthMeters(template)
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
        val store = _store.value
        var list = store.knownTracks + track
        if (autoNamed) {
            list = pruneAutoNamed(list)
        }
        persist(store.copy(knownTracks = list))
        return track
    }

    /**
     * Merge a confirmed lap template into an existing track and bump freshness.
     */
    suspend fun refineTrack(trackId: String, newLapTemplate: List<GeoPoint>): KnownTrack? {
        val store = _store.value
        val idx = store.knownTracks.indexOfFirst { it.id == trackId }
        if (idx < 0) return null
        val old = store.knownTracks[idx]
        val merged = GeoMath.averagePaths(
            listOf(old.templatePoints(), newLapTemplate),
            samples = 64,
        )
        val updated = old.copy(
            template = merged.map { StoredPoint.from(it) },
            computedLapLengthMeters = GeoMath.pathLengthMeters(merged),
            runCount = old.runCount + 1,
            updatedAtMs = System.currentTimeMillis(),
        )
        val list = store.knownTracks.toMutableList()
        list[idx] = updated
        persist(store.copy(knownTracks = list))
        return updated
    }

    suspend fun updateManualLength(trackId: String, meters: Double?) {
        val store = _store.value
        val list = store.knownTracks.map {
            if (it.id == trackId) it.copy(
                manualLapLengthMeters = meters,
                updatedAtMs = System.currentTimeMillis(),
            ) else it
        }
        persist(store.copy(knownTracks = list))
    }

    /** Rename; giving a custom name clears [KnownTrack.autoNamed]. */
    suspend fun renameTrack(trackId: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val store = _store.value
        val list = store.knownTracks.map {
            if (it.id == trackId) it.copy(
                name = trimmed,
                autoNamed = false,
                updatedAtMs = System.currentTimeMillis(),
            ) else it
        }
        persist(store.copy(knownTracks = list))
    }

    suspend fun deleteTrack(trackId: String) {
        val store = _store.value
        persist(store.copy(knownTracks = store.knownTracks.filterNot { it.id == trackId }))
    }

    suspend fun addRun(run: SavedRun) {
        val store = _store.value
        persist(store.copy(runs = (listOf(run) + store.runs).take(100)))
    }

    suspend fun setPreferredHrSensor(address: String?, name: String?) {
        val store = _store.value
        val sensor = if (address != null) {
            PreferredHrSensor(address = address, name = name?.ifBlank { address } ?: address)
        } else {
            null
        }
        persist(store.copy(preferredHrSensor = sensor))
    }

    companion object {
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
