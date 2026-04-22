package com.securicam.camera.api

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.webrtc.IceCandidate
import java.io.IOException
import java.util.concurrent.TimeUnit

class SignalingClient(
    private val serverUrl: String,
    private val authToken: String,
    private val cameraId: Int
) {

    companion object {
        private const val TAG = "SignalingClient"
        private const val EVENT_SUBSCRIPTION_SUCCEEDED_INTERNAL = "pusher_internal:subscription_succeeded"
        private const val EVENT_SUBSCRIPTION_SUCCEEDED = "pusher:subscription_succeeded"
        private const val EVENT_PUSHER_ERROR = "pusher:error"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val gson = Gson()
    private var webSocket: WebSocket? = null
    private val normalizedServerUrl: String? = normalizeServerUrl(serverUrl)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var onOfferCallback: ((String) -> Unit)? = null
    private var onAnswerCallback: ((String) -> Unit)? = null
    private var onIceCandidateCallback: ((String) -> Unit)? = null
    private var onCapturePhotoCallback: (() -> Unit)? = null
    private var onStartStreamingCallback: (() -> Unit)? = null
    private var onStopStreamingCallback: (() -> Unit)? = null
    private var onStartRecordingCallback: (() -> Unit)? = null
    private var onStopRecordingCallback: (() -> Unit)? = null
    private var connectionReadyDeferred: CompletableDeferred<Boolean>? = null
    private var socketId: String? = null

    suspend fun connect(
        onOffer: (String) -> Unit,
        onAnswer: (String) -> Unit,
        onIceCandidate: (String) -> Unit,
        onCapturePhoto: () -> Unit,
        onStartStreaming: () -> Unit,
        onStopStreaming: () -> Unit,
        onStartRecording: () -> Unit,
        onStopRecording: () -> Unit,
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
        connectionReadyDeferred = CompletableDeferred()

        val wsUrl = buildWebSocketUrl()
        if (wsUrl == null) {
            val errorMessage = "Cannot connect signaling: invalid server URL '$serverUrl'"
            Log.e(TAG, errorMessage)
            onError(errorMessage)
            return false
        }

        val request = try {
            Request.Builder()
                .url(wsUrl)
                .addHeader("Authorization", "Bearer $authToken")
                .build()
        } catch (e: IllegalArgumentException) {
            val errorMessage = "Cannot connect signaling: invalid WebSocket URL '$wsUrl'"
            Log.e(TAG, errorMessage, e)
            onError(errorMessage)
            connectionReadyDeferred?.complete(false)
            return false
        }

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected, waiting for connection_established")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "WebSocket message: $text")
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code - $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error", t)
                connectionReadyDeferred?.let {
                    if (!it.isCompleted) {
                        it.complete(false)
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code - $reason")
                connectionReadyDeferred?.let {
                    if (!it.isCompleted) {
                        it.complete(false)
                    }
                }
            }
        })
        val readyDeferred = connectionReadyDeferred ?: return false
        val awaitedResult = withTimeoutOrNull(timeoutMs) { readyDeferred.await() }
        val connected = awaitedResult ?: false

        if (!connected) {
            if (awaitedResult == null) {
                Log.e(TAG, "WebSocket signaling connection timed out after ${timeoutMs}ms")
            } else {
                Log.e(TAG, "WebSocket signaling subscription failed")
            }
            disconnect()
        }

        return connected
    }

    private fun performAuthAndSubscribe() {
        val sid = socketId ?: run {
            Log.e(TAG, "performAuthAndSubscribe called without socket_id")
            connectionReadyDeferred?.let { if (!it.isCompleted) it.complete(false) }
            return
        }
        val channelName = "private-camera.$cameraId"
        val authUrl = buildBroadcastingAuthUrl() ?: run {
            Log.e(TAG, "Cannot build broadcasting/auth URL from '$serverUrl'")
            connectionReadyDeferred?.let { if (!it.isCompleted) it.complete(false) }
            return
        }

        val formBody = okhttp3.FormBody.Builder()
            .add("socket_id", sid)
            .add("channel_name", channelName)
            .build()

        val request = Request.Builder()
            .url(authUrl)
            .post(formBody)
            .addHeader("Authorization", "Bearer $authToken")
            .addHeader("Accept", "application/json")
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                val authJson = gson.fromJson(body, JsonObject::class.java)
                val auth = authJson?.get("auth")?.asString
                if (auth != null) {
                    val subscribeMessage = JsonObject().apply {
                        addProperty("event", "pusher:subscribe")
                        add("data", JsonObject().apply {
                            addProperty("channel", channelName)
                            addProperty("auth", auth)
                        })
                    }
                    webSocket?.send(gson.toJson(subscribeMessage))
                    Log.d(TAG, "Subscribed to $channelName with proper auth")
                } else {
                    Log.e(TAG, "No auth field in broadcasting/auth response: $body")
                    connectionReadyDeferred?.let { if (!it.isCompleted) it.complete(false) }
                }
            } else {
                Log.e(TAG, "broadcasting/auth failed: ${response.code} - ${response.body?.string()}")
                connectionReadyDeferred?.let { if (!it.isCompleted) it.complete(false) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error calling broadcasting/auth", e)
            connectionReadyDeferred?.let { if (!it.isCompleted) it.complete(false) }
        }
    }

    private fun buildBroadcastingAuthUrl(): String? {
        val baseUrl = normalizedServerUrl ?: return null
        val parsed = baseUrl.toHttpUrlOrNull() ?: return null
        val pathSegments = parsed.pathSegments.filter { it.isNotEmpty() }.toMutableList()
        if (pathSegments.lastOrNull() == "api") {
            pathSegments.removeAt(pathSegments.lastIndex)
        }
        val builder = parsed.newBuilder().encodedPath("/")
        pathSegments.forEach { builder.addPathSegment(it) }
        builder.addPathSegment("broadcasting")
        builder.addPathSegment("auth")
        return builder.build().toString()
    }

    private fun handleMessage(message: String) {
        try {
            val json = gson.fromJson(message, JsonObject::class.java)
            val event = json.get("event")?.asString ?: return

            val data = json.get("data")?.let { dataElement ->
                try {
                    when {
                        dataElement.isJsonObject -> dataElement.asJsonObject
                        dataElement.isJsonPrimitive && dataElement.asJsonPrimitive.isString ->
                            gson.fromJson(dataElement.asString, JsonObject::class.java)
                        else -> null
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse data field", e)
                    null
                }
            }

            when (event) {
                "pusher:connection_established" -> {
                    val sid = data?.get("socket_id")?.asString
                    Log.d(TAG, "Connection established, socket_id=$sid")
                    if (sid != null) {
                        socketId = sid
                        performAuthAndSubscribe()
                    } else {
                        Log.e(TAG, "pusher:connection_established missing socket_id")
                        connectionReadyDeferred?.let { if (!it.isCompleted) it.complete(false) }
                    }
                }
                EVENT_SUBSCRIPTION_SUCCEEDED_INTERNAL, EVENT_SUBSCRIPTION_SUCCEEDED -> {
                    Log.d(TAG, "WebSocket channel subscription succeeded for camera $cameraId")
                    connectionReadyDeferred?.let { if (!it.isCompleted) it.complete(true) }
                }
                EVENT_PUSHER_ERROR -> {
                    Log.e(TAG, "Pusher error: $message")
                    connectionReadyDeferred?.let { if (!it.isCompleted) it.complete(false) }
                }
                "webrtc.offer" -> {
                    data?.get("sdp")?.asString?.let { onOfferCallback?.invoke(it) }
                }
                "webrtc.answer" -> {
                    data?.get("sdp")?.asString?.let { onAnswerCallback?.invoke(it) }
                }
                "webrtc.ice-candidate" -> {
                    data?.get("candidate")?.toString()?.let { onIceCandidateCallback?.invoke(it) }
                }
                "capture.photo" -> {
                    onCapturePhotoCallback?.invoke()
                }
                "video.streaming.start" -> {
                    onStartStreamingCallback?.invoke()
                }
                "video.streaming.stop" -> {
                    onStopStreamingCallback?.invoke()
                }
                "video.recording.start" -> {
                    onStartRecordingCallback?.invoke()
                }
                "video.recording.stop" -> {
                    onStopRecordingCallback?.invoke()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message", e)
        }
    }

    suspend fun sendOffer(sdp: String) = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            addProperty("camera_id", cameraId)
            addProperty("sdp", sdp)
            addProperty("type", "offer")
        }

        val url = buildApiUrl("/webrtc/offer") ?: run {
            Log.e(TAG, "Failed to send offer: invalid server URL '$serverUrl'")
            return@withContext false
        }
        return@withContext makeRequest(url, body)
    }

    suspend fun sendAnswer(sdp: String) = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            addProperty("camera_id", cameraId)
            addProperty("sdp", sdp)
            addProperty("type", "answer")
        }

        val url = buildApiUrl("/webrtc/answer") ?: run {
            Log.e(TAG, "Failed to send answer: invalid server URL '$serverUrl'")
            return@withContext false
        }
        return@withContext makeRequest(url, body)
    }

    suspend fun sendIceCandidate(candidate: IceCandidate) = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            addProperty("camera_id", cameraId)
            add("candidate", JsonObject().apply {
                addProperty("candidate", candidate.sdp)
                addProperty("sdpMid", candidate.sdpMid)
                addProperty("sdpMLineIndex", candidate.sdpMLineIndex)
            })
        }

        val url = buildApiUrl("/webrtc/ice-candidate") ?: run {
            Log.e(TAG, "Failed to send ICE candidate: invalid server URL '$serverUrl'")
            return@withContext false
        }
        return@withContext makeRequest(url, body)
    }

    suspend fun updateCameraStatus(status: String) = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            addProperty("status", status)
        }

        val url = buildApiUrl("/cameras/$cameraId/status") ?: run {
            Log.e(TAG, "Failed to update camera status: invalid server URL '$serverUrl'")
            return@withContext false
        }
        return@withContext makeRequest(url, body, "PATCH")
    }

    private fun makeRequest(url: String, body: JsonObject, method: String = "POST"): Boolean {
        val requestBody = gson.toJson(body).toRequestBody(JSON_MEDIA_TYPE)

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $authToken")
            .addHeader("Accept", "application/json")
            .method(method, requestBody)
            .build()

        return try {
            val response = httpClient.newCall(request).execute()
            response.isSuccessful.also {
                if (!it) {
                    Log.e(TAG, "Request failed: ${response.code} - ${response.body?.string()}")
                }
            }
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Invalid request URL: $url", e)
            false
        } catch (e: IOException) {
            Log.e(TAG, "Request error", e)
            false
        }
    }

    private fun buildApiUrl(path: String): String? {
        val baseUrl = normalizedServerUrl ?: run {
            Log.e(TAG, "Invalid server URL for API requests: '$serverUrl'")
            return null
        }
        return "${baseUrl.removeSuffix("/")}/${path.trimStart('/')}"
    }

    private fun buildWebSocketUrl(): String? {
        val baseUrl = normalizedServerUrl ?: return null
        val parsed = baseUrl.toHttpUrlOrNull() ?: return null
        val pathSegments = parsed.pathSegments.filter { it.isNotEmpty() }.toMutableList()
        if (pathSegments.lastOrNull() == "api") {
            pathSegments.removeAt(pathSegments.lastIndex)
        }

        // Build as http/https first — OkHttp's HttpUrl.Builder rejects ws/wss schemes
        val builder = parsed.newBuilder().encodedPath("/")
        pathSegments.forEach { segment ->
            builder.addPathSegment(segment)
        }
        builder.addPathSegment("app")
        builder.addPathSegment("securicam-app-key")
        val httpUrl = builder.build().toString()
        // Then swap scheme to ws/wss
        return httpUrl.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
    }

    private fun normalizeServerUrl(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) {
            return null
        }

        val parsed = trimmed.toHttpUrlOrNull() ?: "http://$trimmed".toHttpUrlOrNull() ?: return null

        return parsed.toString().removeSuffix("/")
    }

    fun disconnect() {
        connectionReadyDeferred?.let {
            if (!it.isCompleted) {
                it.complete(false)
            }
        }
        connectionReadyDeferred = null
        webSocket?.close(1000, "Client disconnecting")
        webSocket = null
    }
}
