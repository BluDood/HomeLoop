package com.bludood.homeloop.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bludood.homeloop.data.CropInsets
import com.bludood.homeloop.data.PlaybackSnapshot
import com.bludood.homeloop.data.VirtualTimeline
import com.bludood.homeloop.playback.MOVIE_AUDIO
import com.bludood.homeloop.playback.PlaybackController
import com.bludood.homeloop.playback.PlaybackService
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

internal class InAppPlayer(private val context: Context) {
    private val mediaPlayer = MediaPlayer()
    var soundOn by mutableStateOf(false)
        private set
    var poster by mutableStateOf<ImageBitmap?>(null)
        private set
    var showPoster by mutableStateOf(true)
        private set
    private var posterKey: String? = null
    private var loadedUri: String? = null
    private var lastPlayback: PlaybackSnapshot? = null
    private var lastPlaybackAtMs = 0L
    private var prepared = false
    private var seeking = false
    private var seekAgain = false
    private var seekStartedMs = 0L
    private var seekLeadMs = 0L
    private var corrections = 0
    private var failedAtMs = 0L
    private var loadStartedMs = 0L
    private var textureView: TextureView? = null
    private var surface: Surface? = null
    private var audioClaimed = false
    private var started = true
    private val mainHandler = Handler(Looper.getMainLooper())
    private val loopEnd = Runnable { seekToTimeline() }
    private val posterFile = File(context.cacheDir, POSTER_FILE)
    private val posterKeyFile = File(context.cacheDir, POSTER_KEY_FILE)

    init {
        mediaPlayer.setOnPreparedListener {
            prepared = true
            if (loadStartedMs > 0L) Log.d(TAG, "Prepared after ${sinceLoadMs()} ms")
            seekToTimeline()
        }
        mediaPlayer.setOnSeekCompleteListener { onSeekComplete() }
        mediaPlayer.setOnCompletionListener { seekToTimeline() }
        mediaPlayer.setOnErrorListener { _, what, extra ->
            Log.w(TAG, "Player error $what/$extra")
            prepared = false
            seeking = false
            loadedUri = null
            failedAtMs = SystemClock.elapsedRealtime()
            true
        }
        Thread {
            val key = runCatching { posterKeyFile.readText() }.getOrNull()
            val bitmap = if (key != null) BitmapFactory.decodeFile(posterFile.path) else null
            if (bitmap != null) mainHandler.post {
                posterKey = key; poster = bitmap.asImageBitmap()
            }
        }.start()
    }

    private fun sinceLoadMs() = SystemClock.elapsedRealtime() - loadStartedMs

    val posterMatches: Boolean get() = posterKey != null && posterKey == loadedUri

    fun follow(playback: PlaybackSnapshot) {
        lastPlayback = playback
        lastPlaybackAtMs = SystemClock.elapsedRealtime()
        val uri = playback.videoUri
        if (uri == null || !playback.hasVideo) {
            if (loadedUri != null) mediaPlayer.reset()
            prepared = false
            loadedUri = null
            return
        }
        if (uri != loadedUri) {
            if (SystemClock.elapsedRealtime() - failedAtMs < RETRY_AFTER_ERROR_MS) return
            load(uri)
            return
        }
        if (!prepared || seeking) return
        applyPlayState()
        val drift = driftMs(playback)
        val tolerance = if (playing(playback)) RUNNING_DRIFT_MS else PAUSED_DRIFT_MS
        if (abs(drift) > tolerance) {
            Log.d(TAG, "Correcting drift of $drift ms")
            seekToTimeline()
        } else {
            scheduleLoopEnd(playback)
        }
    }

    private fun load(uri: String) {
        mainHandler.removeCallbacks(loopEnd)
        mediaPlayer.reset()
        prepared = false
        seeking = false
        seekAgain = false
        corrections = 0
        loadStartedMs = SystemClock.elapsedRealtime()
        showPoster = true
        loadedUri = uri
        try {
            mediaPlayer.setAudioAttributes(MOVIE_AUDIO)
            mediaPlayer.setDataSource(context, uri.toUri())
            surface?.let(mediaPlayer::setSurface)
            applyVolume()
            mediaPlayer.prepareAsync()
        } catch (exception: Exception) {
            Log.w(TAG, "Could not open the video: ${exception.message}")
            mediaPlayer.reset()
            loadedUri = null
            failedAtMs = SystemClock.elapsedRealtime()
        }
    }

    private fun playing(playback: PlaybackSnapshot) = playback.timelineRunning && started

    private fun livePositionMs(playback: PlaybackSnapshot): Long {
        if (!playback.timelineRunning || playback.durationMs <= 0L) return playback.currentPositionMs
        val elapsed = SystemClock.elapsedRealtime() - lastPlaybackAtMs
        return Math.floorMod(playback.currentPositionMs + elapsed, playback.durationMs)
    }

    private fun driftMs(playback: PlaybackSnapshot): Long = VirtualTimeline.circularDrift(
        actualMs = mediaPlayer.currentPosition - playback.trimStartMs,
        targetMs = livePositionMs(playback),
        durationMs = playback.durationMs,
    )

    private fun seekToTimeline() {
        val playback = lastPlayback ?: return
        if (!prepared) return
        if (seeking) {
            seekAgain = true
            return
        }
        mainHandler.removeCallbacks(loopEnd)
        val lead = if (playing(playback)) seekLeadMs else 0L
        val target = playback.trimStartMs +
                Math.floorMod(
                    livePositionMs(playback) + lead,
                    playback.durationMs.coerceAtLeast(1L)
                )
        seeking = true
        seekStartedMs = SystemClock.elapsedRealtime()
        mediaPlayer.seekTo(target, MediaPlayer.SEEK_CLOSEST)
    }

    private fun onSeekComplete() {
        seeking = false
        seekLeadMs = (SystemClock.elapsedRealtime() - seekStartedMs).coerceAtMost(MAX_SEEK_LEAD_MS)
        val playback = lastPlayback ?: return
        if (seekAgain) {
            seekAgain = false
            seekToTimeline()
            return
        }
        if (playing(playback) && abs(driftMs(playback)) > START_TOLERANCE_MS &&
            corrections < MAX_CORRECTIONS
        ) {
            corrections++
            seekToTimeline()
            return
        }
        corrections = 0
        applyPlayState()
        scheduleLoopEnd(playback)
    }

    private fun applyPlayState() {
        val playback = lastPlayback ?: return
        if (!prepared || seeking) return
        if (playing(playback)) {
            if (!mediaPlayer.isPlaying) mediaPlayer.start()
        } else if (mediaPlayer.isPlaying) {
            mediaPlayer.pause()
            mainHandler.removeCallbacks(loopEnd)
        }
    }

    private fun scheduleLoopEnd(playback: PlaybackSnapshot) {
        mainHandler.removeCallbacks(loopEnd)
        if (!playing(playback)) return
        val remaining = playback.trimEndMs - mediaPlayer.currentPosition
        if (remaining > 0L) mainHandler.postDelayed(loopEnd, remaining)
    }

    fun updateSound(value: Boolean) {
        if (soundOn == value) return
        soundOn = value
        applyVolume()
    }

    private fun applyVolume() {
        val volume = if (soundOn) 1f else 0f
        mediaPlayer.setVolume(volume, volume)
    }

    fun ownAudio(value: Boolean) {
        audioClaimed = value
        applyAudioClaim()
    }

    private fun applyAudioClaim() {
        val owns = audioClaimed && started
        if (PlaybackController.inAppAudio == owns) return
        PlaybackController.inAppAudio = owns
        if (PlaybackController.serviceAlive) {
            runCatching {
                context.startService(
                    PlaybackService.intent(
                        context,
                        PlaybackService.Action.APPLY_AUDIO
                    )
                )
            }
        }
    }

    internal fun attach(view: TextureView) {
        if (textureView === view) return
        textureView = view
        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                texture: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                if (textureView === view) useSurface(Surface(texture))
            }

            override fun onSurfaceTextureSizeChanged(
                texture: SurfaceTexture,
                width: Int,
                height: Int
            ) = Unit

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                if (textureView === view) useSurface(null)
                return true
            }

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
                if (textureView !== view || !showPoster || !prepared) return
                showPoster = false
                if (loadStartedMs > 0L) {
                    Log.d(TAG, "First frame after ${sinceLoadMs()} ms")
                    loadStartedMs = 0L
                }
            }
        }
        if (view.isAvailable) view.surfaceTexture?.let { useSurface(Surface(it)) }
    }

    internal fun detach(view: TextureView) {
        if (textureView !== view) return
        textureView = null
        useSurface(null)
    }

    private fun useSurface(next: Surface?) {
        val previous = surface
        surface = next
        runCatching { mediaPlayer.setSurface(next) }
        previous?.release()
        if (next != null && loadedUri != null) showPoster = true
        if (next != null && prepared && !seeking && lastPlayback?.let(::playing) == false) seekToTimeline()
    }

    fun onStart() {
        started = true
        applyAudioClaim()
        lastPlayback?.let(::follow)
    }

    fun onStop() {
        started = false
        applyAudioClaim()
        mainHandler.removeCallbacks(loopEnd)
        if (prepared && mediaPlayer.isPlaying) mediaPlayer.pause()
    }

    fun savePoster() {
        val view = textureView ?: return
        val uri = loadedUri ?: return
        if (showPoster || !view.isAvailable || view.width <= 0) return
        val scale = minOf(1f, POSTER_MAX_WIDTH.toFloat() / view.width)
        val bitmap =
            view.getBitmap((view.width * scale).roundToInt(), (view.height * scale).roundToInt())
                ?: return
        poster = bitmap.asImageBitmap()
        posterKey = uri
        Thread {
            runCatching {
                posterFile.outputStream()
                    .use { bitmap.compress(Bitmap.CompressFormat.JPEG, POSTER_QUALITY, it) }
                posterKeyFile.writeText(uri)
            }.onFailure { Log.w(TAG, "Could not save the player poster", it) }
        }.start()
    }

    fun release() {
        updateSound(false)
        ownAudio(false)
        mainHandler.removeCallbacksAndMessages(null)
        mediaPlayer.release()
        surface?.release()
        surface = null
    }

    private companion object {
        const val TAG = "HomeLoopPlayer"
        const val RUNNING_DRIFT_MS = 800L
        const val PAUSED_DRIFT_MS = 100L
        const val START_TOLERANCE_MS = 120L
        const val MAX_CORRECTIONS = 2
        const val MAX_SEEK_LEAD_MS = 1_500L
        const val RETRY_AFTER_ERROR_MS = 5_000L
        const val POSTER_FILE = "player-poster.jpg"
        const val POSTER_KEY_FILE = "player-poster.key"
        const val POSTER_MAX_WIDTH = 960
        const val POSTER_QUALITY = 85
    }
}

@Composable
internal fun rememberInAppPlayer(): InAppPlayer? {
    val context = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    var inAppPlayer by remember { mutableStateOf<InAppPlayer?>(null) }
    DisposableEffect(lifecycleOwner) {
        val handler = Handler(Looper.getMainLooper())
        val releaseIdle = Runnable {
            inAppPlayer?.release()
            inAppPlayer = null
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    handler.removeCallbacks(releaseIdle)
                    inAppPlayer?.onStart() ?: run { inAppPlayer = InAppPlayer(context) }
                }

                Lifecycle.Event.ON_PAUSE -> inAppPlayer?.savePoster()
                Lifecycle.Event.ON_STOP -> {
                    inAppPlayer?.onStop()
                    handler.postDelayed(releaseIdle, KEEP_WARM_MS)
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            handler.removeCallbacks(releaseIdle)
            inAppPlayer?.release()
            inAppPlayer = null
        }
    }
    return inAppPlayer
}

private const val KEEP_WARM_MS = 60_000L

@Composable
internal fun CroppedVideo(
    inAppPlayer: InAppPlayer,
    videoWidth: Int,
    videoHeight: Int,
    crop: CropInsets,
    fill: Boolean,
    modifier: Modifier = Modifier,
) {
    val sizeKnown = videoWidth > 0 && videoHeight > 0
    val frameWidth = if (sizeKnown) videoWidth else 16
    val frameHeight = if (sizeKnown) videoHeight else 9
    val applied = if (sizeKnown) crop else CropInsets()
    val visibleWidth = (frameWidth - applied.left - applied.right).coerceAtLeast(1)
    val visibleHeight = (frameHeight - applied.top - applied.bottom).coerceAtLeast(1)
    val density = LocalDensity.current
    BoxWithConstraints(modifier.clipToBounds()) {
        val boundsWidth = with(density) { maxWidth.toPx() }
        val boundsHeight = with(density) { maxHeight.toPx() }
        val scaleX = boundsWidth / visibleWidth
        val scaleY = boundsHeight / visibleHeight
        val scale = if (fill) maxOf(scaleX, scaleY) else minOf(scaleX, scaleY)
        val surfaceWidth = (frameWidth * scale).roundToInt().coerceAtLeast(1)
        val surfaceHeight = (frameHeight * scale).roundToInt().coerceAtLeast(1)
        val x = ((boundsWidth - visibleWidth * scale) / 2f - applied.left * scale).roundToInt()
        val y = ((boundsHeight - visibleHeight * scale) / 2f - applied.top * scale).roundToInt()
        val frameModifier = Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(Constraints.fixed(surfaceWidth, surfaceHeight))
            layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(x, y) }
        }
        AndroidView(
            factory = { TextureView(it) },
            update = { inAppPlayer.attach(it) },
            onRelease = { inAppPlayer.detach(it) },
            modifier = frameModifier,
        )
        val poster = inAppPlayer.poster
        AnimatedVisibility(
            visible = inAppPlayer.showPoster && inAppPlayer.posterMatches && poster != null,
            enter = EnterTransition.None,
            exit = fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()),
            modifier = Modifier.fillMaxSize(),
        ) {
            poster?.let {
                Image(
                    it,
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = frameModifier
                )
            }
        }
    }
}
