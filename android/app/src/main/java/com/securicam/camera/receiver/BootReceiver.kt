package com.securicam.camera.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.securicam.camera.service.CameraService

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        private const val PREFS_NAME = "securicam_prefs"
        private const val KEY_AUTO_START = "auto_start_enabled"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_CAMERA_ID = "camera_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || 
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            
            Log.d(TAG, "Boot completed, checking auto-start settings")
            
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            
            if (prefs.getBoolean(KEY_AUTO_START, false)) {
                val serverUrl = prefs.getString(KEY_SERVER_URL, "") ?: ""
                val authToken = prefs.getString(KEY_AUTH_TOKEN, "") ?: ""
                val cameraId = prefs.getInt(KEY_CAMERA_ID, 0)
                
                if (serverUrl.isNotEmpty() && authToken.isNotEmpty() && cameraId > 0) {
                    startCameraService(context, serverUrl, authToken, cameraId)
                } else {
                    Log.w(TAG, "Missing configuration for auto-start")
                }
            } else {
                Log.d(TAG, "Auto-start is disabled")
            }
        }
    }

    private fun startCameraService(
        context: Context,
        serverUrl: String,
        authToken: String,
        cameraId: Int
    ) {
        val serviceIntent = Intent(context, CameraService::class.java).apply {
            action = CameraService.ACTION_PREPARE
            putExtra(CameraService.EXTRA_SERVER_URL, serverUrl)
            putExtra(CameraService.EXTRA_AUTH_TOKEN, authToken)
            putExtra(CameraService.EXTRA_CAMERA_ID, cameraId)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.d(TAG, "Camera service prepared on boot")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start camera service", e)
        }
    }
}
