package com.securicam.camera.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.securicam.camera.R
import com.securicam.camera.databinding.ActivityMainBinding
import com.securicam.camera.service.CameraService
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "securicam_prefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_CAMERA_ID = "camera_id"
        private const val KEY_AUTO_START = "auto_start_enabled"
        private const val KEY_TURN_HOST = "turn_host"
        private const val KEY_TURN_USER = "turn_user"
        private const val KEY_TURN_PASSWORD = "turn_password"
        private const val UI_REFRESH_INTERVAL_MS = 1000L
    }

    private lateinit var binding: ActivityMainBinding

    private var cameraService: CameraService? = null
    private var isServiceBound = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private val uiRefreshRunnable = object : Runnable {
        override fun run() {
            updateUI()
            uiHandler.postDelayed(this, UI_REFRESH_INTERVAL_MS)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as CameraService.LocalBinder
            cameraService = binder.getService()
            isServiceBound = true
            updateUI()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            cameraService = null
            isServiceBound = false
            updateUI()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            connectToBackend()
        } else {
            Toast.makeText(this, "Permissions required for camera streaming", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadSavedConfig()
        setupListeners()
        checkBatteryOptimization()

        ensurePermissionsAndConnect()
    }

    override fun onStart() {
        super.onStart()
        bindService(
            Intent(this, CameraService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
        startUiRefresh()
    }

    override fun onStop() {
        super.onStop()
        stopUiRefresh()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }

    private fun loadSavedConfig() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        binding.etServerUrl.setText(prefs.getString(KEY_SERVER_URL, ""))
        binding.etAuthToken.setText(prefs.getString(KEY_AUTH_TOKEN, ""))
        binding.etCameraId.setText(prefs.getInt(KEY_CAMERA_ID, 0).toString())
        binding.etTurnHost.setText(prefs.getString(KEY_TURN_HOST, ""))
        binding.etTurnUser.setText(prefs.getString(KEY_TURN_USER, ""))
        binding.etTurnPassword.setText(prefs.getString(KEY_TURN_PASSWORD, ""))
        binding.switchAutoStart.isChecked = prefs.getBoolean(KEY_AUTO_START, true)
    }

    private fun saveConfig() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString(KEY_SERVER_URL, binding.etServerUrl.text.toString())
            putString(KEY_AUTH_TOKEN, binding.etAuthToken.text.toString())
            putInt(KEY_CAMERA_ID, binding.etCameraId.text.toString().toIntOrNull() ?: 0)
            putString(KEY_TURN_HOST, binding.etTurnHost.text.toString())
            putString(KEY_TURN_USER, binding.etTurnUser.text.toString())
            putString(KEY_TURN_PASSWORD, binding.etTurnPassword.text.toString())
            putBoolean(KEY_AUTO_START, binding.switchAutoStart.isChecked)
            apply()
        }
    }

    private fun setupListeners() {
        binding.btnStartStop.setOnClickListener {
            val service = cameraService
            if (service != null && service.isSignalingConnected) {
                disconnectFromBackend()
            } else {
                ensurePermissionsAndConnect()
            }
        }

        binding.switchAutoStart.setOnCheckedChangeListener { _, _ ->
            saveConfig()
        }

        binding.btnSaveConfig.setOnClickListener {
            saveConfig()
            Toast.makeText(this, "Configuration enregistree", Toast.LENGTH_SHORT).show()
            disconnectFromBackend()
            uiHandler.postDelayed({ ensurePermissionsAndConnect() }, 500)
        }

        binding.btnBatterySettings.setOnClickListener {
            openBatterySettings()
        }
    }

    private fun ensurePermissionsAndConnect() {
        val serverUrl = binding.etServerUrl.text.toString()
        val authToken = binding.etAuthToken.text.toString()
        val cameraId = binding.etCameraId.text.toString().toIntOrNull() ?: 0

        if (serverUrl.isEmpty() || authToken.isEmpty() || cameraId == 0) {
            return
        }

        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            connectToBackend()
        } else {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun connectToBackend() {
        val serverUrl = binding.etServerUrl.text.toString()
        val authToken = binding.etAuthToken.text.toString()
        val cameraId = binding.etCameraId.text.toString().toIntOrNull() ?: 0

        if (serverUrl.isEmpty() || authToken.isEmpty() || cameraId == 0) {
            Toast.makeText(this, "Veuillez remplir la configuration", Toast.LENGTH_LONG).show()
            return
        }

        saveConfig()

        val intent = Intent(this, CameraService::class.java).apply {
            action = CameraService.ACTION_PREPARE
            putExtra(CameraService.EXTRA_SERVER_URL, serverUrl)
            putExtra(CameraService.EXTRA_AUTH_TOKEN, authToken)
            putExtra(CameraService.EXTRA_CAMERA_ID, cameraId)
            putExtra(CameraService.EXTRA_TURN_HOST, binding.etTurnHost.text.toString())
            putExtra(CameraService.EXTRA_TURN_USER, binding.etTurnUser.text.toString())
            putExtra(CameraService.EXTRA_TURN_PASSWORD, binding.etTurnPassword.text.toString())
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        updateUI()
    }

    private fun disconnectFromBackend() {
        val intent = Intent(this, CameraService::class.java).apply {
            action = CameraService.ACTION_STOP
        }
        startService(intent)
        updateUI()
    }

    private fun updateUI() {
        val service = cameraService
        val connected = service?.isSignalingConnected == true
        val streaming = service?.isStreaming == true

        binding.btnStartStop.text = if (connected) {
            getString(R.string.disconnect_from_server)
        } else {
            getString(R.string.connect_to_server)
        }

        binding.statusIndicator.setBackgroundResource(
            if (connected) R.drawable.status_online else R.drawable.status_offline
        )

        binding.tvStatus.text = when {
            streaming -> getString(R.string.status_streaming)
            connected -> getString(R.string.status_connected)
            else -> getString(R.string.status_disconnected)
        }

        val ip = getLocalIpAddress() ?: "-"
        binding.tvLocalIp.text = getString(R.string.local_ip_format, ip)
        val server = service?.configuredServerUrl?.ifEmpty { "-" } ?: "-"
        binding.tvServerInfo.text = getString(R.string.server_info_format, server)

        binding.etServerUrl.isEnabled = !connected
        binding.etAuthToken.isEnabled = !connected
        binding.etCameraId.isEnabled = !connected
        binding.etTurnHost.isEnabled = !connected
        binding.etTurnUser.isEnabled = !connected
        binding.etTurnPassword.isEnabled = !connected
    }

    private fun startUiRefresh() {
        uiHandler.removeCallbacks(uiRefreshRunnable)
        uiHandler.post(uiRefreshRunnable)
    }

    private fun stopUiRefresh() {
        uiHandler.removeCallbacks(uiRefreshRunnable)
    }

    private fun getLocalIpAddress(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces()
                .toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains('.') == true }
                ?.hostAddress
        } catch (e: Exception) {
            null
        }
    }

    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val packageName = packageName

            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                showBatteryOptimizationDialog()
            }
        }
    }

    private fun showBatteryOptimizationDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.battery_optimization_title)
            .setMessage(R.string.battery_optimization_message)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                openBatterySettings()
            }
            .setNegativeButton(R.string.later, null)
            .show()
    }

    private fun openBatterySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }
}
