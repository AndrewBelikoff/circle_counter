package com.circlecounter.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackRepositoryPruneTest {
    @Test
    fun keepsOnlyTenAutoNamedOldestDropped() {
        val named = KnownTrack(
            id = "named",
            name = "домашний стадион",
            template = emptyList(),
            autoNamed = false,
            createdAtMs = 1,
            updatedAtMs = 100,
        )
        val autos = (1..12).map { i ->
            KnownTrack(
                id = "auto-$i",
                name = "26-10-0${i}_10:0$i",
                template = emptyList(),
                autoNamed = true,
                createdAtMs = i.toLong(),
                updatedAtMs = i.toLong(),
            )
        }
        val pruned = TrackRepository.pruneAutoNamed(listOf(named) + autos)
        val autoLeft = pruned.filter { it.autoNamed }
        assertEquals(10, autoLeft.size)
        assertTrue(pruned.any { it.id == "named" })
        // Oldest two auto (createdAt 1 and 2) removed.
        assertTrue(autoLeft.none { it.id == "auto-1" })
        assertTrue(autoLeft.none { it.id == "auto-2" })
        assertTrue(autoLeft.any { it.id == "auto-12" })
    }

    @Test
    fun autoNameFormat() {
        // 2026-10-08 10:01 local — use fixed millis for UTC+0 style check via pattern
        val name = TrackRepository.autoNameForStart(1_760_000_000_000L)
        assertTrue(name.matches(Regex("""\d{2}-\d{2}-\d{2}_\d{2}:\d{2}""")))
    }
}
