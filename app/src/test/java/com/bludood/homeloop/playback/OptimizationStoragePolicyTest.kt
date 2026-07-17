package com.bludood.homeloop.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OptimizationStoragePolicyTest {
    @Test
    fun smallExportStillKeepsMinimumSafetySpace() {
        assertEquals(
            74L * 1024L * 1024L,
            OptimizationStoragePolicy.requiredFreeBytes(10L * 1024L * 1024L),
        )
    }

    @Test
    fun largeExportUsesTwentyFivePercentSafetyMargin() {
        assertEquals(
            625L * 1024L * 1024L,
            OptimizationStoragePolicy.requiredFreeBytes(500L * 1024L * 1024L),
        )
    }

    @Test
    fun availabilityIncludesTheSafetyMargin() {
        val estimate = 500L * 1024L * 1024L
        assertFalse(OptimizationStoragePolicy.hasEnoughSpace(estimate, 600L * 1024L * 1024L))
        assertTrue(OptimizationStoragePolicy.hasEnoughSpace(estimate, 625L * 1024L * 1024L))
    }
}
