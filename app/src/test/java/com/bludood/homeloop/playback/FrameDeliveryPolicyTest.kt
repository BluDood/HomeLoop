package com.bludood.homeloop.playback

import org.junit.Assert.*
import org.junit.Test

class FrameDeliveryPolicyTest {
    @Test fun lateStartupMustStillProduceAFrame() {
        assertFalse(FrameDeliveryPolicy.shouldDrop(500_000_000, 12_000_000, 0, null))
    }

    @Test fun lateFramesDropOnlyWhileTheWidgetHasRecentFrames() {
        assertTrue(FrameDeliveryPolicy.shouldDrop(50, 12, 999_999_999, 0))
        assertFalse(FrameDeliveryPolicy.shouldDrop(50, 12, 1_000_000_000, 0))
        assertFalse(FrameDeliveryPolicy.shouldDrop(10, 12, 100, 0))
    }

    @Test fun stalledCodecTimesOutAndProgressResetsDeadline() {
        assertFalse(FrameDeliveryPolicy.outputStalled(14_999, 0))
        assertTrue(FrameDeliveryPolicy.outputStalled(15_000, 0))
        assertFalse(FrameDeliveryPolicy.outputStalled(15_000, 14_999))
    }
}
