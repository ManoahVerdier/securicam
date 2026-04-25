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

/**
 * One-shot [VideoSink] that captures a single frame from the live WebRTC video
 * track, converts it to JPEG, and hands the bytes to [onPhoto].
 *
 * The sink detaches itself from the track on the first delivered frame so it
 * never holds the encoder pipeline.
 */
class PhotoCapturer(
    private val onPhoto: (jpegBytes: ByteArray, widthPx: Int, heightPx: Int) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val jpegQuality: Int = 90
) : VideoSink {

    companion object {
        private const val TAG = "PhotoCapturer"
    }

    private val captured = AtomicBoolean(false)

    override fun onFrame(frame: VideoFrame) {
        if (!captured.compareAndSet(false, true)) return
        // Retain the frame's buffer until we are done with it (libwebrtc requires this).
        val buffer = frame.buffer
        buffer.retain()
        try {
            val i420 = buffer.toI420()
                ?: throw IllegalStateException("toI420() returned null for frame buffer")
            try {
                val jpeg = encodeI420ToJpeg(i420, frame.rotation, jpegQuality)
                val (w, h) = if (frame.rotation % 180 == 0) {
                    i420.width to i420.height
                } else {
                    i420.height to i420.width
                }
                onPhoto(jpeg, w, h)
            } finally {
                i420.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encode photo", e)
            onError(e)
        } finally {
            buffer.release()
        }
    }

    private fun encodeI420ToJpeg(
        buffer: VideoFrame.I420Buffer,
        rotationDegrees: Int,
        quality: Int
    ): ByteArray {
        val width = buffer.width
        val height = buffer.height
        val nv21 = ByteArray(width * height * 3 / 2)

        // Copy the Y plane row by row to skip stride padding.
        val dataY = buffer.dataY
        val strideY = buffer.strideY
        var offset = 0
        for (row in 0 until height) {
            dataY.position(row * strideY)
            dataY.get(nv21, offset, width)
            offset += width
        }

        // Interleave V then U (NV21 layout) row by row.
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
        val rawJpegStream = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), quality, rawJpegStream)
        val rawJpeg = rawJpegStream.toByteArray()

        if (rotationDegrees == 0) {
            return rawJpeg
        }

        // Re-encode after applying rotation so the saved file is in the expected orientation.
        val bitmap = BitmapFactory.decodeByteArray(rawJpeg, 0, rawJpeg.size)
            ?: return rawJpeg
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        val rotated = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
        )
        val rotatedStream = ByteArrayOutputStream()
        rotated.compress(Bitmap.CompressFormat.JPEG, quality, rotatedStream)
        if (rotated !== bitmap) bitmap.recycle()
        rotated.recycle()
        return rotatedStream.toByteArray()
    }
}
