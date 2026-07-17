package com.bludood.homeloop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameRateEstimatorTest {
    @Test
    fun estimatesFractionalNtscCadenceInsteadOfRoundedContainerRate() {
        val timestamps = generateTimestamps(frameCount = 300, fps = 24_000.0 / 1_001.0)

        assertEquals(23.976f, FrameRateEstimator.estimate(timestamps), 0.002f)
    }

    @Test
    fun ignoresDuplicateTimestampsAndLargeDiscontinuities() {
        val original = generateTimestamps(frameCount = 180, fps = 24_000.0 / 1_001.0)
        val timestamps = original
            .toMutableList()
            .apply {
                add(this[40])
                add(original.last() + 5_000_000L)
            }

        assertEquals(23.976f, FrameRateEstimator.estimate(timestamps), 0.002f)
    }

    @Test
    fun rejectsInsufficientSamples() {
        assertTrue(FrameRateEstimator.estimate(listOf(0L, 41_708L, 83_416L)) == 0f)
    }

    private fun generateTimestamps(frameCount: Int, fps: Double): List<Long> =
        List(frameCount) { frame -> (frame * 1_000_000.0 / fps).toLong() }
}
