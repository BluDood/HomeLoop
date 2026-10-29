package com.bludood.homeloop.playback

import org.junit.Assert.*
import org.junit.Test

class PlaybackLifecycleTest {
    @Test fun recoveryRequiresIntentAndResources() {
        assertTrue(PlaybackLifecycle.shouldRun(true, true, true, false))
        assertFalse(PlaybackLifecycle.shouldRun(true, true, false, false))
        assertFalse(PlaybackLifecycle.shouldRun(false, true, true, false))
        assertFalse(PlaybackLifecycle.shouldRun(true, false, true, false))
        assertFalse(PlaybackLifecycle.shouldRun(true, true, true, true))
    }

    @Test fun optimizationDoesNotLosePlaybackIntent() {
        val wantsPlayback = true
        assertFalse(PlaybackLifecycle.shouldRun(true, true, wantsPlayback, true))
        assertTrue(PlaybackLifecycle.shouldRun(true, true, wantsPlayback, false))
        assertFalse(PlaybackLifecycle.shouldRun(true, true, false, false))
    }
}
