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
import com.securicam.camera.webrtc.WebRtcClient
import com.securicam.camera.api.SignalingClient
import kotlinx.coroutines.*
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
    }

    private val binder = LocalBinder()
    private var cameraExecutor: ExecutorService? = null
    private var cameraProvider: ProcessCameraProvider? = null
    
    private var webRtcClient: WebRtcClient? = null
    private var signalingClient: SignalingClient? = null
    
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private var serverUrl: String = ""
    private var authToken: String = ""
    private var cameraId: Int = 0
    private var isStreamStarting: Boolean = false
    
    var isStreaming: Boolean = false
        private set

    inner class LocalBinder : Binder() {
        fun getService(): CameraService = this@CameraService
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
                serviceScope.launch {
                    updateStatus("online")
                }
            }
            ACTION_START -> {
                loadConfiguration(intent)
                startForegroundService()
                connectSignalingIfNeeded()
                startStreamingIfNeeded()
            }
            ACTION_STOP -> {
                stopStreaming(keepReady = false)
                disconnectSignaling()
                stopSelf()
            }
        }

        return START_STICKY
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
    }

    private fun connectSignalingIfNeeded() {
        if (signalingClient != null) {
            return
        }

        if (serverUrl.isEmpty() || authToken.isEmpty() || cameraId == 0) {
            Log.w(TAG, "Cannot connect signaling: missing configuration")
            return
        }

        val client = SignalingClient(serverUrl, authToken, cameraId)
        val connected = client.connect(
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
            onError = { error ->
                Log.e(TAG, "Signaling error: $error")
            }
        )
        if (connected) {
            signalingClient = client
        } else {
            signalingClient = null
        }
    }

    private fun disconnectSignaling() {
        signalingClient?.disconnect()
        signalingClient = null
    }

    private fun startStreamingIfNeeded() {
        if (isStreaming || isStreamStarting) {
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
                // Initialize WebRTC
                webRtcClient = WebRtcClient(applicationContext, cameraProvider!!, this@CameraService)
                
                // Create and send offer
                webRtcClient?.createOffer { sdp ->
                    serviceScope.launch {
                        signalingClient?.sendOffer(sdp)
                    }
                }
                
                // Set ICE candidate callback
                webRtcClient?.setIceCandidateCallback { candidate ->
                    serviceScope.launch {
                        signalingClient?.sendIceCandidate(candidate)
                    }
                }
                
                isStreaming = true
                isStreamStarting = false
                updateStatus("streaming")
                
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
        // Photo capture implementation
        Log.d(TAG, "Capturing photo...")
        // TODO: Implement photo capture with CameraX ImageCapture
    }

    private fun startRecording() {
        // Video recording implementation
        Log.d(TAG, "Starting recording...")
        // TODO: Implement video recording with CameraX VideoCapture
    }

    private fun stopRecording() {
        // Stop video recording
        Log.d(TAG, "Stopping recording...")
        // TODO: Stop video recording
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        stopStreaming(keepReady = false)
        disconnectSignaling()
        serviceScope.cancel()
        super.onDestroy()
    }
}
