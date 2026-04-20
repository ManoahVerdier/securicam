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
import android.os.IBinder
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

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "securicam_prefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_CAMERA_ID = "camera_id"
        private const val KEY_AUTO_START = "auto_start_enabled"
    }

    private lateinit var binding: ActivityMainBinding
    
    private var cameraService: CameraService? = null
    private var isServiceBound = false

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
            startStreaming()
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
    }

    override fun onStart() {
        super.onStart()
        bindService(
            Intent(this, CameraService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onStop() {
        super.onStop()
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
        binding.switchAutoStart.isChecked = prefs.getBoolean(KEY_AUTO_START, false)
    }

    private fun saveConfig() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString(KEY_SERVER_URL, binding.etServerUrl.text.toString())
            putString(KEY_AUTH_TOKEN, binding.etAuthToken.text.toString())
            putInt(KEY_CAMERA_ID, binding.etCameraId.text.toString().toIntOrNull() ?: 0)
            putBoolean(KEY_AUTO_START, binding.switchAutoStart.isChecked)
            apply()
        }
    }

    private fun setupListeners() {
        binding.btnStartStop.setOnClickListener {
            if (cameraService?.isStreaming == true) {
                stopStreaming()
            } else {
                checkPermissionsAndStart()
            }
        }

        binding.switchAutoStart.setOnCheckedChangeListener { _, isChecked ->
            saveConfig()
        }

        binding.btnSaveConfig.setOnClickListener {
            saveConfig()
            Toast.makeText(this, "Configuration saved", Toast.LENGTH_SHORT).show()
        }

        binding.btnBatterySettings.setOnClickListener {
            openBatterySettings()
        }
    }

    private fun checkPermissionsAndStart() {
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
            startStreaming()
        } else {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun startStreaming() {
        val serverUrl = binding.etServerUrl.text.toString()
        val authToken = binding.etAuthToken.text.toString()
        val cameraId = binding.etCameraId.text.toString().toIntOrNull() ?: 0

        if (serverUrl.isEmpty() || authToken.isEmpty() || cameraId == 0) {
            Toast.makeText(this, "Please fill all configuration fields", Toast.LENGTH_LONG).show()
            return
        }

        saveConfig()

        val intent = Intent(this, CameraService::class.java).apply {
            action = CameraService.ACTION_START
            putExtra(CameraService.EXTRA_SERVER_URL, serverUrl)
            putExtra(CameraService.EXTRA_AUTH_TOKEN, authToken)
            putExtra(CameraService.EXTRA_CAMERA_ID, cameraId)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        Toast.makeText(this, "Starting camera stream...", Toast.LENGTH_SHORT).show()
    }

    private fun stopStreaming() {
        val intent = Intent(this, CameraService::class.java).apply {
            action = CameraService.ACTION_STOP
        }
        startService(intent)
        Toast.makeText(this, "Stopping camera stream...", Toast.LENGTH_SHORT).show()
    }

    private fun updateUI() {
        val isStreaming = cameraService?.isStreaming == true
        
        binding.btnStartStop.text = if (isStreaming) {
            getString(R.string.stop_streaming)
        } else {
            getString(R.string.start_streaming)
        }

        binding.statusIndicator.setBackgroundResource(
            if (isStreaming) R.drawable.status_online else R.drawable.status_offline
        )

        binding.tvStatus.text = if (isStreaming) {
            getString(R.string.status_streaming)
        } else {
            getString(R.string.status_stopped)
        }

        // Disable config editing while streaming
        binding.etServerUrl.isEnabled = !isStreaming
        binding.etAuthToken.isEnabled = !isStreaming
        binding.etCameraId.isEnabled = !isStreaming
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
