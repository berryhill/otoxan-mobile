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
import com.otoxan.mobile.voice.VoiceCaptureConfig
import com.otoxan.mobile.voice.VoiceTurnTelemetryPacket
import com.otoxan.mobile.voice.XanderVoiceClient
import com.otoxan.mobile.voice.createXanderVoiceClient
import com.otoxan.mobile.voice.expectedPcmBytes
import com.otoxan.mobile.voice.isUsableVoiceCapture
import com.otoxan.mobile.voice.peakPcm16Amplitude
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import java.util.UUID

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
    private var conversationJob: Job? = null

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

    fun startConversationSession() {
        if (conversationJob?.isActive == true) return
        if (_uiState.value.permissionState != PermissionState.Granted) {
            _uiState.update {
                it.copy(
                    sessionState = VoiceSessionState.Error,
                    lastError = "Grant microphone and Bluetooth permissions before starting a Xander session."
                )
            }
            return
        }
        _uiState.update {
            it.copy(
                conversationActive = true,
                conversationTurnCount = 0,
                sessionState = VoiceSessionState.ConversationActive,
                turnStage = "Starting Xander conversation session",
                telemetryStatus = "Session active; waiting for first turn",
                lastError = null
            )
        }
        conversationJob = viewModelScope.launch(Dispatchers.IO) {
            while (currentCoroutineContext().isActive && _uiState.value.conversationActive) {
                val turnId = UUID.randomUUID().toString()
                runCatching { performVoiceTurn(turnId, requireExistingRoute = false) }
                    .onSuccess { proof ->
                        applyVoiceTurnSuccess(turnId, proof, keepConversationActive = true)
                        delay(350L)
                    }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        val releaseEvidence = runCatching { releaseCommunicationRoute("Released communication route after failed conversation turn") }
                            .getOrElse { RouteEvidence.default("Communication route release failed after conversation turn") }
                        _uiState.update {
                            it.copy(
                                conversationActive = true,
                                wearableRouteActive = false,
                                liveVoicePeak = 0,
                                liveVoiceLevel = 0f,
                                liveSpeechDetected = false,
                                sessionState = VoiceSessionState.ConversationActive,
                                turnStage = "Turn failed; session still active and retrying",
                                telemetryStatus = "Turn failed; next listen will retry",
                                lastEvidence = releaseEvidence.message,
                                lastError = error.message ?: error::class.java.simpleName
                            )
                        }
                        delay(900L)
                    }
            }
        }
    }

    fun endConversationSession() {
        conversationJob?.cancel()
        conversationJob = null
        _uiState.update {
            it.copy(
                conversationActive = false,
                wearableRouteActive = false,
                sessionState = VoiceSessionState.CheckingRoute,
                turnStage = "Ending Xander conversation session",
                lastError = null
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val releaseEvidence = runCatching { releaseCommunicationRoute("Ended Xander conversation session") }
                .getOrElse { error -> RouteEvidence.default("Conversation route release failed: ${error.message ?: error::class.java.simpleName}") }
            _uiState.update {
                it.copy(
                    selectedInputName = releaseEvidence.inputName,
                    selectedInputType = releaseEvidence.inputType,
                    selectedOutputName = releaseEvidence.outputName,
                    selectedOutputType = releaseEvidence.outputType,
                    wearableRouteActive = false,
                    sessionState = VoiceSessionState.Idle,
                    turnStage = "Xander conversation session ended",
                    lastEvidence = releaseEvidence.message,
                    lastError = null
                )
            }
        }
    }

    fun recordFiveSecondProof() {
        val turnId = UUID.randomUUID().toString()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { performVoiceTurn(turnId, requireExistingRoute = true) }
                .onSuccess { proof -> applyVoiceTurnSuccess(turnId, proof, keepConversationActive = false) }
                .onFailure { error ->
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

    private suspend fun performVoiceTurn(turnId: String, requireExistingRoute: Boolean): VoiceTurnUiResult {
        if (requireExistingRoute && !_uiState.value.wearableRouteActive) {
            error("No wearable communication route is active. Refusing to claim glasses mic capture.")
        }
        _uiState.update {
            it.copy(
                sessionState = if (it.conversationActive) VoiceSessionState.ConversationActive else VoiceSessionState.RecordingTest,
                turnStage = if (it.conversationActive) "Listening for Xander session turn ${it.conversationTurnCount + 1}" else "Starting Ray-Ban route capture",
                telemetryStatus = "Pending turnId=$turnId",
                lastError = null
            )
        }

        var releaseEvidence = RouteEvidence.default("Communication route release not reached")
        try {
            val turnStarted = System.nanoTime()
            val routeStarted = System.nanoTime()
            val routeEvidence = audioRouter.inspectAndSelectWearable()
            val routeSelectMs = elapsedMs(routeStarted)
            if (!routeEvidence.wearableActive) {
                error("Wearable route dropped before capture: ${routeEvidence.message}")
            }
            _uiState.update { it.copy(turnStage = "Listening — speak now") }
            val captureConfig = VoiceCaptureConfig()
            val captureStarted = System.nanoTime()
            val capture = micCapture.recordPcmUntilSpeechSilence(captureConfig) { chunkPeak, capturedMillis, speechDetected ->
                val level = (chunkPeak / 8_000f).coerceIn(0f, 1f)
                _uiState.update {
                    it.copy(
                        liveVoicePeak = chunkPeak,
                        liveVoiceLevel = level,
                        liveSpeechDetected = speechDetected,
                        turnStage = if (speechDetected) {
                            "Hearing you — ${capturedMillis}ms captured"
                        } else {
                            "Listening — speak now"
                        }
                    )
                }
            }
            val pcm = capture.pcm16Mono16k
            val captureReadMs = elapsedMs(captureStarted)
            val expectedBytes = expectedPcmBytes(capture.maxCaptureMillis)
            val minimumUsableBytes = expectedPcmBytes(capture.minCaptureMillis)
            val peak = pcm.peakPcm16Amplitude()
            val usable = isUsableVoiceCapture(pcm, minimumUsableBytes)
            if (!usable) {
                error("Microphone capture unusable: captured=${pcm.size} bytes, minimum=$minimumUsableBytes, peak=$peak, stop=${capture.stopReason}, speech=${capture.speechDetected}")
            }
            _uiState.update { it.copy(turnStage = "Thinking — ${pcm.size} bytes to Xander after ${capture.stopReason}") }
            val backendStarted = System.nanoTime()
            val result = xanderVoiceClient.sendVoiceTurn(pcm, routeEvidence)
            val backendRoundTripMs = elapsedMs(backendStarted)
            _uiState.update { it.copy(turnStage = "Releasing call route before assistant playback") }
            val releaseStarted = System.nanoTime()
            releaseEvidence = releaseCommunicationRoute("Released communication route before assistant playback")
            val routeReleaseMs = elapsedMs(releaseStarted)
            val playbackMode = _uiState.value.playbackMode
            _uiState.update {
                it.copy(
                    turnStage = if (playbackMode == PlaybackMode.SilentAfterCapture) {
                        "Skipping playback by operator mode"
                    } else {
                        "Speaking Xander response"
                    }
                )
            }
            val backendTts = result.ttsPcm16Mono16k
            var playbackKind = "none"
            val playbackStarted = System.nanoTime()
            if (playbackMode == PlaybackMode.SilentAfterCapture) {
                playbackKind = "silent"
            } else if (backendTts != null && backendTts.isNotEmpty()) {
                playbackKind = "backend_pcm"
                speechPlayback.playPcm16Mono16k(backendTts)
            } else if (result.assistantText.isNotBlank() && result.provider != "stub") {
                playbackKind = "android_tts"
                speechPlayback.speakText(result.assistantText)
            }
            val playbackTotalMs = elapsedMs(playbackStarted)
            return VoiceTurnUiResult(
                routeEvidence = routeEvidence,
                releaseEvidence = releaseEvidence,
                capturedBytes = pcm.size,
                expectedCaptureBytes = expectedBytes,
                capturePeakAmplitude = peak,
                captureUsable = usable,
                result = result,
                turnTotalMs = elapsedMs(turnStarted),
                routeSelectMs = routeSelectMs,
                captureReadMs = captureReadMs,
                captureExpectedMs = capture.maxCaptureMillis,
                captureStopReason = capture.stopReason,
                backendRoundTripMs = backendRoundTripMs,
                routeReleaseMs = routeReleaseMs,
                playbackTotalMs = playbackTotalMs,
                playbackKind = playbackKind
            )
        } finally {
            if (releaseEvidence.message == "Communication route release not reached") {
                releaseCommunicationRoute("Released communication route after interrupted voice turn")
            }
        }
    }

    private suspend fun applyVoiceTurnSuccess(turnId: String, proof: VoiceTurnUiResult, keepConversationActive: Boolean) {
        val result = proof.result
        val ttsBytes = result.ttsPcm16Mono16k?.size ?: 0
        _uiState.update {
            val nextTurnCount = if (keepConversationActive) it.conversationTurnCount + 1 else it.conversationTurnCount
            it.copy(
                selectedInputName = proof.routeEvidence.inputName,
                selectedInputType = proof.routeEvidence.inputType,
                selectedOutputName = proof.routeEvidence.outputName,
                selectedOutputType = proof.routeEvidence.outputType,
                wearableRouteActive = false,
                conversationActive = keepConversationActive,
                conversationTurnCount = nextTurnCount,
                sessionState = if (keepConversationActive) VoiceSessionState.ConversationActive else VoiceSessionState.Idle,
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
                liveVoicePeak = 0,
                liveVoiceLevel = 0f,
                liveSpeechDetected = false,
                captureUsable = proof.captureUsable,
                turnTotalMs = proof.turnTotalMs,
                routeSelectMs = proof.routeSelectMs,
                captureReadMs = proof.captureReadMs,
                captureExpectedMs = proof.captureExpectedMs,
                backendRoundTripMs = proof.backendRoundTripMs,
                routeReleaseMs = proof.routeReleaseMs,
                playbackTotalMs = proof.playbackTotalMs,
                playbackKind = proof.playbackKind,
                httpStatusCode = result.httpStatusCode,
                requestBytes = result.requestBytes,
                responseBytes = result.responseBytes,
                requestBuildMs = result.requestBuildMs,
                uploadMs = result.uploadMs,
                responseCodeWaitMs = result.responseCodeWaitMs,
                responseReadMs = result.responseReadMs,
                responseParseMs = result.responseParseMs,
                backendTotalMs = result.backendTotalMs,
                decodePcmMs = result.decodePcmMs,
                audioStatsMs = result.audioStatsMs,
                transcriptTotalMs = result.transcriptTotalMs,
                xanderSessionMs = result.xanderSessionMs,
                responseBuildMs = result.responseBuildMs,
                turnStage = if (keepConversationActive) {
                    "Turn $nextTurnCount complete; listening continues until End session"
                } else if (it.playbackMode == PlaybackMode.SilentAfterCapture) {
                    "Turn complete; playback skipped by operator mode"
                } else {
                    "Turn complete; communication route released before playback"
                },
                lastEvidence = if (result.provider == "stub") {
                    "Stub mode: captured=${proof.capturedBytes} bytes locally; no backend endpoint is configured."
                } else {
                    "Voice loop ok: pass1=${result.pass1Status ?: "unknown"}; total=${proof.turnTotalMs}ms; backend=${proof.backendRoundTripMs}ms/server=${result.backendTotalMs ?: "unknown"}ms; captured=${proof.capturedBytes}/${proof.expectedCaptureBytes} bytes actual=${proof.captureReadMs}ms stop=${proof.captureStopReason} peak=${proof.capturePeakAmplitude}; backendReceived=${result.bytesReceived ?: "unknown"}; provider=${result.provider ?: "unknown"}; transcriptSource=${result.transcriptSource ?: "unknown"}; stt=${result.sttStatus ?: "unknown"}; tts=$ttsBytes bytes; routeUsed=[${proof.routeEvidence.message}]; release=[${proof.releaseEvidence.message}]"
                }
            )
        }
        val telemetry = buildTelemetryPacket(turnId, proof, success = true, error = null)
        val telemetryResult = xanderVoiceClient.postVoiceTurnMetrics(telemetry)
        _uiState.update {
            it.copy(
                telemetryStatus = if (telemetryResult.ok) {
                    "Sent turnId=$turnId record=${telemetryResult.recordId ?: "unknown"}"
                } else {
                    "FAILED turnId=$turnId: ${telemetryResult.error ?: "unknown"}"
                }
            )
        }
    }

    private fun buildTelemetryPacket(
        turnId: String,
        proof: VoiceTurnUiResult,
        success: Boolean,
        error: String?
    ): VoiceTurnTelemetryPacket {
        val result = proof.result
        return VoiceTurnTelemetryPacket(
            turnId = turnId,
            stage = if (success) "complete" else "failure",
            success = success,
            playbackMode = _uiState.value.playbackMode.name,
            playbackKind = proof.playbackKind,
            routeEvidence = proof.routeEvidence,
            releaseEvidence = proof.releaseEvidence,
            capturedBytes = proof.capturedBytes,
            expectedCaptureBytes = proof.expectedCaptureBytes,
            capturePeakAmplitude = proof.capturePeakAmplitude,
            captureUsable = proof.captureUsable,
            captureExpectedMs = proof.captureExpectedMs,
            captureReadMs = proof.captureReadMs,
            captureStopReason = proof.captureStopReason,
            routeSelectMs = proof.routeSelectMs,
            routeReleaseMs = proof.routeReleaseMs,
            turnTotalMs = proof.turnTotalMs,
            backendRoundTripMs = proof.backendRoundTripMs,
            httpStatusCode = result.httpStatusCode,
            requestBytes = result.requestBytes,
            responseBytes = result.responseBytes,
            requestBuildMs = result.requestBuildMs,
            uploadMs = result.uploadMs,
            responseCodeWaitMs = result.responseCodeWaitMs,
            responseReadMs = result.responseReadMs,
            responseParseMs = result.responseParseMs,
            backendTotalMs = result.backendTotalMs,
            decodePcmMs = result.decodePcmMs,
            audioStatsMs = result.audioStatsMs,
            transcriptTotalMs = result.transcriptTotalMs,
            sttLatencyMs = result.sttLatencyMs,
            xanderSessionMs = result.xanderSessionMs,
            xanderFastMs = result.xanderFastMs,
            xanderFastStatus = result.xanderFastStatus,
            xanderFastTimedOut = result.xanderFastTimedOut,
            xanderFallbackSessionStatus = result.xanderFallbackSessionStatus,
            xanderFallbackSkipped = result.xanderFallbackSkipped,
            responseBuildMs = result.responseBuildMs,
            provider = result.provider,
            transcriptSource = result.transcriptSource,
            sttStatus = result.sttStatus,
            pass1Status = result.pass1Status,
            pass1Ready = result.pass1Ready,
            transcriptLength = result.transcript.length,
            assistantTextLength = result.assistantText.length,
            ttsBytes = result.ttsPcm16Mono16k?.size ?: 0,
            playbackTotalMs = proof.playbackTotalMs,
            error = error
        )
    }

    private data class VoiceTurnUiResult(
        val routeEvidence: com.otoxan.mobile.voice.RouteEvidence,
        val releaseEvidence: com.otoxan.mobile.voice.RouteEvidence,
        val capturedBytes: Int,
        val expectedCaptureBytes: Int,
        val capturePeakAmplitude: Int,
        val captureUsable: Boolean,
        val result: com.otoxan.mobile.voice.VoiceTurnResult,
        val turnTotalMs: Long,
        val routeSelectMs: Long,
        val captureReadMs: Long,
        val captureExpectedMs: Long,
        val captureStopReason: String,
        val backendRoundTripMs: Long,
        val routeReleaseMs: Long,
        val playbackTotalMs: Long,
        val playbackKind: String
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

    private fun elapsedMs(startedNanos: Long): Long = ((System.nanoTime() - startedNanos) / 1_000_000L).coerceAtLeast(0L)

    private fun releaseCommunicationRoute(reason: String): RouteEvidence {
        val first = audioRouter.clearRoute()
        Thread.sleep(350L)
        val second = audioRouter.clearRoute()
        return RouteEvidence.default(
            "$reason; first=[${first.message}]; second=[${second.message}]"
        )
    }

    override fun onCleared() {
        conversationJob?.cancel()
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
