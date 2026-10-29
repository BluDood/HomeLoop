package com.bludood.homeloop.playback.gl

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import com.bludood.homeloop.data.CropInsets
import com.bludood.homeloop.data.ScalingFilter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

internal class TextureRenderer(private val scalingFilter: ScalingFilter) {
    private val vertexBuffer = allocate(VERTICES)
    private val textureBuffer = allocate(TEXTURE_COORDINATES)
    private val surfaceTransform = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val modelViewProjection = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private var program = 0
    private var textureId = 0
    private var positionHandle = 0
    private var textureCoordinateHandle = 0
    private var mvpMatrixHandle = 0
    private var surfaceTransformHandle = 0
    private var sampleStepHandle = -1
    private var cropBoundsHandle = -1
    private var cachedDisplayWidth = 0
    private var cachedDisplayHeight = 0
    private var cachedCropInsets: CropInsets? = null

    fun initialize(): Int {
        program = GlUtil.createProgram(VERTEX_SHADER, fragmentShader(scalingFilter))
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        textureCoordinateHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
        mvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        surfaceTransformHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")
        sampleStepHandle = GLES20.glGetUniformLocation(program, "uSampleStep")
        cropBoundsHandle = GLES20.glGetUniformLocation(program, "uCropBounds")
        check(
            positionHandle >= 0 && textureCoordinateHandle >= 0 &&
                    mvpMatrixHandle >= 0 && surfaceTransformHandle >= 0,
        ) { "Required OpenGL shader handle was not found" }
        if (scalingFilter == ScalingFilter.BICUBIC || scalingFilter == ScalingFilter.LANCZOS2) {
            check(sampleStepHandle >= 0 && cropBoundsHandle >= 0) {
                "High-quality scaling shader uniforms were not found"
            }
        }
        textureId = GlUtil.createExternalTexture(
            useLinearSampling = scalingFilter == ScalingFilter.BILINEAR,
        )
        return textureId
    }

    fun draw(
        surfaceTexture: SurfaceTexture,
        outputWidth: Int,
        outputHeight: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        rotationDegrees: Int,
        cropInsets: CropInsets,
    ) {
        GlUtil.checkGlError("before draw")
        surfaceTexture.getTransformMatrix(surfaceTransform)
        val rotated = rotationDegrees == 90 || rotationDegrees == 270
        val uncroppedDisplayWidth = if (rotated) sourceHeight else sourceWidth
        val uncroppedDisplayHeight = if (rotated) sourceWidth else sourceHeight
        val safeCrop = cropInsets.clamped(uncroppedDisplayWidth, uncroppedDisplayHeight)
        updateTextureCoordinates(uncroppedDisplayWidth, uncroppedDisplayHeight, safeCrop)
        val displayWidth = safeCrop.remainingWidth(uncroppedDisplayWidth)
        val displayHeight = safeCrop.remainingHeight(uncroppedDisplayHeight)
        val sourceAspect = if (displayHeight == 0) 1f else displayWidth.toFloat() / displayHeight
        val targetAspect = if (outputHeight == 0) 1f else outputWidth.toFloat() / outputHeight
        val scaleX = if (sourceAspect > targetAspect) sourceAspect / targetAspect else 1f
        val scaleY = if (sourceAspect > targetAspect) 1f else targetAspect / sourceAspect

        Matrix.setIdentityM(modelViewProjection, 0)
        Matrix.scaleM(modelViewProjection, 0, scaleX, -scaleY, 1f)
        GLES20.glViewport(0, 0, outputWidth, outputHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(positionHandle)
        textureBuffer.position(0)
        GLES20.glVertexAttribPointer(
            textureCoordinateHandle,
            2,
            GLES20.GL_FLOAT,
            false,
            0,
            textureBuffer,
        )
        GLES20.glEnableVertexAttribArray(textureCoordinateHandle)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, modelViewProjection, 0)
        GLES20.glUniformMatrix4fv(surfaceTransformHandle, 1, false, surfaceTransform, 0)
        if (sampleStepHandle >= 0) {
            val sourcePixelsPerOutputX = displayWidth.toFloat() / (outputWidth * scaleX)
            val sourcePixelsPerOutputY = displayHeight.toFloat() / (outputHeight * scaleY)
            GLES20.glUniform2f(
                sampleStepHandle,
                maxOf(1f, sourcePixelsPerOutputX) / uncroppedDisplayWidth,
                maxOf(1f, sourcePixelsPerOutputY) / uncroppedDisplayHeight,
            )
        }
        if (cropBoundsHandle >= 0) {
            GLES20.glUniform4f(
                cropBoundsHandle,
                safeCrop.left.toFloat() / uncroppedDisplayWidth,
                safeCrop.bottom.toFloat() / uncroppedDisplayHeight,
                1f - safeCrop.right.toFloat() / uncroppedDisplayWidth,
                1f - safeCrop.top.toFloat() / uncroppedDisplayHeight,
            )
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GlUtil.checkGlError("draw frame")
        GLES20.glFinish()
    }

    fun release() {
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
    }

    private fun updateTextureCoordinates(
        displayWidth: Int,
        displayHeight: Int,
        cropInsets: CropInsets,
    ) {
        if (
            displayWidth == cachedDisplayWidth &&
            displayHeight == cachedDisplayHeight &&
            cropInsets == cachedCropInsets
        ) return
        val left = cropInsets.left.toFloat() / displayWidth
        val right = 1f - cropInsets.right.toFloat() / displayWidth
        val bottom = cropInsets.bottom.toFloat() / displayHeight
        val top = 1f - cropInsets.top.toFloat() / displayHeight
        textureBuffer.clear()
        textureBuffer.put(
            floatArrayOf(
                left, bottom,
                right, bottom,
                left, top,
                right, top,
            ),
        )
        textureBuffer.position(0)
        cachedDisplayWidth = displayWidth
        cachedDisplayHeight = displayHeight
        cachedCropInsets = cropInsets
    }

    companion object {
        private val VERTICES = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        private val TEXTURE_COORDINATES = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
        private const val VERTEX_SHADER =
            "uniform mat4 uMVPMatrix;\n" +
                    "attribute vec4 aPosition;\n" +
                    "attribute vec4 aTextureCoord;\n" +
                    "varying vec2 vCanonicalCoord;\n" +
                    "void main() {\n" +
                    "  gl_Position = uMVPMatrix * aPosition;\n" +
                    "  vCanonicalCoord = aTextureCoord.xy;\n" +
                    "}\n"
        private const val FRAGMENT_HEADER =
            "#extension GL_OES_EGL_image_external : require\n" +
                    "precision mediump float;\n" +
                    "varying vec2 vCanonicalCoord;\n" +
                    "uniform samplerExternalOES sTexture;\n" +
                    "uniform mat4 uSTMatrix;\n"
        private const val DIRECT_FRAGMENT_BODY =
            "void main() {\n" +
                    "  vec2 textureCoord = (uSTMatrix * vec4(vCanonicalCoord, 0.0, 1.0)).xy;\n" +
                    "  gl_FragColor = texture2D(sTexture, textureCoord);\n" +
                    "}\n"
        private const val FILTER_HELPERS =
            "uniform vec2 uSampleStep;\n" +
                    "uniform vec4 uCropBounds;\n" +
                    "vec4 sampleSource(vec2 coord) {\n" +
                    "  vec2 safeCoord = clamp(coord, uCropBounds.xy, uCropBounds.zw);\n" +
                    "  vec2 transformed = (uSTMatrix * vec4(safeCoord, 0.0, 1.0)).xy;\n" +
                    "  return texture2D(sTexture, transformed);\n" +
                    "}\n"
        private const val BICUBIC_BODY =
            "float cubicWeight(float value) {\n" +
                    "  float x = abs(value);\n" +
                    "  if (x <= 1.0) return 1.5*x*x*x - 2.5*x*x + 1.0;\n" +
                    "  if (x < 2.0) return -0.5*x*x*x + 2.5*x*x - 4.0*x + 2.0;\n" +
                    "  return 0.0;\n" +
                    "}\n" +
                    "void main() {\n" +
                    "  vec2 grid = vCanonicalCoord / uSampleStep - 0.5;\n" +
                    "  vec2 base = floor(grid);\n" +
                    "  vec2 fraction = fract(grid);\n" +
                    "  vec4 color = vec4(0.0);\n" +
                    "  float totalWeight = 0.0;\n" +
                    "  for (int y = 0; y < 4; y++) {\n" +
                    "    float offsetY = float(y) - 1.0;\n" +
                    "    float weightY = cubicWeight(offsetY - fraction.y);\n" +
                    "    for (int x = 0; x < 4; x++) {\n" +
                    "      float offsetX = float(x) - 1.0;\n" +
                    "      float weight = cubicWeight(offsetX - fraction.x) * weightY;\n" +
                    "      vec2 coord = (base + vec2(offsetX, offsetY) + 0.5) * uSampleStep;\n" +
                    "      color += sampleSource(coord) * weight;\n" +
                    "      totalWeight += weight;\n" +
                    "    }\n" +
                    "  }\n" +
                    "  gl_FragColor = clamp(color / max(totalWeight, 0.0001), 0.0, 1.0);\n" +
                    "}\n"
        private const val LANCZOS_BODY =
            "float sincWeight(float value) {\n" +
                    "  float angle = 3.14159265 * value;\n" +
                    "  if (abs(angle) < 0.0001) return 1.0;\n" +
                    "  return sin(angle) / angle;\n" +
                    "}\n" +
                    "float lanczosWeight(float value) {\n" +
                    "  float x = abs(value);\n" +
                    "  if (x >= 2.0) return 0.0;\n" +
                    "  return sincWeight(x) * sincWeight(x * 0.5);\n" +
                    "}\n" +
                    "void main() {\n" +
                    "  vec2 grid = vCanonicalCoord / uSampleStep - 0.5;\n" +
                    "  vec2 base = floor(grid);\n" +
                    "  vec2 fraction = fract(grid);\n" +
                    "  vec4 color = vec4(0.0);\n" +
                    "  float totalWeight = 0.0;\n" +
                    "  for (int y = 0; y < 4; y++) {\n" +
                    "    float offsetY = float(y) - 1.0;\n" +
                    "    float weightY = lanczosWeight(offsetY - fraction.y);\n" +
                    "    for (int x = 0; x < 4; x++) {\n" +
                    "      float offsetX = float(x) - 1.0;\n" +
                    "      float weight = lanczosWeight(offsetX - fraction.x) * weightY;\n" +
                    "      vec2 coord = (base + vec2(offsetX, offsetY) + 0.5) * uSampleStep;\n" +
                    "      color += sampleSource(coord) * weight;\n" +
                    "      totalWeight += weight;\n" +
                    "    }\n" +
                    "  }\n" +
                    "  gl_FragColor = clamp(color / max(totalWeight, 0.0001), 0.0, 1.0);\n" +
                    "}\n"

        private fun fragmentShader(filter: ScalingFilter): String = when (filter) {
            ScalingFilter.NEAREST,
            ScalingFilter.BILINEAR,
                -> FRAGMENT_HEADER + DIRECT_FRAGMENT_BODY

            ScalingFilter.BICUBIC -> FRAGMENT_HEADER + FILTER_HELPERS + BICUBIC_BODY
            ScalingFilter.LANCZOS2 -> FRAGMENT_HEADER + FILTER_HELPERS + LANCZOS_BODY
        }

        private fun allocate(values: FloatArray): FloatBuffer = ByteBuffer
            .allocateDirect(values.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(values)
                position(0)
            }
    }
}
