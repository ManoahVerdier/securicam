package com.securicam.camera.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import android.content.ContentValues
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
import com.securicam.camera.webrtc.BurstPhotoCapturer
import com.securicam.camera.webrtc.LensCatalog
import com.securicam.camera.webrtc.PhotoCapturer
import com.securicam.camera.webrtc.VideoRecorder
import com.securicam.camera.webrtc.WebRtcClient
import com.securicam.camera.api.SignalingClient
import org.webrtc.PeerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

        // Grace period before tearing down the camera after a transient ICE
        // DISCONNECTED, so a brief mobile-network blip doesn't kill a live stream.
        private const val CONNECTION_LOSS_GRACE_MS = 10_000L

        private const val PREFS_NAME = "securicam_prefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_CAMERA_ID = "camera_id"
        private const val KEY_TURN_HOST = "turn_host"
        private const val KEY_TURN_USER = "turn_user"
        private const val KEY_TURN_PASSWORD = "turn_password"

        private val RECONNECT_DELAYS_MS = longArrayOf(5_000, 15_000, 30_000, 60_000, 300_000)
    }

    private val binder = LocalBinder()
    private var cameraExecutor: ExecutorService? = null
    private var cameraProvider: ProcessCameraProvider? = null

    private var webRtcClient: WebRtcClient? = null
    private var signalingClient: SignalingClient? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var heartbeatManager: HeartbeatManager
    private var reconnectJob: Job? = null
    private var connectionLossJob: Job? = null
    private var reconnectAttempts: Int = 0
    private var isStopping: Boolean = false

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
    private var isCapturingPhoto: Boolean = false
    private var activeBurstCapturer: BurstPhotoCapturer? = null
    private var continuousJob: Job? = null

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

    fun reconnect() {
        if (isReconnecting) return
        cancelReconnect()
        reconnectAttempts = 0
        serviceScope.launch {
            isReconnecting = true
            try {
                disconnectSignaling()
                if (ensureSignalingConnected()) {
                    reconnectAttempts = 0
                    val activeLens = webRtcClient?.activeLensId ?: LensCatalog.defaultLensId(applicationContext)
                    signalingClient?.notifyConnect(
                        availableLenses = lensCatalogPayload(),
                        activeLens = activeLens
                    )
                    startHeartbeat()
                    Log.i(TAG, "Reconnected successfully")
                } else {
                    Log.w(TAG, "Reconnect failed — scheduling auto-reconnect")
                    scheduleAutoReconnect()
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
                isStopping = false
                loadConfiguration(intent)
                startForegroundService()
                connectSignalingIfNeeded()
            }
            ACTION_START -> {
                isStopping = false
                loadConfiguration(intent)
                startForegroundService()
                connectSignalingIfNeeded()
            }
            ACTION_STOP -> {
                isStopping = true
                cancelReconnect()
                stopHeartbeat()
                stopStreaming(keepReady = false)
                serviceScope.launch {
                    signalingClient?.notifyDisconnect()
                    disconnectSignaling()
                    stopSelf()
                }
            }
            else -> {
                // Restarted by START_STICKY with null intent — restore config from prefs
                if (!isStopping && serverUrl.isEmpty()) {
                    loadConfigurationFromPrefs()
                }
                if (!isStopping && serverUrl.isNotEmpty() && authToken.isNotEmpty() && cameraId > 0) {
                    startForegroundService()
                    connectSignalingIfNeeded()
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
            val type = if (hasCameraPermission()) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
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

    private fun loadConfigurationFromPrefs() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        serverUrl = prefs.getString(KEY_SERVER_URL, "") ?: ""
        authToken = prefs.getString(KEY_AUTH_TOKEN, "") ?: ""
        cameraId = prefs.getInt(KEY_CAMERA_ID, 0)
        turnHost = prefs.getString(KEY_TURN_HOST, "") ?: ""
        turnUser = prefs.getString(KEY_TURN_USER, "") ?: ""
        turnPassword = prefs.getString(KEY_TURN_PASSWORD, "") ?: ""
    }

    private fun scheduleAutoReconnect() {
        if (isStopping) return
        cancelReconnect()
        val delayMs = RECONNECT_DELAYS_MS.getOrElse(reconnectAttempts) { RECONNECT_DELAYS_MS.last() }
        reconnectAttempts++
        Log.i(TAG, "WebSocket disconnected — reconnecting in ${delayMs}ms (attempt #$reconnectAttempts)")
        reconnectJob = serviceScope.launch {
            delay(delayMs)
            if (!isStopping) {
                connectSignalingIfNeeded()
            }
        }
    }

    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    private fun connectSignalingIfNeeded() {
        serviceScope.launch {
            isConnecting = true
            try {
                if (ensureSignalingConnected()) {
                    reconnectAttempts = 0
                    val activeLens = webRtcClient?.activeLensId ?: LensCatalog.defaultLensId(applicationContext)
                    signalingClient?.notifyConnect(
                        availableLenses = lensCatalogPayload(),
                        activeLens = activeLens
                    )
                    startHeartbeat()
                } else {
                    Log.w(TAG, "Signaling connection failed — will retry")
                    scheduleAutoReconnect()
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
                    capturePhotoClassic()
                },
                onCapturePhotoHd = {
                    capturePhotoHD()
                },
                onCaptureBurstClassic = { count ->
                    startBurstClassic(count)
                },
                onContinuousStartClassic = {
                    startContinuousClassic()
                },
                onContinuousStartHd = {
                    startContinuousHD()
                },
                onContinuousStop = {
                    stopContinuous()
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
                },
                onDisconnected = {
                    Log.w(TAG, "WebSocket dropped — scheduling auto-reconnect")
                    serviceScope.launch {
                        signalingClient = null
                        stopHeartbeat()
                        scheduleAutoReconnect()
                    }
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
            if (webRtcClient != null) {
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

                val iceServers = WebRtcClient.buildIceServers(turnHost, turnUser, turnPassword)
                webRtcClient = WebRtcClient(applicationContext, cameraProvider!!, this@CameraService, iceServers)

                webRtcClient?.setConnectionStateCallback { state ->
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> {
                            cancelConnectionLoss()
                            if (!isStreaming) {
                                isStreaming = true
                                isStreamStarting = false
                                serviceScope.launch { updateStatus("streaming") }
                            }
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED -> {
                            // Often transient on mobile — give ICE a chance to recover
                            // before tearing the camera down.
                            if (isStreaming) scheduleConnectionLossTeardown()
                        }
                        PeerConnection.IceConnectionState.FAILED -> {
                            // Terminal: the viewer is gone. Release the camera/encoder now
                            // so the sensor stops capturing (heat + battery drain).
                            cancelConnectionLoss()
                            if (isStreaming) stopStreaming(keepReady = true)
                        }
                        else -> {}
                    }
                }

                webRtcClient?.setIceCandidateCallback { candidate ->
                    serviceScope.launch {
                        signalingClient?.sendIceCandidate(candidate)
                    }
                }

                webRtcClient?.createOffer { sdp ->
                    serviceScope.launch {
                        signalingClient?.sendOffer(sdp)
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start WebRTC streaming", e)
                isStreaming = false
                isStreamStarting = false
            }
        }
    }

    private fun scheduleConnectionLossTeardown() {
        if (connectionLossJob != null) return
        connectionLossJob = serviceScope.launch {
            delay(CONNECTION_LOSS_GRACE_MS)
            connectionLossJob = null
            if (isStreaming) {
                Log.i(TAG, "ICE still disconnected after grace — releasing camera to stop battery drain")
                stopStreaming(keepReady = true)
            }
        }
    }

    private fun cancelConnectionLoss() {
        connectionLossJob?.cancel()
        connectionLossJob = null
    }

    private fun stopStreaming(keepReady: Boolean) {
        cancelConnectionLoss()
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

    private fun capturePhotoClassic() {
        Log.d(TAG, "Capturing classic photo (WebRTC frame)...")
        val client = webRtcClient ?: run { Log.w(TAG, "capturePhotoClassic: no WebRTC client"); return }
        if (isCapturingPhoto) { Log.d(TAG, "capturePhotoClassic: already in progress"); return }
        isCapturingPhoto = true

        val outDir = File(filesDir, "captures").apply { mkdirs() }
        val photoFile = File(outDir, "photo_${System.currentTimeMillis()}.jpg")

        val capturer = PhotoCapturer(
            onPhoto = { jpegBytes, _, _ ->
                serviceScope.launch {
                    try {
                        photoFile.writeBytes(jpegBytes)
                        val ok = signalingClient?.uploadCapture(type = "photo", file = photoFile) ?: false
                        Log.i(TAG, "Classic photo upload ${if (ok) "OK" else "FAILED"} (${photoFile.length()} bytes)")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save/upload classic photo", e)
                    } finally {
                        photoFile.delete()
                        isCapturingPhoto = false
                    }
                }
            },
            onError = { error ->
                Log.e(TAG, "Classic photo capture error", error)
                isCapturingPhoto = false
            }
        )
        client.addVideoSink(capturer)
    }

    private fun capturePhotoHD() {
        Log.d(TAG, "Capturing HD photo (CameraX ImageCapture)...")
        val client = webRtcClient ?: run { Log.w(TAG, "capturePhotoHD: no WebRTC client"); return }
        if (isCapturingPhoto) { Log.d(TAG, "capturePhotoHD: already in progress"); return }
        isCapturingPhoto = true

        val outDir = File(filesDir, "captures").apply { mkdirs() }
        val photoFile = File(outDir, "photo_hd_${System.currentTimeMillis()}.jpg")

        client.takePicture(photoFile) { success ->
            serviceScope.launch {
                isCapturingPhoto = false
                if (success && photoFile.exists() && photoFile.length() > 0) {
                    try {
                        val ok = signalingClient?.uploadCapture(type = "photo", file = photoFile) ?: false
                        Log.i(TAG, "HD photo upload ${if (ok) "OK" else "FAILED"} (${photoFile.length()} bytes)")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to upload HD photo", e)
                    }
                } else {
                    Log.w(TAG, "HD capture failed or empty file")
                }
                photoFile.delete()
            }
        }
    }

    private fun startBurstClassic(count: Int) {
        Log.d(TAG, "Starting burst classic ($count photos)...")
        val client = webRtcClient ?: run { Log.w(TAG, "startBurstClassic: no WebRTC client"); return }
        if (activeBurstCapturer != null) { Log.d(TAG, "startBurstClassic: burst already active"); return }

        val outDir = File(filesDir, "captures").apply { mkdirs() }
        var uploadedCount = 0

        val burstCapturer = BurstPhotoCapturer(
            totalCount = count,
            onEachPhoto = { jpegBytes, index ->
                serviceScope.launch {
                    val photoFile = File(outDir, "burst_${System.currentTimeMillis()}_${index}.jpg")
                    try {
                        photoFile.writeBytes(jpegBytes)
                        val ok = signalingClient?.uploadCapture(type = "photo", file = photoFile) ?: false
                        if (ok) uploadedCount++
                        Log.d(TAG, "Burst frame $index upload ${if (ok) "OK" else "FAILED"}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Burst frame $index upload error", e)
                    } finally {
                        photoFile.delete()
                    }
                }
            },
            onComplete = {
                serviceScope.launch {
                    Log.i(TAG, "Burst complete: $uploadedCount/$count uploaded")
                    activeBurstCapturer?.let { client.removeVideoSink(it) }
                    activeBurstCapturer = null
                }
            }
        )

        activeBurstCapturer = burstCapturer
        client.addVideoSink(burstCapturer)
    }

    private fun startContinuousClassic() {
        Log.d(TAG, "Starting continuous classic capture...")
        if (continuousJob?.isActive == true) { Log.d(TAG, "startContinuousClassic: already active"); return }

        continuousJob = serviceScope.launch {
            var count = 0
            while (isActive) {
                val client = webRtcClient
                if (client == null) { delay(1000); continue }

                val outDir = File(filesDir, "captures").apply { mkdirs() }
                val photoFile = File(outDir, "cont_${System.currentTimeMillis()}.jpg")

                val latch = java.util.concurrent.CountDownLatch(1)
                val photoCapturer = PhotoCapturer(
                    onPhoto = { jpegBytes, _, _ ->
                        serviceScope.launch {
                            try {
                                photoFile.writeBytes(jpegBytes)
                                signalingClient?.uploadCapture(type = "photo", file = photoFile)
                                count++
                            } catch (e: Exception) {
                                Log.e(TAG, "Continuous classic upload error", e)
                            } finally {
                                photoFile.delete()
                                latch.countDown()
                            }
                        }
                    },
                    onError = { latch.countDown() }
                )
                client.addVideoSink(photoCapturer)
                // Wait for the frame to be captured before taking the next one
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
                }
                client.removeVideoSink(photoCapturer)
                delay(200)
            }
            Log.i(TAG, "Continuous classic stopped after $count photos")
        }
    }

    private fun startContinuousHD() {
        Log.d(TAG, "Starting continuous HD capture (saved to DCIM)...")
        if (continuousJob?.isActive == true) { Log.d(TAG, "startContinuousHD: already active"); return }

        continuousJob = serviceScope.launch {
            var count = 0
            while (isActive) {
                val client = webRtcClient
                if (client == null) { delay(1000); continue }

                val tempFile = File(filesDir, "captures/cont_hd_${System.currentTimeMillis()}.jpg")
                    .also { it.parentFile?.mkdirs() }

                var success = false
                val latch = java.util.concurrent.CountDownLatch(1)
                client.takePicture(tempFile) { ok ->
                    success = ok
                    latch.countDown()
                }
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
                }

                if (success && tempFile.exists() && tempFile.length() > 0) {
                    saveToDcim(tempFile, "securicam_hd_${System.currentTimeMillis()}.jpg")
                    count++
                }
                tempFile.delete()
                // Small gap between HD shots to let the sensor stabilize
                delay(500)
            }
            Log.i(TAG, "Continuous HD stopped after $count photos")
        }
    }

    private fun stopContinuous() {
        Log.d(TAG, "Stopping continuous capture")
        continuousJob?.cancel()
        continuousJob = null
    }

    private fun saveToDcim(sourceFile: File, displayName: String) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Securicam")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
            resolver.openOutputStream(uri)?.use { out -> sourceFile.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            Log.d(TAG, "Saved to DCIM/Securicam/$displayName")
        } catch (e: Exception) {
            Log.e(TAG, "saveToDcim failed", e)
        }
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
        isStopping = true
        cancelReconnect()
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
