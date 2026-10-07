package com.circlecounter.app.hr

/**
 * Parses Bluetooth SIG Heart Rate Measurement (0x2A37).
 */
object HeartRateParser {
    fun parseBpm(value: ByteArray): Int? {
        if (value.isEmpty()) return null
        val flags = value[0].toInt() and 0xFF
        val uint16 = flags and 0x01 != 0
        return if (uint16) {
            if (value.size < 3) return null
            ((value[1].toInt() and 0xFF) or ((value[2].toInt() and 0xFF) shl 8))
                .takeIf { it in 1..250 }
        } else {
            if (value.size < 2) return null
            (value[1].toInt() and 0xFF).takeIf { it in 1..250 }
        }
    }
}
