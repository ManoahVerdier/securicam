package com.securicam.camera.webrtc

import android.content.Context
import android.util.Log
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import com.google.gson.JsonParser
import org.webrtc.*
import java.util.concurrent.Executors

class WebRtcClient(
    private val context: Context,
    private val cameraProvider: ProcessCameraProvider,
    private val lifecycleOwner: LifecycleOwner,
    private val iceServers: List<PeerConnection.IceServer> = DEFAULT_ICE_SERVERS
) {

    companion object {
        private const val TAG = "WebRtcClient"

        val DEFAULT_ICE_SERVERS: List<PeerConnection.IceServer> = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )

        /**
         * Build a TURN+STUN server list from raw config.
         * If [turnHost] is null/blank, returns only the public Google STUN servers.
         */
        fun buildIceServers(
            turnHost: String?,
            turnUser: String?,
            turnPassword: String?
        ): List<PeerConnection.IceServer> {
            if (turnHost.isNullOrBlank()) {
                return DEFAULT_ICE_SERVERS
            }
            val stun = PeerConnection.IceServer.builder("stun:$turnHost:3478").createIceServer()
            val turnBuilder = PeerConnection.IceServer
                .builder(listOf(
                    "turn:$turnHost:3478?transport=udp",
                    "turn:$turnHost:3478?transport=tcp"
                ))
            if (!turnUser.isNullOrBlank()) {
                turnBuilder.setUsername(turnUser)
            }
            if (!turnPassword.isNullOrBlank()) {
                turnBuilder.setPassword(turnPassword)
            }
            return listOf(stun, turnBuilder.createIceServer())
        }
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
    private var connectionStateCallback: ((Boolean) -> Unit)? = null
    private val executor = Executors.newSingleThreadExecutor()

    /** True when the active capturer is currently aimed at the front-facing lens. */
    @Volatile
    var isFrontCameraActive: Boolean = false
        private set

    /** Camera2 deviceName of the lens currently used (matches LensCatalog.Lens.id). */
    @Volatile
    var activeLensId: String? = null
        private set

    /** Last frame dimensions reported by the capture pipeline (for recorder hints). */
    @Volatile
    var lastFrameWidth: Int = 1920
        private set
    @Volatile
    var lastFrameHeight: Int = 1080
        private set

    /** Shared EGL context required to feed an external encoder/preview Surface. */
    val eglBaseContext: EglBase.Context?
        get() = eglBase?.eglBaseContext

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
        val videoSource = localVideoSource
            ?: throw IllegalStateException("Failed to create local video source")

        // Create video capturer
        val eglBaseContext = eglBase?.eglBaseContext
            ?: throw IllegalStateException("EGL base context is unavailable")
        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBaseContext)
            ?: throw IllegalStateException("Failed to create SurfaceTextureHelper")

        videoCapturer = createCameraCapturer()
            ?: throw IllegalStateException(
                "No camera capturer available. Verify camera permission is granted and camera hardware is not in use by another app."
            )

        try {
            videoCapturer?.initialize(surfaceTextureHelper, context, videoSource.capturerObserver)
            // Capture at Full HD (1080p, 30 fps). The actual transmitted resolution
            // is also bounded by the SDP/encoder; see PeerConnection setup where we
            // bump the maximum bitrate to keep this resolution sharp on Wi-Fi/4G.
            videoCapturer?.startCapture(1920, 1080, 30)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize or start camera capture", e)
            throw IllegalStateException("Failed to initialize or start camera capture", e)
        }

        // Create video track
        localVideoTrack = factory.createVideoTrack("video_track", videoSource)
        localVideoTrack?.setEnabled(true)

        // Audio is intentionally disabled: stream-webrtc-android currently emits an SDP
        // audio section that Chrome rejects ("a=ssrc ... msid:stream audio_track Invalid SDP line").
        // Video-only streaming is sufficient for the surveillance use case.
    }

    private fun createCameraCapturer(): CameraVideoCapturer? {
        val enumerator = Camera2Enumerator(context)

        // Try back camera first
        for (deviceName in enumerator.deviceNames) {
            if (enumerator.isBackFacing(deviceName)) {
                isFrontCameraActive = false
                activeLensId = deviceName
                return enumerator.createCapturer(deviceName, null)
            }
        }

        // Fallback to front camera
        for (deviceName in enumerator.deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                isFrontCameraActive = true
                activeLensId = deviceName
                return enumerator.createCapturer(deviceName, null)
            }
        }

        return null
    }

    /**
     * Switch to a specific physical lens (by Camera2 deviceName). Use this for
     * multi-back-camera phones (wide / ultrawide / telephoto) so the user can
     * pick which sensor streams from the web UI. Falls back to a regular
     * front/back toggle if [lensId] is null.
     */
    fun switchCameraTo(lensId: String?, onResult: (success: Boolean, lensId: String?) -> Unit = { _, _ -> }) {
        val capturer = videoCapturer
        if (capturer == null) {
            onResult(false, activeLensId)
            return
        }
        if (lensId.isNullOrBlank()) {
            switchCamera { success, _ -> onResult(success, activeLensId) }
            return
        }
        try {
            val enumerator = Camera2Enumerator(context)
            val isFront = try { enumerator.isFrontFacing(lensId) } catch (_: Throwable) { false }
            capturer.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
                override fun onCameraSwitchDone(isFrontFacing: Boolean) {
                    isFrontCameraActive = isFrontFacing
                    activeLensId = lensId
                    Log.i(TAG, "Camera switched to lens $lensId (isFront=$isFrontFacing)")
                    onResult(true, lensId)
                }

                override fun onCameraSwitchError(errorDescription: String?) {
                    Log.e(TAG, "Camera switch to $lensId failed: $errorDescription")
                    onResult(false, activeLensId)
                }
            }, lensId)
            // Hint: also pre-update isFront so consumers (preview, recorder) get a quicker UI update.
            isFrontCameraActive = isFront
        } catch (e: Exception) {
            Log.e(TAG, "switchCameraTo($lensId) threw", e)
            onResult(false, activeLensId)
        }
    }

    /**
     * Switch between the front-facing and rear-facing lenses without tearing
     * down the WebRTC peer connection. Safe to call from any thread — libwebrtc
     * dispatches the switch on the capturer's internal handler.
     */
    fun switchCamera(onResult: (success: Boolean, isFront: Boolean) -> Unit = { _, _ -> }) {
        val capturer = videoCapturer
        if (capturer == null) {
            onResult(false, isFrontCameraActive)
            return
        }
        try {
            capturer.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
                override fun onCameraSwitchDone(isFrontFacing: Boolean) {
                    isFrontCameraActive = isFrontFacing
                    // Refresh active lens id by enumerating; libwebrtc switches to the
                    // first lens of the opposite facing.
                    activeLensId = try {
                        val enumerator = Camera2Enumerator(context)
                        enumerator.deviceNames.firstOrNull { name ->
                            if (isFrontFacing) enumerator.isFrontFacing(name) else enumerator.isBackFacing(name)
                        } ?: activeLensId
                    } catch (_: Throwable) { activeLensId }
                    Log.i(TAG, "Camera switched: isFront=$isFrontFacing lensId=$activeLensId")
                    onResult(true, isFrontFacing)
                }

                override fun onCameraSwitchError(errorDescription: String?) {
                    Log.e(TAG, "Camera switch failed: $errorDescription")
                    onResult(false, isFrontCameraActive)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "switchCamera threw", e)
            onResult(false, isFrontCameraActive)
        }
    }

    /** Attach an extra [VideoSink] (photo/video recorder) to the live track. */
    fun addVideoSink(sink: VideoSink) {
        try {
            localVideoTrack?.addSink(sink)
        } catch (e: Exception) {
            Log.w(TAG, "addVideoSink failed", e)
        }
    }

    /** Detach a sink previously registered via [addVideoSink]. */
    fun removeVideoSink(sink: VideoSink) {
        try {
            localVideoTrack?.removeSink(sink)
        } catch (e: Exception) {
            Log.w(TAG, "removeVideoSink failed", e)
        }
    }

    private fun createPeerConnection() {
        val factory = peerConnectionFactory
            ?: throw IllegalStateException("PeerConnectionFactory is not initialized")

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
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
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED ->
                            connectionStateCallback?.invoke(true)
                        PeerConnection.IceConnectionState.DISCONNECTED,
                        PeerConnection.IceConnectionState.FAILED,
                        PeerConnection.IceConnectionState.CLOSED ->
                            connectionStateCallback?.invoke(false)
                        else -> {}
                    }
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
            val sender = peerConnection?.addTrack(it, listOf("stream"))
            sender?.let { configureVideoSender(it) }
        }
        localAudioTrack?.let {
            peerConnection?.addTrack(it, listOf("stream"))
        }
    }

    /**
     * Cap the encoder bitrate so 1080p30 stays sharp instead of being dropped to
     * 500 kbps by libwebrtc's default. 6 Mbps is comfortable for a 1080p feed on
     * a typical Wi-Fi / 4G uplink and visibly improves perceived sharpness.
     */
    private fun configureVideoSender(sender: RtpSender) {
        try {
            val params = sender.parameters ?: return
            params.encodings.forEach { enc ->
                enc.maxBitrateBps = 6_000_000
                enc.minBitrateBps = 1_500_000
                enc.maxFramerate = 30
            }
            // Prefer keeping resolution sharp over framerate when uplink is constrained.
            try {
                params.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
            } catch (e: Throwable) {
                Log.d(TAG, "degradationPreference not available on this WebRTC build")
            }
            sender.parameters = params
        } catch (e: Exception) {
            Log.w(TAG, "Could not tune video sender bitrate", e)
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
                            onOfferCreated(ensureSdpTerminator(sessionDescription.description))
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

    private fun ensureSdpTerminator(sdp: String): String {
        // 1. Strip legacy `a=ssrc:<id> msid:<stream> <track>` lines that Chrome (Unified Plan)
        //    rejects with "Invalid SDP line". msid is already declared at the m= section level
        //    via `a=msid:<stream> <track>`, so removing the per-ssrc copies is safe.
        // 2. Some libwebrtc builds emit a SDP whose last line is not terminated by CRLF, which
        //    also makes Chrome's parser fail; ensure a trailing CRLF is present.
        val cleaned = sdp.lineSequence()
            .filterNot { it.startsWith("a=ssrc:") && it.contains(" msid:") }
            .joinToString("\r\n")
        return if (cleaned.endsWith("\r\n")) cleaned else cleaned + "\r\n"
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
            val obj = JsonParser.parseString(candidateJson).asJsonObject
            val candidateSdp = obj.get("candidate")?.asString ?: run {
                Log.w(TAG, "ICE candidate JSON missing 'candidate' field: $candidateJson")
                return
            }
            val sdpMid = obj.get("sdpMid")?.takeIf { !it.isJsonNull }?.asString ?: ""
            val sdpMLineIndex = obj.get("sdpMLineIndex")?.takeIf { !it.isJsonNull }?.asInt ?: 0
            val candidate = IceCandidate(sdpMid, sdpMLineIndex, candidateSdp)
            peerConnection?.addIceCandidate(candidate)
            Log.d(TAG, "ICE candidate added")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add ICE candidate", e)
        }
    }

    fun setIceCandidateCallback(callback: (IceCandidate) -> Unit) {
        iceCandidateCallback = callback
    }

    fun setConnectionStateCallback(callback: (Boolean) -> Unit) {
        connectionStateCallback = callback
    }

    fun release() {
        executor.execute {
            try {
                stopCaptureSafely()
                releaseResources()

                Log.d(TAG, "WebRTC resources released")
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing WebRTC resources", e)
            }
        }
    }

    private fun cleanupAfterInitializationFailure() {
        try {
            releaseResources()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clean up WebRTC resources after initialization error", e)
        }
    }

    private fun stopCaptureSafely() {
        try {
            videoCapturer?.stopCapture()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop capturer", e)
        }
    }

    private fun releaseResources() {
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
    }
}
