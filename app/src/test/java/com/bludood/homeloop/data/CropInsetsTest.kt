package com.bludood.homeloop.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CropInsetsTest {
    @Test
    fun negativeInsetsAreClampedToZero() {
        assertEquals(
            CropInsets(),
            CropInsets(top = -1, right = -2, bottom = -3, left = -4)
                .clamped(1920, 1080),
        )
    }

    @Test
    fun opposingInsetsAlwaysLeaveAtLeastTwoPixels() {
        val crop = CropInsets(top = 800, right = 900, bottom = 800, left = 900)
            .clamped(1000, 900)
        assertEquals(2, crop.remainingWidth(1000))
        assertEquals(2, crop.remainingHeight(900))
    }

    @Test
    fun remainingDimensionsReflectAsymmetricCrop() {
        val crop = CropInsets(top = 20, right = 30, bottom = 40, left = 50)
        assertEquals(1840, crop.remainingWidth(1920))
        assertEquals(1020, crop.remainingHeight(1080))
    }
}
