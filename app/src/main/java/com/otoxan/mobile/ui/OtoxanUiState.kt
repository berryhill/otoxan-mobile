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
    val liveVoicePeak: Int = 0,
    val liveVoiceLevel: Float = 0f,
    val liveSpeechDetected: Boolean = false,
    val captureUsable: Boolean? = null,
    val backendSelfTestStatus: String = "Not run",
    val telemetryStatus: String = "Not sent",
    val routeReleasePolicy: String = "Release communication route before playback; clear communication device twice; reset SCO and MODE_NORMAL",
    val playbackMode: PlaybackMode = PlaybackMode.NonCallPlayback,
    val playbackPolicy: String = PlaybackMode.NonCallPlayback.description,
    val conversationActive: Boolean = false,
    val conversationTurnCount: Int = 0,
    val turnStage: String = "Idle",
    val turnTotalMs: Long? = null,
    val routeSelectMs: Long? = null,
    val captureReadMs: Long? = null,
    val captureExpectedMs: Long? = null,
    val backendRoundTripMs: Long? = null,
    val routeReleaseMs: Long? = null,
    val playbackTotalMs: Long? = null,
    val playbackKind: String = "unknown",
    val httpStatusCode: Int? = null,
    val requestBytes: Int? = null,
    val responseBytes: Int? = null,
    val requestBuildMs: Long? = null,
    val uploadMs: Long? = null,
    val responseCodeWaitMs: Long? = null,
    val responseReadMs: Long? = null,
    val responseParseMs: Long? = null,
    val backendTotalMs: Int? = null,
    val decodePcmMs: Int? = null,
    val audioStatsMs: Int? = null,
    val transcriptTotalMs: Int? = null,
    val xanderSessionMs: Int? = null,
    val responseBuildMs: Int? = null,
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
    ConversationActive,
    RecordingTest,
    PlayingTest,
    Error
}


enum class PlaybackMode(val label: String, val description: String) {
    NonCallPlayback(
        label = "Non-call playback",
        description = "Playback uses non-call speech audio attributes with transient audio focus"
    ),
    SilentAfterCapture(
        label = "Silent after capture",
        description = "Capture and backend run, then playback is skipped to isolate whether capture alone wedges Meta"
    );

    fun next(): PlaybackMode = when (this) {
        NonCallPlayback -> SilentAfterCapture
        SilentAfterCapture -> NonCallPlayback
    }
}
