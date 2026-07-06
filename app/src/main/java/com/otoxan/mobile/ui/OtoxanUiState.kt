package com.otoxan.mobile.ui

data class TelemetryPassSummary(
    val turnId: String,
    val success: Boolean,
    val pass1Status: String?,
    val routeName: String,
    val totalMs: Long?,
    val ttfaMs: Long?,
    val postCaptureAckDelayMs: Long?,
    val backendMs: Long?,
    val sttMs: Int?,
    val xanderMs: Int?,
    val playbackMs: Long?,
    val capturedBytes: Int,
    val peakAmplitude: Int,
    val transcriptSource: String?,
    val assistantTextLength: Int,
    val error: String? = null
)

data class LatencyCardMetric(
    val label: String,
    val value: String,
    val detail: String
)

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
    val sttProvider: String? = null,
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
    val localAckKind: String = "none",
    val localAckStartMs: Long? = null,
    val localAckTotalMs: Long? = null,
    val assistantPlaybackStartMs: Long? = null,
    val backendResponseReadyMs: Long? = null,
    val ttfaMs: Long? = null,
    val postCaptureAckDelayMs: Long? = null,
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
    val telemetryHistory: List<TelemetryPassSummary> = emptyList(),
    val lastError: String? = null
)

val OtoxanUiState.voiceActivityActive: Boolean
    get() = conversationActive || sessionState == VoiceSessionState.RecordingTest || sessionState == VoiceSessionState.PlayingTest

val OtoxanUiState.latencyCardMetrics: List<LatencyCardMetric>
    get() = listOf(
        LatencyCardMetric(
            label = "Capture",
            value = captureReadMs.toLatencyMsText(),
            detail = "target ${captureExpectedMs.toLatencyMsText()}"
        ),
        LatencyCardMetric(
            label = "Ack gap",
            value = postCaptureAckDelayMs.toLatencyMsText(),
            detail = "after capture · $localAckKind"
        ),
        LatencyCardMetric(
            label = "TTFA",
            value = ttfaMs.toLatencyMsText(),
            detail = firstAudioLatencyDetail
        )
    )

private val OtoxanUiState.firstAudioLatencyDetail: String
    get() = when {
        localAckStartMs != null -> "local ack at ${localAckStartMs.toLatencyMsText()}"
        assistantPlaybackStartMs != null -> "assistant at ${assistantPlaybackStartMs.toLatencyMsText()}"
        else -> "first audio unknown"
    }

private fun Long?.toLatencyMsText(): String = this?.let { "${it}ms" } ?: "unknown"

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
