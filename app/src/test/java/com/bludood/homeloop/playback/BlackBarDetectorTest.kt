package com.bludood.homeloop.playback

import com.bludood.homeloop.data.CropInsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BlackBarDetectorTest {
    private val black = 0xFF101010.toInt()
    private val picture = 0xFF808080.toInt()

    private fun frame(width: Int, height: Int, bars: CropInsets): IntArray = IntArray(width * height) { index ->
        val x = index % width
        val y = index / width
        val inside = x >= bars.left && x < width - bars.right && y >= bars.top && y < height - bars.bottom
        if (inside) picture else black
    }

    @Test
    fun findsLetterboxAndPillarboxBars() {
        val bars = CropInsets(top = 12, right = 5, bottom = 10, left = 7)
        assertEquals(bars, BlackBarDetector.detect(frame(100, 80, bars), 100, 80))
    }

    @Test
    fun aFullPictureHasNoBars() {
        assertEquals(CropInsets(), BlackBarDetector.detect(frame(100, 80, CropInsets()), 100, 80))
    }

    @Test
    fun aDarkFrameIsIgnored() {
        assertNull(BlackBarDetector.detect(IntArray(100 * 80) { black }, 100, 80))
    }

    @Test
    fun aFewBrightPixelsInABarStillCountAsDark() {
        val bars = CropInsets(top = 10, bottom = 10)
        val pixels = frame(100, 80, bars).also { it[2 * 100 + 50] = picture }
        assertEquals(bars, BlackBarDetector.detect(pixels, 100, 80))
    }

    @Test
    fun combineIgnoresOneSubtitleFrameAndDarkScenes() {
        val usual = CropInsets(top = 140, bottom = 140)
        val combined = BlackBarDetector.combine(
            listOf(usual, usual, CropInsets(top = 140, bottom = 20), usual, CropInsets(top = 400, bottom = 400)),
        )
        assertEquals(usual, combined)
    }

    @Test
    fun scaleMapsToVideoPixels() {
        assertEquals(
            CropInsets(top = 141, right = 0, bottom = 141, left = 0),
            BlackBarDetector.scale(CropInsets(top = 47, bottom = 47), 640, 360, 1920, 1080),
        )
    }
}
