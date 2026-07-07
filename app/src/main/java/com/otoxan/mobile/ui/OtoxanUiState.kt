package com.otoxan.mobile.ui

import com.otoxan.mobile.voice.VOICE_TURN_ACK_GAP_TARGET_MS
import com.otoxan.mobile.voice.VOICE_TURN_BACKEND_TARGET_MS
import com.otoxan.mobile.voice.VOICE_TURN_STT_TARGET_MS
import com.otoxan.mobile.voice.VOICE_TURN_TOTAL_TARGET_MS
import com.otoxan.mobile.voice.VOICE_TURN_TTFA_TARGET_MS

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
    val mobileFastFailureReason: String? = null,
    val mobileFastSessionFallbackEnabled: Boolean? = null,
    val mobileFastSessionFallbackHardTimeoutSeconds: Double? = null,
    val xanderFallbackSessionStatus: Int? = null,
    val xanderFallbackSkipped: Int? = null,
    val xanderFallbackTimedOut: Int? = null,
    val xanderFallbackFailureReason: String? = null,
    val playbackMs: Long?,
    val capturedBytes: Int,
    val peakAmplitude: Int,
    val transcriptSource: String?,
    val assistantTextLength: Int,
    val error: String? = null
)

enum class TimingAcceptanceState(val label: String) {
    Pass("pass"),
    Miss("miss"),
    Unknown("unknown")
}

data class TimingAcceptanceMetric(
    val label: String,
    val valueMs: Long?,
    val targetMs: Long,
    val state: TimingAcceptanceState
) {
    val valueText: String = valueMs.toLatencyMsText()
    val targetText: String = "target ${targetMs}ms"
    val summaryText: String = "${state.label}: $valueText · $targetText"
}

data class TelemetryAcceptanceSummary(
    val overallState: TimingAcceptanceState,
    val passCount: Int,
    val missCount: Int,
    val unknownCount: Int,
    val metrics: List<TimingAcceptanceMetric>
) {
    val summaryText: String = "${overallState.label} · pass=$passCount miss=$missCount unknown=$unknownCount"
}

data class LatencyCardMetric(
    val label: String,
    val value: String,
    val detail: String
)

data class CaptureSplitMetric(
    val label: String,
    val value: String,
    val detail: String
)

data class DiagnosticTimelineItem(
    val label: String,
    val atMs: Long?,
    val durationMs: Long?,
    val detail: String
) {
    val timingText: String = when {
        atMs != null && durationMs != null -> "t+${atMs}ms · ${durationMs}ms"
        atMs != null -> "t+${atMs}ms"
        durationMs != null -> "${durationMs}ms"
        else -> "unknown"
    }
}

data class DiagnosticTimelineSummary(
    val title: String,
    val evidenceClass: String,
    val items: List<DiagnosticTimelineItem>
) {
    val summaryText: String = "$title · ${items.count { it.atMs != null || it.durationMs != null }}/${items.size} timed · $evidenceClass"
}

enum class EvidenceClassState(val label: String) {
    Proven("proven"),
    NeedsEvidence("needs evidence"),
    DiagnosticOnly("diagnostic only"),
    NotRuntimeEvidence("not runtime evidence")
}

data class PhoneTelemetryEvidenceClass(
    val label: String,
    val state: EvidenceClassState,
    val detail: String
)

data class StreamTelemetrySummary(
    val transportKind: String,
    val eventCount: Int?,
    val eventTypes: List<String>,
    val streamStarted: Boolean?,
    val streamCompleted: Boolean?,
    val protocolName: String?,
    val protocolVersion: Int?,
    val partialTranscriptCount: Int?,
    val latestPartialTranscriptLength: Int?,
    val finalTranscriptObserved: Boolean?,
    val finalTranscriptLength: Int?
) {
    val statusText: String = "transport=$transportKind; events=${eventCount?.toString() ?: "unknown"}; started=${streamStarted?.toString() ?: "unknown"}; completed=${streamCompleted?.toString() ?: "unknown"}"
    val protocolText: String = "protocol=${protocolName ?: "unknown"} v${protocolVersion?.toString() ?: "unknown"}"
    val eventsText: String = eventTypes.takeIf { it.isNotEmpty() }?.joinToString(" → ") ?: "no stream events observed"
    val transcriptStateText: String = "partial=${partialTranscriptCount?.toString() ?: "unknown"} latestChars=${latestPartialTranscriptLength?.toString() ?: "unknown"}; final=${finalTranscriptObserved?.toString() ?: "unknown"} finalChars=${finalTranscriptLength?.toString() ?: "unknown"}"
}

data class SpeculativeBargeInSummary(
    val assistantPrepObserved: Boolean?,
    val bargeInDetected: Boolean?,
    val responseCancelled: Boolean?,
    val cancelReason: String?
) {
    val stateText: String = "prep=${assistantPrepObserved?.toString() ?: "unknown"}; bargeIn=${bargeInDetected?.toString() ?: "unknown"}; cancelled=${responseCancelled?.toString() ?: "unknown"}; reason=${cancelReason ?: "unknown"}"
    val evidenceClass: String = "candidate readiness/control readback only — not hardware proof"
    val policyText: String = "Speculative prep is a prewarm marker only; barge-in may cancel playback/prep, but it never starts a new assistant turn without explicit commit."
}

data class SttProviderFallbackSummary(
    val selectedProvider: String,
    val selectedStatus: String,
    val selectedLatencyText: String,
    val primaryText: String,
    val fallbackText: String,
    val budgetText: String,
    val evidenceClass: String = "backend STT diagnostic only — not hardware proof"
) {
    val statusText: String = "selected=$selectedProvider/$selectedStatus in $selectedLatencyText; primary=$primaryText; fallback=$fallbackText; budget=$budgetText"
}

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
    val turnOutcomeStatus: String? = null,
    val assistantResponseSource: String? = null,
    val turnOutcomeDegraded: Boolean? = null,
    val turnOutcomeEvidenceClass: String? = null,
    val transcriptSource: String? = null,
    val sttProvider: String? = null,
    val sttStatus: String? = null,
    val sttLatencyMs: Int? = null,
    val primarySttStatus: String? = null,
    val primarySttMs: Int? = null,
    val primarySttProvider: String? = null,
    val fallbackSttStatus: String? = null,
    val fallbackSttMs: Int? = null,
    val fallbackSttProvider: String? = null,
    val sttBudgetRemainingMs: Int? = null,
    val pass1Status: String? = null,
    val pass1Ready: Boolean? = null,
    val mobileFastFailureReason: String? = null,
    val mobileFastSessionFallbackEnabled: Boolean? = null,
    val mobileFastSessionFallbackHardTimeoutSeconds: Double? = null,
    val xanderFallbackSessionStatus: Int? = null,
    val xanderFallbackSkipped: Int? = null,
    val xanderFallbackTimedOut: Int? = null,
    val xanderFallbackFailureReason: String? = null,
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
    val routeSelectStartMs: Long? = null,
    val routeSelectEndMs: Long? = null,
    val captureStartMs: Long? = null,
    val captureEndMs: Long? = null,
    val speechFirstDetectedMs: Long? = null,
    val speechLastDetectedMs: Long? = null,
    val captureReadMs: Long? = null,
    val captureExpectedMs: Long? = null,
    val backendRoundTripMs: Long? = null,
    val routeReleaseMs: Long? = null,
    val routeReleaseStartMs: Long? = null,
    val routeReleaseEndMs: Long? = null,
    val playbackTotalMs: Long? = null,
    val playbackKind: String = "unknown",
    val endpointDispatchMs: Long? = null,
    val endpointResponseReadyMs: Long? = null,
    val localAckKind: String = "none",
    val localAckStartMs: Long? = null,
    val localAckTotalMs: Long? = null,
    val assistantPlaybackStartMs: Long? = null,
    val backendResponseReadyMs: Long? = null,
    val ttfaMs: Long? = null,
    val ttfaBackendWaitAfterReleaseMs: Long? = null,
    val postCaptureAckDelayMs: Long? = null,
    val httpStatusCode: Int? = null,
    val requestBytes: Int? = null,
    val responseBytes: Int? = null,
    val requestBuildMs: Long? = null,
    val uploadMs: Long? = null,
    val responseCodeWaitMs: Long? = null,
    val responseReadMs: Long? = null,
    val responseParseMs: Long? = null,
    val transportKind: String = "http_voice_turn",
    val streamEventCount: Int? = null,
    val streamEventTypes: List<String> = emptyList(),
    val streamStarted: Boolean? = null,
    val streamCompleted: Boolean? = null,
    val streamProtocolName: String? = null,
    val streamProtocolVersion: Int? = null,
    val streamPartialTranscriptCount: Int? = null,
    val streamLatestPartialTranscriptLength: Int? = null,
    val streamFinalTranscriptObserved: Boolean? = null,
    val streamFinalTranscriptLength: Int? = null,
    val streamAssistantPrepStarted: Boolean? = null,
    val streamBargeInDetected: Boolean? = null,
    val streamResponseCancelled: Boolean? = null,
    val streamCancelReason: String? = null,
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
            detail = "target ${VOICE_TURN_ACK_GAP_TARGET_MS}ms · $localAckKind"
        ),
        LatencyCardMetric(
            label = "TTFA",
            value = ttfaMs.toLatencyMsText(),
            detail = "target ${VOICE_TURN_TTFA_TARGET_MS}ms · $firstAudioLatencyDetail"
        )
    )

val OtoxanUiState.captureSplitMetrics: List<CaptureSplitMetric>
    get() = listOf(
        CaptureSplitMetric(
            label = "Route select",
            value = routeSelectMs.toLatencyMsText(),
            detail = "communication route setup before capture"
        ),
        CaptureSplitMetric(
            label = "Capture read",
            value = captureReadMs.toLatencyMsText(),
            detail = "actual mic read · target ${captureExpectedMs.toLatencyMsText()}"
        ),
        CaptureSplitMetric(
            label = "Post-capture ack",
            value = postCaptureAckDelayMs.toLatencyMsText(),
            detail = "capture end to local feedback · target ${VOICE_TURN_ACK_GAP_TARGET_MS}ms"
        ),
        CaptureSplitMetric(
            label = "Endpoint wait",
            value = endpointWaitMs.toLatencyMsText(),
            detail = "dispatch ${endpointDispatchMs.toLatencyMsText()} → response ${endpointResponseReadyMs.toLatencyMsText()}"
        )
    )

val OtoxanUiState.routeDiagnosticTimeline: DiagnosticTimelineSummary
    get() = DiagnosticTimelineSummary(
        title = "Route/capture timeline",
        evidenceClass = "phone-side route diagnostic — hardware proof only when paired with real Ray-Ban route and real speech",
        items = listOf(
            DiagnosticTimelineItem(
                label = "Wearable route select",
                atMs = routeSelectStartMs,
                durationMs = routeSelectMs,
                detail = "${selectedInputName.ifBlank { "unknown route" }} (${selectedInputType.ifBlank { "unknown" }})"
            ),
            DiagnosticTimelineItem(
                label = "Capture window",
                atMs = captureStartMs,
                durationMs = captureReadMs,
                detail = "${capturedBytes}/${expectedCaptureBytes} bytes; peak=$capturePeakAmplitude; usable=${captureUsable?.toString() ?: "unknown"}"
            ),
            DiagnosticTimelineItem(
                label = "Speech first/last detected",
                atMs = speechFirstDetectedMs,
                durationMs = speechWindowMs,
                detail = "first=${speechFirstDetectedMs.toLatencyMsText()} last=${speechLastDetectedMs.toLatencyMsText()}"
            ),
            DiagnosticTimelineItem(
                label = "Route release",
                atMs = routeReleaseStartMs,
                durationMs = routeReleaseMs,
                detail = "release end=${routeReleaseEndMs.toLatencyMsText()}; policy=$routeReleasePolicy"
            )
        )
    )

val OtoxanUiState.sttDiagnosticTimeline: DiagnosticTimelineSummary
    get() = DiagnosticTimelineSummary(
        title = "STT/backend timeline",
        evidenceClass = "backend STT diagnostic only — not hardware proof",
        items = listOf(
            DiagnosticTimelineItem(
                label = "Endpoint dispatch",
                atMs = endpointDispatchMs,
                durationMs = requestBuildMs,
                detail = "transport=$transportKind; request=${requestBytes?.toString() ?: "unknown"} bytes"
            ),
            DiagnosticTimelineItem(
                label = "Upload + HTTP wait/read",
                atMs = endpointDispatchMs,
                durationMs = httpExchangeMs,
                detail = "upload=${uploadMs.toLatencyMsText()}; wait=${responseCodeWaitMs.toLatencyMsText()}; read=${responseReadMs.toLatencyMsText()}; http=${httpStatusCode?.toString() ?: "unknown"}"
            ),
            DiagnosticTimelineItem(
                label = "Selected STT",
                atMs = null,
                durationMs = sttLatencyMs?.toLong(),
                detail = "${sttProvider ?: "unknown"}/${sttStatus ?: "unknown"}; source=${transcriptSource ?: "unknown"}; budget=${sttBudgetRemainingMs?.let { "${it}ms" } ?: "unknown"}"
            ),
            DiagnosticTimelineItem(
                label = "Primary/fallback STT",
                atMs = null,
                durationMs = primarySttMs?.toLong() ?: fallbackSttMs?.toLong(),
                detail = "primary=${sttAttemptText(primarySttProvider, primarySttStatus, primarySttMs)}; fallback=${sttAttemptText(fallbackSttProvider, fallbackSttStatus, fallbackSttMs)}"
            ),
            DiagnosticTimelineItem(
                label = "Backend response ready",
                atMs = backendResponseReadyMs,
                durationMs = backendRoundTripMs,
                detail = "server=${backendTotalMs.toNullableMsText()}; transcript=${transcriptTotalMs.toNullableMsText()}; xander=${xanderSessionMs.toNullableMsText()}; responseBuild=${responseBuildMs.toNullableMsText()}"
            )
        )
    )

val OtoxanUiState.playbackDiagnosticTimeline: DiagnosticTimelineSummary
    get() = DiagnosticTimelineSummary(
        title = "Playback/TTFA timeline",
        evidenceClass = "phone-side playback diagnostic — proves audible path only with operator-observed device playback",
        items = listOf(
            DiagnosticTimelineItem(
                label = "Post-capture local ack",
                atMs = localAckStartMs,
                durationMs = localAckTotalMs,
                detail = "$localAckKind; postCaptureDelay=${postCaptureAckDelayMs.toLatencyMsText()}"
            ),
            DiagnosticTimelineItem(
                label = "Backend wait after release",
                atMs = routeReleaseEndMs,
                durationMs = ttfaBackendWaitAfterReleaseMs,
                detail = "responseReady=${backendResponseReadyMs.toLatencyMsText()}"
            ),
            DiagnosticTimelineItem(
                label = "Assistant playback start",
                atMs = assistantPlaybackStartMs,
                durationMs = null,
                detail = "kind=$playbackKind; TTFA=${ttfaMs.toLatencyMsText()}"
            ),
            DiagnosticTimelineItem(
                label = "Playback total",
                atMs = assistantPlaybackStartMs,
                durationMs = playbackTotalMs,
                detail = "mode=${playbackMode.label}; tts=$ttsBytes bytes"
            )
        )
    )

val OtoxanUiState.timingAcceptanceSummary: TelemetryAcceptanceSummary
    get() = timingAcceptanceSummaryOf(
        totalMs = turnTotalMs,
        ttfaMs = ttfaMs,
        postCaptureAckDelayMs = postCaptureAckDelayMs,
        backendMs = backendRoundTripMs,
        sttMs = sttLatencyMs,
        xanderMs = xanderSessionMs,
        playbackMs = playbackTotalMs
    )

val TelemetryPassSummary.timingAcceptanceSummary: TelemetryAcceptanceSummary
    get() = timingAcceptanceSummaryOf(
        totalMs = totalMs,
        ttfaMs = ttfaMs,
        postCaptureAckDelayMs = postCaptureAckDelayMs,
        backendMs = backendMs,
        sttMs = sttMs,
        xanderMs = xanderMs,
        playbackMs = playbackMs
    )

val OtoxanUiState.endpointEvidenceText: String
    get() = "endpoint=${voiceEndpoint.ifBlank { "unknown" }}; transport=$transportKind; http=${httpStatusCode?.toString() ?: "unknown"}; dispatch=${endpointDispatchMs.toLatencyMsText()}; responseReady=${endpointResponseReadyMs.toLatencyMsText()}; clientRoundTrip=${backendRoundTripMs.toLatencyMsText()}; request=${requestBytes?.toString() ?: "unknown"} bytes; response=${responseBytes?.toString() ?: "unknown"} bytes"

val OtoxanUiState.streamTelemetrySummary: StreamTelemetrySummary
    get() = StreamTelemetrySummary(
        transportKind = transportKind,
        eventCount = streamEventCount,
        eventTypes = streamEventTypes,
        streamStarted = streamStarted,
        streamCompleted = streamCompleted,
        protocolName = streamProtocolName,
        protocolVersion = streamProtocolVersion,
        partialTranscriptCount = streamPartialTranscriptCount,
        latestPartialTranscriptLength = streamLatestPartialTranscriptLength,
        finalTranscriptObserved = streamFinalTranscriptObserved,
        finalTranscriptLength = streamFinalTranscriptLength
    )

val OtoxanUiState.speculativeBargeInSummary: SpeculativeBargeInSummary
    get() = SpeculativeBargeInSummary(
        assistantPrepObserved = streamAssistantPrepStarted,
        bargeInDetected = streamBargeInDetected,
        responseCancelled = streamResponseCancelled,
        cancelReason = streamCancelReason
    )

val OtoxanUiState.sttProviderFallbackSummary: SttProviderFallbackSummary
    get() = SttProviderFallbackSummary(
        selectedProvider = sttProvider ?: "unknown",
        selectedStatus = sttStatus ?: "unknown",
        selectedLatencyText = sttLatencyMs.toNullableMsText(),
        primaryText = sttAttemptText(primarySttProvider, primarySttStatus, primarySttMs),
        fallbackText = sttAttemptText(fallbackSttProvider, fallbackSttStatus, fallbackSttMs),
        budgetText = sttBudgetRemainingMs?.let { "${it}ms remaining" } ?: "unknown"
    )

val OtoxanUiState.recoveryEvidenceText: String
    get() = listOfNotNull(
        mobileFastFailureReason?.let { "fastFailure=$it" },
        mobileFastSessionFallbackEnabled?.let { "fallbackEnabled=$it" },
        mobileFastSessionFallbackHardTimeoutSeconds?.let { "fallbackDeadline=${it}s" },
        xanderFallbackSessionStatus?.let { "fallbackStatus=$it" },
        xanderFallbackSkipped?.let { "fallbackSkipped=$it" },
        xanderFallbackTimedOut?.let { "fallbackTimedOut=$it" },
        xanderFallbackFailureReason?.let { "fallbackFailure=$it" }
    ).joinToString("; ").ifBlank { "none" }

val OtoxanUiState.phoneTelemetryEvidenceClasses: List<PhoneTelemetryEvidenceClass>
    get() = listOf(
        hardwareGateEvidenceClass,
        captureReliabilityEvidenceClass,
        backendReliabilityEvidenceClass,
        streamTelemetryEvidenceClass,
        speculativeBargeInEvidenceClass,
        latencyScorecardEvidenceClass,
        PhoneTelemetryEvidenceClass(
            label = "Source/build proof",
            state = EvidenceClassState.NotRuntimeEvidence,
            detail = "Gradle/tests prove package health only; they do not prove Ray-Ban route, speech, or latency on hardware."
        )
    )

private val OtoxanUiState.hardwareGateEvidenceClass: PhoneTelemetryEvidenceClass
    get() {
        val proven = pass1Ready == true && pass1Status == "real-speech-proven" && transcriptSource != null && sttStatus == "success"
        return PhoneTelemetryEvidenceClass(
            label = "Hardware gate",
            state = if (proven) EvidenceClassState.Proven else EvidenceClassState.NeedsEvidence,
            detail = "Requires real phone + Ray-Ban route, pass1Ready=true/pass1Status=real-speech-proven, successful STT, and semantic response; current pass1=${pass1Status ?: "unknown"}, source=${transcriptSource ?: "unknown"}, stt=${sttStatus ?: "unknown"}."
        )
    }

private val OtoxanUiState.captureReliabilityEvidenceClass: PhoneTelemetryEvidenceClass
    get() {
        val proven = captureUsable == true && capturedBytes > 0 && capturePeakAmplitude > 0
        return PhoneTelemetryEvidenceClass(
            label = "Capture reliability",
            state = if (proven) EvidenceClassState.Proven else EvidenceClassState.NeedsEvidence,
            detail = "Guardrail evidence only: captured=$capturedBytes/$expectedCaptureBytes bytes, peak=$capturePeakAmplitude, usable=${captureUsable?.toString() ?: "unknown"}. It detects bad captures but does not alone prove real speech."
        )
    }

private val OtoxanUiState.backendReliabilityEvidenceClass: PhoneTelemetryEvidenceClass
    get() {
        val status = httpStatusCode
        val httpOk = status != null && status in 200..299
        val backendOk = httpOk && provider != null && provider != "stub" && lastError == null
        return PhoneTelemetryEvidenceClass(
            label = "Backend turn reliability",
            state = if (backendOk) EvidenceClassState.Proven else EvidenceClassState.NeedsEvidence,
            detail = "Needs non-stub backend response with successful HTTP and no turn error; provider=${provider ?: "unknown"}, http=${httpStatusCode?.toString() ?: "unknown"}, backendBytes=${backendBytesReceived?.toString() ?: "unknown"}, error=${lastError ?: "none"}."
        )
    }

private val OtoxanUiState.streamTelemetryEvidenceClass: PhoneTelemetryEvidenceClass
    get() {
        val streamObserved = transportKind != "http_voice_turn" || streamEventCount != null || streamEventTypes.isNotEmpty()
        return PhoneTelemetryEvidenceClass(
            label = "Stream transport telemetry",
            state = if (streamObserved) EvidenceClassState.DiagnosticOnly else EvidenceClassState.NotRuntimeEvidence,
            detail = "Transport readback only: ${streamTelemetrySummary.statusText}; ${streamTelemetrySummary.protocolText}; events=${streamTelemetrySummary.eventsText}. This can prove stream plumbing, not Ray-Ban hardware or real-speech success."
        )
    }

private val OtoxanUiState.speculativeBargeInEvidenceClass: PhoneTelemetryEvidenceClass
    get() = PhoneTelemetryEvidenceClass(
        label = "Speculative prep/barge-in control",
        state = if (streamAssistantPrepStarted == true || streamBargeInDetected == true || streamResponseCancelled == true) {
            EvidenceClassState.DiagnosticOnly
        } else {
            EvidenceClassState.NotRuntimeEvidence
        },
        detail = "${speculativeBargeInSummary.stateText}. ${speculativeBargeInSummary.policyText} Evidence class: ${speculativeBargeInSummary.evidenceClass}."
    )

private val OtoxanUiState.latencyScorecardEvidenceClass: PhoneTelemetryEvidenceClass
    get() = PhoneTelemetryEvidenceClass(
        label = "Latency scorecard",
        state = when (timingAcceptanceSummary.overallState) {
            TimingAcceptanceState.Pass -> EvidenceClassState.Proven
            TimingAcceptanceState.Miss -> EvidenceClassState.NeedsEvidence
            TimingAcceptanceState.Unknown -> EvidenceClassState.DiagnosticOnly
        },
        detail = "Timing acceptance=${timingAcceptanceSummary.summaryText}. These targets are tuning baselines/readback evidence and never override hardware-gate proof."
    )

private val OtoxanUiState.endpointWaitMs: Long?
    get() = if (endpointDispatchMs != null && endpointResponseReadyMs != null) {
        (endpointResponseReadyMs - endpointDispatchMs).coerceAtLeast(0L)
    } else {
        backendRoundTripMs
    }

private val OtoxanUiState.speechWindowMs: Long?
    get() = if (speechFirstDetectedMs != null && speechLastDetectedMs != null) {
        (speechLastDetectedMs - speechFirstDetectedMs).coerceAtLeast(0L)
    } else {
        null
    }

private val OtoxanUiState.httpExchangeMs: Long?
    get() {
        val values = listOfNotNull(uploadMs, responseCodeWaitMs, responseReadMs)
        return values.takeIf { it.isNotEmpty() }?.sum()
    }

private val OtoxanUiState.firstAudioLatencyDetail: String
    get() = when {
        localAckStartMs != null -> "local ack at ${localAckStartMs.toLatencyMsText()}"
        assistantPlaybackStartMs != null -> "assistant at ${assistantPlaybackStartMs.toLatencyMsText()}"
        else -> "first audio unknown"
    }

private fun Long?.toLatencyMsText(): String = this?.let { "${it}ms" } ?: "unknown"

private fun Int?.toNullableMsText(): String = this?.let { "${it}ms" } ?: "unknown"

private fun sttAttemptText(provider: String?, status: String?, ms: Int?): String {
    val providerText = provider ?: "unknown"
    val statusText = status ?: "unknown"
    val latencyText = ms.toNullableMsText()
    return "$providerText/$statusText in $latencyText"
}

private fun timingAcceptanceSummaryOf(
    totalMs: Long?,
    ttfaMs: Long?,
    postCaptureAckDelayMs: Long?,
    backendMs: Long?,
    sttMs: Int?,
    xanderMs: Int?,
    playbackMs: Long?
): TelemetryAcceptanceSummary {
    val metrics = listOf(
        TimingAcceptanceMetric("TTFA", ttfaMs, VOICE_TURN_TTFA_TARGET_MS, ttfaMs.acceptanceState(VOICE_TURN_TTFA_TARGET_MS)),
        TimingAcceptanceMetric("Ack delay", postCaptureAckDelayMs, VOICE_TURN_ACK_GAP_TARGET_MS, postCaptureAckDelayMs.acceptanceState(VOICE_TURN_ACK_GAP_TARGET_MS)),
        TimingAcceptanceMetric("Total", totalMs, VOICE_TURN_TOTAL_TARGET_MS, totalMs.acceptanceState(VOICE_TURN_TOTAL_TARGET_MS)),
        TimingAcceptanceMetric("Backend", backendMs, VOICE_TURN_BACKEND_TARGET_MS, backendMs.acceptanceState(VOICE_TURN_BACKEND_TARGET_MS)),
        TimingAcceptanceMetric("STT", sttMs?.toLong(), VOICE_TURN_STT_TARGET_MS, sttMs?.toLong().acceptanceState(VOICE_TURN_STT_TARGET_MS)),
        TimingAcceptanceMetric("Xander", xanderMs?.toLong(), XANDER_ACCEPTANCE_TARGET_MS, xanderMs?.toLong().acceptanceState(XANDER_ACCEPTANCE_TARGET_MS)),
        TimingAcceptanceMetric("Playback", playbackMs, PLAYBACK_ACCEPTANCE_TARGET_MS, playbackMs.acceptanceState(PLAYBACK_ACCEPTANCE_TARGET_MS))
    )
    val passCount = metrics.count { it.state == TimingAcceptanceState.Pass }
    val missCount = metrics.count { it.state == TimingAcceptanceState.Miss }
    val unknownCount = metrics.count { it.state == TimingAcceptanceState.Unknown }
    val overallState = when {
        missCount > 0 -> TimingAcceptanceState.Miss
        unknownCount > 0 -> TimingAcceptanceState.Unknown
        else -> TimingAcceptanceState.Pass
    }
    return TelemetryAcceptanceSummary(
        overallState = overallState,
        passCount = passCount,
        missCount = missCount,
        unknownCount = unknownCount,
        metrics = metrics
    )
}

private fun Long?.acceptanceState(targetMs: Long): TimingAcceptanceState = when {
    this == null -> TimingAcceptanceState.Unknown
    this <= targetMs -> TimingAcceptanceState.Pass
    else -> TimingAcceptanceState.Miss
}

private const val XANDER_ACCEPTANCE_TARGET_MS = 2_500L
private const val PLAYBACK_ACCEPTANCE_TARGET_MS = 1_500L

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
