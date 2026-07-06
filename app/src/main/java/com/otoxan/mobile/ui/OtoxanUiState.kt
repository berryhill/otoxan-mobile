package com.otoxan.mobile.ui

data class OtoxanUiState(
    val voiceEndpoint: String = "",
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
    val capturedBytes: Int = 0,
    val backendBytesReceived: Int? = null,
    val ttsBytes: Int = 0,
    val provider: String? = null,
    val transcriptSource: String? = null,
    val sttStatus: String? = null,
    val sttLatencyMs: Int? = null,
    val pass1Status: String? = null,
    val pass1Ready: Boolean? = null,
    val audioFormat: String? = null,
    val backendAudioDurationMs: Int? = null,
    val backendAudioPeak: Int? = null,
    val backendAudioRms: Double? = null,
    val expectedCaptureBytes: Int = 0,
    val capturePeakAmplitude: Int = 0,
    val captureUsable: Boolean? = null,
    val backendSelfTestStatus: String = "Not run",
    val routeReleasePolicy: String = "Release communication route before playback; clear communication device twice; reset SCO and MODE_NORMAL",
    val playbackPolicy: String = "Playback uses non-call speech audio attributes with transient audio focus",
    val turnStage: String = "Idle",
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
