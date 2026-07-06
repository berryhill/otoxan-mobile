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
import com.otoxan.mobile.voice.conversationVoiceCaptureConfig
import com.otoxan.mobile.voice.createXanderVoiceClient
import com.otoxan.mobile.voice.expectedPcmBytes
import com.otoxan.mobile.voice.shouldSubmitVoiceTurn
import com.otoxan.mobile.voice.peakPcm16Amplitude
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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

private class NoSpeechDetectedForTurn(message: String) : RuntimeException(message)

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
                        delay(900L)
                    }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        if (error is NoSpeechDetectedForTurn) {
                            _uiState.update {
                                it.copy(
                                    conversationActive = true,
                                    wearableRouteActive = false,
                                    liveVoicePeak = 0,
                                    liveVoiceLevel = 0f,
                                    liveSpeechDetected = false,
                                    sessionState = VoiceSessionState.ConversationActive,
                                    turnStage = "Still listening — no speech heard",
                                    telemetryStatus = "Idle silence; no backend turn sent",
                                    lastError = null
                                )
                            }
                            delay(700L)
                            return@onFailure
                        }
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
            releaseCommunicationRoute("Ended Xander conversation session")
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
            runCatching { performVoiceTurn(turnId, requireExistingRoute = false) }
                .onSuccess { proof -> applyVoiceTurnSuccess(turnId, proof, keepConversationActive = false) }
                .onFailure { error ->
                    val errorText = error.message ?: error::class.java.simpleName
                    val telemetryResult = postFailureTelemetry(turnId, errorText, if (error is NoSpeechDetectedForTurn) "no_speech" else "failure")
                    val telemetryText = if (telemetryResult.ok) {
                        "Sent failure turnId=$turnId record=${telemetryResult.recordId ?: "unknown"}"
                    } else {
                        "FAILED failure telemetry turnId=$turnId: ${telemetryResult.error ?: "unknown"}"
                    }
                    _uiState.update {
                        if (error is NoSpeechDetectedForTurn) {
                            it.copy(
                                sessionState = VoiceSessionState.Idle,
                                wearableRouteActive = false,
                                liveVoicePeak = 0,
                                liveVoiceLevel = 0f,
                                liveSpeechDetected = false,
                                turnStage = "No speech heard — tap single turn and speak after Listening appears",
                                telemetryStatus = telemetryText,
                                lastEvidence = "Single turn heard no usable speech; no backend turn sent",
                                lastError = null
                            )
                        } else {
                            it.copy(
                                sessionState = VoiceSessionState.Error,
                                wearableRouteActive = false,
                                liveVoicePeak = 0,
                                liveVoiceLevel = 0f,
                                liveSpeechDetected = false,
                                turnStage = "Voice turn failed before completion",
                                telemetryStatus = telemetryText,
                                lastEvidence = "Single turn failed before backend completion: $errorText",
                                lastError = errorText
                            )
                        }
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
            var routeEvidence = audioRouter.inspectAndSelectWearable()
            if (!routeEvidence.wearableActive) {
                val firstFailure = routeEvidence.message
                releaseCommunicationRoute("Reset communication route before retrying wearable selection")
                delay(250L)
                routeEvidence = audioRouter.inspectAndSelectWearable()
                if (!routeEvidence.wearableActive) {
                    error("Wearable route dropped before capture after retry: first=[$firstFailure]; retry=[${routeEvidence.message}]")
                }
            }
            val routeSelectMs = elapsedMs(routeStarted)
            _uiState.update { it.copy(turnStage = "Listening — speak now") }
            val conversationMode = _uiState.value.conversationActive
            val captureConfig = conversationVoiceCaptureConfig()
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
            val proof = processCapturedVoiceTurn(
                turnId = turnId,
                capture = capture,
                routeEvidence = routeEvidence,
                routeSelectMs = routeSelectMs,
                captureReadMs = elapsedMs(captureStarted),
                turnStarted = turnStarted,
                conversationMode = conversationMode,
                requireSpeechDetected = true
            )
            releaseEvidence = proof.releaseEvidence
            return proof
        } finally {
            if (releaseEvidence.message == "Communication route release not reached") {
                releaseCommunicationRoute("Released communication route after interrupted voice turn")
            }
        }
    }

    private suspend fun processCapturedVoiceTurn(
        turnId: String,
        capture: com.otoxan.mobile.voice.VoiceCaptureResult,
        routeEvidence: RouteEvidence,
        routeSelectMs: Long,
        captureReadMs: Long,
        turnStarted: Long,
        conversationMode: Boolean,
        requireSpeechDetected: Boolean
    ): VoiceTurnUiResult {
        val pcm = capture.pcm16Mono16k
        val expectedBytes = expectedPcmBytes(capture.maxCaptureMillis)
        val minimumUsableBytes = expectedPcmBytes(capture.minCaptureMillis)
        val peak = pcm.peakPcm16Amplitude()
        val usable = shouldSubmitVoiceTurn(capture, minimumUsableBytes, requireSpeechDetected = requireSpeechDetected)
        if (!usable) {
            if (requireSpeechDetected && !capture.speechDetected) {
                throw NoSpeechDetectedForTurn("No speech detected; skipping backend turn")
            }
            error("Microphone capture unusable: captured=${pcm.size} bytes, minimum=$minimumUsableBytes, peak=$peak, stop=${capture.stopReason}, speech=${capture.speechDetected}")
        }
        _uiState.update { it.copy(turnStage = "Thinking — ${pcm.size} bytes to Xander after ${capture.stopReason}") }
        return coroutineScope {
            val backendStarted = System.nanoTime()
            val backendDeferred = async(Dispatchers.IO) { xanderVoiceClient.sendVoiceTurn(pcm, routeEvidence) }
            val releaseStarted = System.nanoTime()
            val routeReleaseMs: Long
            val releaseEvidence: RouteEvidence
            _uiState.update { it.copy(turnStage = "Releasing call route before local acknowledgement") }
            releaseEvidence = releaseCommunicationRoute(
                if (conversationMode) {
                    "Released communication route before local acknowledgement; next listen will re-select Ray-Ban mic"
                } else {
                    "Released communication route before single-turn local acknowledgement"
                }
            )
            routeReleaseMs = elapsedMs(releaseStarted)
            val playbackMode = _uiState.value.playbackMode
            var localAckKind = "none"
            var localAckStartMs: Long? = null
            var localAckTotalMs: Long? = null
            if (playbackMode != PlaybackMode.SilentAfterCapture) {
                _uiState.update { it.copy(turnStage = "Acknowledged locally — waiting on Xander") }
                localAckStartMs = elapsedMs(turnStarted)
                val ackStarted = System.nanoTime()
                localAckKind = if (runCatching { speechPlayback.playAckEarcon() }.isSuccess) {
                    "earcon"
                } else {
                    "failed"
                }
                localAckTotalMs = elapsedMs(ackStarted)
            } else {
                localAckKind = "silent_mode"
            }

            _uiState.update { it.copy(turnStage = "Waiting on Xander response") }
            val result = backendDeferred.await()
            val backendRoundTripMs = elapsedMs(backendStarted)
            val backendResponseReadyMs = elapsedMs(turnStarted)
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
            val silentEmptyTranscript = conversationMode && result.sttStatus == "empty"
            var playbackKind = "none"
            val playbackStarted = System.nanoTime()
            var assistantPlaybackStartMs: Long? = null
            if (playbackMode == PlaybackMode.SilentAfterCapture) {
                playbackKind = "silent"
            } else if (silentEmptyTranscript) {
                playbackKind = "stt_empty_silent"
            } else if (backendTts != null && backendTts.isNotEmpty()) {
                playbackKind = "backend_pcm"
                assistantPlaybackStartMs = elapsedMs(turnStarted)
                runCatching { speechPlayback.playPcm16Mono16k(backendTts) }
                    .onFailure { playbackKind = "backend_pcm_failed" }
            } else if (result.assistantText.isNotBlank() && result.provider != "stub") {
                playbackKind = "android_tts"
                assistantPlaybackStartMs = elapsedMs(turnStarted)
                runCatching { speechPlayback.speakText(result.assistantText) }
                    .onFailure { playbackKind = "android_tts_failed" }
            }
            val playbackTotalMs = elapsedMs(playbackStarted)
            val ttfaMs = when {
                localAckKind == "earcon" -> localAckStartMs
                assistantPlaybackStartMs != null -> assistantPlaybackStartMs
                else -> null
            }
            VoiceTurnUiResult(
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
                playbackKind = playbackKind,
                localAckKind = localAckKind,
                localAckStartMs = localAckStartMs,
                localAckTotalMs = localAckTotalMs,
                assistantPlaybackStartMs = assistantPlaybackStartMs,
                backendResponseReadyMs = backendResponseReadyMs,
                ttfaMs = ttfaMs
            )
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
                sttProvider = result.sttProvider,
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
                localAckKind = proof.localAckKind,
                localAckStartMs = proof.localAckStartMs,
                localAckTotalMs = proof.localAckTotalMs,
                assistantPlaybackStartMs = proof.assistantPlaybackStartMs,
                backendResponseReadyMs = proof.backendResponseReadyMs,
                ttfaMs = proof.ttfaMs,
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
                    "Turn $nextTurnCount complete; route released for playback, next listen will re-select Ray-Ban mic"
                } else if (it.playbackMode == PlaybackMode.SilentAfterCapture) {
                    "Turn complete; playback skipped by operator mode"
                } else {
                    "Turn complete; communication route released before playback"
                },
                lastEvidence = if (result.provider == "stub") {
                    "Stub mode: captured=${proof.capturedBytes} bytes locally; no backend endpoint is configured."
                } else {
                    "Voice loop ok: pass1=${result.pass1Status ?: "unknown"}; total=${proof.turnTotalMs}ms; backend=${proof.backendRoundTripMs}ms/server=${result.backendTotalMs ?: "unknown"}ms; captured=${proof.capturedBytes}/${proof.expectedCaptureBytes} bytes actual=${proof.captureReadMs}ms stop=${proof.captureStopReason} peak=${proof.capturePeakAmplitude}; backendReceived=${result.bytesReceived ?: "unknown"}; provider=${result.provider ?: "unknown"}; transcriptSource=${result.transcriptSource ?: "unknown"}; stt=${result.sttProvider ?: "unknown"}/${result.sttStatus ?: "unknown"}; tts=$ttsBytes bytes; routeUsed=[${proof.routeEvidence.message}]; release=[${proof.releaseEvidence.message}]"
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

    private suspend fun postFailureTelemetry(turnId: String, error: String, stage: String): com.otoxan.mobile.voice.VoiceTurnTelemetryResult {
        val routeEvidence = audioRouter.currentEvidence()
        return xanderVoiceClient.postVoiceTurnMetrics(
            VoiceTurnTelemetryPacket(
                turnId = turnId,
                stage = stage,
                success = false,
                playbackMode = _uiState.value.playbackMode.name,
                playbackKind = "none",
                routeEvidence = routeEvidence,
                releaseEvidence = null,
                error = error
            )
        )
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
            sttProvider = result.sttProvider,
            sttStatus = result.sttStatus,
            pass1Status = result.pass1Status,
            pass1Ready = result.pass1Ready,
            transcriptLength = result.transcript.length,
            assistantTextLength = result.assistantText.length,
            ttsBytes = result.ttsPcm16Mono16k?.size ?: 0,
            playbackTotalMs = proof.playbackTotalMs,
            localAckKind = proof.localAckKind,
            localAckStartMs = proof.localAckStartMs,
            localAckTotalMs = proof.localAckTotalMs,
            assistantPlaybackStartMs = proof.assistantPlaybackStartMs,
            backendResponseReadyMs = proof.backendResponseReadyMs,
            ttfaMs = proof.ttfaMs,
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
        val playbackKind: String,
        val localAckKind: String,
        val localAckStartMs: Long?,
        val localAckTotalMs: Long?,
        val assistantPlaybackStartMs: Long?,
        val backendResponseReadyMs: Long?,
        val ttfaMs: Long?
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
                "OK provider=${result.provider ?: "unknown"}; backendReceived=${result.bytesReceived ?: "unknown"}; stt=${result.sttProvider ?: "unknown"}/${result.sttStatus ?: "unknown"}; pass1=${result.pass1Status ?: "unknown"}; endpoint=${BuildConfig.XANDER_VOICE_ENDPOINT.ifBlank { "stub" }}"
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
        speechPlayback.shutdown()
        audioRouter.clearRoute()
        super.onCleared()
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val appContext = context.applicationContext
                val speechPlayback = SpeechPlayback(appContext)
                speechPlayback.warmUpTextToSpeech()
                return OtoxanViewModel(
                    audioRouter = AudioRouter(appContext),
                    micCapture = MicCapture(),
                    speechPlayback = speechPlayback,
                    xanderVoiceClient = createXanderVoiceClient(BuildConfig.XANDER_VOICE_ENDPOINT)
                ) as T
            }
        }
    }
}
