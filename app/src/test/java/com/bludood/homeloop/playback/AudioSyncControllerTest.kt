package com.bludood.homeloop.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSyncControllerTest {
    @Test
    fun leavesSmallClockNoiseAlone() {
        val decision = AudioSyncController.decide(driftMs = 15L, targetPositionMs = 1_000L)

        assertEquals(1f, decision.playbackSpeed)
        assertNull(decision.seekPositionMs)
    }

    @Test
    fun slowsAudioThatIsAhead() {
        val decision = AudioSyncController.decide(driftMs = 80L, targetPositionMs = 1_000L)

        assertTrue(decision.playbackSpeed < 1f)
        assertNull(decision.seekPositionMs)
    }

    @Test
    fun speedsUpAudioThatIsBehind() {
        val decision = AudioSyncController.decide(driftMs = -80L, targetPositionMs = 1_000L)

        assertTrue(decision.playbackSpeed > 1f)
        assertNull(decision.seekPositionMs)
    }

    @Test
    fun seeksLargeDrift() {
        val decision = AudioSyncController.decide(driftMs = 250L, targetPositionMs = 4_321L)

        assertEquals(1f, decision.playbackSpeed)
        assertEquals(4_321L, decision.seekPositionMs)
    }
}
