package com.bludood.homeloop.playback

object PlaybackLifecycle {
    fun shouldRun(
        hasVideo: Boolean, hasWidgets: Boolean, timelineRunning: Boolean,
        optimizing: Boolean
    ): Boolean =
        hasVideo && hasWidgets && timelineRunning && !optimizing
}
