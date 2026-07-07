package com.otoxan.mobile.voice

import java.io.InputStream
import java.io.IOException
import java.net.ServerSocket
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpXanderVoiceClientTest {
    private val server = MockWebServer()

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun normalizeVoiceTurnEndpoint_acceptsBaseHostOrExplicitPath() {
        assertEquals("http://10.0.2.2:8787/voice-turn", normalizeVoiceTurnEndpoint(" http://10.0.2.2:8787 "))
        assertEquals("http://10.0.2.2:8787/voice-turn", normalizeVoiceTurnEndpoint("http://10.0.2.2:8787/voice-turn/"))
    }

    @Test
    fun createXanderVoiceClient_appliesEndpointPolicyWithoutChangingDefaultStubBehavior() {
        val blankClient = createXanderVoiceClient(
            endpointUrl = "",
            endpointPolicy = XanderVoiceEndpointPolicy(
                connectTimeoutMillis = 111,
                readTimeoutMillis = 222,
                metricsTimeoutMillis = 333
            )
        )
        val httpClient = createXanderVoiceClient(
            endpointUrl = "http://10.0.2.2:8787",
            endpointPolicy = XanderVoiceEndpointPolicy(
                connectTimeoutMillis = 111,
                readTimeoutMillis = 222,
                metricsTimeoutMillis = 333
            )
        ) as HttpXanderVoiceClient

        assertTrue(blankClient is StubXanderVoiceClient)
        assertEquals(111, httpClient.privateIntField("connectTimeoutMillis"))
        assertEquals(222, httpClient.privateIntField("readTimeoutMillis"))
        assertEquals(333, httpClient.privateIntField("metricsTimeoutMillis"))
    }

    @Test
    fun createXanderVoiceClient_streamingSeamIsDisabledByDefaultAndOptInOnly() {
        val defaultClient = createXanderVoiceClient(endpointUrl = "http://10.0.2.2:8787")
        val explicitlyDisabledClient = createXanderVoiceClient(
            endpointUrl = "http://10.0.2.2:8787",
            streamingConfig = StreamingVoiceClientConfig(enabled = false, endpointUrl = "http://10.0.2.2:8787/voice-stream")
        )
        val enabledClient = createXanderVoiceClient(
            endpointUrl = "http://10.0.2.2:8787",
            streamingConfig = StreamingVoiceClientConfig(enabled = true, endpointUrl = "http://10.0.2.2:8787/voice-stream")
        )

        assertTrue(defaultClient is HttpXanderVoiceClient)
        assertFalse(defaultClient is StreamingXanderVoiceClient)
        assertTrue(explicitlyDisabledClient is HttpXanderVoiceClient)
        assertFalse(explicitlyDisabledClient is StreamingXanderVoiceClient)
        assertTrue(enabledClient is StreamingXanderVoiceClient)
    }

    @Test
    fun normalizeVoiceStreamEndpoint_acceptsBaseVoiceTurnOrExplicitStreamPath() {
        assertEquals("http://10.0.2.2:8787/voice-stream", normalizeVoiceStreamEndpoint(" http://10.0.2.2:8787 "))
        assertEquals("http://10.0.2.2:8787/voice-stream", normalizeVoiceStreamEndpoint("http://10.0.2.2:8787/voice-turn"))
        assertEquals("http://10.0.2.2:8787/voice-stream", normalizeVoiceStreamEndpoint("http://10.0.2.2:8787/voice-stream/"))
    }

    @Test
    fun normalizeRealtimeEndpoint_acceptsBaseVoiceTurnStreamOrExplicitRealtimePath() {
        assertEquals("ws://10.0.2.2:8788/realtime", normalizeRealtimeEndpoint(" http://10.0.2.2:8788 "))
        assertEquals("ws://10.0.2.2:8788/realtime", normalizeRealtimeEndpoint("http://10.0.2.2:8788/voice-turn"))
        assertEquals("ws://10.0.2.2:8788/realtime", normalizeRealtimeEndpoint("http://10.0.2.2:8788/voice-stream/"))
        assertEquals("ws://10.0.2.2:8788/realtime", normalizeRealtimeEndpoint("ws://10.0.2.2:8788/realtime/"))
    }

    @Test
    fun diagnosticPcmWrapperSendsDiagnosticChunksThenAlwaysPreservesVoiceTurnFallback() {
        val diagnosticClient = RecordingDiagnosticPcmChunkClient()
        val fallbackClient = RecordingXanderVoiceClient()
        val client = DiagnosticPcmXanderVoiceClient(diagnosticClient, fallbackClient)

        val result = kotlinx.coroutines.runBlocking {
            client.sendVoiceTurn(byteArrayOf(1, 2, 3), RouteEvidence.default("diagnostic route"))
        }

        assertEquals("fallback transcript", result.transcript)
        assertEquals(1, diagnosticClient.calls)
        assertEquals(1, fallbackClient.calls)
        assertEquals(listOf<Byte>(1, 2, 3), diagnosticClient.lastPcm!!.toList())
        assertEquals(listOf<Byte>(1, 2, 3), fallbackClient.lastPcm!!.toList())

        val failingDiagnosticClient = RecordingDiagnosticPcmChunkClient(IOException("diagnostic endpoint down"))
        val fallbackAfterFailure = RecordingXanderVoiceClient()
        val fallbackResult = kotlinx.coroutines.runBlocking {
            DiagnosticPcmXanderVoiceClient(failingDiagnosticClient, fallbackAfterFailure)
                .sendVoiceTurn(byteArrayOf(4, 5), RouteEvidence.default("diagnostic failure route"))
        }

        assertEquals("fallback transcript", fallbackResult.transcript)
        assertEquals(1, failingDiagnosticClient.calls)
        assertEquals(1, fallbackAfterFailure.calls)
    }

    @Test
    fun realtimeDiagnosticPcmClientSendsSessionUpdateBinaryPcmChunksClearAndCloseWithoutCommit() {
        val capturedFrames = LinkedBlockingQueue<List<CapturedWebSocketFrame>>()
        ServerSocket(0).use { serverSocket ->
            val port = serverSocket.localPort
            val serverThread = thread(start = true) {
                serverSocket.accept().use { socket ->
                    socket.soTimeout = 2_000
                    readHttpRequest(socket.getInputStream())
                    socket.getOutputStream().write(
                        (
                            "HTTP/1.1 101 Switching Protocols\r\n" +
                                "Upgrade: websocket\r\n" +
                                "Connection: Upgrade\r\n" +
                                "Sec-WebSocket-Accept: test\r\n" +
                                "\r\n"
                            ).toByteArray(Charsets.US_ASCII)
                    )
                    socket.getOutputStream().flush()
                    capturedFrames.offer(List(6) { readClientFrame(socket.getInputStream()) })
                }
            }

            val pcm = ByteArray(650) { index -> (index % 127).toByte() }
            kotlinx.coroutines.runBlocking {
                RealtimeDiagnosticPcmChunkClient(
                    endpointUrl = "ws://127.0.0.1:$port/realtime",
                    chunkBytes = 320,
                    timeoutMillis = 2_000
                ).sendDiagnosticPcmChunks(pcm, RouteEvidence.default("diagnostic websocket route"))
            }
            serverThread.join(2_000)
        }

        val frames = capturedFrames.poll(2, TimeUnit.SECONDS) ?: error("diagnostic server did not capture frames")
        assertEquals(listOf(0x1, 0x2, 0x2, 0x2, 0x1, 0x8), frames.map { it.opcode })
        assertTrue(frames[0].payload.toString(Charsets.UTF_8).contains("\"type\":\"session.update\""))
        assertTrue(frames[0].payload.toString(Charsets.UTF_8).contains("diagnostic websocket route"))
        assertEquals(320, frames[1].payload.size)
        assertEquals(320, frames[2].payload.size)
        assertEquals(10, frames[3].payload.size)
        assertEquals("{\"type\":\"input_audio.clear\"}", frames[4].payload.toString(Charsets.UTF_8))
        assertEquals(0, frames[5].payload.size)
    }

    @Test
    fun httpStreamingVoiceClient_postsToVoiceStreamAndParsesCompletedVoiceTurnEvent() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/x-ndjson")
                .setBody(
                    """
                    {"type":"stream.started","protocol":{"version":1}}
                    {"type":"response.completed","voiceTurn":{"provider":"stream-proof","transcript":"stream hello","assistantText":"stream route confirmed","bytesReceived":4,"transcriptSource":"proof-stream","sttStatus":"success","pass1Ready":true}}
                    {"type":"stream.completed"}
                    """.trimIndent()
                )
        )
        server.start()

        val client = HttpStreamingVoiceClient(
            endpointUrl = server.url("/voice-turn").toString(),
            connectTimeoutMillis = 1_000,
            readTimeoutMillis = 1_000
        )
        val result = kotlinx.coroutines.runBlocking {
            client.sendStreamingVoiceTurn(byteArrayOf(1, 2, 3, 4), RouteEvidence.default("stream route"))
        }

        val request = server.takeRequest()
        val requestBody = request.body.readUtf8()
        assertEquals("POST", request.method)
        assertEquals("/voice-stream", request.path)
        assertEquals("application/x-ndjson", request.getHeader("Accept"))
        assertTrue(requestBody.contains("\"pcm16Mono16kBase64\":\"AQIDBA==\""))
        assertEquals("stream-proof", result.provider)
        assertEquals("stream hello", result.transcript)
        assertEquals("stream route confirmed", result.assistantText)
        assertEquals(4, result.bytesReceived)
        assertEquals("proof-stream", result.transcriptSource)
        assertEquals("success", result.sttStatus)
        assertEquals(true, result.pass1Ready)
        assertEquals(200, result.httpStatusCode)
        assertTrue(result.responseBytes!! > 0)
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
                    playbackTotalMs = 1100,
                    localAckKind = "earcon",
                    localAckStartMs = 2400,
                    localAckTotalMs = 115,
                    assistantPlaybackStartMs = 5100,
                    backendResponseReadyMs = 4800,
                    ttfaMs = 2400,
                    routeSelectStartMs = 0,
                    routeSelectEndMs = 12,
                    captureStartMs = 12,
                    captureEndMs = 5022,
                    speechFirstDetectedMs = 240,
                    speechLastDetectedMs = 4570,
                    endpointDispatchMs = 5023,
                    endpointResponseReadyMs = 4800,
                    routeReleaseStartMs = 2516,
                    routeReleaseEndMs = 3226,
                    ttfaRouteSelectMs = 12,
                    ttfaCaptureReadMs = 5010,
                    ttfaPostCaptureDispatchMs = 7,
                    postCaptureAckDelayMs = 7,
                    ttfaBackendWaitAfterReleaseMs = 650
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
        assertTrue(requestBody.contains("\"timingContract\""))
        assertTrue(requestBody.contains("\"name\":\"otoxan-mobile-canonical-timing\""))
        assertTrue(requestBody.contains("\"clock\":\"turn_elapsed_ms_from_android_monotonic_start\""))
        assertTrue(requestBody.contains("\"ttfaMs\":1500"))
        assertTrue(requestBody.contains("\"postCaptureAckDelayMs\":250"))
        assertTrue(requestBody.contains("\"turnTotalMs\":8000"))
        assertTrue(requestBody.contains("\"backendRoundTripMs\":4000"))
        assertTrue(requestBody.contains("\"turnId\":\"turn-1\""))
        assertTrue(requestBody.contains("\"transcriptLength\":32"))
        assertTrue(requestBody.contains("\"assistantTextLength\":44"))
        assertTrue(requestBody.contains("\"xanderFastTimedOut\":1"))
        assertTrue(requestBody.contains("\"xanderFallbackSkipped\":1"))
        assertTrue(requestBody.contains("\"perceivedLatency\""))
        assertTrue(requestBody.contains("\"ttfaMs\":2400"))
        assertTrue(requestBody.contains("\"localAckKind\":\"earcon\""))
        assertTrue(requestBody.contains("\"postCaptureAckDelayMs\":7"))
        assertTrue(requestBody.contains("\"assistantPlaybackStartMs\":5100"))
        assertTrue(requestBody.contains("\"backendResponseReadyMs\":4800"))
        assertTrue(requestBody.contains("\"breakdown\""))
        assertTrue(requestBody.contains("\"routeSelectMs\":12"))
        assertTrue(requestBody.contains("\"captureReadMs\":5010"))
        assertTrue(requestBody.contains("\"postCaptureDispatchMs\":7"))
        assertTrue(requestBody.contains("\"backendWaitAfterReleaseMs\":650"))
        assertTrue(requestBody.contains("\"sttProvider\":\"moonshine-stt\""))
        assertTrue(requestBody.contains("\"stopReason\":\"speech_silence\""))
        assertTrue(requestBody.contains("\"timestamps\""))
        assertTrue(requestBody.contains("\"routeSelectStartMs\":0"))
        assertTrue(requestBody.contains("\"routeSelectEndMs\":12"))
        assertTrue(requestBody.contains("\"captureStartMs\":12"))
        assertTrue(requestBody.contains("\"captureEndMs\":5022"))
        assertTrue(requestBody.contains("\"firstSpeechDetectedMs\":240"))
        assertTrue(requestBody.contains("\"lastSpeechDetectedMs\":4570"))
        assertTrue(requestBody.contains("\"endpointDispatchMs\":5023"))
        assertTrue(requestBody.contains("\"endpointResponseReadyMs\":4800"))
        assertTrue(requestBody.contains("\"routeReleaseStartMs\":2516"))
        assertTrue(requestBody.contains("\"routeReleaseEndMs\":3226"))
        assertTrue(!requestBody.contains("rawTranscript"))
        assertTrue(!requestBody.contains("assistantText\""))
    }


    @Test
    fun postVoiceTurnMetrics_emitsCanonicalTimingContractAndMonotonicAnchors() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"ok\":true,\"recordId\":\"record-timing\"}")
        )
        server.start()

        val client = HttpXanderVoiceClient(
            endpointUrl = server.url("/voice-turn").toString(),
            connectTimeoutMillis = 1_000,
            readTimeoutMillis = 1_000
        )

        kotlinx.coroutines.runBlocking {
            client.postVoiceTurnMetrics(
                VoiceTurnTelemetryPacket(
                    turnId = "turn-timing",
                    stage = "complete",
                    success = true,
                    playbackMode = "NonCallPlayback",
                    playbackKind = "android_tts",
                    routeEvidence = RouteEvidence.default("route selected"),
                    releaseEvidence = RouteEvidence.default("route released"),
                    routeSelectStartMs = 0,
                    routeSelectEndMs = 48,
                    routeSelectMs = 48,
                    captureStartMs = 48,
                    captureEndMs = 1328,
                    captureReadMs = 1280,
                    endpointDispatchMs = 1373,
                    backendRoundTripMs = 3100,
                    endpointResponseReadyMs = 4473,
                    routeReleaseStartMs = 1460,
                    routeReleaseEndMs = 1910,
                    routeReleaseMs = 450,
                    localAckKind = "earcon_while_route_active",
                    localAckStartMs = 1418,
                    localAckTotalMs = 82,
                    assistantPlaybackStartMs = 4550,
                    backendResponseReadyMs = 4473,
                    ttfaMs = 1418,
                    ttfaRouteSelectMs = 48,
                    ttfaCaptureReadMs = 1280,
                    ttfaPostCaptureDispatchMs = 90,
                    postCaptureAckDelayMs = 90,
                    ttfaBackendWaitAfterReleaseMs = 2523,
                    turnTotalMs = 6200,
                    backendTotalMs = 3000,
                    sttLatencyMs = 600,
                    xanderFastMs = 1400,
                    xanderFastStatus = 1,
                    provider = "mobile-fast",
                    transcriptSource = "moonshine-stt",
                    sttProvider = "moonshine-stt",
                    sttStatus = "success",
                    pass1Status = "real-speech-proven",
                    pass1Ready = true,
                    transcriptLength = 21,
                    assistantTextLength = 37,
                    ttsBytes = 0,
                    playbackTotalMs = 1200
                )
            )
        }

        val requestBody = server.takeRequest().body.readUtf8()
        val root = JSONObject(requestBody)
        val contract = root.getJSONObject("timingContract")
        val targets = contract.getJSONObject("targets")
        val timestamps = root.getJSONObject("timestamps")
        val perceived = root.getJSONObject("perceivedLatency")
        val breakdown = perceived.getJSONObject("breakdown")

        assertEquals(VOICE_TURN_TIMING_CONTRACT_NAME, contract.getString("name"))
        assertEquals(VOICE_TURN_TIMING_CONTRACT_VERSION, contract.getInt("version"))
        assertEquals(VOICE_TURN_TIMING_CONTRACT_CLOCK, contract.getString("clock"))
        assertEquals(VOICE_TURN_TTFA_TARGET_MS, targets.getLong("ttfaMs"))
        assertEquals(VOICE_TURN_ACK_GAP_TARGET_MS, targets.getLong("postCaptureAckDelayMs"))
        assertEquals(VOICE_TURN_TOTAL_TARGET_MS, targets.getLong("turnTotalMs"))
        assertEquals(VOICE_TURN_BACKEND_TARGET_MS, targets.getLong("backendRoundTripMs"))
        assertEquals(0L, timestamps.getLong("routeSelectStartMs"))
        assertEquals(48L, timestamps.getLong("routeSelectEndMs"))
        assertEquals(48L, timestamps.getLong("captureStartMs"))
        assertEquals(1328L, timestamps.getLong("captureEndMs"))
        assertEquals(1373L, timestamps.getLong("endpointDispatchMs"))
        assertEquals(4473L, timestamps.getLong("endpointResponseReadyMs"))
        assertEquals(1460L, timestamps.getLong("routeReleaseStartMs"))
        assertEquals(1910L, timestamps.getLong("routeReleaseEndMs"))
        assertEquals(1418L, perceived.getLong("ttfaMs"))
        assertEquals("earcon_while_route_active", perceived.getString("localAckKind"))
        assertEquals(90L, perceived.getLong("postCaptureAckDelayMs"))
        assertEquals(48L, breakdown.getLong("routeSelectMs"))
        assertEquals(1280L, breakdown.getLong("captureReadMs"))
        assertEquals(90L, breakdown.getLong("postCaptureDispatchMs"))
        assertEquals(2523L, breakdown.getLong("backendWaitAfterReleaseMs"))
        assertEquals(90L, perceived.getLong("localAckStartMs") - breakdown.getLong("routeSelectMs") - breakdown.getLong("captureReadMs"))
        assertTrue(timestamps.getLong("routeSelectStartMs") <= timestamps.getLong("routeSelectEndMs"))
        assertTrue(timestamps.getLong("routeSelectEndMs") <= timestamps.getLong("captureStartMs"))
        assertTrue(timestamps.getLong("captureStartMs") <= timestamps.getLong("captureEndMs"))
        assertTrue(timestamps.getLong("captureEndMs") <= timestamps.getLong("endpointDispatchMs"))
        assertTrue(timestamps.getLong("routeReleaseStartMs") <= timestamps.getLong("routeReleaseEndMs"))
    }

    @Test
    fun fetchRecentVoiceTurnMetrics_parsesRecentServerTelemetry() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "ok": true,
                      "count": 2,
                      "records": [
                        {
                          "recordId": "record-2",
                          "receivedAtMs": 12345,
                          "payload": {
                            "turn": {"turnId": "turn-2", "success": true, "stage": "complete", "error": null},
                            "route": {"inputName": "RB Meta 03YS", "inputType": "TYPE_BLUETOOTH_SCO"},
                            "totals": {"turnTotalMs": 12000},
                            "perceivedLatency": {"ttfaMs": 5500, "postCaptureAckDelayMs": 125},
                            "backend": {"roundTripMs": 7400, "sttLatencyMs": 500, "xanderFastMs": 2600},
                            "playback": {"totalMs": 4500},
                            "capture": {"capturedBytes": 144000, "peakAmplitude": 9000},
                            "verdict": {"transcriptSource": "hermes-stt", "pass1Status": "real-speech-proven", "assistantTextLength": 54}
                          }
                        }
                      ]
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
        val result = kotlinx.coroutines.runBlocking { client.fetchRecentVoiceTurnMetrics(limit = 24) }

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/voice-turn-metrics/recent?limit=24", request.path)
        assertTrue(result.error ?: "history fetch failed", result.ok)
        assertEquals(2, result.count)
        assertEquals(1, result.records.size)
        val record = result.records.single()
        assertEquals("turn-2", record.turnId)
        assertEquals("RB Meta 03YS", record.routeName)
        assertEquals(5500L, record.ttfaMs)
        assertEquals(125L, record.postCaptureAckDelayMs)
        assertEquals(2600, record.xanderMs)
        assertEquals("real-speech-proven", record.pass1Status)
    }

}

private fun Any.privateIntField(name: String): Int {
    return javaClass.getDeclaredField(name).apply { isAccessible = true }.getInt(this)
}

private class RecordingDiagnosticPcmChunkClient(
    private val error: Throwable? = null
) : DiagnosticPcmChunkClient {
    var calls: Int = 0
    var lastPcm: ByteArray? = null

    override suspend fun sendDiagnosticPcmChunks(pcm16Mono16k: ByteArray, routeEvidence: RouteEvidence) {
        calls += 1
        lastPcm = pcm16Mono16k
        error?.let { throw it }
    }
}

private class RecordingXanderVoiceClient : XanderVoiceClient {
    var calls: Int = 0
    var lastPcm: ByteArray? = null

    override suspend fun sendVoiceTurn(pcm16Mono16k: ByteArray, routeEvidence: RouteEvidence): VoiceTurnResult {
        calls += 1
        lastPcm = pcm16Mono16k
        return VoiceTurnResult(transcript = "fallback transcript", assistantText = "fallback assistant")
    }

    override suspend fun postVoiceTurnMetrics(packet: VoiceTurnTelemetryPacket): VoiceTurnTelemetryResult {
        return VoiceTurnTelemetryResult(ok = true, recordId = "fallback")
    }

    override suspend fun fetchRecentVoiceTurnMetrics(limit: Int): VoiceTurnTelemetryHistoryResult {
        return VoiceTurnTelemetryHistoryResult(ok = true)
    }
}

private data class CapturedWebSocketFrame(val opcode: Int, val payload: ByteArray)

private fun readHttpRequest(input: InputStream): String {
    val bytes = mutableListOf<Byte>()
    while (true) {
        val next = input.read()
        if (next < 0) break
        bytes.add(next.toByte())
        val size = bytes.size
        if (size >= 4 &&
            bytes[size - 4] == '\r'.code.toByte() &&
            bytes[size - 3] == '\n'.code.toByte() &&
            bytes[size - 2] == '\r'.code.toByte() &&
            bytes[size - 1] == '\n'.code.toByte()
        ) break
    }
    return bytes.toByteArray().toString(Charsets.ISO_8859_1)
}

private fun readClientFrame(input: InputStream): CapturedWebSocketFrame {
    val first = input.read()
    val second = input.read()
    if (first < 0 || second < 0) error("unexpected end of websocket frame")
    val opcode = first and 0x0F
    val masked = (second and 0x80) != 0
    var length = second and 0x7F
    if (length == 126) {
        length = (input.readExactByte() shl 8) or input.readExactByte()
    } else if (length == 127) {
        var longLength = 0L
        repeat(8) { longLength = (longLength shl 8) or input.readExactByte().toLong() }
        length = longLength.toInt()
    }
    val mask = if (masked) input.readExactBytes(4) else ByteArray(0)
    val payload = input.readExactBytes(length)
    val unmaskedPayload = if (masked) {
        ByteArray(payload.size) { index -> (payload[index].toInt() xor mask[index % 4].toInt()).toByte() }
    } else {
        payload
    }
    return CapturedWebSocketFrame(opcode = opcode, payload = unmaskedPayload)
}

private fun InputStream.readExactByte(): Int {
    val value = read()
    if (value < 0) error("unexpected end of stream")
    return value
}

private fun InputStream.readExactBytes(length: Int): ByteArray {
    val bytes = ByteArray(length)
    var offset = 0
    while (offset < length) {
        val read = read(bytes, offset, length - offset)
        if (read < 0) error("unexpected end of stream")
        offset += read
    }
    return bytes
}
