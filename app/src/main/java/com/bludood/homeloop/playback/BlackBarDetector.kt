package com.bludood.homeloop.playback

import com.bludood.homeloop.data.CropInsets

object BlackBarDetector {
    fun detect(pixels: IntArray, width: Int, height: Int): CropInsets? {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return null
        val rowAllowance = (width * BRIGHT_TOLERANCE).toInt()
        val columnAllowance = (height * BRIGHT_TOLERANCE).toInt()
        fun darkRow(y: Int): Boolean {
            var bright = 0
            for (x in 0 until width) if (isBright(pixels[y * width + x]) && ++bright > rowAllowance) return false
            return true
        }

        fun darkColumn(x: Int): Boolean {
            var bright = 0
            for (y in 0 until height) if (isBright(pixels[y * width + x]) && ++bright > columnAllowance) return false
            return true
        }

        val top = (0 until height).firstOrNull { !darkRow(it) } ?: return null
        val bottom = height - 1 - (height - 1 downTo top).first { !darkRow(it) }
        val left = (0 until width).first { !darkColumn(it) }
        val right = width - 1 - (width - 1 downTo left).first { !darkColumn(it) }
        if (width - left - right < width * MIN_PICTURE_FRACTION ||
            height - top - bottom < height * MIN_PICTURE_FRACTION
        ) return null
        return CropInsets(top = top, right = right, bottom = bottom, left = left)
    }

    fun combine(frames: List<CropInsets>): CropInsets? {
        if (frames.isEmpty()) return null
        fun pick(values: List<Int>): Int = values.sorted()[(values.size - 1) / 4]
        return CropInsets(
            top = pick(frames.map { it.top }),
            right = pick(frames.map { it.right }),
            bottom = pick(frames.map { it.bottom }),
            left = pick(frames.map { it.left }),
        )
    }

    fun scale(
        bars: CropInsets,
        frameWidth: Int,
        frameHeight: Int,
        videoWidth: Int,
        videoHeight: Int
    ) = CropInsets(
        top = Math.round(bars.top.toFloat() * videoHeight / frameHeight),
        right = Math.round(bars.right.toFloat() * videoWidth / frameWidth),
        bottom = Math.round(bars.bottom.toFloat() * videoHeight / frameHeight),
        left = Math.round(bars.left.toFloat() * videoWidth / frameWidth),
    )

    private fun isBright(argb: Int): Boolean {
        val red = argb shr 16 and 0xFF
        val green = argb shr 8 and 0xFF
        val blue = argb and 0xFF
        return (299 * red + 587 * green + 114 * blue) / 1_000 > DARK_LUMA
    }

    private const val DARK_LUMA = 40
    private const val BRIGHT_TOLERANCE = 0.02f
    private const val MIN_PICTURE_FRACTION = 0.2f
}
