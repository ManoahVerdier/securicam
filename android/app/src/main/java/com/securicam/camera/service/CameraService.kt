package com.securicam.camera.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.securicam.camera.R
import com.securicam.camera.SecuricamApp
import com.securicam.camera.ui.MainActivity
import com.securicam.camera.webrtc.LensCatalog
import com.securicam.camera.webrtc.PhotoCapturer
import com.securicam.camera.webrtc.VideoRecorder
import com.securicam.camera.webrtc.WebRtcClient
import com.securicam.camera.api.SignalingClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraService : LifecycleService() {

    companion object {
        private const val TAG = "CameraService"
        const val NOTIFICATION_ID = 1001

        const val ACTION_PREPARE = "com.securicam.camera.action.PREPARE"
        const val ACTION_START = "com.securicam.camera.action.START"
        const val ACTION_STOP = "com.securicam.camera.action.STOP"

        const val EXTRA_SERVER_URL = "extra_server_url"
        const val EXTRA_AUTH_TOKEN = "extra_auth_token"
        const val EXTRA_CAMERA_ID = "extra_camera_id"
        const val EXTRA_TURN_HOST = "extra_turn_host"
        const val EXTRA_TURN_USER = "extra_turn_user"
        const val EXTRA_TURN_PASSWORD = "extra_turn_password"

        private const val RENEGOTIATION_DEBOUNCE_MS = 1500L
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
    }

    private val binder = LocalBinder()
    private var cameraExecutor: ExecutorService? = null
    private var cameraProvider: ProcessCameraProvider? = null

    private var webRtcClient: WebRtcClient? = null
    private var signalingClient: SignalingClient? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var heartbeatManager: HeartbeatManager

    private var serverUrl: String = ""
    private var authToken: String = ""
    private var cameraId: Int = 0
    private var turnHost: String = ""
    private var turnUser: String = ""
    private var turnPassword: String = ""
    private var isStreamStarting: Boolean = false
    private val signalingConnectMutex = Mutex()
    private var lastRenegotiationAt: Long = 0L
    private var activeRecorder: VideoRecorder? = null
    private var activePhotoCapturer: PhotoCapturer? = null

    var isStreaming: Boolean = false
        private set

    val isSignalingConnected: Boolean
        get() = signalingClient != null

    val configuredServerUrl: String
        get() = serverUrl

    val configuredCameraId: Int
        get() = cameraId

    /** True while the initial signaling connection is being established. */
    var isConnecting: Boolean = false
        private set

    /** True while a reconnection attempt is in progress. */
    var isReconnecting: Boolean = false
        private set

    inner class LocalBinder : Binder() {
        fun getService(): CameraService = this@CameraService
    }

    /**
     * Force a fresh signaling connection without restarting the whole service.
     * Use this when the WebSocket was lost mid-session.
     */
    fun reconnect() {
        if (isReconnecting) return
        serviceScope.launch {
            isReconnecting = true
            try {
                disconnectSignaling()
                if (ensureSignalingConnected()) {
                    val activeLens = webRtcClient?.activeLensId ?: LensCatalog.defaultLensId(applicationContext)
                    signalingClient?.notifyConnect(
                        availableLenses = lensCatalogPayload(),
                        activeLens = activeLens
                    )
                    startHeartbeat()
                    Log.i(TAG, "Reconnected successfully")
                } else {
                    Log.w(TAG, "Reconnect failed")
                }
            } finally {
                isReconnecting = false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        heartbeatManager = HeartbeatManager(serviceScope) {
            val activeLens = webRtcClient?.activeLensId ?: LensCatalog.defaultLensId(applicationContext)
            signalingClient?.notifyHeartbeat(
                availableLenses = lensCatalogPayload(),
                activeLens = activeLens
            )
        }
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_PREPARE -> {
                loadConfiguration(intent)
                startForegroundService()
                connectSignalingIfNeeded()
            }
            ACTION_START -> {
                loadConfiguration(intent)
                startForegroundService()
                connectSignalingIfNeeded()
            }
            ACTION_STOP -> {
                stopHeartbeat()
                stopStreaming(keepReady = false)
                serviceScope.launch {
                    signalingClient?.notifyDisconnect()
                    disconnectSignaling()
                    stopSelf()
                }
            }
        }

        return START_STICKY
    }

    private fun startHeartbeat() {
        heartbeatManager.start()
    }

    private fun lensCatalogPayload(): List<Map<String, String>> =
        try {
            LensCatalog.enumerate(applicationContext).map {
                mapOf("id" to it.id, "facing" to it.facing, "label" to it.label)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enumerate lenses", e)
            emptyList()
        }

    private fun stopHeartbeat() {
        heartbeatManager.stop()
    }

    private fun startForegroundService() {
        val notification = createNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, CameraService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, SecuricamApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Securicam")
            .setContentText(
                when {
                    isStreaming -> "Camera is streaming..."
                    isStreamStarting -> "Camera is starting stream..."
                    else -> "Camera is ready to stream"
                }
            )
            .setSmallIcon(R.drawable.ic_camera)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_stop, "Stop", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun loadConfiguration(intent: Intent) {
        serverUrl = intent.getStringExtra(EXTRA_SERVER_URL) ?: ""
        authToken = intent.getStringExtra(EXTRA_AUTH_TOKEN) ?: ""
        cameraId = intent.getIntExtra(EXTRA_CAMERA_ID, 0)
        turnHost = intent.getStringExtra(EXTRA_TURN_HOST) ?: ""
        turnUser = intent.getStringExtra(EXTRA_TURN_USER) ?: ""
        turnPassword = intent.getStringExtra(EXTRA_TURN_PASSWORD) ?: ""
    }

    private fun connectSignalingIfNeeded() {
        serviceScope.launch {
            isConnecting = true
            try {
                if (ensureSignalingConnected()) {
                    val activeLens = webRtcClient?.activeLensId ?: LensCatalog.defaultLensId(applicationContext)
                    signalingClient?.notifyConnect(
                        availableLenses = lensCatalogPayload(),
                        activeLens = activeLens
                    )
                    startHeartbeat()
                } else {
                    Log.w(TAG, "Signaling connection is not ready")
                }
            } finally {
                isConnecting = false
            }
        }
    }

    private suspend fun ensureSignalingConnected(): Boolean = signalingConnectMutex.withLock {
        signalingClient?.let { return@withLock true }

        if (serverUrl.isEmpty() || authToken.isEmpty() || cameraId == 0) {
            Log.w(TAG, "Cannot connect signaling: missing configuration")
            return@withLock false
        }

        val client = SignalingClient(serverUrl, authToken, cameraId)
        val isConnected = try {
            client.connect(
                onOffer = { _ ->
                    // Viewer answers camera offers in this flow, so incoming offers are ignored.
                },
                onAnswer = { sdp ->
                    webRtcClient?.handleAnswer(sdp)
                },
                onIceCandidate = { candidate ->
                    webRtcClient?.addIceCandidate(candidate)
                },
                onCapturePhoto = {
                    capturePhoto()
                },
                onStartStreaming = {
                    startStreamingIfNeeded()
                },
                onStopStreaming = {
                    stopStreaming(keepReady = true)
                },
                onStartRecording = {
                    startRecording()
                },
                onStopRecording = {
                    stopRecording()
                },
                onSwitchCamera = { lensId ->
                    switchPhoneCamera(lensId)
                },
                onError = { error ->
                    Log.e(TAG, "Signaling error: $error")
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Signaling connection failed", e)
            false
        }

        if (isConnected) {
            signalingClient = client
        } else {
            client.disconnect()
            signalingClient = null
        }

        return@withLock isConnected
    }

    private fun disconnectSignaling() {
        signalingClient?.disconnect()
        signalingClient = null
    }

    private fun startStreamingIfNeeded() {
        if (isStreaming || isStreamStarting) {
            // Already streaming: a (new) viewer just asked for the stream.
            // Re-issue an offer so the freshly-subscribed peer receives it.
            if (webRtcClient != null) {
                // Debounce: the web viewer can fire several start requests in a row
                // (initial connect + status retry). Skip extra renegotiations within
                // a short window — they cause libwebrtc to recreate the encoder and
                // briefly tear down the active camera capture session.
                val now = System.currentTimeMillis()
                if (now - lastRenegotiationAt < RENEGOTIATION_DEBOUNCE_MS) {
                    Log.d(TAG, "startStreamingIfNeeded: skipping duplicate renegotiation (debounced)")
                    return
                }
                lastRenegotiationAt = now
                Log.d(TAG, "startStreamingIfNeeded: already streaming, renegotiating offer for new viewer")
                webRtcClient?.createOffer { sdp ->
                    serviceScope.launch {
                        signalingClient?.sendOffer(sdp)
                    }
                }
            }
            return
        }
        isStreamStarting = true
        initializeCamera()
    }

    private fun initializeCamera() {
        if (!hasCameraPermission()) {
            Log.e(TAG, "Camera permission not granted")
            isStreamStarting = false
            stopSelf()
            return
        }

        if (cameraProvider != null) {
            startWebRtcStreaming()
            return
        }

        if (cameraExecutor == null) {
            cameraExecutor = Executors.newSingleThreadExecutor()
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                startWebRtcStreaming()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get camera provider", e)
                isStreamStarting = false
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startWebRtcStreaming() {
        if (serverUrl.isEmpty() || authToken.isEmpty() || cameraId == 0) {
            Log.e(TAG, "Missing configuration for WebRTC streaming")
            return
        }

        serviceScope.launch {
            try {
                val isSignalingReady = ensureSignalingConnected()
                if (!isSignalingReady) {
                    throw IllegalStateException("Signaling is not connected")
                }

                updateStatus("online")

                // Initialize WebRTC
                val iceServers = WebRtcClient.buildIceServers(turnHost, turnUser, turnPassword)
                webRtcClient = WebRtcClient(applicationContext, cameraProvider!!, this@CameraService, iceServers)

                // Set callbacks before creating the offer so no early candidates are lost
                webRtcClient?.setConnectionStateCallback { connected ->
                    if (connected && !isStreaming) {
                        isStreaming = true
                        isStreamStarting = false
                        serviceScope.launch { updateStatus("streaming") }
                    } else if (!connected && isStreaming) {
                        isStreaming = false
                        serviceScope.launch { updateStatus("online") }
                    }
                }

                webRtcClient?.setIceCandidateCallback { candidate ->
                    serviceScope.launch {
                        signalingClient?.sendIceCandidate(candidate)
                    }
                }

                // Create and send offer
                webRtcClient?.createOffer { sdp ->
                    serviceScope.launch {
                        signalingClient?.sendOffer(sdp)
                    }
                }

                // isStreaming will be set to true by the connection-state callback
                // when ICE reaches CONNECTED; isStreamStarting was set by the caller.

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start WebRTC streaming", e)
                isStreaming = false
                isStreamStarting = false
            }
        }
    }

    private fun stopStreaming(keepReady: Boolean) {
        serviceScope.launch {
            isStreaming = false
            isStreamStarting = false
            updateStatus(if (keepReady) "online" else "offline")

            webRtcClient?.release()
            webRtcClient = null

            cameraProvider?.unbindAll()
            cameraExecutor?.shutdown()
            cameraExecutor = null
            cameraProvider = null
        }
    }

    private suspend fun updateStatus(status: String) {
        signalingClient?.updateCameraStatus(status)
    }

    private fun capturePhoto() {
        Log.d(TAG, "Capturing photo...")
        val client = webRtcClient
        if (client == null) {
            Log.w(TAG, "capturePhoto: WebRTC client not initialized; ignoring")
            return
        }
        if (activePhotoCapturer != null) {
            Log.d(TAG, "capturePhoto: already in progress")
            return
        }

        val outDir = File(filesDir, "captures").apply { mkdirs() }
        val photoFile = File(outDir, "photo_${System.currentTimeMillis()}.jpg")

        val capturer = PhotoCapturer(
            onPhoto = { jpegBytes, _, _ ->
                serviceScope.launch {
                    try {
                        photoFile.writeBytes(jpegBytes)
                        val ok = signalingClient?.uploadCapture(
                            type = "photo",
                            file = photoFile
                        ) ?: false
                        Log.i(TAG, "Photo upload ${if (ok) "OK" else "FAILED"} (${photoFile.length()} bytes)")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save/upload photo", e)
                    } finally {
                        photoFile.delete()
                        activePhotoCapturer?.let { client.removeVideoSink(it) }
                        activePhotoCapturer = null
                    }
                }
            },
            onError = { error ->
                Log.e(TAG, "Photo capture error", error)
                activePhotoCapturer?.let { client.removeVideoSink(it) }
                activePhotoCapturer = null
            }
        )

        activePhotoCapturer = capturer
        client.addVideoSink(capturer)
    }

    private fun startRecording() {
        Log.d(TAG, "Starting recording...")
        val client = webRtcClient
        if (client == null) {
            Log.w(TAG, "startRecording: WebRTC client not initialized; ignoring")
            return
        }
        if (activeRecorder != null) {
            Log.d(TAG, "startRecording: already recording")
            return
        }
        val eglContext = client.eglBaseContext
        if (eglContext == null) {
            Log.e(TAG, "startRecording: no EGL context available")
            return
        }

        val outDir = File(filesDir, "captures").apply { mkdirs() }
        val videoFile = File(outDir, "video_${System.currentTimeMillis()}.mp4")

        val recorder = VideoRecorder(
            sharedContext = eglContext,
            outputFile = videoFile
        ) { success, file, durationMs ->
            serviceScope.launch {
                if (success && file.exists() && file.length() > 0) {
                    val ok = signalingClient?.uploadCapture(
                        type = "video",
                        file = file,
                        durationSeconds = (durationMs / 1000L).coerceAtLeast(1L)
                    ) ?: false
                    Log.i(TAG, "Video upload ${if (ok) "OK" else "FAILED"} (${file.length()} bytes, ${durationMs}ms)")
                } else {
                    Log.w(TAG, "Recording produced no usable file")
                }
                file.delete()
            }
        }

        activeRecorder = recorder
        client.addVideoSink(recorder)
    }

    private fun stopRecording() {
        Log.d(TAG, "Stopping recording...")
        val recorder = activeRecorder ?: run {
            Log.d(TAG, "stopRecording: no active recorder")
            return
        }
        activeRecorder = null
        val client = webRtcClient
        recorder.stop()
        // The recorder finalizes itself on the next frame; remove the sink shortly after
        // to stop feeding it, then it will close its encoder.
        serviceScope.launch {
            delay(500)
            try {
                client?.removeVideoSink(recorder)
            } catch (e: Exception) {
                Log.w(TAG, "removeVideoSink(recorder) failed", e)
            }
        }
    }

    private fun switchPhoneCamera(lensId: String? = null) {
        Log.d(TAG, "Switching phone camera (lensId=$lensId)")
        val client = webRtcClient
        if (client == null) {
            Log.w(TAG, "switchPhoneCamera: WebRTC client not initialized; ignoring")
            return
        }
        client.switchCameraTo(lensId) { success, newLensId ->
            Log.i(TAG, "switchCamera result: success=$success lensId=$newLensId")
            // Push the new active lens to the backend so the web UI reflects it.
            serviceScope.launch {
                try {
                    signalingClient?.notifyHeartbeat(
                        availableLenses = lensCatalogPayload(),
                        activeLens = newLensId
                    )
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        stopHeartbeat()
        stopStreaming(keepReady = false)
        runBlocking {
            try {
                signalingClient?.notifyDisconnect()
            } catch (_: Exception) {
            }
        }
        disconnectSignaling()
        serviceScope.cancel()
        super.onDestroy()
    }
}
