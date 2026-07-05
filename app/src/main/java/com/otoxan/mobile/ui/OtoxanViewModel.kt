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
    private val _uiState = MutableStateFlow(OtoxanUiState())
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

    fun refreshRoute() {
        _uiState.update { it.copy(sessionState = VoiceSessionState.CheckingRoute, lastError = null) }
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

        _uiState.update { it.copy(sessionState = VoiceSessionState.RecordingTest, lastError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val routeEvidence = audioRouter.inspectAndSelectWearable()
                if (!routeEvidence.wearableActive) {
                    error("Wearable route dropped before capture: ${routeEvidence.message}")
                }
                val captureMillis = 5_000L
                val pcm = micCapture.recordPcmForMillis(captureMillis)
                val expectedBytes = expectedPcmBytes(captureMillis)
                val peak = pcm.peakPcm16Amplitude()
                val usable = isUsableVoiceCapture(pcm, expectedBytes)
                if (!usable) {
                    error("Microphone capture unusable: captured=${pcm.size} bytes, expected=$expectedBytes, peak=$peak, likely silent or truncated")
                }
                val result = xanderVoiceClient.sendVoiceTurn(pcm, routeEvidence)
                val backendTts = result.ttsPcm16Mono16k
                if (backendTts != null && backendTts.isNotEmpty()) {
                    speechPlayback.playPcm16Mono16k(backendTts)
                } else if (result.assistantText.isNotBlank() && result.provider != "stub") {
                    speechPlayback.speakText(result.assistantText)
                }
                VoiceTurnUiResult(
                    routeEvidence = routeEvidence,
                    capturedBytes = pcm.size,
                    expectedCaptureBytes = expectedBytes,
                    capturePeakAmplitude = peak,
                    captureUsable = usable,
                    result = result
                )
            }.onSuccess { proof ->
                val result = proof.result
                val ttsBytes = result.ttsPcm16Mono16k?.size ?: 0
                _uiState.update {
                    it.copy(
                        selectedInputName = proof.routeEvidence.inputName,
                        selectedInputType = proof.routeEvidence.inputType,
                        selectedOutputName = proof.routeEvidence.outputName,
                        selectedOutputType = proof.routeEvidence.outputType,
                        wearableRouteActive = proof.routeEvidence.wearableActive,
                        sessionState = VoiceSessionState.Ready,
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
                        lastEvidence = if (result.provider == "stub") {
                            "Stub mode: captured=${proof.capturedBytes} bytes locally; no backend endpoint is configured."
                        } else {
                            "Voice loop ok: pass1=${result.pass1Status ?: "unknown"}; captured=${proof.capturedBytes}/${proof.expectedCaptureBytes} bytes peak=${proof.capturePeakAmplitude}; backendReceived=${result.bytesReceived ?: "unknown"}; provider=${result.provider ?: "unknown"}; transcriptSource=${result.transcriptSource ?: "unknown"}; stt=${result.sttStatus ?: "unknown"}; tts=$ttsBytes bytes; ${proof.routeEvidence.message}"
                        }
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        sessionState = VoiceSessionState.Error,
                        lastEvidence = "Capture failed",
                        lastError = error.message ?: error::class.java.simpleName
                    )
                }
            }
        }
    }

    private data class VoiceTurnUiResult(
        val routeEvidence: com.otoxan.mobile.voice.RouteEvidence,
        val capturedBytes: Int,
        val expectedCaptureBytes: Int,
        val capturePeakAmplitude: Int,
        val captureUsable: Boolean,
        val result: com.otoxan.mobile.voice.VoiceTurnResult
    )

    fun playRouteProof() {
        _uiState.update { it.copy(sessionState = VoiceSessionState.PlayingTest, lastError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val evidence = audioRouter.inspectAndSelectWearable()
                speechPlayback.playProofTone()
                evidence
            }
                .onSuccess { evidence ->
                    _uiState.update {
                        it.copy(
                            selectedInputName = evidence.inputName,
                            selectedInputType = evidence.inputType,
                            selectedOutputName = evidence.outputName,
                            selectedOutputType = evidence.outputType,
                            wearableRouteActive = evidence.wearableActive,
                            sessionState = if (evidence.wearableActive) VoiceSessionState.Ready else VoiceSessionState.Idle,
                            lastEvidence = "Played audible 660 Hz proof tone; ${evidence.message}"
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            sessionState = VoiceSessionState.Error,
                            lastError = error.message ?: error::class.java.simpleName
                        )
                    }
                }
        }
    }

    fun clearRoute() {
        _uiState.update { it.copy(sessionState = VoiceSessionState.CheckingRoute, lastError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val evidence = runCatching { audioRouter.clearRoute() }
                .getOrElse { error -> RouteEvidence.default("Communication route release failed: ${error.message ?: error::class.java.simpleName}") }
            _uiState.update {
                it.copy(
                    selectedInputName = evidence.inputName,
                    selectedInputType = evidence.inputType,
                    selectedOutputName = evidence.outputName,
                    selectedOutputType = evidence.outputType,
                    wearableRouteActive = false,
                    sessionState = VoiceSessionState.Idle,
                    lastEvidence = evidence.message,
                    lastError = null
                )
            }
        }
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
