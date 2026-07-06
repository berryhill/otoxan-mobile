package com.otoxan.mobile.voice

import java.util.Base64
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpXanderVoiceClientTest {
    private val server = MockWebServer()

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun sendVoiceTurn_postsPcmAndRouteEvidenceAndParsesResponse() {
        val ttsBytes = Base64.getEncoder().encodeToString(byteArrayOf(9, 8, 7))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "provider": "proof",
                      "transcript": "hello xander",
                      "assistantText": "route confirmed",
                      "ttsPcm16Mono16kBase64": "$ttsBytes",
                      "audioFormat": "pcm_s16le_16khz_mono",
                      "bytesReceived": 4,
                      "transcriptSource": "proof",
                      "sttProvider": "not-run",
                      "sttStatus": "not-run",
                      "sttLatencyMs": null,
                      "pass1Status": "proof-mode-not-real-speech",
                      "pass1Ready": false,
                      "audioStats": {"bytes": 4, "samples": 2, "durationMs": 0, "peak": 513, "rms": 512.5},
                      "backendTotalMs": 123,
                      "decodePcmMs": 2,
                      "audioStatsMs": 3,
                      "transcriptTotalMs": 4,
                      "xanderSessionMs": 5,
                      "xanderFastMs": 6,
                      "xanderFastStatus": 1,
                      "xanderFastTimedOut": 0,
                      "xanderFallbackSessionStatus": 0,
                      "xanderFallbackSkipped": 0,
                      "responseBuildMs": 1
                    }
                    """.trimIndent()
                )
        )
        server.start()

        val client = HttpXanderVoiceClient(
            endpointUrl = server.url("/voice-turn").toString(),
            connectTimeoutMillis = 1_000,
            readTimeoutMillis = 1_000
        )
        val result = kotlinx.coroutines.runBlocking {
            client.sendVoiceTurn(
                pcm16Mono16k = byteArrayOf(1, 2, 3, 4),
                routeEvidence = RouteEvidence(
                    inputName = "Ray-Ban Meta",
                    inputType = "TYPE_BLE_HEADSET",
                    outputName = "Ray-Ban Meta",
                    outputType = "TYPE_BLE_HEADSET",
                    wearableActive = true,
                    message = "setCommunicationDevice=true"
                )
            )
        }

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("application/json", request.getHeader("Content-Type"))
        val requestBody = request.body.readUtf8()
        assertTrue(requestBody.contains("\"pcm16Mono16kBase64\":\"AQIDBA==\""))
        assertTrue(requestBody.contains("\"inputName\":\"Ray-Ban Meta\""))
        assertTrue(requestBody.contains("\"wearableActive\":true"))
        assertEquals("proof", result.provider)
        assertEquals("hello xander", result.transcript)
        assertEquals("route confirmed", result.assistantText)
        assertEquals(listOf<Byte>(9, 8, 7), result.ttsPcm16Mono16k!!.toList())
        assertEquals(4, result.bytesReceived)
        assertEquals("proof", result.transcriptSource)
        assertEquals("not-run", result.sttProvider)
        assertEquals("not-run", result.sttStatus)
        assertEquals(null, result.sttLatencyMs)
        assertEquals("proof-mode-not-real-speech", result.pass1Status)
        assertEquals(false, result.pass1Ready)
        assertEquals("pcm_s16le_16khz_mono", result.audioFormat)
        assertEquals(0, result.audioDurationMs)
        assertEquals(513, result.audioPeak)
        assertEquals(512.5, result.audioRms!!, 0.01)
        assertEquals(123, result.backendTotalMs)
        assertEquals(2, result.decodePcmMs)
        assertEquals(3, result.audioStatsMs)
        assertEquals(4, result.transcriptTotalMs)
        assertEquals(5, result.xanderSessionMs)
        assertEquals(6, result.xanderFastMs)
        assertEquals(1, result.xanderFastStatus)
        assertEquals(0, result.xanderFastTimedOut)
        assertEquals(0, result.xanderFallbackSessionStatus)
        assertEquals(0, result.xanderFallbackSkipped)
        assertEquals(1, result.responseBuildMs)
        assertEquals(200, result.httpStatusCode)
        assertTrue(result.requestBytes!! > 0)
        assertTrue(result.responseBytes!! > 0)
        assertTrue(result.clientBackendRoundTripMs!! >= 0)
        assertTrue(result.requestBuildMs!! >= 0)
        assertTrue(result.uploadMs!! >= 0)
        assertTrue(result.responseCodeWaitMs!! >= 0)
        assertTrue(result.responseReadMs!! >= 0)
        assertTrue(result.responseParseMs!! >= 0)
    }

    @Test
    fun sendVoiceTurn_decodesUnicodeEscapesFromProviderJson() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "provider": "xander-session",
                      "transcript": "I\u2019m here",
                      "assistantText": "Route \u2713",
                      "bytesReceived": 2
                    }
                    """.trimIndent()
                )
        )
        server.start()

        val client = HttpXanderVoiceClient(
            endpointUrl = server.url("/voice-turn").toString(),
            connectTimeoutMillis = 1_000,
            readTimeoutMillis = 1_000
        )

        val result = kotlinx.coroutines.runBlocking {
            client.sendVoiceTurn(byteArrayOf(1, 2), RouteEvidence.default("unicode route"))
        }

        assertEquals("xander-session", result.provider)
        assertEquals("I’m here", result.transcript)
        assertEquals("Route ✓", result.assistantText)
    }

    @Test
    fun sendVoiceTurn_throwsHelpfulErrorForInvalidTtsBase64() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "provider": "proof",
                      "transcript": "hello",
                      "assistantText": "route confirmed",
                      "ttsPcm16Mono16kBase64": "not base64",
                      "bytesReceived": 4
                    }
                    """.trimIndent()
                )
        )
        server.start()

        val client = HttpXanderVoiceClient(
            endpointUrl = server.url("/voice-turn").toString(),
            connectTimeoutMillis = 1_000,
            readTimeoutMillis = 1_000
        )

        val error = kotlin.runCatching {
            kotlinx.coroutines.runBlocking {
                client.sendVoiceTurn(byteArrayOf(1, 2), RouteEvidence.default("bad tts"))
            }
        }.exceptionOrNull()

        assertTrue(error is XanderVoiceClientException)
        assertTrue(error!!.message!!.contains("ttsPcm16Mono16kBase64"))
    }

    @Test
    fun sendVoiceTurn_throwsHelpfulErrorForNon2xxResponse() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("backend unavailable"))
        server.start()

        val client = HttpXanderVoiceClient(
            endpointUrl = server.url("/voice-turn").toString(),
            connectTimeoutMillis = 1_000,
            readTimeoutMillis = 1_000
        )

        val error = kotlin.runCatching {
            kotlinx.coroutines.runBlocking {
                client.sendVoiceTurn(byteArrayOf(1), RouteEvidence.default("test route"))
            }
        }.exceptionOrNull()

        assertTrue(error is XanderVoiceClientException)
        assertTrue(error!!.message!!.contains("HTTP 503"))
        assertTrue(error.message!!.contains("backend unavailable"))
    }
    @Test
    fun postVoiceTurnMetrics_postsTelemetryWithoutRawTranscript() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"ok\":true,\"recordId\":\"record-1\"}")
        )
        server.start()

        val client = HttpXanderVoiceClient(
            endpointUrl = server.url("/voice-turn").toString(),
            connectTimeoutMillis = 1_000,
            readTimeoutMillis = 1_000
        )
        val result = kotlinx.coroutines.runBlocking {
            client.postVoiceTurnMetrics(
                VoiceTurnTelemetryPacket(
                    turnId = "turn-1",
                    stage = "complete",
                    success = true,
                    playbackMode = "NonCallPlayback",
                    playbackKind = "android_tts",
                    routeEvidence = RouteEvidence.default("route selected"),
                    releaseEvidence = RouteEvidence.default("route released"),
                    capturedBytes = 160000,
                    expectedCaptureBytes = 160000,
                    capturePeakAmplitude = 123,
                    captureUsable = true,
                    captureExpectedMs = 5000,
                    captureReadMs = 5010,
                    captureStopReason = "speech_silence",
                    routeSelectMs = 12,
                    routeReleaseMs = 710,
                    turnTotalMs = 12000,
                    backendRoundTripMs = 4000,
                    httpStatusCode = 200,
                    requestBytes = 200000,
                    responseBytes = 1500,
                    backendTotalMs = 3900,
                    sttLatencyMs = 900,
                    xanderSessionMs = 2500,
                    xanderFastMs = 2500,
                    xanderFastStatus = 0,
                    xanderFastTimedOut = 1,
                    xanderFallbackSessionStatus = 0,
                    xanderFallbackSkipped = 1,
                    provider = "xander-session",
                    transcriptSource = "hermes-stt",
                    sttProvider = "moonshine-stt",
                    sttStatus = "success",
                    pass1Status = "real-speech-proven",
                    pass1Ready = true,
                    transcriptLength = 32,
                    assistantTextLength = 44,
                    ttsBytes = 0,
                    playbackTotalMs = 1100
                )
            )
        }

        val request = server.takeRequest()
        val requestBody = request.body.readUtf8()
        assertEquals("POST", request.method)
        assertEquals("/voice-turn-metrics", request.path)
        assertEquals(true, result.ok)
        assertEquals("record-1", result.recordId)
        assertTrue(requestBody.contains("\"type\":\"otoxan_mobile_voice_turn_metrics\""))
        assertTrue(requestBody.contains("\"turnId\":\"turn-1\""))
        assertTrue(requestBody.contains("\"transcriptLength\":32"))
        assertTrue(requestBody.contains("\"assistantTextLength\":44"))
        assertTrue(requestBody.contains("\"xanderFastTimedOut\":1"))
        assertTrue(requestBody.contains("\"xanderFallbackSkipped\":1"))
        assertTrue(requestBody.contains("\"sttProvider\":\"moonshine-stt\""))
        assertTrue(requestBody.contains("\"stopReason\":\"speech_silence\""))
        assertTrue(!requestBody.contains("rawTranscript"))
        assertTrue(!requestBody.contains("assistantText\""))
    }

}
