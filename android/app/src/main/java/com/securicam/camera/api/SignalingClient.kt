package com.securicam.camera.api

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.webrtc.IceCandidate
import java.io.File

/**
 * Manages the WebSocket connection to Laravel Reverb and routes inbound events
 * to the appropriate callbacks. All HTTP API calls are handled by [SignalingHttpClient].
 */
class SignalingClient(
    private val serverUrl: String,
    authToken: String,
    private val cameraId: Int
) {
    companion object {
        private const val TAG = "SignalingClient"
        private const val EVENT_SUBSCRIPTION_SUCCEEDED_INTERNAL = "pusher_internal:subscription_succeeded"
        private const val EVENT_SUBSCRIPTION_SUCCEEDED = "pusher:subscription_succeeded"
        private const val EVENT_PUSHER_ERROR = "pusher:error"
    }

    private val gson = Gson()
    private val http = SignalingHttpClient(serverUrl, authToken, cameraId)

    private var webSocket: WebSocket? = null
    private var connectionReadyDeferred: CompletableDeferred<Boolean>? = null
    private var socketId: String? = null

    private var onOfferCallback: ((String) -> Unit)? = null
    private var onAnswerCallback: ((String) -> Unit)? = null
    private var onIceCandidateCallback: ((String) -> Unit)? = null
    private var onCapturePhotoCallback: (() -> Unit)? = null
    private var onStartStreamingCallback: (() -> Unit)? = null
    private var onStopStreamingCallback: (() -> Unit)? = null
    private var onStartRecordingCallback: (() -> Unit)? = null
    private var onStopRecordingCallback: (() -> Unit)? = null
    private var onSwitchCameraCallback: ((String?) -> Unit)? = null

    // -------------------------------------------------------------------------
    // Connection lifecycle
    // -------------------------------------------------------------------------

    suspend fun connect(
        onOffer: (String) -> Unit,
        onAnswer: (String) -> Unit,
        onIceCandidate: (String) -> Unit,
        onCapturePhoto: () -> Unit,
        onStartStreaming: () -> Unit,
        onStopStreaming: () -> Unit,
        onStartRecording: () -> Unit,
        onStopRecording: () -> Unit,
        onSwitchCamera: (lensId: String?) -> Unit = {},
        timeoutMs: Long = 10_000L,
        onError: (String) -> Unit = {}
    ): Boolean {
        onOfferCallback = onOffer
        onAnswerCallback = onAnswer
        onIceCandidateCallback = onIceCandidate
        onCapturePhotoCallback = onCapturePhoto
        onStartStreamingCallback = onStartStreaming
        onStopStreamingCallback = onStopStreaming
        onStartRecordingCallback = onStartRecording
        onStopRecordingCallback = onStopRecording
        onSwitchCameraCallback = onSwitchCamera
        connectionReadyDeferred = CompletableDeferred()

        val wsUrl = buildWebSocketUrl()
        if (wsUrl == null) {
            val msg = "Cannot connect signaling: invalid server URL '$serverUrl'"
            Log.e(TAG, msg)
            onError(msg)
            return false
        }

        Log.d(TAG, "Connecting to $wsUrl")

        val request = try {
            Request.Builder().url(wsUrl).build()
        } catch (e: IllegalArgumentException) {
            val msg = "Cannot connect signaling: invalid WebSocket URL '$wsUrl'"
            Log.e(TAG, msg, e)
            onError(msg)
            connectionReadyDeferred?.complete(false)
            return false
        }

        webSocket = http.let {
            // OkHttp client is internal to SignalingHttpClient; we build our own here
            // for the WebSocket because it needs a different listener lifecycle.
            OkHttpClient().newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    Log.d(TAG, "WebSocket opened, awaiting connection_established")
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    Log.d(TAG, "WS message: $text")
                    handleMessage(text)
                }

                override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WS closing: $code - $reason")
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "WS failure: ${t.message}", t)
                    connectionReadyDeferred?.let { if (!it.isCompleted) it.complete(false) }
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WS closed: $code - $reason")
                    connectionReadyDeferred?.let { if (!it.isCompleted) it.complete(false) }
                }
            })
        }

        val result = withTimeoutOrNull(timeoutMs) { connectionReadyDeferred!!.await() } ?: false
        if (!result) {
            Log.e(TAG, "Signaling connection failed or timed out")
            disconnect()
        }
        return result
    }

    fun disconnect() {
        connectionReadyDeferred?.let { if (!it.isCompleted) it.complete(false) }
        connectionReadyDeferred = null
        webSocket?.close(1000, "Client disconnecting")
        webSocket = null
    }

    // -------------------------------------------------------------------------
    // HTTP API — delegated to SignalingHttpClient
    // -------------------------------------------------------------------------

    suspend fun sendOffer(sdp: String) = http.sendOffer(sdp)
    suspend fun sendAnswer(sdp: String) = http.sendAnswer(sdp)
    suspend fun sendIceCandidate(candidate: IceCandidate) = http.sendIceCandidate(candidate)
    suspend fun updateCameraStatus(status: String) = http.updateCameraStatus(status)
    suspend fun notifyConnect(availableLenses: List<Map<String, String>> = emptyList(), activeLens: String? = null) =
        http.notifyConnect(availableLenses, activeLens)
    suspend fun notifyHeartbeat(availableLenses: List<Map<String, String>> = emptyList(), activeLens: String? = null) =
        http.notifyHeartbeat(availableLenses, activeLens)
    suspend fun notifyDisconnect() = http.notifyDisconnect()
    suspend fun uploadCapture(type: String, file: File, durationSeconds: Long? = null) =
        http.uploadCapture(type, file, durationSeconds)

    // -------------------------------------------------------------------------
    // WebSocket message routing
    // -------------------------------------------------------------------------

    private fun handleMessage(message: String) {
        try {
            val json = gson.fromJson(message, JsonObject::class.java)
            val event = json.get("event")?.asString ?: return

            val data = json.get("data")?.let { el ->
                try {
                    when {
                        el.isJsonObject -> el.asJsonObject
                        el.isJsonPrimitive && el.asJsonPrimitive.isString ->
                            gson.fromJson(el.asString, JsonObject::class.java)
                        else -> null
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse data field", e); null
                }
            }

            when (event) {
                "pusher:ping" -> {
                    webSocket?.send(gson.toJson(JsonObject().apply {
                        addProperty("event", "pusher:pong")
                        add("data", JsonObject())
                    }))
                }
                "pusher:pong" -> Unit
                "pusher:connection_established" -> {
                    val sid = data?.get("socket_id")?.asString
                    if (sid != null) {
                        socketId = sid
                        subscribeToChannel(sid)
                    } else {
                        Log.e(TAG, "connection_established missing socket_id")
                        connectionReadyDeferred?.let { if (!it.isCompleted) it.complete(false) }
                    }
                }
                EVENT_SUBSCRIPTION_SUCCEEDED_INTERNAL, EVENT_SUBSCRIPTION_SUCCEEDED -> {
                    Log.d(TAG, "Subscribed to camera $cameraId channel")
                    connectionReadyDeferred?.let { if (!it.isCompleted) it.complete(true) }
                }
                EVENT_PUSHER_ERROR -> {
                    Log.e(TAG, "Pusher error: $message")
                    connectionReadyDeferred?.let { if (!it.isCompleted) it.complete(false) }
                }
                "webrtc.offer" -> data?.get("sdp")?.asString?.let { onOfferCallback?.invoke(it) }
                "webrtc.answer" -> data?.get("sdp")?.asString?.let { onAnswerCallback?.invoke(it) }
                "webrtc.ice-candidate" -> data?.get("candidate")?.toString()?.let { onIceCandidateCallback?.invoke(it) }
                "capture.photo" -> onCapturePhotoCallback?.invoke()
                "video.streaming.start" -> onStartStreamingCallback?.invoke()
                "video.streaming.stop" -> onStopStreamingCallback?.invoke()
                "video.recording.start" -> onStartRecordingCallback?.invoke()
                "video.recording.stop" -> onStopRecordingCallback?.invoke()
                "camera.switch" -> {
                    val lensId = data?.get("lens_id")
                        ?.takeIf { !it.isJsonNull && it.isJsonPrimitive }
                        ?.asString
                    onSwitchCameraCallback?.invoke(lensId)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling WS message", e)
        }
    }

    private fun subscribeToChannel(sid: String) {
        val ws = webSocket ?: run {
            connectionReadyDeferred?.let { if (!it.isCompleted) it.complete(false) }
            return
        }
        val channelName = "private-camera.$cameraId"
        http.performAuthAndSubscribe(
            webSocket = ws,
            socketId = sid,
            onSuccess = { auth ->
                val msg = gson.toJson(JsonObject().apply {
                    addProperty("event", "pusher:subscribe")
                    add("data", JsonObject().apply {
                        addProperty("channel", channelName)
                        addProperty("auth", auth)
                    })
                })
                ws.send(msg)
                Log.d(TAG, "Subscribe sent for $channelName")
            },
            onFailure = {
                connectionReadyDeferred?.let { if (!it.isCompleted) it.complete(false) }
            }
        )
    }

    // -------------------------------------------------------------------------
    // URL helpers
    // -------------------------------------------------------------------------

    private fun buildWebSocketUrl(): String? {
        val baseUrl = http.normalizedUrl ?: return null
        val parsed = baseUrl.toHttpUrlOrNull() ?: return null
        val pathSegments = parsed.pathSegments.filter { it.isNotEmpty() }.toMutableList()
        if (pathSegments.lastOrNull() == "api") pathSegments.removeAt(pathSegments.lastIndex)
        val builder = parsed.newBuilder().encodedPath("/")
        pathSegments.forEach { builder.addPathSegment(it) }
        builder.addPathSegment("app")
        builder.addPathSegment("securicam-app-key")
        val httpUrl = builder.build().toString()
        return httpUrl.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
    }
}
