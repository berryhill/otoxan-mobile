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
}
