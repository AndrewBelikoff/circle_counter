package com.circlecounter.app.data

import android.content.Context
import com.circlecounter.app.domain.GeoPoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last [KEEP] start→stop run logs on shared app external storage
 * so they can be copied via USB/MTP without an in-app UI:
 * `Android/data/com.circlecounter.app/files/run_logs/`.
 */
class RunLogStore(context: Context) {
    private val dir: File = File(context.getExternalFilesDir(null), DIR_NAME).also { it.mkdirs() }
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun save(
        startedAtMs: Long,
        endedAtMs: Long,
        elapsedMs: Long,
        completedLaps: Int,
        lapsConfirmed: Boolean,
        trackName: String?,
        points: List<GeoPoint>,
    ): File {
        val stamp = fileStamp.format(Date(startedAtMs))
        val file = File(dir, "run_$stamp.json")
        val payload = RunLogFile(
            startedAtMs = startedAtMs,
            endedAtMs = endedAtMs,
            elapsedMs = elapsedMs,
            completedLaps = completedLaps,
            lapsConfirmed = lapsConfirmed,
            trackName = trackName,
            pointCount = points.size,
            points = points.map { LogPoint.from(it) },
        )
        file.writeText(json.encodeToString(payload))
        prune()
        return file
    }

    private fun prune() {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("run_") && f.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        files.drop(KEEP).forEach { it.delete() }
    }

    @Serializable
    data class RunLogFile(
        val startedAtMs: Long,
        val endedAtMs: Long,
        val elapsedMs: Long,
        val completedLaps: Int,
        val lapsConfirmed: Boolean,
        val trackName: String? = null,
        val pointCount: Int,
        val points: List<LogPoint>,
    )

    @Serializable
    data class LogPoint(
        val lat: Double,
        val lon: Double,
        val t: Long,
        val acc: Float? = null,
        val speed: Float? = null,
    ) {
        companion object {
            fun from(p: GeoPoint) = LogPoint(
                lat = p.latitude,
                lon = p.longitude,
                t = p.timestampMs,
                acc = p.accuracyMeters,
                speed = p.speedMps,
            )
        }
    }

    companion object {
        const val DIR_NAME = "run_logs"
        const val KEEP = 30
        private val fileStamp = SimpleDateFormat("yy-MM-dd_HH-mm-ss", Locale.US)
    }
}
