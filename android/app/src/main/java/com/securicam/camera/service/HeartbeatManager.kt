package com.securicam.camera.service

import android.util.Log
import kotlinx.coroutines.*

class HeartbeatManager(
    private val scope: CoroutineScope,
    private val intervalMs: Long = 30_000L,
    private val onTick: suspend () -> Unit
) {
    private var job: Job? = null

    fun start() {
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                delay(intervalMs)
                try {
                    onTick()
                } catch (e: Exception) {
                    Log.w("HeartbeatManager", "Heartbeat tick failed", e)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
