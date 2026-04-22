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
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val gson = Gson()
    private var webSocket: WebSocket? = null
    
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

    suspend fun connect(
        onOffer: (String) -> Unit,
        onAnswer: (String) -> Unit,
        onIceCandidate: (String) -> Unit,
        onCapturePhoto: () -> Unit,
        onStartStreaming: () -> Unit,
        onStopStreaming: () -> Unit,
        onStartRecording: () -> Unit,
        onStopRecording: () -> Unit,
        timeoutMs: Long = 10_000L
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

        val wsUrl = serverUrl
            .replace("http://", "ws://")
            .replace("https://", "wss://")
            .replace("/api", "") + "/app/securicam-app-key"

        val request = try {
            Request.Builder()
                .url(wsUrl)
                .addHeader("Authorization", "Bearer $authToken")
                .build()
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Invalid WebSocket URL: $wsUrl", e)
            connectionReadyDeferred?.complete(false)
            return false
        }

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                subscribeToChannel()
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

        val connected = withTimeoutOrNull(timeoutMs) {
            connectionReadyDeferred?.await() ?: false
        } ?: false

        if (!connected) {
            Log.e(TAG, "WebSocket signaling connection timeout or subscription failed")
            disconnect()
        }

        return connected
    }

    private fun subscribeToChannel() {
        val subscribeMessage = JsonObject().apply {
            addProperty("event", "pusher:subscribe")
            add("data", JsonObject().apply {
                addProperty("channel", "private-camera.$cameraId")
                addProperty("auth", authToken)
            })
        }
        webSocket?.send(gson.toJson(subscribeMessage))
    }

    private fun handleMessage(message: String) {
        try {
            val json = gson.fromJson(message, JsonObject::class.java)
            val event = json.get("event")?.asString ?: return
            if (event == "pusher_internal:subscription_succeeded" || event == "pusher:subscription_succeeded") {
                connectionReadyDeferred?.let {
                    if (!it.isCompleted) {
                        it.complete(true)
                    }
                }
                return
            }
            if (event == "pusher:error") {
                connectionReadyDeferred?.let {
                    if (!it.isCompleted) {
                        it.complete(false)
                    }
                }
                return
            }

            val data = json.get("data")?.asJsonObject

            when (event) {
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
        
        makeRequest("$serverUrl/webrtc/offer", body)
    }

    suspend fun sendAnswer(sdp: String) = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            addProperty("camera_id", cameraId)
            addProperty("sdp", sdp)
            addProperty("type", "answer")
        }
        
        makeRequest("$serverUrl/webrtc/answer", body)
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
        
        makeRequest("$serverUrl/webrtc/ice-candidate", body)
    }

    suspend fun updateCameraStatus(status: String) = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            addProperty("status", status)
        }
        
        makeRequest("$serverUrl/cameras/$cameraId/status", body, "PATCH")
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
        } catch (e: IOException) {
            Log.e(TAG, "Request error", e)
            false
        }
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
