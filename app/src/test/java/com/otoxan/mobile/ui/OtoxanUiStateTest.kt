package com.otoxan.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OtoxanUiStateTest {
    @Test
    fun voiceActivityActive_includesSingleTurnAndProofStates() {
        assertTrue(OtoxanUiState(conversationActive = true).voiceActivityActive)
        assertTrue(OtoxanUiState(sessionState = VoiceSessionState.RecordingTest).voiceActivityActive)
        assertTrue(OtoxanUiState(sessionState = VoiceSessionState.PlayingTest).voiceActivityActive)
    }

    @Test
    fun voiceActivityActive_excludesIdleAndRouteCheck() {
        assertFalse(OtoxanUiState(sessionState = VoiceSessionState.Idle).voiceActivityActive)
        assertFalse(OtoxanUiState(sessionState = VoiceSessionState.CheckingRoute).voiceActivityActive)
        assertFalse(OtoxanUiState(sessionState = VoiceSessionState.Ready).voiceActivityActive)
    }

    @Test
    fun perceivedLatencyDefaults_doNotClaimFirstAudio() {
        val state = OtoxanUiState()

        assertEquals("none", state.localAckKind)
        assertNull(state.localAckStartMs)
        assertNull(state.localAckTotalMs)
        assertNull(state.assistantPlaybackStartMs)
        assertNull(state.backendResponseReadyMs)
        assertNull(state.ttfaMs)
    }

    @Test
    fun latencyCardMetrics_showCaptureAckGapAndTtfaRowValues() {
        val state = OtoxanUiState(
            captureReadMs = 1180,
            captureExpectedMs = 1200,
            postCaptureAckDelayMs = 42,
            localAckKind = "earcon_while_route_active",
            localAckStartMs = 1222,
            ttfaMs = 1222
        )

        val metrics = state.latencyCardMetrics

        assertEquals(3, metrics.size)
        assertEquals(LatencyCardMetric("Capture", "1180ms", "target 1200ms"), metrics[0])
        assertEquals(LatencyCardMetric("Ack gap", "42ms", "target 250ms · earcon_while_route_active"), metrics[1])
        assertEquals(LatencyCardMetric("TTFA", "1222ms", "target 1500ms · local ack at 1222ms"), metrics[2])
    }

    @Test
    fun latencyCardMetrics_keepUnknownValuesExplicit() {
        val metrics = OtoxanUiState().latencyCardMetrics

        assertEquals(LatencyCardMetric("Capture", "unknown", "target unknown"), metrics[0])
        assertEquals(LatencyCardMetric("Ack gap", "unknown", "target 250ms · none"), metrics[1])
        assertEquals(LatencyCardMetric("TTFA", "unknown", "target 1500ms · first audio unknown"), metrics[2])
    }

    @Test
    fun captureSplitMetrics_separateCaptureFromEndpointWait() {
        val state = OtoxanUiState(
            routeSelectMs = 80,
            captureReadMs = 1230,
            captureExpectedMs = 1500,
            postCaptureAckDelayMs = 45,
            endpointDispatchMs = 1320,
            endpointResponseReadyMs = 2800,
            backendRoundTripMs = 1480
        )

        val metrics = state.captureSplitMetrics

        assertEquals(4, metrics.size)
        assertEquals(CaptureSplitMetric("Route select", "80ms", "communication route setup before capture"), metrics[0])
        assertEquals(CaptureSplitMetric("Capture read", "1230ms", "actual mic read · target 1500ms"), metrics[1])
        assertEquals(CaptureSplitMetric("Post-capture ack", "45ms", "capture end to local feedback · target 250ms"), metrics[2])
        assertEquals(CaptureSplitMetric("Endpoint wait", "1480ms", "dispatch 1320ms → response 2800ms"), metrics[3])
    }

    @Test
    fun endpointEvidenceText_showsEndpointHttpAndPayloadEvidence() {
        val evidence = OtoxanUiState(
            voiceEndpoint = "https://voice.example/voice-turn",
            httpStatusCode = 200,
            endpointDispatchMs = 1320,
            endpointResponseReadyMs = 2800,
            backendRoundTripMs = 1480,
            requestBytes = 44000,
            responseBytes = 900
        ).endpointEvidenceText

        assertEquals(
            "endpoint=https://voice.example/voice-turn; transport=http_voice_turn; http=200; dispatch=1320ms; responseReady=2800ms; clientRoundTrip=1480ms; request=44000 bytes; response=900 bytes",
            evidence
        )
    }

    @Test
    fun timingAcceptanceSummary_defaultsToUnknownWhenTimingIsMissing() {
        val summary = OtoxanUiState().timingAcceptanceSummary

        assertEquals(TimingAcceptanceState.Unknown, summary.overallState)
        assertEquals(0, summary.passCount)
        assertEquals(0, summary.missCount)
        assertEquals(7, summary.unknownCount)
        assertEquals("unknown · pass=0 miss=0 unknown=7", summary.summaryText)
        assertEquals(TimingAcceptanceMetric("TTFA", null, 1500, TimingAcceptanceState.Unknown), summary.metrics[0])
        assertEquals("unknown: unknown · target 1500ms", summary.metrics[0].summaryText)
    }

    @Test
    fun timingAcceptanceSummary_marksPassAndMissAgainstTargets() {
        val summary = OtoxanUiState(
            ttfaMs = 1200,
            postCaptureAckDelayMs = 300,
            turnTotalMs = 7900,
            backendRoundTripMs = 4500,
            sttLatencyMs = 1500,
            xanderSessionMs = 2600,
            playbackTotalMs = 1499
        ).timingAcceptanceSummary

        assertEquals(TimingAcceptanceState.Miss, summary.overallState)
        assertEquals(4, summary.passCount)
        assertEquals(3, summary.missCount)
        assertEquals(0, summary.unknownCount)
        assertEquals("TTFA=pass · Ack delay=miss · Total=pass · Backend=miss · STT=pass · Xander=miss · Playback=pass", summary.metrics.joinToString(" · ") { "${it.label}=${it.state.label}" })
    }

    @Test
    fun telemetryPassSummary_exposesSameTimingAcceptanceModel() {
        val pass = TelemetryPassSummary(
            turnId = "turn-1",
            success = true,
            pass1Status = "real-speech-proven",
            routeName = "Ray-Ban Meta",
            totalMs = 7000,
            ttfaMs = 1400,
            postCaptureAckDelayMs = 100,
            backendMs = 3000,
            sttMs = null,
            xanderMs = 2400,
            playbackMs = 800,
            capturedBytes = 32000,
            peakAmplitude = 9000,
            transcriptSource = "hermes-stt",
            assistantTextLength = 42
        )

        val summary = pass.timingAcceptanceSummary

        assertEquals(TimingAcceptanceState.Unknown, summary.overallState)
        assertEquals(6, summary.passCount)
        assertEquals(0, summary.missCount)
        assertEquals(1, summary.unknownCount)
    }

    @Test
    fun phoneTelemetryEvidenceClasses_keepReliabilityLatencyAndBuildEvidenceSeparate() {
        val classes = OtoxanUiState().phoneTelemetryEvidenceClasses

        assertEquals(6, classes.size)
        assertEquals("Hardware gate", classes[0].label)
        assertEquals(EvidenceClassState.NeedsEvidence, classes[0].state)
        assertEquals("Capture reliability", classes[1].label)
        assertEquals("Backend turn reliability", classes[2].label)
        assertEquals("Stream transport telemetry", classes[3].label)
        assertEquals(EvidenceClassState.NotRuntimeEvidence, classes[3].state)
        assertEquals("Latency scorecard", classes[4].label)
        assertEquals(EvidenceClassState.DiagnosticOnly, classes[4].state)
        assertEquals("Source/build proof", classes[5].label)
        assertEquals(EvidenceClassState.NotRuntimeEvidence, classes[5].state)
    }

    @Test
    fun streamTelemetrySummary_surfacesStreamEventsAsDiagnosticOnly() {
        val state = OtoxanUiState(
            transportKind = "http_voice_stream_ndjson",
            streamEventCount = 5,
            streamEventTypes = listOf("stream.started", "stt.partial", "stt.final", "response.completed", "stream.completed"),
            streamStarted = true,
            streamCompleted = true,
            streamProtocolName = "otoxan-mobile-realtime-stream",
            streamProtocolVersion = 1,
            streamPartialTranscriptCount = 1,
            streamLatestPartialTranscriptLength = 8,
            streamFinalTranscriptObserved = true,
            streamFinalTranscriptLength = 12,
            pass1Ready = false,
            pass1Status = "proof-mode-not-real-speech"
        )

        val summary = state.streamTelemetrySummary
        val evidence = state.phoneTelemetryEvidenceClasses[3]

        assertEquals("transport=http_voice_stream_ndjson; events=5; started=true; completed=true", summary.statusText)
        assertEquals("stream.started → stt.partial → stt.final → response.completed → stream.completed", summary.eventsText)
        assertEquals("partial=1 latestChars=8; final=true finalChars=12", summary.transcriptStateText)
        assertEquals("Stream transport telemetry", evidence.label)
        assertEquals(EvidenceClassState.DiagnosticOnly, evidence.state)
        assertTrue(evidence.detail.contains("not Ray-Ban hardware or real-speech success"))
        assertEquals(EvidenceClassState.NeedsEvidence, state.phoneTelemetryEvidenceClasses[0].state)
    }

    @Test
    fun phoneTelemetryEvidenceClasses_markRealHardwareAndLatencyPassWithoutMergingEvidenceClasses() {
        val classes = OtoxanUiState(
            pass1Ready = true,
            pass1Status = "real-speech-proven",
            transcriptSource = "hermes-stt",
            sttStatus = "success",
            capturedBytes = 160000,
            expectedCaptureBytes = 160000,
            capturePeakAmplitude = 5990,
            captureUsable = true,
            provider = "xander-session",
            httpStatusCode = 200,
            backendBytesReceived = 160000,
            ttfaMs = 1200,
            postCaptureAckDelayMs = 80,
            turnTotalMs = 7000,
            backendRoundTripMs = 3000,
            sttLatencyMs = 900,
            xanderSessionMs = 2200,
            playbackTotalMs = 700
        ).phoneTelemetryEvidenceClasses

        assertEquals(EvidenceClassState.Proven, classes[0].state)
        assertTrue(classes[0].detail.contains("real-speech-proven"))
        assertEquals(EvidenceClassState.Proven, classes[1].state)
        assertEquals(EvidenceClassState.Proven, classes[2].state)
        assertEquals(EvidenceClassState.NotRuntimeEvidence, classes[3].state)
        assertEquals(EvidenceClassState.Proven, classes[4].state)
        assertEquals(EvidenceClassState.NotRuntimeEvidence, classes[5].state)
    }
}
