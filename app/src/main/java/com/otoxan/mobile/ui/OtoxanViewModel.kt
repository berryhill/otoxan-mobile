package com.otoxan.mobile.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.otoxan.mobile.BuildConfig
import com.otoxan.mobile.voice.AudioRouter
import com.otoxan.mobile.voice.MicCapture
import com.otoxan.mobile.voice.SpeechPlayback
import com.otoxan.mobile.voice.XanderVoiceClient
import com.otoxan.mobile.voice.createXanderVoiceClient
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
                val pcm = micCapture.recordPcmForMillis(5_000)
                if (pcm.isEmpty()) error("Microphone capture returned 0 bytes")
                val result = xanderVoiceClient.sendVoiceTurn(pcm, routeEvidence)
                result.ttsPcm16Mono16k?.let { speechPlayback.playPcm16Mono16k(it) }
                VoiceTurnUiResult(routeEvidence = routeEvidence, capturedBytes = pcm.size, result = result)
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
                        lastEvidence = "Voice loop ok: captured=${proof.capturedBytes} bytes; backendReceived=${result.bytesReceived ?: "unknown"}; tts=$ttsBytes bytes; ${proof.routeEvidence.message}"
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
        audioRouter.clearRoute()
        _uiState.update {
            it.copy(
                selectedInputName = "Unknown",
                selectedInputType = "Unknown",
                selectedOutputName = "Unknown",
                selectedOutputType = "Unknown",
                wearableRouteActive = false,
                sessionState = VoiceSessionState.Idle,
                lastEvidence = "Communication route cleared and audio mode restored"
            )
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
                    speechPlayback = SpeechPlayback(),
                    xanderVoiceClient = createXanderVoiceClient(BuildConfig.XANDER_VOICE_ENDPOINT)
                ) as T
            }
        }
    }
}
