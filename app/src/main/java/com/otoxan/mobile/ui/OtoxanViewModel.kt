package com.otoxan.mobile.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.otoxan.mobile.BuildConfig
import com.otoxan.mobile.voice.AudioRouter
import com.otoxan.mobile.voice.MicCapture
import com.otoxan.mobile.voice.RouteEvidence
import com.otoxan.mobile.voice.SpeechPlayback
import com.otoxan.mobile.voice.XanderVoiceClient
import com.otoxan.mobile.voice.createXanderVoiceClient
import com.otoxan.mobile.voice.expectedPcmBytes
import com.otoxan.mobile.voice.isUsableVoiceCapture
import com.otoxan.mobile.voice.peakPcm16Amplitude
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class OtoxanViewModel(
    private val audioRouter: AudioRouter,
    private val micCapture: MicCapture,
    private val speechPlayback: SpeechPlayback,
    private val xanderVoiceClient: XanderVoiceClient
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        OtoxanUiState(
            voiceEndpoint = BuildConfig.XANDER_VOICE_ENDPOINT.ifBlank { "stub/no endpoint baked" }
        )
    )
    val uiState: StateFlow<OtoxanUiState> = _uiState.asStateFlow()

    fun onPermissionResult(granted: Boolean) {
        _uiState.update {
            it.copy(
                permissionState = if (granted) PermissionState.Granted else PermissionState.Denied,
                lastEvidence = if (granted) "Audio/Bluetooth permissions granted" else "Permission denied; route proof disabled",
                lastError = if (granted) null else "Grant microphone and Bluetooth permissions to test Ray-Ban Meta routing."
            )
        }
    }


    fun togglePlaybackMode() {
        _uiState.update {
            val nextMode = it.playbackMode.next()
            it.copy(
                playbackMode = nextMode,
                playbackPolicy = nextMode.description,
                turnStage = "Playback mode set to ${nextMode.label}",
                lastEvidence = "Playback mode: ${nextMode.description}"
            )
        }
    }

    fun refreshRoute() {
        _uiState.update { it.copy(sessionState = VoiceSessionState.CheckingRoute, turnStage = "Selecting wearable route", lastError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { audioRouter.inspectAndSelectWearable() }
                .onSuccess { evidence ->
                    _uiState.update {
                        it.copy(
                            selectedInputName = evidence.inputName,
                            selectedInputType = evidence.inputType,
                            selectedOutputName = evidence.outputName,
                            selectedOutputType = evidence.outputType,
                            wearableRouteActive = evidence.wearableActive,
                            sessionState = if (evidence.wearableActive) VoiceSessionState.Ready else VoiceSessionState.Idle,
                            turnStage = if (evidence.wearableActive) "Wearable route selected" else "No wearable route selected",
                            lastEvidence = evidence.message,
                            lastError = null
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            sessionState = VoiceSessionState.Error,
                            wearableRouteActive = false,
                            turnStage = "Route check failed",
                            lastEvidence = "Route check failed",
                            lastError = error.message ?: error::class.java.simpleName
                        )
                    }
                }
        }
    }

    fun recordFiveSecondProof() {
        val routeActive = _uiState.value.wearableRouteActive
        if (!routeActive) {
            _uiState.update {
                it.copy(
                    sessionState = VoiceSessionState.Error,
                    lastError = "No wearable communication route is active. Refusing to claim glasses mic capture."
                )
            }
            return
        }

        _uiState.update { it.copy(sessionState = VoiceSessionState.RecordingTest, turnStage = "Starting Ray-Ban route capture", lastError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                var releaseEvidence = RouteEvidence.default("Communication route release not reached")
                try {
                    val routeEvidence = audioRouter.inspectAndSelectWearable()
                    if (!routeEvidence.wearableActive) {
                        error("Wearable route dropped before capture: ${routeEvidence.message}")
                    }
                    _uiState.update { it.copy(turnStage = "Capturing 5 seconds from selected route") }
                    val captureMillis = 5_000L
                    val pcm = micCapture.recordPcmForMillis(captureMillis)
                    val expectedBytes = expectedPcmBytes(captureMillis)
                    val peak = pcm.peakPcm16Amplitude()
                    val usable = isUsableVoiceCapture(pcm, expectedBytes)
                    if (!usable) {
                        error("Microphone capture unusable: captured=${pcm.size} bytes, expected=$expectedBytes, peak=$peak, likely silent or truncated")
                    }
                    _uiState.update { it.copy(turnStage = "Posting ${pcm.size} bytes to Xander backend") }
                    val result = xanderVoiceClient.sendVoiceTurn(pcm, routeEvidence)
                    _uiState.update { it.copy(turnStage = "Releasing call route before assistant playback") }
                    releaseEvidence = releaseCommunicationRoute("Released communication route before assistant playback")
                    val playbackMode = _uiState.value.playbackMode
                    _uiState.update {
                        it.copy(
                            turnStage = if (playbackMode == PlaybackMode.SilentAfterCapture) {
                                "Skipping playback by operator mode"
                            } else {
                                "Playing assistant response with non-call playback policy"
                            }
                        )
                    }
                    val backendTts = result.ttsPcm16Mono16k
                    if (playbackMode == PlaybackMode.SilentAfterCapture) {
                        // Intentional diagnostic mode: isolate whether capture/route release alone wedges Meta call state.
                    } else if (backendTts != null && backendTts.isNotEmpty()) {
                        speechPlayback.playPcm16Mono16k(backendTts)
                    } else if (result.assistantText.isNotBlank() && result.provider != "stub") {
                        speechPlayback.speakText(result.assistantText)
                    }
                    VoiceTurnUiResult(
                        routeEvidence = routeEvidence,
                        releaseEvidence = releaseEvidence,
                        capturedBytes = pcm.size,
                        expectedCaptureBytes = expectedBytes,
                        capturePeakAmplitude = peak,
                        captureUsable = usable,
                        result = result
                    )
                } finally {
                    if (releaseEvidence.message == "Communication route release not reached") {
                        releaseCommunicationRoute("Released communication route after interrupted voice turn")
                    }
                }
            }.onSuccess { proof ->
                val result = proof.result
                val ttsBytes = result.ttsPcm16Mono16k?.size ?: 0
                _uiState.update {
                    it.copy(
                        selectedInputName = proof.routeEvidence.inputName,
                        selectedInputType = proof.routeEvidence.inputType,
                        selectedOutputName = proof.routeEvidence.outputName,
                        selectedOutputType = proof.routeEvidence.outputType,
                        wearableRouteActive = false,
                        sessionState = VoiceSessionState.Idle,
                        transcript = result.transcript,
                        assistantResponse = result.assistantText,
                        capturedBytes = proof.capturedBytes,
                        backendBytesReceived = result.bytesReceived,
                        ttsBytes = ttsBytes,
                        provider = result.provider,
                        transcriptSource = result.transcriptSource,
                        sttStatus = result.sttStatus,
                        sttLatencyMs = result.sttLatencyMs,
                        pass1Status = result.pass1Status,
                        pass1Ready = result.pass1Ready,
                        audioFormat = result.audioFormat,
                        backendAudioDurationMs = result.audioDurationMs,
                        backendAudioPeak = result.audioPeak,
                        backendAudioRms = result.audioRms,
                        expectedCaptureBytes = proof.expectedCaptureBytes,
                        capturePeakAmplitude = proof.capturePeakAmplitude,
                        captureUsable = proof.captureUsable,
                        turnStage = if (it.playbackMode == PlaybackMode.SilentAfterCapture) "Turn complete; playback skipped by operator mode" else "Turn complete; communication route released before playback",
                        lastEvidence = if (result.provider == "stub") {
                            "Stub mode: captured=${proof.capturedBytes} bytes locally; no backend endpoint is configured."
                        } else {
                            "Voice loop ok: pass1=${result.pass1Status ?: "unknown"}; captured=${proof.capturedBytes}/${proof.expectedCaptureBytes} bytes peak=${proof.capturePeakAmplitude}; backendReceived=${result.bytesReceived ?: "unknown"}; provider=${result.provider ?: "unknown"}; transcriptSource=${result.transcriptSource ?: "unknown"}; stt=${result.sttStatus ?: "unknown"}; tts=$ttsBytes bytes; routeUsed=[${proof.routeEvidence.message}]; release=[${proof.releaseEvidence.message}]"
                        }
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        sessionState = VoiceSessionState.Error,
                        turnStage = "Voice turn failed",
                        lastEvidence = "Capture failed",
                        lastError = error.message ?: error::class.java.simpleName
                    )
                }
            }
        }
    }

    private data class VoiceTurnUiResult(
        val routeEvidence: com.otoxan.mobile.voice.RouteEvidence,
        val releaseEvidence: com.otoxan.mobile.voice.RouteEvidence,
        val capturedBytes: Int,
        val expectedCaptureBytes: Int,
        val capturePeakAmplitude: Int,
        val captureUsable: Boolean,
        val result: com.otoxan.mobile.voice.VoiceTurnResult
    )

    fun playRouteProof() {
        _uiState.update { it.copy(sessionState = VoiceSessionState.PlayingTest, turnStage = "Starting proof-tone route test", lastError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                var releaseEvidence = RouteEvidence.default("Communication route release not reached")
                try {
                    val evidence = audioRouter.inspectAndSelectWearable()
                    _uiState.update { it.copy(turnStage = "Releasing call route before proof tone playback") }
                    releaseEvidence = releaseCommunicationRoute("Released communication route before proof tone playback")
                    _uiState.update { it.copy(turnStage = "Playing proof tone with non-call playback policy") }
                    speechPlayback.playProofTone()
                    Pair(evidence, releaseEvidence)
                } finally {
                    if (releaseEvidence.message == "Communication route release not reached") {
                        releaseCommunicationRoute("Released communication route after interrupted proof tone")
                    }
                }
            }
                .onSuccess { (evidence, releaseEvidence) ->
                    _uiState.update {
                        it.copy(
                            selectedInputName = evidence.inputName,
                            selectedInputType = evidence.inputType,
                            selectedOutputName = evidence.outputName,
                            selectedOutputType = evidence.outputType,
                            wearableRouteActive = false,
                            sessionState = VoiceSessionState.Idle,
                            turnStage = "Proof tone complete; communication route released before playback",
                            lastEvidence = "Played audible 660 Hz proof tone; routeUsed=[${evidence.message}]; release=[${releaseEvidence.message}]"
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            sessionState = VoiceSessionState.Error,
                            turnStage = "Proof tone failed",
                            lastError = error.message ?: error::class.java.simpleName
                        )
                    }
                }
        }
    }

    fun clearRoute() {
        _uiState.update { it.copy(sessionState = VoiceSessionState.CheckingRoute, turnStage = "Manual route clear requested", lastError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val evidence = runCatching { releaseCommunicationRoute("Manual clear voice route") }
                .getOrElse { error -> RouteEvidence.default("Communication route release failed: ${error.message ?: error::class.java.simpleName}") }
            _uiState.update {
                it.copy(
                    selectedInputName = evidence.inputName,
                    selectedInputType = evidence.inputType,
                    selectedOutputName = evidence.outputName,
                    selectedOutputType = evidence.outputType,
                    wearableRouteActive = false,
                    sessionState = VoiceSessionState.Idle,
                    turnStage = "Manual route clear complete",
                    lastEvidence = evidence.message,
                    lastError = null
                )
            }
        }
    }


    fun runBackendSelfTest() {
        _uiState.update {
            it.copy(
                sessionState = VoiceSessionState.CheckingRoute,
                turnStage = "Running backend self-test without Ray-Bans",
                backendSelfTestStatus = "Running",
                lastError = null
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val pcm = ByteArray(320) { index -> if (index % 2 == 0) 1 else 2 }
                val result = xanderVoiceClient.sendVoiceTurn(
                    pcm,
                    RouteEvidence.default("Backend self-test: no wearable route selected")
                )
                "OK provider=${result.provider ?: "unknown"}; backendReceived=${result.bytesReceived ?: "unknown"}; stt=${result.sttStatus ?: "unknown"}; pass1=${result.pass1Status ?: "unknown"}; endpoint=${BuildConfig.XANDER_VOICE_ENDPOINT.ifBlank { "stub" }}"
            }.onSuccess { status ->
                _uiState.update {
                    it.copy(
                        sessionState = VoiceSessionState.Idle,
                        turnStage = "Backend self-test complete",
                        backendSelfTestStatus = status,
                        lastEvidence = status,
                        lastError = null
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        sessionState = VoiceSessionState.Error,
                        turnStage = "Backend self-test failed",
                        backendSelfTestStatus = "FAILED: ${error.message ?: error::class.java.simpleName}",
                        lastError = error.message ?: error::class.java.simpleName
                    )
                }
            }
        }
    }

    private fun releaseCommunicationRoute(reason: String): RouteEvidence {
        val first = audioRouter.clearRoute()
        Thread.sleep(350L)
        val second = audioRouter.clearRoute()
        return RouteEvidence.default(
            "$reason; first=[${first.message}]; second=[${second.message}]"
        )
    }

    override fun onCleared() {
        audioRouter.clearRoute()
        super.onCleared()
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val appContext = context.applicationContext
                return OtoxanViewModel(
                    audioRouter = AudioRouter(appContext),
                    micCapture = MicCapture(),
                    speechPlayback = SpeechPlayback(appContext),
                    xanderVoiceClient = createXanderVoiceClient(BuildConfig.XANDER_VOICE_ENDPOINT)
                ) as T
            }
        }
    }
}
