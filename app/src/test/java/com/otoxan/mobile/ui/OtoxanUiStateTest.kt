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
}
