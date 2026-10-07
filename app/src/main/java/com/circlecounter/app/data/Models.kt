package com.circlecounter.app.data

import com.circlecounter.app.domain.GeoPoint
import kotlinx.serialization.Serializable

@Serializable
data class KnownTrack(
    val id: String,
    val name: String,
    val template: List<StoredPoint>,
    /** Official / documented lap length entered by user, meters. */
    val manualLapLengthMeters: Double? = null,
    /** GPS-derived average lap length from accumulated runs. */
    val computedLapLengthMeters: Double? = null,
    val runCount: Int = 0,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
    /**
     * Auto-saved after Stop→Cancel when laps were confirmed but the user
     * did not name the ring or merge it into an existing one.
     * At most [MAX_AUTO_NAMED] such tracks are kept.
     */
    val autoNamed: Boolean = false,
) {
    fun templatePoints(): List<GeoPoint> = template.map { it.toGeoPoint() }

    companion object {
        const val MAX_AUTO_NAMED = 10
    }
}

@Serializable
data class StoredPoint(
    val lat: Double,
    val lon: Double,
    val t: Long = 0L,
) {
    fun toGeoPoint(): GeoPoint = GeoPoint(lat, lon, t)

    companion object {
        fun from(p: GeoPoint) = StoredPoint(p.latitude, p.longitude, p.timestampMs)
    }
}

@Serializable
data class SavedRun(
    val id: String,
    val trackId: String?,
    val trackName: String?,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val elapsedMs: Long,
    val completedLaps: Int,
    val lapsConfirmed: Boolean,
    val gpsDistanceMeters: Double,
    val manualDistanceMeters: Double?,
    val computedDistanceMeters: Double?,
    val points: List<StoredPoint>,
    val avgHeartRateBpm: Int? = null,
    val maxHeartRateBpm: Int? = null,
)

@Serializable
data class PreferredHrSensor(
    val address: String,
    val name: String,
)

@Serializable
data class AppStore(
    val knownTracks: List<KnownTrack> = emptyList(),
    val runs: List<SavedRun> = emptyList(),
    val preferredHrSensor: PreferredHrSensor? = null,
)
