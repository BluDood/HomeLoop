package com.bludood.homeloop.playback

import com.bludood.homeloop.data.OutputSize
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoResolutionCalculatorTest {
    @Test
    fun keepsWidgetPixelSizeWhenAllBoundsAllowIt() {
        assertEquals(
            OutputSize(900, 500),
            AutoResolutionCalculator.calculate(
                desiredWidth = 900,
                desiredHeight = 500,
                sourceWidth = 1920,
                sourceHeight = 1080,
                screenWidth = 1440,
                screenHeight = 3120,
            ),
        )
    }

    @Test
    fun scalesDownToSourceWithoutChangingAspectRatio() {
        assertEquals(
            OutputSize(640, 360),
            AutoResolutionCalculator.calculate(
                desiredWidth = 1280,
                desiredHeight = 720,
                sourceWidth = 640,
                sourceHeight = 360,
                screenWidth = 1440,
                screenHeight = 3120,
            ),
        )
    }

    @Test
    fun keepsPixelCorrectSizeAndReturnsCodecFriendlyEvenValues() {
        val result = AutoResolutionCalculator.calculate(
            desiredWidth = 2001,
            desiredHeight = 1201,
            sourceWidth = 3840,
            sourceHeight = 2160,
            screenWidth = 2560,
            screenHeight = 1600,
        )
        assertEquals(OutputSize(2000, 1200), result)
    }

    @Test
    fun capsToTheDisplayWhenTheWidgetRequestIsLarger() {
        assertEquals(
            OutputSize(1600, 960),
            AutoResolutionCalculator.calculate(
                desiredWidth = 2000,
                desiredHeight = 1200,
                sourceWidth = 3840,
                sourceHeight = 2160,
                screenWidth = 1600,
                screenHeight = 2560,
            ),
        )
    }
}
