package com.bludood.homeloop.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EncodingTimeEstimatorTest {
    @Test
    fun waitsForEnoughProgressAndElapsedTime() {
        val estimator = EncodingTimeEstimator()
        estimator.reset(1_000L)

        assertNull(estimator.estimateRemainingMs(1, 20_000L))
        assertNull(estimator.estimateRemainingMs(20, 5_000L))
    }

    @Test
    fun estimatesRemainingTimeFromStableProgress() {
        val estimator = EncodingTimeEstimator()
        estimator.reset(1_000L)
        estimator.estimateRemainingMs(10, 11_000L)

        assertEquals(80_000L, estimator.estimateRemainingMs(20, 21_000L))
    }
}
