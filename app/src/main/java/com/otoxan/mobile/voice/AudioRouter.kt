package com.otoxan.mobile.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat

class AudioRouter(private val context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var lastEvidence = RouteEvidence.default()

    @SuppressLint("MissingPermission")
    fun inspectAndSelectWearable(): RouteEvidence {
        if (!hasBluetoothConnectPermission()) {
            lastEvidence = RouteEvidence.default(
                message = "BLUETOOTH_CONNECT permission missing; cannot inspect communication devices"
            )
            return lastEvidence
        }

        val communicationDevices = audioManager.availableCommunicationDevices
        val preferred = communicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
            ?: communicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }

        lastEvidence = if (preferred != null) {
            val selectedBefore = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.communicationDevice else null
            val alreadySelected = selectedBefore?.id == preferred.id && selectedBefore.type == preferred.type
            val selected = if (alreadySelected) {
                true
            } else {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                audioManager.setCommunicationDevice(preferred)
            }
            RouteEvidence(
                inputName = preferred.safeProductName(),
                inputType = preferred.type.label(),
                outputName = preferred.safeProductName(),
                outputType = preferred.type.label(),
                wearableActive = selected,
                message = if (alreadySelected) {
                    "communicationDevice already selected; device=${preferred.safeProductName()}; type=${preferred.type.label()}"
                } else {
                    "setCommunicationDevice=$selected; device=${preferred.safeProductName()}; type=${preferred.type.label()}"
                }
            )
        } else {
            RouteEvidence.default(
                message = "No BLE headset or Bluetooth SCO communication device reported by Android; available=${communicationDevices.map { it.type.label() }}"
            )
        }
        return lastEvidence
    }

    fun currentEvidence(): RouteEvidence = lastEvidence

    @Suppress("DEPRECATION")
    fun clearRoute(): RouteEvidence {
        val selectedBefore = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.communicationDevice?.safeProductName()
        } else {
            null
        }
        val releaseSteps = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audioManager.clearCommunicationDevice() }
                .onSuccess { releaseSteps += "clearCommunicationDevice" }
                .onFailure { releaseSteps += "clearCommunicationDevice failed=${it.javaClass.simpleName}" }
        }
        runCatching { audioManager.stopBluetoothSco() }
            .onSuccess { releaseSteps += "stopBluetoothSco" }
            .onFailure { releaseSteps += "stopBluetoothSco failed=${it.javaClass.simpleName}" }
        runCatching { audioManager.isBluetoothScoOn = false }
            .onSuccess { releaseSteps += "bluetoothScoOff" }
            .onFailure { releaseSteps += "bluetoothScoOff failed=${it.javaClass.simpleName}" }
        runCatching { audioManager.mode = AudioManager.MODE_NORMAL }
            .onSuccess { releaseSteps += "mode=MODE_NORMAL" }
            .onFailure { releaseSteps += "mode reset failed=${it.javaClass.simpleName}" }

        val selectedAfter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.communicationDevice?.safeProductName()
        } else {
            null
        }
        lastEvidence = RouteEvidence.default(
            message = "Communication route released; before=${selectedBefore ?: "none"}; after=${selectedAfter ?: "none"}; ${releaseSteps.joinToString("; ")}"
        )
        return lastEvidence
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }
}

data class RouteEvidence(
    val inputName: String,
    val inputType: String,
    val outputName: String,
    val outputType: String,
    val wearableActive: Boolean,
    val message: String
) {
    companion object {
        fun default(message: String = "No route check yet") = RouteEvidence(
            inputName = "Default Android route",
            inputType = "default",
            outputName = "Default Android route",
            outputType = "default",
            wearableActive = false,
            message = message
        )
    }
}

private fun AudioDeviceInfo.safeProductName(): String = productName?.toString() ?: "Bluetooth communication device"

private fun Int.label(): String = when (this) {
    AudioDeviceInfo.TYPE_BLE_HEADSET -> "TYPE_BLE_HEADSET"
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "TYPE_BLUETOOTH_SCO"
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "TYPE_BLUETOOTH_A2DP"
    AudioDeviceInfo.TYPE_BUILTIN_MIC -> "TYPE_BUILTIN_MIC"
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "TYPE_BUILTIN_SPEAKER"
    else -> "TYPE_$this"
}
