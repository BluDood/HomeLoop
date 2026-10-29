package com.bludood.homeloop.playback

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import com.bludood.homeloop.data.CropInsets
import com.bludood.homeloop.data.ScalingFilter
import com.bludood.homeloop.playback.gl.CodecOutputSurface
import java.io.IOException

class VideoFrameDecoder(
    context: Context,
    private val uri: Uri,
    private val startPositionMs: Long,
    private val endPositionMs: Long? = null,
    private val playbackAnchorRealtimeNs: Long = SystemClock.elapsedRealtimeNanos(),
    private val frameDivisor: Int,
    private val knownSourceFrameRate: Float,
    private val outputWidth: Int,
    private val outputHeight: Int,
    private val cropInsets: CropInsets,
    private val upscaleFilter: ScalingFilter,
    private val downscaleFilter: ScalingFilter,
    private val onFrame: (Bitmap, presentationTimeUs: Long) -> Bitmap?,
    private val onInfo: (String) -> Unit,
    private val onDiagnostics: (DecoderDiagnosticsDelta) -> Unit,
    private val shouldStop: () -> Boolean,
    private val realtime: Boolean = true,
) {
    private val appContext = context.applicationContext

    @Throws(IOException::class)
    fun decodeUntilEndOrStopped() {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var outputSurface: CodecOutputSurface? = null
        try {
            extractor.setDataSource(appContext, uri, null)
            val trackIndex = selectVideoTrack(extractor)
            if (trackIndex < 0) throw IOException("The selected file has no video track")
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: throw IOException("The video track has no MIME type")
            val sourceWidth = format.integerOr(MediaFormat.KEY_WIDTH, outputWidth)
            val sourceHeight = format.integerOr(MediaFormat.KEY_HEIGHT, outputHeight)
            val rotation = format.integerOr(MediaFormat.KEY_ROTATION, 0)
            val sourceFps = knownSourceFrameRate.takeIf { it > 0f }
                ?: format.frameRateOr(0f)
            val rotated = rotation == 90 || rotation == 270
            val displayWidth = if (rotated) sourceHeight else sourceWidth
            val displayHeight = if (rotated) sourceWidth else sourceHeight
            val safeCrop = cropInsets.clamped(displayWidth, displayHeight)
            val visibleWidth = safeCrop.remainingWidth(displayWidth)
            val visibleHeight = safeCrop.remainingHeight(displayHeight)
            val scalingFilter = if (visibleWidth > outputWidth || visibleHeight > outputHeight) {
                downscaleFilter
            } else {
                upscaleFilter
            }
            val startUs = startPositionMs.coerceAtLeast(0L) * 1_000L
            val endUs = endPositionMs?.coerceAtLeast(startPositionMs + 1L)?.times(1_000L)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            onInfo(
                "Decoder: $mime ${sourceWidth}×$sourceHeight, " +
                        "output ${outputWidth}×$outputHeight, filter ${scalingFilter.label}",
            )

            outputSurface = CodecOutputSurface(outputWidth, outputHeight, scalingFilter)
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(format, outputSurface.surface, null, 0)
                start()
            }
            runCodecLoop(
                extractor = extractor,
                codec = codec,
                outputSurface = outputSurface,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                rotation = rotation,
                requestedStartUs = startUs,
                requestedEndUs = endUs,
                sourceFps = sourceFps,
            )
        } finally {
            codec?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
            }
            outputSurface?.release()
            extractor.release()
        }
    }

    private fun runCodecLoop(
        extractor: MediaExtractor,
        codec: MediaCodec,
        outputSurface: CodecOutputSurface,
        sourceWidth: Int,
        sourceHeight: Int,
        rotation: Int,
        requestedStartUs: Long,
        requestedEndUs: Long?,
        sourceFps: Float,
    ) {
        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var sourceFrameIndex = 0L
        val playbackStartNs = playbackAnchorRealtimeNs
        val effectiveFps = if (sourceFps > 0f) sourceFps / frameDivisor else 30f
        val frameBudgetNs = (1_000_000_000.0 / effectiveFps.coerceAtLeast(1f)).toLong()
        val lateToleranceNs = maxOf(frameBudgetNs / 2L, MIN_LATE_TOLERANCE_NS)
        var selectedFrameCount = 0L
        var publishedFrameCount = 0L
        var lateDropCount = 0L
        var decodedFrameCount = 0L
        var cadenceSkippedCount = 0L
        var renderLeadNs = INITIAL_RENDER_LEAD_NS
        var reportedDecoded = 0L
        var reportedSelected = 0L
        var reportedPublished = 0L
        var reportedCadenceSkipped = 0L
        var reportedLateDropped = 0L
        var lastReportMs = SystemClock.elapsedRealtime()
        var lastOutputMs = lastReportMs
        var lastPublishedNs: Long? = null

        fun reportDiagnostics(force: Boolean = false) {
            val now = SystemClock.elapsedRealtime()
            val elapsed = (now - lastReportMs).let { if (force) it.coerceAtLeast(1L) else it }
            if (!force && elapsed < DIAGNOSTICS_INTERVAL_MS) return
            if (elapsed <= 0L) return
            onDiagnostics(
                DecoderDiagnosticsDelta(
                    decodedFrames = decodedFrameCount - reportedDecoded,
                    selectedFrames = selectedFrameCount - reportedSelected,
                    publishedFrames = publishedFrameCount - reportedPublished,
                    cadenceSkippedFrames = cadenceSkippedCount - reportedCadenceSkipped,
                    lateDroppedFrames = lateDropCount - reportedLateDropped,
                    elapsedMs = elapsed,
                ),
            )
            reportedDecoded = decodedFrameCount
            reportedSelected = selectedFrameCount
            reportedPublished = publishedFrameCount
            reportedCadenceSkipped = cadenceSkippedCount
            reportedLateDropped = lateDropCount
            lastReportMs = now
        }

        while (!outputDone && !shouldStop()) {
            if (FrameDeliveryPolicy.outputStalled(SystemClock.elapsedRealtime(), lastOutputMs)) {
                throw IOException("Video decoder stopped producing output")
            }
            if (!inputDone) {
                val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)
                        ?: error("Decoder returned a null input buffer")
                    inputBuffer.clear()
                    val sampleTimeUs = extractor.sampleTime
                    val reachedTrimEnd = requestedEndUs != null &&
                            sampleTimeUs >= requestedEndUs
                    val sampleSize = if (reachedTrimEnd) {
                        -1
                    } else {
                        extractor.readSampleData(inputBuffer, 0)
                    }
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(
                            inputIndex,
                            0,
                            0,
                            0L,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, sampleSize, sampleTimeUs, 0)
                        extractor.advance()
                    }
                }
            }

            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> continue
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    onInfo("Output format: ${codec.outputFormat}")
                    continue
                }

                outputIndex < 0 -> continue
            }

            lastOutputMs = SystemClock.elapsedRealtime()
            val endOfStream = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            val presentationUs = bufferInfo.presentationTimeUs
            decodedFrameCount++
            if (requestedEndUs != null && presentationUs >= requestedEndUs) {
                codec.releaseOutputBuffer(outputIndex, false)
                outputDone = true
            } else if (presentationUs < requestedStartUs) {
                codec.releaseOutputBuffer(outputIndex, false)
            } else {
                if (sourceFrameIndex++ % frameDivisor == 0L) {
                    selectedFrameCount++
                    val targetNs = playbackStartNs +
                            (presentationUs - requestedStartUs).coerceAtLeast(0L) * 1_000L
                    val nowNs = SystemClock.elapsedRealtimeNanos()
                    val predictedCompletionNs = nowNs + renderLeadNs
                    val latenessNs = if (realtime) predictedCompletionNs - targetNs else 0L
                    if (realtime && FrameDeliveryPolicy.shouldDrop(
                            latenessNs, lateToleranceNs, nowNs, lastPublishedNs
                        )
                    ) {
                        codec.releaseOutputBuffer(outputIndex, false)
                        lateDropCount++
                        reportDiagnostics()
                        outputDone = endOfStream
                        continue
                    }
                    if (realtime && latenessNs < 0L) sleepUntil(targetNs - renderLeadNs)
                    if (shouldStop()) {
                        codec.releaseOutputBuffer(outputIndex, false)
                        break
                    }
                    val renderStartNs = SystemClock.elapsedRealtimeNanos()
                    codec.releaseOutputBuffer(outputIndex, true)
                    outputSurface.awaitNewImage()
                    val bitmap = outputSurface.drawAndReadBitmap(
                        sourceWidth,
                        sourceHeight,
                        rotation,
                        cropInsets,
                    )
                    outputSurface.acceptReusableBitmap(onFrame(bitmap, presentationUs))
                    val observedRenderNs = SystemClock.elapsedRealtimeNanos() - renderStartNs
                    renderLeadNs = (
                            renderLeadNs * (RENDER_LEAD_SMOOTHING - 1L) + observedRenderNs
                            ) / RENDER_LEAD_SMOOTHING
                    renderLeadNs = renderLeadNs.coerceIn(0L, MAX_RENDER_LEAD_NS)
                    lastPublishedNs = SystemClock.elapsedRealtimeNanos()
                    lastOutputMs = SystemClock.elapsedRealtime()
                    publishedFrameCount++
                } else {
                    cadenceSkippedCount++
                    codec.releaseOutputBuffer(outputIndex, false)
                }
            }
            outputDone = outputDone || endOfStream
            reportDiagnostics()
        }
        reportDiagnostics(force = true)
        onInfo(
            "Cadence: selected=$selectedFrameCount, published=$publishedFrameCount, " +
                    "lateDropped=$lateDropCount",
        )
    }

    private fun sleepUntil(targetNs: Long) {
        while (!shouldStop()) {
            val remaining = targetNs - SystemClock.elapsedRealtimeNanos()
            if (remaining <= 0L) return
            try {
                Thread.sleep(remaining / 1_000_000L, (remaining % 1_000_000L).toInt())
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private fun selectVideoTrack(extractor: MediaExtractor): Int =
        (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                ?.startsWith("video/") == true
        } ?: -1

    private fun MediaFormat.integerOr(key: String, fallback: Int): Int = try {
        if (containsKey(key)) getInteger(key) else fallback
    } catch (_: RuntimeException) {
        fallback
    }

    private fun MediaFormat.frameRateOr(fallback: Float): Float {
        if (!containsKey(MediaFormat.KEY_FRAME_RATE)) return fallback
        return runCatching { getFloat(MediaFormat.KEY_FRAME_RATE) }.getOrNull()
            ?: runCatching { getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }.getOrDefault(
                fallback
            )
    }

    companion object {
        private const val CODEC_TIMEOUT_US = 10_000L
        private const val MIN_LATE_TOLERANCE_NS = 12_000_000L
        private const val INITIAL_RENDER_LEAD_NS = 8_000_000L
        private const val MAX_RENDER_LEAD_NS = 100_000_000L
        private const val RENDER_LEAD_SMOOTHING = 4L
        private const val DIAGNOSTICS_INTERVAL_MS = 2_000L
    }
}
