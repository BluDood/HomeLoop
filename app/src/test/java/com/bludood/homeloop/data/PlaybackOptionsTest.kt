package com.bludood.homeloop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackOptionsTest {
    @Test
    fun sourceCadencePresetsUseWholeFrameDivisors() {
        assertEquals(listOf(1, 2, 4, 8), PlaybackOptions.frameRates.map { it.sourceDivisor })
        assertEquals(listOf("Match video", "1/2", "1/4", "1/8"), PlaybackOptions.frameRates.map { it.label })
        assertTrue(PlaybackOptions.frameRates.all { it.isSourceBased })
    }

    @Test
    fun autoQualityPresetsExposeExpectedDimensionScales() {
        assertEquals(
            listOf(100, 75, 50, 25),
            PlaybackOptions.resolutions.map { it.autoScalePercent },
        )
        assertTrue(PlaybackOptions.resolutions.all { it.isAuto })
    }

    @Test
    fun scalingFiltersExposeFastAndHighQualityChoices() {
        assertEquals(
            listOf(
                ScalingFilter.BICUBIC,
                ScalingFilter.BILINEAR,
                ScalingFilter.LANCZOS2,
                ScalingFilter.NEAREST,
            ),
            ScalingFilter.entries,
        )
    }
}
