package com.securicam.camera.webrtc

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import android.view.Surface
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Records the live WebRTC video pipeline to an MP4 file using a [MediaCodec]
 * H.264 encoder fed by an [EglRenderer] that draws each [VideoFrame] onto the
 * encoder's input [Surface]. The output is multiplexed by [MediaMuxer].
 *
 * The encoder is lazily initialized on the first frame so we know the
 * effective video dimensions (rotation-aware).
 *
 * Call [stop] to finalize the file. The [onCompleted] callback is invoked once
 * the muxer has closed and the file is ready to upload.
 */
class VideoRecorder(
    private val sharedContext: EglBase.Context,
    private val outputFile: File,
    private val bitrateBps: Int = 6_000_000,
    private val frameRate: Int = 30,
    private val onCompleted: (success: Boolean, file: File, durationMs: Long) -> Unit
) : VideoSink {

    companion object {
        private const val TAG = "VideoRecorder"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val IFRAME_INTERVAL_S = 2
    }

    private val initialized = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private val eosSignaled = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)

    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var muxerVideoTrack: Int = -1
    private var muxerStarted = false
    private var inputSurface: Surface? = null
    private var encoderEglBase: EglBase? = null
    private val eglRenderer: EglRenderer = EglRenderer("VideoRecorder")
    private var rendererInitialized = false
    private var encoderWidth = 0
    private var encoderHeight = 0

    private var startTimeNs: Long = 0L
    private var lastPresentationTimeUs: Long = -1L
    private var drainThread: Thread? = null

    /** Asynchronously stops recording and finalizes the output file. */
    fun stop() {
        stopRequested.set(true)
    }

    override fun onFrame(frame: VideoFrame) {
        if (finished.get()) return

        if (!initialized.get()) {
            try {
                initializeFromFirstFrame(frame)
                initialized.set(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize VideoRecorder", e)
                cleanup()
                if (finished.compareAndSet(false, true)) {
                    onCompleted(false, outputFile, 0L)
                }
                return
            }
        }

        if (stopRequested.get()) {
            finalizeRecording()
            return
        }

        // Render the frame onto the encoder's input surface; EglRenderer applies
        // the WebRTC frame's rotation transform automatically.
        try {
            eglRenderer.onFrame(frame)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to render frame", e)
        }
    }

    private fun initializeFromFirstFrame(frame: VideoFrame) {
        // Effective dimensions after applying frame.rotation
        val rawWidth = frame.buffer.width
        val rawHeight = frame.buffer.height
        val (w, h) = if (frame.rotation % 180 == 0) rawWidth to rawHeight else rawHeight to rawWidth
        // H.264 typically requires even dimensions
        encoderWidth = w and 1.inv()
        encoderHeight = h and 1.inv()

        Log.i(TAG, "Initializing encoder ${encoderWidth}x${encoderHeight}@${frameRate}fps bitrate=${bitrateBps}")

        val format = MediaFormat.createVideoFormat(MIME_TYPE, encoderWidth, encoderHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL_S)
        }

        val codec = MediaCodec.createEncoderByType(MIME_TYPE)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()
        encoder = codec
        inputSurface = surface

        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        // Initialize the EglRenderer that draws each WebRTC frame onto the
        // encoder's input surface. The renderer manages its own GL thread.
        eglRenderer.init(sharedContext, EglBase.CONFIG_RECORDABLE, GlRectDrawer())
        eglRenderer.createEglSurface(surface)
        rendererInitialized = true

        startTimeNs = System.nanoTime()
        startDrainThread()
    }

    private fun startDrainThread() {
        drainThread = thread(name = "VideoRecorder-drain", isDaemon = true) {
            val bufferInfo = MediaCodec.BufferInfo()
            try {
                while (!finished.get()) {
                    val codec = encoder ?: break
                    val outIndex = try {
                        codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                    } catch (e: IllegalStateException) {
                        Log.w(TAG, "Encoder in invalid state during drain", e)
                        break
                    }
                    when {
                        outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            // No output yet; loop.
                        }
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (muxerStarted) {
                                Log.w(TAG, "Output format changed twice; ignoring")
                            } else {
                                val newFormat = codec.outputFormat
                                muxerVideoTrack = muxer?.addTrack(newFormat) ?: -1
                                muxer?.start()
                                muxerStarted = true
                            }
                        }
                        outIndex >= 0 -> {
                            val outputBuffer: ByteBuffer = codec.getOutputBuffer(outIndex)
                                ?: throw IllegalStateException("getOutputBuffer($outIndex) returned null")

                            // Codec config (SPS/PPS) is folded into the format header.
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                bufferInfo.size = 0
                            }

                            if (bufferInfo.size > 0 && muxerStarted) {
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                // Build a monotonic presentation time relative to recording start.
                                val pts = (System.nanoTime() - startTimeNs) / 1000L
                                bufferInfo.presentationTimeUs = if (pts <= lastPresentationTimeUs) {
                                    lastPresentationTimeUs + 1L
                                } else pts
                                lastPresentationTimeUs = bufferInfo.presentationTimeUs
                                try {
                                    muxer?.writeSampleData(muxerVideoTrack, outputBuffer, bufferInfo)
                                } catch (e: Exception) {
                                    Log.w(TAG, "writeSampleData failed", e)
                                }
                            }

                            codec.releaseOutputBuffer(outIndex, false)

                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                Log.i(TAG, "Encoder reported EOS")
                                break
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Drain thread error", e)
            } finally {
                completeAndCleanup()
            }
        }
    }

    private fun finalizeRecording() {
        if (finished.get()) return
        if (!eosSignaled.compareAndSet(false, true)) return
        try {
            encoder?.signalEndOfInputStream()
        } catch (e: Exception) {
            Log.w(TAG, "signalEndOfInputStream failed", e)
        }
    }

    private fun completeAndCleanup() {
        if (!finished.compareAndSet(false, true)) return
        val durationMs = if (startTimeNs > 0L) {
            (System.nanoTime() - startTimeNs) / 1_000_000L
        } else 0L
        cleanup()
        try {
            onCompleted(true, outputFile, durationMs)
        } catch (e: Exception) {
            Log.w(TAG, "onCompleted callback failed", e)
        }
    }

    private fun cleanup() {
        if (rendererInitialized) {
            try {
                val latch = java.util.concurrent.CountDownLatch(1)
                eglRenderer.releaseEglSurface { latch.countDown() }
                latch.await(1, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: Exception) {
                Log.w(TAG, "releaseEglSurface failed", e)
            }
            try { eglRenderer.release() } catch (e: Exception) { Log.w(TAG, "EglRenderer.release", e) }
            rendererInitialized = false
        }
        try { encoder?.stop() } catch (e: Exception) { Log.w(TAG, "encoder.stop", e) }
        try { encoder?.release() } catch (e: Exception) { Log.w(TAG, "encoder.release", e) }
        encoder = null
        try { inputSurface?.release() } catch (e: Exception) { Log.w(TAG, "surface.release", e) }
        inputSurface = null
        if (muxerStarted) {
            try { muxer?.stop() } catch (e: Exception) { Log.w(TAG, "muxer.stop", e) }
        }
        try { muxer?.release() } catch (e: Exception) { Log.w(TAG, "muxer.release", e) }
        muxer = null
        encoderEglBase?.release()
        encoderEglBase = null
    }
}
