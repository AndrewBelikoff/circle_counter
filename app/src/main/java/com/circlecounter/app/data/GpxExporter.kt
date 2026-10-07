package com.circlecounter.app.data

import com.circlecounter.app.domain.GeoPoint
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object GpxExporter {
    private val iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    fun toGpx(
        points: List<GeoPoint>,
        name: String,
        startedAtMs: Long,
    ): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<gpx version="1.1" creator="CircleCounter" xmlns="http://www.topografix.com/GPX/1/1">""").append('\n')
        sb.append("  <trk>\n")
        sb.append("    <name>").append(escape(name)).append("</name>\n")
        sb.append("    <trkseg>\n")
        for (p in points) {
            val t = if (p.timestampMs > 0) p.timestampMs else startedAtMs
            val time = Instant.ofEpochMilli(t).atOffset(ZoneOffset.UTC).format(iso)
            sb.append("      <trkpt lat=\"").append(p.latitude).append("\" lon=\"")
                .append(p.longitude).append("\">\n")
            sb.append("        <time>").append(time).append("</time>\n")
            sb.append("      </trkpt>\n")
        }
        sb.append("    </trkseg>\n")
        sb.append("  </trk>\n")
        sb.append("</gpx>\n")
        return sb.toString()
    }

    private fun escape(value: String): String =
        value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
