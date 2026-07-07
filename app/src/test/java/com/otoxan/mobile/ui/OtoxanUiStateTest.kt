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
            "endpoint=https://voice.example/voice-turn; http=200; dispatch=1320ms; responseReady=2800ms; clientRoundTrip=1480ms; request=44000 bytes; response=900 bytes",
            evidence
        )
    }
}
