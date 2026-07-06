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
}
