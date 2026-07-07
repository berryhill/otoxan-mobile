package com.otoxan.mobile.voice

import com.otoxan.mobile.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointDefaultsGuardrailTest {
    @Test
    fun debugBuildCapturesPhoneReachableVoiceTurnDefault() {
        assertEquals(
            "http://100.126.0.110:8787/voice-turn",
            BuildConfig.XANDER_VOICE_ENDPOINT
        )
    }

    @Test
    fun defaultEndpointStaysOnRepoLocalVoiceTurnContract() {
        val endpoint = normalizeVoiceTurnEndpoint(BuildConfig.XANDER_VOICE_ENDPOINT)

        assertEquals("http://100.126.0.110:8787/voice-turn", endpoint)
        assertTrue(endpoint.endsWith("/voice-turn"))
        assertFalse("Hosted draft path must not become the mobile default", endpoint.contains("/api/mobile/voice-turn"))
        assertFalse("Emulator loopback is not phone-reachable for the physical Ray-Ban proof", endpoint.contains("10.0.2.2"))
        assertFalse("Blank endpoint would silently select StubXanderVoiceClient", endpoint.isBlank())
    }

    @Test
    fun defaultEndpointCreatesHttpClientNotStub() {
        val client = createXanderVoiceClient(BuildConfig.XANDER_VOICE_ENDPOINT)

        assertTrue(client is HttpXanderVoiceClient)
        assertFalse(client is StubXanderVoiceClient)
    }

    @Test
    fun defaultEndpointPolicyPreservesExistingTimeoutBehavior() {
        assertEquals(10_000, BuildConfig.XANDER_VOICE_CONNECT_TIMEOUT_MILLIS)
        assertEquals(60_000, BuildConfig.XANDER_VOICE_READ_TIMEOUT_MILLIS)
        assertEquals(5_000, BuildConfig.XANDER_VOICE_METRICS_TIMEOUT_MILLIS)
    }

    @Test
    fun conversationCaptureTuningDefaultsStayEvidenceGatedAndBounded() {
        assertFalse(BuildConfig.OTOXAN_CONVERSATION_CAPTURE_TUNING_EVIDENCE_GATE)
        assertEquals(12_000, BuildConfig.OTOXAN_CONVERSATION_CAPTURE_MAX_MILLIS)
        assertEquals(700, BuildConfig.OTOXAN_CONVERSATION_CAPTURE_MIN_MILLIS)
        assertEquals(450, BuildConfig.OTOXAN_CONVERSATION_CAPTURE_SILENCE_AFTER_SPEECH_MILLIS)
        assertEquals(900, BuildConfig.OTOXAN_CONVERSATION_CAPTURE_SPEECH_PEAK_AMPLITUDE)
        assertEquals(100, BuildConfig.OTOXAN_CONVERSATION_CAPTURE_CHUNK_MILLIS)
    }
}
