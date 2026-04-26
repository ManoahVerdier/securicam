package com.securicam.camera.webrtc

import android.content.Context
import org.webrtc.Camera2Enumerator

/**
 * Reports the physical camera lenses available on this phone (front, back,
 * ultrawide, telephoto, …) so the web interface can let the user pick one.
 *
 * Uses libwebrtc's [Camera2Enumerator]: lens IDs match the strings expected by
 * [org.webrtc.CameraVideoCapturer.switchCamera] when a specific deviceName is
 * requested.
 */
object LensCatalog {

    /** A physical lens advertised to the web UI. */
    data class Lens(
        val id: String,
        val facing: String, // "front" | "back" | "external"
        val label: String
    )

    /**
     * Build the list of lenses. Multiple back lenses get distinct labels
     * (e.g. "Caméra arrière (1)", "Caméra arrière (2)") so the user can pick
     * between wide / ultrawide / telephoto without us guessing focal length.
     */
    fun enumerate(context: Context): List<Lens> {
        val enumerator = Camera2Enumerator(context)
        val deviceNames = enumerator.deviceNames

        val backCount = deviceNames.count { enumerator.isBackFacing(it) }
        val frontCount = deviceNames.count { enumerator.isFrontFacing(it) }

        var backIdx = 0
        var frontIdx = 0
        var externalIdx = 0

        return deviceNames.map { name ->
            when {
                enumerator.isBackFacing(name) -> {
                    backIdx += 1
                    val label = if (backCount <= 1) "Caméra arrière" else "Caméra arrière ($backIdx)"
                    Lens(id = name, facing = "back", label = label)
                }
                enumerator.isFrontFacing(name) -> {
                    frontIdx += 1
                    val label = if (frontCount <= 1) "Caméra avant" else "Caméra avant ($frontIdx)"
                    Lens(id = name, facing = "front", label = label)
                }
                else -> {
                    externalIdx += 1
                    Lens(id = name, facing = "external", label = "Caméra externe ($externalIdx)")
                }
            }
        }
    }

    /** Default lens to pick on startup: prefer the first back camera, fall back to front. */
    fun defaultLensId(context: Context): String? {
        val lenses = enumerate(context)
        return lenses.firstOrNull { it.facing == "back" }?.id
            ?: lenses.firstOrNull { it.facing == "front" }?.id
            ?: lenses.firstOrNull()?.id
    }
}
