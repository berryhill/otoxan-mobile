package com.otoxan.mobile.ui

data class OtoxanUiState(
    val permissionState: PermissionState = PermissionState.Unknown,
    val selectedInputName: String = "Unknown",
    val selectedInputType: String = "Unknown",
    val selectedOutputName: String = "Unknown",
    val selectedOutputType: String = "Unknown",
    val wearableRouteActive: Boolean = false,
    val sessionState: VoiceSessionState = VoiceSessionState.Idle,
    val lastEvidence: String = "No route check yet",
    val transcript: String = "",
    val assistantResponse: String = "",
    val lastError: String? = null
)

enum class PermissionState {
    Unknown,
    Granted,
    Denied
}

enum class VoiceSessionState {
    Idle,
    CheckingRoute,
    Ready,
    RecordingTest,
    PlayingTest,
    Error
}
