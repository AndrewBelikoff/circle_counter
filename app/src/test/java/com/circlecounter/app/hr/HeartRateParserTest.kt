package com.circlecounter.app.hr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeartRateParserTest {
    @Test
    fun parsesUint8Bpm() {
        assertEquals(142, HeartRateParser.parseBpm(byteArrayOf(0x00, 142.toByte())))
    }

    @Test
    fun parsesUint16Bpm() {
        // flags bit0 = 1 → uint16 little-endian 300 would be invalid; use 156
        assertEquals(156, HeartRateParser.parseBpm(byteArrayOf(0x01, 156.toByte(), 0x00)))
    }

    @Test
    fun rejectsEmpty() {
        assertNull(HeartRateParser.parseBpm(byteArrayOf()))
    }
}
