package com.bludood.homeloop.data

/** Estimates the video's nominal cadence from presentation timestamps.
 *
 * Container frame-rate fields are frequently rounded (notably 23 for 23.976 FPS in MKV).
 * Sorting also makes the estimate tolerant of presentation timestamps stored in decode order.
 */
internal object FrameRateEstimator {
    fun estimate(presentationTimesUs: Iterable<Long>): Float {
        val orderedTimes = presentationTimesUs
            .filter { it >= 0L }
            .distinct()
            .sorted()
        if (orderedTimes.size < MIN_TIMESTAMP_COUNT) return 0f

        val intervals = orderedTimes.zipWithNext { first, second -> second - first }
            .filter { it in MIN_FRAME_INTERVAL_US..MAX_FRAME_INTERVAL_US }
            .sorted()
        if (intervals.size < MIN_INTERVAL_COUNT) return 0f

        val median = median(intervals)
        val inliers = intervals.filter {
            it >= median * INLIER_LOWER_BOUND && it <= median * INLIER_UPPER_BOUND
        }
        if (inliers.size < MIN_INTERVAL_COUNT) return 0f

        // The inlier mean preserves fractional NTSC rates better than an integer metadata field.
        val meanIntervalUs = inliers.average()
        val fps = (MICROSECONDS_PER_SECOND / meanIntervalUs).toFloat()
        return fps.takeIf { it in MIN_REASONABLE_FPS..MAX_REASONABLE_FPS } ?: 0f
    }

    private fun median(sortedValues: List<Long>): Double {
        val middle = sortedValues.size / 2
        return if (sortedValues.size % 2 == 0) {
            (sortedValues[middle - 1].toDouble() + sortedValues[middle].toDouble()) / 2.0
        } else {
            sortedValues[middle].toDouble()
        }
    }

    private const val MIN_TIMESTAMP_COUNT = 12
    private const val MIN_INTERVAL_COUNT = MIN_TIMESTAMP_COUNT - 1
    private const val MIN_FRAME_INTERVAL_US = 4_000L
    private const val MAX_FRAME_INTERVAL_US = 1_000_000L
    private const val INLIER_LOWER_BOUND = 0.75
    private const val INLIER_UPPER_BOUND = 1.25
    private const val MICROSECONDS_PER_SECOND = 1_000_000.0
    private const val MIN_REASONABLE_FPS = 1f
    private const val MAX_REASONABLE_FPS = 240f
}
