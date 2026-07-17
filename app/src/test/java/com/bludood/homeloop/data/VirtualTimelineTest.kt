package com.bludood.homeloop.data

import org.junit.Assert.assertEquals
import org.junit.Test

class VirtualTimelineTest {
    @Test
    fun sameBootUsesMonotonicClockAndWraps() {
        val position = VirtualTimeline.position(
            durationMs = 10_000L,
            anchorPositionMs = 9_000L,
            anchorElapsedMs = 2_000L,
            anchorWallMs = 50_000L,
            anchorBootCount = 4,
            elapsedNowMs = 5_500L,
            wallNowMs = 99_000L,
            bootCountNow = 4,
        )
        assertEquals(2_500L, position)
    }

    @Test
    fun timelineKeepsLoopingAcrossMultipleDurations() {
        val position = VirtualTimeline.position(
            durationMs = 10_000L,
            anchorPositionMs = 0L,
            anchorElapsedMs = 1_000L,
            anchorWallMs = 1_000L,
            anchorBootCount = 4,
            elapsedNowMs = 36_250L,
            wallNowMs = 36_250L,
            bootCountNow = 4,
        )

        assertEquals(5_250L, position)
    }

    @Test
    fun differentBootFallsBackToWallClock() {
        val position = VirtualTimeline.position(
            durationMs = 20_000L,
            anchorPositionMs = 3_000L,
            anchorElapsedMs = 8_000L,
            anchorWallMs = 100_000L,
            anchorBootCount = 4,
            elapsedNowMs = 1_000L,
            wallNowMs = 106_500L,
            bootCountNow = 5,
        )
        assertEquals(9_500L, position)
    }

    @Test
    fun invalidDurationReturnsZero() {
        assertEquals(
            0L,
            VirtualTimeline.position(0L, 10L, 0L, 0L, 1, 50L, 50L, 1),
        )
    }

    @Test
    fun durationFormattingIsStable() {
        assertEquals("01:02:03", VirtualTimeline.format(3_723_999L))
    }

    @Test
    fun pausedTimelineDoesNotAdvance() {
        val position = VirtualTimeline.position(
            durationMs = 10_000L,
            anchorPositionMs = 4_200L,
            anchorElapsedMs = 100L,
            anchorWallMs = 100L,
            anchorBootCount = 1,
            elapsedNowMs = 9_000L,
            wallNowMs = 9_000L,
            bootCountNow = 1,
            isRunning = false,
        )
        assertEquals(4_200L, position)
    }

    @Test
    fun circularDriftUsesShortestDistanceAcrossLoop() {
        assertEquals(200L, VirtualTimeline.circularDrift(100L, 9_900L, 10_000L))
        assertEquals(-200L, VirtualTimeline.circularDrift(9_900L, 100L, 10_000L))
    }
}
