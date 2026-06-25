package com.securicam.camera.webrtc

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * [VideoSink] that captures [totalCount] consecutive frames from the live WebRTC
 * track and encodes each to JPEG without pausing the stream.
 *
 * [onEachPhoto] is called on the WebRTC frame-delivery thread for every captured
 * frame; implementations should offload heavy work (I/O, network) to a coroutine.
 * [onComplete] fires once all frames have been delivered.
 */
class BurstPhotoCapturer(
    private val totalCount: Int,
    private val onEachPhoto: (jpegBytes: ByteArray, index: Int) -> Unit,
    private val onComplete: () -> Unit,
    private val jpegQuality: Int = 85
) : VideoSink {

    companion object {
        private const val TAG = "BurstPhotoCapturer"
    }

    private val capturedCount = AtomicInteger(0)
    private val done = AtomicBoolean(false)

    override fun onFrame(frame: VideoFrame) {
        if (done.get()) return
        val index = capturedCount.getAndIncrement()
        if (index >= totalCount) {
            if (done.compareAndSet(false, true)) onComplete()
            return
        }

        val buffer = frame.buffer
        buffer.retain()
        try {
            val i420 = buffer.toI420()
                ?: run { Log.w(TAG, "toI420() null for frame $index"); return }
            try {
                val jpeg = encodeToJpeg(i420, frame.rotation, jpegQuality)
                onEachPhoto(jpeg, index)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to encode burst frame $index", e)
            } finally {
                i420.release()
            }
        } finally {
            buffer.release()
        }

        if (capturedCount.get() >= totalCount && done.compareAndSet(false, true)) {
            onComplete()
        }
    }

    private fun encodeToJpeg(
        buffer: VideoFrame.I420Buffer,
        rotationDegrees: Int,
        quality: Int
    ): ByteArray {
        val width = buffer.width
        val height = buffer.height
        val nv21 = ByteArray(width * height * 3 / 2)

        val dataY = buffer.dataY
        val strideY = buffer.strideY
        var offset = 0
        for (row in 0 until height) {
            dataY.position(row * strideY)
            dataY.get(nv21, offset, width)
            offset += width
        }

        val dataU = buffer.dataU
        val dataV = buffer.dataV
        val strideU = buffer.strideU
        val strideV = buffer.strideV
        val chromaHeight = height / 2
        val chromaWidth = width / 2
        for (row in 0 until chromaHeight) {
            val baseV = row * strideV
            val baseU = row * strideU
            for (col in 0 until chromaWidth) {
                nv21[offset++] = dataV.get(baseV + col)
                nv21[offset++] = dataU.get(baseU + col)
            }
        }

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val rawStream = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), quality, rawStream)
        val rawJpeg = rawStream.toByteArray()

        if (rotationDegrees == 0) return rawJpeg

        val bitmap = BitmapFactory.decodeByteArray(rawJpeg, 0, rawJpeg.size) ?: return rawJpeg
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        val rotatedStream = ByteArrayOutputStream()
        rotated.compress(Bitmap.CompressFormat.JPEG, quality, rotatedStream)
        if (rotated !== bitmap) bitmap.recycle()
        rotated.recycle()
        return rotatedStream.toByteArray()
    }
}
