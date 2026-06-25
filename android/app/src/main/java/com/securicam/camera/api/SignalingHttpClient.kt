package com.securicam.camera.api

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.webrtc.IceCandidate
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Handles all HTTP API calls to the Laravel backend.
 * WebSocket connection management lives in [SignalingClient].
 */
class SignalingHttpClient(
    serverUrl: String,
    private val authToken: String,
    private val cameraId: Int
) {
    companion object {
        private const val TAG = "SignalingHttpClient"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun normalizeUrl(rawUrl: String): String? {
            val trimmed = rawUrl.trim()
            if (trimmed.isEmpty()) return null
            val parsed = trimmed.toHttpUrlOrNull()
                ?: "http://$trimmed".toHttpUrlOrNull()
                ?: return null
            return parsed.toString().removeSuffix("/")
        }
    }

    private val gson = Gson()
    val normalizedUrl: String? = normalizeUrl(serverUrl)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun sendOffer(sdp: String): Boolean = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            addProperty("camera_id", cameraId)
            addProperty("sdp", sdp)
            addProperty("type", "offer")
        }
        makeRequest(buildApiUrl("/webrtc/offer") ?: return@withContext logMissingUrl("sendOffer"), body)
    }

    suspend fun sendAnswer(sdp: String): Boolean = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            addProperty("camera_id", cameraId)
            addProperty("sdp", sdp)
            addProperty("type", "answer")
        }
        makeRequest(buildApiUrl("/webrtc/answer") ?: return@withContext logMissingUrl("sendAnswer"), body)
    }

    suspend fun sendIceCandidate(candidate: IceCandidate): Boolean = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            addProperty("camera_id", cameraId)
            add("candidate", JsonObject().apply {
                addProperty("candidate", candidate.sdp)
                addProperty("sdpMid", candidate.sdpMid)
                addProperty("sdpMLineIndex", candidate.sdpMLineIndex)
            })
        }
        makeRequest(buildApiUrl("/webrtc/ice-candidate") ?: return@withContext logMissingUrl("sendIceCandidate"), body)
    }

    suspend fun updateCameraStatus(status: String): Boolean = withContext(Dispatchers.IO) {
        val body = JsonObject().apply { addProperty("status", status) }
        makeRequest(buildApiUrl("/cameras/$cameraId/status") ?: return@withContext logMissingUrl("updateCameraStatus"), body, "PATCH")
    }

    suspend fun notifyConnect(
        availableLenses: List<Map<String, String>> = emptyList(),
        activeLens: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            if (availableLenses.isNotEmpty()) add("available_lenses", gson.toJsonTree(availableLenses))
            if (!activeLens.isNullOrBlank()) addProperty("active_lens", activeLens)
        }
        makeRequest(buildApiUrl("/cameras/$cameraId/connect") ?: return@withContext logMissingUrl("notifyConnect"), body)
    }

    suspend fun notifyHeartbeat(
        availableLenses: List<Map<String, String>> = emptyList(),
        activeLens: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            if (availableLenses.isNotEmpty()) add("available_lenses", gson.toJsonTree(availableLenses))
            if (!activeLens.isNullOrBlank()) addProperty("active_lens", activeLens)
        }
        makeRequest(buildApiUrl("/cameras/$cameraId/heartbeat") ?: return@withContext logMissingUrl("notifyHeartbeat"), body)
    }

    suspend fun notifyDisconnect(): Boolean = withContext(Dispatchers.IO) {
        makeRequest(buildApiUrl("/cameras/$cameraId/disconnect") ?: return@withContext logMissingUrl("notifyDisconnect"), JsonObject())
    }

    suspend fun uploadCapture(
        type: String,
        file: File,
        durationSeconds: Long? = null,
        burstId: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val url = buildApiUrl("/captures/upload") ?: return@withContext logMissingUrl("uploadCapture")

        val mediaType = when (type) {
            "photo" -> "image/jpeg".toMediaTypeOrNull()
            "video" -> "video/mp4".toMediaTypeOrNull()
            else -> "application/octet-stream".toMediaTypeOrNull()
        }

        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("camera_id", cameraId.toString())
            .addFormDataPart("type", type)
            .addFormDataPart("file", file.name, file.asRequestBody(mediaType))
            .also { if (durationSeconds != null) it.addFormDataPart("duration", durationSeconds.toString()) }
            .also { if (burstId != null) it.addFormDataPart("burst_id", burstId) }
            .build()

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $authToken")
            .addHeader("Accept", "application/json")
            .post(multipart)
            .build()

        return@withContext try {
            val response = httpClient.newCall(request).execute()
            response.isSuccessful.also {
                if (!it) Log.e(TAG, "Capture upload failed: ${response.code} - ${response.body?.string()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Capture upload error", e)
            false
        }
    }

    fun buildBroadcastingAuthUrl(): String? {
        val baseUrl = normalizedUrl ?: return null
        val parsed = baseUrl.toHttpUrlOrNull() ?: return null
        val pathSegments = parsed.pathSegments.filter { it.isNotEmpty() }.toMutableList()
        if (pathSegments.lastOrNull() == "api") pathSegments.removeAt(pathSegments.lastIndex)
        val builder = parsed.newBuilder().encodedPath("/")
        pathSegments.forEach { builder.addPathSegment(it) }
        builder.addPathSegment("broadcasting")
        builder.addPathSegment("auth")
        return builder.build().toString()
    }

    fun performAuthAndSubscribe(
        webSocket: WebSocket,
        socketId: String,
        onSuccess: (auth: String) -> Unit,
        onFailure: () -> Unit
    ) {
        val channelName = "private-camera.$cameraId"
        val authUrl = buildBroadcastingAuthUrl() ?: run {
            Log.e(TAG, "Cannot build broadcasting/auth URL")
            onFailure()
            return
        }

        val formBody = FormBody.Builder()
            .add("socket_id", socketId)
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
                val auth = com.google.gson.JsonParser.parseString(body)
                    ?.asJsonObject?.get("auth")?.asString
                if (auth != null) {
                    onSuccess(auth)
                } else {
                    Log.e(TAG, "No auth field in broadcasting/auth response: $body")
                    onFailure()
                }
            } else {
                Log.e(TAG, "broadcasting/auth failed: ${response.code} - ${response.body?.string()}")
                onFailure()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error calling broadcasting/auth", e)
            onFailure()
        }
    }

    private fun buildApiUrl(path: String): String? {
        val base = normalizedUrl ?: run {
            Log.e(TAG, "Invalid server URL for API request")
            return null
        }
        return "${base.removeSuffix("/")}/${path.trimStart('/')}"
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
                if (!it) Log.e(TAG, "Request failed: ${response.code} - ${response.body?.string()}")
            }
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Invalid request URL: $url", e)
            false
        } catch (e: IOException) {
            Log.e(TAG, "Request error", e)
            false
        }
    }

    private fun logMissingUrl(caller: String): Boolean {
        Log.e(TAG, "Failed to call $caller: invalid server URL")
        return false
    }
}
