package com.securicam.camera.webrtc

import android.content.Context
import android.util.Log
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import org.webrtc.*
import java.util.concurrent.Executors

class WebRtcClient(
    private val context: Context,
    private val cameraProvider: ProcessCameraProvider,
    private val lifecycleOwner: LifecycleOwner
) {

    companion object {
        private const val TAG = "WebRtcClient"
        
        private val ICE_SERVERS = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
    }

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localVideoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var eglBase: EglBase? = null
    
    private var iceCandidateCallback: ((IceCandidate) -> Unit)? = null
    private val executor = Executors.newSingleThreadExecutor()

    init {
        try {
            initializePeerConnectionFactory()
            initializeMediaSources()
            createPeerConnection()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize WebRTC client", e)
            cleanupAfterInitializationFailure()
            throw IllegalStateException("Failed to initialize WebRTC client: ${e.message}", e)
        }
    }

    private fun initializePeerConnectionFactory() {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        
        PeerConnectionFactory.initialize(options)

        eglBase = try {
            EglBase.create()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create EGL base", e)
            throw IllegalStateException("Failed to create EGL base", e)
        }

        val eglBaseContext = eglBase?.eglBaseContext
            ?: throw IllegalStateException("EGL base context is unavailable")

        val encoderFactory = DefaultVideoEncoderFactory(
            eglBaseContext,
            true,
            true
        )
        
        val decoderFactory = DefaultVideoDecoderFactory(eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
            ?: throw IllegalStateException("Failed to create PeerConnectionFactory")
    }

    private fun initializeMediaSources() {
        val factory = peerConnectionFactory
            ?: throw IllegalStateException("PeerConnectionFactory is not initialized")

        // Create video source
        localVideoSource = factory.createVideoSource(false)
        
        // Create video capturer
        val eglBaseContext = eglBase?.eglBaseContext
            ?: throw IllegalStateException("EGL base context is unavailable")
        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBaseContext)
            ?: throw IllegalStateException("Failed to create SurfaceTextureHelper")
        
        videoCapturer = createCameraCapturer()
            ?: throw IllegalStateException("No camera capturer available. Camera hardware may be missing or inaccessible.")

        videoCapturer?.initialize(surfaceTextureHelper, context, localVideoSource?.capturerObserver)
        try {
            videoCapturer?.startCapture(1280, 720, 30)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start camera capture", e)
            throw IllegalStateException("Failed to start camera capture", e)
        }
        
        // Create video track
        localVideoTrack = factory.createVideoTrack("video_track", localVideoSource)
        localVideoTrack?.setEnabled(true)
        
        // Create audio source and track
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        }
        
        localAudioSource = factory.createAudioSource(audioConstraints)
        localAudioTrack = factory.createAudioTrack("audio_track", localAudioSource)
        localAudioTrack?.setEnabled(true)
    }

    private fun createCameraCapturer(): CameraVideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        
        // Try back camera first
        for (deviceName in enumerator.deviceNames) {
            if (enumerator.isBackFacing(deviceName)) {
                return enumerator.createCapturer(deviceName, null)
            }
        }
        
        // Fallback to front camera
        for (deviceName in enumerator.deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                return enumerator.createCapturer(deviceName, null)
            }
        }
        
        return null
    }

    private fun createPeerConnection() {
        val factory = peerConnectionFactory
            ?: throw IllegalStateException("PeerConnectionFactory is not initialized")

        val rtcConfig = PeerConnection.RTCConfiguration(ICE_SERVERS).apply {
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        peerConnection = factory.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                    Log.d(TAG, "onSignalingChange: $state")
                }

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    Log.d(TAG, "onIceConnectionChange: $state")
                }

                override fun onIceConnectionReceivingChange(receiving: Boolean) {
                    Log.d(TAG, "onIceConnectionReceivingChange: $receiving")
                }

                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                    Log.d(TAG, "onIceGatheringChange: $state")
                }

                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate?.let {
                        Log.d(TAG, "onIceCandidate: ${it.sdp}")
                        iceCandidateCallback?.invoke(it)
                    }
                }

                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
                    Log.d(TAG, "onIceCandidatesRemoved")
                }

                override fun onAddStream(stream: MediaStream?) {
                    Log.d(TAG, "onAddStream")
                }

                override fun onRemoveStream(stream: MediaStream?) {
                    Log.d(TAG, "onRemoveStream")
                }

                override fun onDataChannel(channel: DataChannel?) {
                    Log.d(TAG, "onDataChannel")
                }

                override fun onRenegotiationNeeded() {
                    Log.d(TAG, "onRenegotiationNeeded")
                }

                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                    Log.d(TAG, "onAddTrack")
                }
            }
        )
            ?: throw IllegalStateException("Failed to create peer connection")

        // Add local tracks to peer connection
        localVideoTrack?.let {
            peerConnection?.addTrack(it, listOf("stream"))
        }
        localAudioTrack?.let {
            peerConnection?.addTrack(it, listOf("stream"))
        }
    }

    fun createOffer(onOfferCreated: (String) -> Unit) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp?.let { sessionDescription ->
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(sdp: SessionDescription?) {}
                        override fun onSetSuccess() {
                            Log.d(TAG, "Local description set successfully")
                            onOfferCreated(sessionDescription.description)
                        }
                        override fun onCreateFailure(error: String?) {
                            Log.e(TAG, "Failed to create local description: $error")
                        }
                        override fun onSetFailure(error: String?) {
                            Log.e(TAG, "Failed to set local description: $error")
                        }
                    }, sessionDescription)
                }
            }

            override fun onSetSuccess() {}
            
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Failed to create offer: $error")
            }

            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    fun handleAnswer(sdp: String) {
        val sessionDescription = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {}
            override fun onSetSuccess() {
                Log.d(TAG, "Remote description set successfully")
            }
            override fun onCreateFailure(error: String?) {}
            override fun onSetFailure(error: String?) {
                Log.e(TAG, "Failed to set remote description: $error")
            }
        }, sessionDescription)
    }

    fun addIceCandidate(candidateJson: String) {
        try {
            // Parse candidate JSON and add to peer connection
            // This is a simplified version - actual implementation needs JSON parsing
            val candidate = IceCandidate("0", 0, candidateJson)
            peerConnection?.addIceCandidate(candidate)
            Log.d(TAG, "ICE candidate added")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add ICE candidate", e)
        }
    }

    fun setIceCandidateCallback(callback: (IceCandidate) -> Unit) {
        iceCandidateCallback = callback
    }

    fun release() {
        executor.execute {
            try {
                videoCapturer?.stopCapture()
                videoCapturer?.dispose()
                
                localVideoTrack?.dispose()
                localAudioTrack?.dispose()
                
                localVideoSource?.dispose()
                localAudioSource?.dispose()
                
                surfaceTextureHelper?.dispose()
                
                peerConnection?.close()
                peerConnection?.dispose()
                
                peerConnectionFactory?.dispose()
                eglBase?.release()
                
                Log.d(TAG, "WebRTC resources released")
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing WebRTC resources", e)
            }
        }
    }

    private fun cleanupAfterInitializationFailure() {
        try {
            videoCapturer?.stopCapture()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop capturer during initialization cleanup", e)
        }
        try {
            videoCapturer?.dispose()
            localVideoTrack?.dispose()
            localAudioTrack?.dispose()
            localVideoSource?.dispose()
            localAudioSource?.dispose()
            surfaceTextureHelper?.dispose()
            peerConnection?.close()
            peerConnection?.dispose()
            peerConnectionFactory?.dispose()
            eglBase?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clean up WebRTC resources after initialization error", e)
        }
    }
}
