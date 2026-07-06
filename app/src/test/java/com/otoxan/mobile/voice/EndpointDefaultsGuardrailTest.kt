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
}
