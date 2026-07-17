package com.bludood.homeloop.playback.gl

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.view.Surface
import androidx.core.graphics.createBitmap
import com.bludood.homeloop.data.CropInsets
import com.bludood.homeloop.data.ScalingFilter
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class CodecOutputSurface(
    private val width: Int,
    private val height: Int,
    private val scalingFilter: ScalingFilter,
) : SurfaceTexture.OnFrameAvailableListener {
    private val frameLock = Object()
    private val pixelBuffer: ByteBuffer
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private lateinit var textureRenderer: TextureRenderer
    private lateinit var surfaceTexture: SurfaceTexture
    private var reusableBitmap: Bitmap? = null
    lateinit var surface: Surface
        private set
    private var frameAvailable = false

    init {
        require(width > 0 && height > 0) { "Output dimensions must be positive" }
        pixelBuffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        setupEgl()
        setupSurface()
    }

    private fun setupEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "Unable to get EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            "Unable to initialize EGL"
        }
        val configAttributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val configCount = IntArray(1)
        check(
            EGL14.eglChooseConfig(
                eglDisplay,
                configAttributes,
                0,
                configs,
                0,
                configs.size,
                configCount,
                0,
            ) && configCount[0] > 0,
        ) { "Unable to find a suitable EGL config" }
        val config = requireNotNull(configs[0])
        eglContext = EGL14.eglCreateContext(
            eglDisplay,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        checkEglError("eglCreateContext")
        eglSurface = EGL14.eglCreatePbufferSurface(
            eglDisplay,
            config,
            intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE),
            0,
        )
        checkEglError("eglCreatePbufferSurface")
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            "eglMakeCurrent failed"
        }
    }

    private fun setupSurface() {
        textureRenderer = TextureRenderer(scalingFilter)
        surfaceTexture = SurfaceTexture(textureRenderer.initialize()).apply {
            setOnFrameAvailableListener(this@CodecOutputSurface)
        }
        surface = Surface(surfaceTexture)
    }

    fun awaitNewImage() {
        synchronized(frameLock) {
            val deadline = System.currentTimeMillis() + FRAME_WAIT_TIMEOUT_MS
            while (!frameAvailable) {
                val remaining = deadline - System.currentTimeMillis()
                check(remaining > 0L) { "Timed out waiting for a decoded video frame" }
                try {
                    frameLock.wait(remaining)
                } catch (exception: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IllegalStateException("Interrupted while waiting for a decoded frame", exception)
                }
            }
            frameAvailable = false
        }
        GlUtil.checkGlError("before updateTexImage")
        surfaceTexture.updateTexImage()
    }

    fun drawAndReadBitmap(
        sourceWidth: Int,
        sourceHeight: Int,
        rotationDegrees: Int,
        cropInsets: CropInsets,
    ): Bitmap {
        textureRenderer.draw(
            surfaceTexture,
            width,
            height,
            sourceWidth,
            sourceHeight,
            rotationDegrees,
            cropInsets,
        )
        pixelBuffer.rewind()
        GLES20.glReadPixels(
            0,
            0,
            width,
            height,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            pixelBuffer,
        )
        GlUtil.checkGlError("glReadPixels")
        pixelBuffer.rewind()
        val bitmap = reusableBitmap?.takeIf {
            !it.isRecycled && it.width == width && it.height == height && it.isMutable
        } ?: createBitmap(width, height, Bitmap.Config.ARGB_8888)
        reusableBitmap = null
        return bitmap.also {
            it.copyPixelsFromBuffer(pixelBuffer)
        }
    }

    fun acceptReusableBitmap(bitmap: Bitmap?) {
        reusableBitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
        reusableBitmap = bitmap
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        synchronized(frameLock) {
            frameAvailable = true
            frameLock.notifyAll()
        }
    }

    fun release() {
        reusableBitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
        reusableBitmap = null
        if (::surface.isInitialized) surface.release()
        if (::surfaceTexture.isInitialized) surfaceTexture.release()
        if (::textureRenderer.isInitialized) textureRenderer.release()
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }

    private fun checkEglError(operation: String) {
        val error = EGL14.eglGetError()
        check(error == EGL14.EGL_SUCCESS) { "$operation: EGL error 0x${error.toString(16)}" }
    }

    companion object {
        private const val FRAME_WAIT_TIMEOUT_MS = 2_500L
    }
}
