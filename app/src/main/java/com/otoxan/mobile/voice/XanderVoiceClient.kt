package com.otoxan.mobile.voice

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

const val VOICE_TURN_TIMING_CONTRACT_NAME = "otoxan-mobile-canonical-timing"
const val VOICE_TURN_TIMING_CONTRACT_VERSION = 1
const val VOICE_TURN_TIMING_CONTRACT_CLOCK = "turn_elapsed_ms_from_android_monotonic_start"
const val VOICE_TURN_TTFA_TARGET_MS = 1_500L
const val VOICE_TURN_ACK_GAP_TARGET_MS = 250L
const val VOICE_TURN_TOTAL_TARGET_MS = 8_000L
const val VOICE_TURN_BACKEND_TARGET_MS = 4_000L

interface XanderVoiceClient {
    suspend fun sendVoiceTurn(pcm16Mono16k: ByteArray, routeEvidence: RouteEvidence): VoiceTurnResult
    suspend fun postVoiceTurnMetrics(packet: VoiceTurnTelemetryPacket): VoiceTurnTelemetryResult
    suspend fun fetchRecentVoiceTurnMetrics(limit: Int = 20): VoiceTurnTelemetryHistoryResult
}


data class VoiceTurnTelemetryHistoryResult(
    val ok: Boolean,
    val count: Int = 0,
    val records: List<VoiceTurnTelemetryRecord> = emptyList(),
    val error: String? = null
)


data class VoiceTurnTelemetryRecord(
    val turnId: String,
    val success: Boolean,
    val stage: String?,
    val error: String?,
    val routeName: String,
    val routeType: String?,
    val receivedAtMs: Long?,
    val totalMs: Long?,
    val ttfaMs: Long?,
    val postCaptureAckDelayMs: Long?,
    val backendMs: Long?,
    val sttMs: Int?,
    val xanderMs: Int?,
    val playbackMs: Long?,
    val capturedBytes: Int?,
    val peakAmplitude: Int?,
    val transcriptSource: String?,
    val pass1Status: String?,
    val assistantTextLength: Int?
)


data class VoiceTurnTelemetryResult(
    val ok: Boolean,
    val recordId: String? = null,
    val error: String? = null
)

data class VoiceTurnTelemetryPacket(
    val turnId: String,
    val stage: String,
    val success: Boolean,
    val playbackMode: String,
    val playbackKind: String,
    val routeEvidence: RouteEvidence?,
    val releaseEvidence: RouteEvidence?,
    val capturedBytes: Int? = null,
    val expectedCaptureBytes: Int? = null,
    val capturePeakAmplitude: Int? = null,
    val captureUsable: Boolean? = null,
    val captureExpectedMs: Long? = null,
    val captureReadMs: Long? = null,
    val captureStopReason: String? = null,
    val routeSelectStartMs: Long? = null,
    val routeSelectEndMs: Long? = null,
    val captureStartMs: Long? = null,
    val captureEndMs: Long? = null,
    val speechFirstDetectedMs: Long? = null,
    val speechLastDetectedMs: Long? = null,
    val endpointDispatchMs: Long? = null,
    val endpointResponseReadyMs: Long? = null,
    val routeReleaseStartMs: Long? = null,
    val routeReleaseEndMs: Long? = null,
    val routeSelectMs: Long? = null,
    val routeReleaseMs: Long? = null,
    val turnTotalMs: Long? = null,
    val backendRoundTripMs: Long? = null,
    val backendBytesReceived: Int? = null,
    val httpStatusCode: Int? = null,
    val requestBytes: Int? = null,
    val responseBytes: Int? = null,
    val requestBuildMs: Long? = null,
    val uploadMs: Long? = null,
    val responseCodeWaitMs: Long? = null,
    val responseReadMs: Long? = null,
    val responseParseMs: Long? = null,
    val backendTotalMs: Int? = null,
    val decodePcmMs: Int? = null,
    val audioStatsMs: Int? = null,
    val transcriptTotalMs: Int? = null,
    val sttLatencyMs: Int? = null,
    val primarySttStatus: String? = null,
    val primarySttMs: Int? = null,
    val primarySttProvider: String? = null,
    val fallbackSttStatus: String? = null,
    val fallbackSttMs: Int? = null,
    val fallbackSttProvider: String? = null,
    val sttBudgetRemainingMs: Int? = null,
    val xanderSessionMs: Int? = null,
    val xanderFastMs: Int? = null,
    val xanderFastStatus: Int? = null,
    val xanderFastTimedOut: Int? = null,
    val xanderFallbackSessionStatus: Int? = null,
    val xanderFallbackSkipped: Int? = null,
    val responseBuildMs: Int? = null,
    val provider: String? = null,
    val transcriptSource: String? = null,
    val sttProvider: String? = null,
    val sttStatus: String? = null,
    val pass1Status: String? = null,
    val pass1Ready: Boolean? = null,
    val transcriptLength: Int? = null,
    val assistantTextLength: Int? = null,
    val ttsBytes: Int? = null,
    val playbackTotalMs: Long? = null,
    val localAckKind: String? = null,
    val ttfaRouteSelectMs: Long? = null,
    val ttfaCaptureReadMs: Long? = null,
    val ttfaPostCaptureDispatchMs: Long? = null,
    val postCaptureAckDelayMs: Long? = null,
    val localAckStartMs: Long? = null,
    val localAckTotalMs: Long? = null,
    val ttfaBackendWaitAfterReleaseMs: Long? = null,
    val assistantPlaybackStartMs: Long? = null,
    val backendResponseReadyMs: Long? = null,
    val ttfaMs: Long? = null,
    val error: String? = null
)

data class VoiceTurnResult(
    val transcript: String,
    val assistantText: String,
    val ttsPcm16Mono16k: ByteArray? = null,
    val bytesReceived: Int? = null,
    val provider: String? = null,
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
    val audioFormat: String? = null,
    val audioDurationMs: Int? = null,
    val audioPeak: Int? = null,
    val audioRms: Double? = null,
    val backendTotalMs: Int? = null,
    val decodePcmMs: Int? = null,
    val audioStatsMs: Int? = null,
    val transcriptTotalMs: Int? = null,
    val xanderSessionMs: Int? = null,
    val xanderFastMs: Int? = null,
    val xanderFastStatus: Int? = null,
    val xanderFastTimedOut: Int? = null,
    val xanderFallbackSessionStatus: Int? = null,
    val xanderFallbackSkipped: Int? = null,
    val responseBuildMs: Int? = null,
    val httpStatusCode: Int? = null,
    val requestBytes: Int? = null,
    val responseBytes: Int? = null,
    val requestBuildMs: Long? = null,
    val uploadMs: Long? = null,
    val responseCodeWaitMs: Long? = null,
    val responseReadMs: Long? = null,
    val responseParseMs: Long? = null,
    val clientBackendRoundTripMs: Long? = null
)

class XanderVoiceClientException(message: String, cause: Throwable? = null) : IOException(message, cause)

data class XanderVoiceEndpointPolicy(
    val connectTimeoutMillis: Int = 10_000,
    val readTimeoutMillis: Int = 60_000,
    val metricsTimeoutMillis: Int = 5_000
)

data class StreamingVoiceClientConfig(
    val enabled: Boolean = false,
    val endpointUrl: String = ""
)

interface StreamingVoiceClient {
    suspend fun sendStreamingVoiceTurn(pcm16Mono16k: ByteArray, routeEvidence: RouteEvidence): VoiceTurnResult
}

class HttpXanderVoiceClient(
    endpointUrl: String,
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 60_000,
    private val metricsTimeoutMillis: Int = 5_000
) : XanderVoiceClient {
    private val voiceTurnEndpointUrl = normalizeVoiceTurnEndpoint(endpointUrl)
    private val metricsEndpointUrl = voiceTurnEndpointUrl.replace(Regex("/voice-turn/?$"), "/voice-turn-metrics")

    override suspend fun sendVoiceTurn(
        pcm16Mono16k: ByteArray,
        routeEvidence: RouteEvidence
    ): VoiceTurnResult = withContext(Dispatchers.IO) {
        val connection = (URL(voiceTurnEndpointUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMillis
            readTimeout = readTimeoutMillis
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }

        try {
            val totalStarted = System.nanoTime()
            val buildStarted = System.nanoTime()
            val body = buildVoiceTurnRequestBody(pcm16Mono16k, routeEvidence)
            val bodyBytes = body.toByteArray(Charsets.UTF_8)
            val requestBuildMs = elapsedMs(buildStarted)
            val uploadStarted = System.nanoTime()
            connection.outputStream.use { output ->
                output.write(bodyBytes)
            }
            val uploadMs = elapsedMs(uploadStarted)

            val responseCodeStarted = System.nanoTime()
            val responseCode = connection.responseCode
            val responseCodeWaitMs = elapsedMs(responseCodeStarted)
            val responseReadStarted = System.nanoTime()
            val responseBody = if (responseCode in 200..299) {
                connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } else {
                val errorBody = connection.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                throw XanderVoiceClientException("Xander voice endpoint returned HTTP $responseCode: $errorBody")
            }
            val responseReadMs = elapsedMs(responseReadStarted)

            val parseStarted = System.nanoTime()
            val result = parseVoiceTurnResult(
                responseBody = responseBody,
                httpStatusCode = responseCode,
                requestBytes = bodyBytes.size,
                responseBytes = responseBody.toByteArray(Charsets.UTF_8).size,
                requestBuildMs = requestBuildMs,
                uploadMs = uploadMs,
                responseCodeWaitMs = responseCodeWaitMs,
                responseReadMs = responseReadMs,
                responseParseMs = elapsedMs(parseStarted),
                clientBackendRoundTripMs = elapsedMs(totalStarted)
            )
            result
        } catch (error: XanderVoiceClientException) {
            throw error
        } catch (error: Exception) {
            throw XanderVoiceClientException("Xander voice turn failed: ${error.message ?: error::class.java.simpleName}", error)
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun postVoiceTurnMetrics(packet: VoiceTurnTelemetryPacket): VoiceTurnTelemetryResult = withContext(Dispatchers.IO) {
        val connection = (URL(metricsEndpointUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMillis
            readTimeout = metricsTimeoutMillis
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val bodyBytes = buildMetricsBody(packet).toByteArray(Charsets.UTF_8)
            connection.outputStream.use { it.write(bodyBytes) }
            val responseCode = connection.responseCode
            val responseBody = if (responseCode in 200..299) {
                connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } else {
                connection.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            }
            if (responseCode in 200..299) {
                VoiceTurnTelemetryResult(
                    ok = responseBody.optionalJsonBoolean("ok") ?: true,
                    recordId = responseBody.optionalJsonString("recordId")
                )
            } else {
                VoiceTurnTelemetryResult(ok = false, error = "HTTP $responseCode: $responseBody")
            }
        } catch (error: Exception) {
            VoiceTurnTelemetryResult(ok = false, error = error.message ?: error::class.java.simpleName)
        } finally {
            connection.disconnect()
        }
    }


    override suspend fun fetchRecentVoiceTurnMetrics(limit: Int): VoiceTurnTelemetryHistoryResult = withContext(Dispatchers.IO) {
        val boundedLimit = limit.coerceIn(1, 100)
        val recentUrl = voiceTurnEndpointUrl.replace(Regex("/voice-turn/?$"), "/voice-turn-metrics/recent?limit=$boundedLimit")
        val connection = (URL(recentUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMillis
            readTimeout = metricsTimeoutMillis
            setRequestProperty("Accept", "application/json")
        }
        try {
            val responseCode = connection.responseCode
            val responseBody = if (responseCode in 200..299) {
                connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } else {
                connection.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            }
            if (responseCode !in 200..299) {
                return@withContext VoiceTurnTelemetryHistoryResult(ok = false, error = "HTTP $responseCode: $responseBody")
            }
            val root = JSONObject(responseBody)
            val records = root.optJSONArray("records")
            val parsed = buildList {
                if (records != null) {
                    for (index in 0 until records.length()) {
                        val record = records.optJSONObject(index) ?: continue
                        val payload = record.optJSONObject("payload") ?: continue
                        val turn = payload.optJSONObject("turn")
                        val route = payload.optJSONObject("route")
                        val totals = payload.optJSONObject("totals")
                        val perceived = payload.optJSONObject("perceivedLatency")
                        val backend = payload.optJSONObject("backend")
                        val playback = payload.optJSONObject("playback")
                        val capture = payload.optJSONObject("capture")
                        val verdict = payload.optJSONObject("verdict")
                        add(
                            VoiceTurnTelemetryRecord(
                                turnId = turn.optStringOrNull("turnId") ?: record.optStringOrNull("recordId") ?: "unknown",
                                success = turn?.optBoolean("success") ?: false,
                                stage = turn.optStringOrNull("stage"),
                                error = turn.optStringOrNull("error"),
                                routeName = route.optStringOrNull("inputName") ?: "unknown route",
                                routeType = route.optStringOrNull("inputType"),
                                receivedAtMs = record.optLongOrNull("receivedAtMs"),
                                totalMs = totals.optLongOrNull("turnTotalMs"),
                                ttfaMs = perceived.optLongOrNull("ttfaMs"),
                                postCaptureAckDelayMs = perceived.optLongOrNull("postCaptureAckDelayMs")
                                    ?: perceived?.optJSONObject("breakdown").optLongOrNull("postCaptureDispatchMs"),
                                backendMs = backend.optLongOrNull("roundTripMs"),
                                sttMs = backend.optIntOrNull("sttLatencyMs"),
                                xanderMs = backend.optIntOrNull("xanderFastMs") ?: backend.optIntOrNull("xanderSessionMs"),
                                playbackMs = playback.optLongOrNull("totalMs"),
                                capturedBytes = capture.optIntOrNull("capturedBytes"),
                                peakAmplitude = capture.optIntOrNull("peakAmplitude"),
                                transcriptSource = verdict.optStringOrNull("transcriptSource"),
                                pass1Status = verdict.optStringOrNull("pass1Status"),
                                assistantTextLength = verdict.optIntOrNull("assistantTextLength")
                            )
                        )
                    }
                }
            }
            VoiceTurnTelemetryHistoryResult(ok = root.optBoolean("ok", true), count = root.optInt("count", parsed.size), records = parsed)
        } catch (error: Exception) {
            VoiceTurnTelemetryHistoryResult(ok = false, error = error.message ?: error::class.java.simpleName)
        } finally {
            connection.disconnect()
        }
    }


    private fun buildMetricsBody(packet: VoiceTurnTelemetryPacket): String {
        return """
            {
              "type":"otoxan_mobile_voice_turn_metrics",
              "schemaVersion":1,
              "sentAtMs":${System.currentTimeMillis()},
              "device":{
                "appId":"com.otoxan.mobile",
                "buildType":"${com.otoxan.mobile.BuildConfig.BUILD_TYPE.jsonEscape()}",
                "voiceEndpoint":"${voiceTurnEndpointUrl.jsonEscape()}"
              },
              "timingContract":{
                "name":"$VOICE_TURN_TIMING_CONTRACT_NAME",
                "version":$VOICE_TURN_TIMING_CONTRACT_VERSION,
                "clock":"$VOICE_TURN_TIMING_CONTRACT_CLOCK",
                "anchors":{
                  "turnStartMs":0,
                  "ttfaMs":"first audible feedback after turn start; local ack preferred over assistant playback",
                  "postCaptureAckDelayMs":"local acknowledgement start minus routeSelectMs and captureReadMs",
                  "backendResponseReadyMs":"backend response ready elapsed from turn start",
                  "assistantPlaybackStartMs":"assistant audio playback start elapsed from turn start"
                },
                "targets":{
                  "ttfaMs":$VOICE_TURN_TTFA_TARGET_MS,
                  "postCaptureAckDelayMs":$VOICE_TURN_ACK_GAP_TARGET_MS,
                  "turnTotalMs":$VOICE_TURN_TOTAL_TARGET_MS,
                  "backendRoundTripMs":$VOICE_TURN_BACKEND_TARGET_MS
                }
              },
              "turn":{
                "turnId":"${packet.turnId.jsonEscape()}",
                "stage":"${packet.stage.jsonEscape()}",
                "success":${packet.success},
                "playbackMode":"${packet.playbackMode.jsonEscape()}",
                "playbackKind":"${packet.playbackKind.jsonEscape()}",
                "error":${packet.error.jsonValue()}
              },
              "route":{
                "inputName":${packet.routeEvidence?.inputName.jsonValue()},
                "inputType":${packet.routeEvidence?.inputType.jsonValue()},
                "outputName":${packet.routeEvidence?.outputName.jsonValue()},
                "outputType":${packet.routeEvidence?.outputType.jsonValue()},
                "wearableActiveAtCapture":${packet.routeEvidence?.wearableActive.jsonValue()},
                "routeMessage":${packet.routeEvidence?.message.jsonValue()},
                "releaseEvidence":${packet.releaseEvidence?.message.jsonValue()}
              },
              "capture":{
                "capturedBytes":${packet.capturedBytes.jsonValue()},
                "expectedBytes":${packet.expectedCaptureBytes.jsonValue()},
                "peakAmplitude":${packet.capturePeakAmplitude.jsonValue()},
                "usable":${packet.captureUsable.jsonValue()},
                "expectedMs":${packet.captureExpectedMs.jsonValue()},
                "actualMs":${packet.captureReadMs.jsonValue()},
                "stopReason":${packet.captureStopReason.jsonValue()},
                "firstSpeechDetectedMs":${packet.speechFirstDetectedMs.jsonValue()},
                "lastSpeechDetectedMs":${packet.speechLastDetectedMs.jsonValue()}
              },
              "timestamps":{
                "clock":"$VOICE_TURN_TIMING_CONTRACT_CLOCK",
                "routeSelectStartMs":${packet.routeSelectStartMs.jsonValue()},
                "routeSelectEndMs":${packet.routeSelectEndMs.jsonValue()},
                "captureStartMs":${packet.captureStartMs.jsonValue()},
                "captureEndMs":${packet.captureEndMs.jsonValue()},
                "speechFirstDetectedMs":${packet.speechFirstDetectedMs.jsonValue()},
                "speechLastDetectedMs":${packet.speechLastDetectedMs.jsonValue()},
                "endpointDispatchMs":${packet.endpointDispatchMs.jsonValue()},
                "endpointResponseReadyMs":${packet.endpointResponseReadyMs.jsonValue()},
                "routeReleaseStartMs":${packet.routeReleaseStartMs.jsonValue()},
                "routeReleaseEndMs":${packet.routeReleaseEndMs.jsonValue()}
              },
              "http":{
                "statusCode":${packet.httpStatusCode.jsonValue()},
                "requestBytes":${packet.requestBytes.jsonValue()},
                "responseBytes":${packet.responseBytes.jsonValue()},
                "requestBuildMs":${packet.requestBuildMs.jsonValue()},
                "uploadMs":${packet.uploadMs.jsonValue()},
                "responseCodeWaitMs":${packet.responseCodeWaitMs.jsonValue()},
                "responseReadMs":${packet.responseReadMs.jsonValue()},
                "responseParseMs":${packet.responseParseMs.jsonValue()}
              },
              "backend":{
                "roundTripMs":${packet.backendRoundTripMs.jsonValue()},
                "bytesReceived":${packet.backendBytesReceived.jsonValue()},
                "serverTotalMs":${packet.backendTotalMs.jsonValue()},
                "decodePcmMs":${packet.decodePcmMs.jsonValue()},
                "audioStatsMs":${packet.audioStatsMs.jsonValue()},
                "transcriptTotalMs":${packet.transcriptTotalMs.jsonValue()},
                "sttLatencyMs":${packet.sttLatencyMs.jsonValue()},
                "primarySttStatus":${packet.primarySttStatus.jsonValue()},
                "primarySttMs":${packet.primarySttMs.jsonValue()},
                "primarySttProvider":${packet.primarySttProvider.jsonValue()},
                "fallbackSttStatus":${packet.fallbackSttStatus.jsonValue()},
                "fallbackSttMs":${packet.fallbackSttMs.jsonValue()},
                "fallbackSttProvider":${packet.fallbackSttProvider.jsonValue()},
                "sttBudgetRemainingMs":${packet.sttBudgetRemainingMs.jsonValue()},
                "xanderSessionMs":${packet.xanderSessionMs.jsonValue()},
                "xanderFastMs":${packet.xanderFastMs.jsonValue()},
                "xanderFastStatus":${packet.xanderFastStatus.jsonValue()},
                "xanderFastTimedOut":${packet.xanderFastTimedOut.jsonValue()},
                "xanderFallbackSessionStatus":${packet.xanderFallbackSessionStatus.jsonValue()},
                "xanderFallbackSkipped":${packet.xanderFallbackSkipped.jsonValue()},
                "responseBuildMs":${packet.responseBuildMs.jsonValue()}
              },
              "playback":{
                "kind":"${packet.playbackKind.jsonEscape()}",
                "ttsBytes":${packet.ttsBytes.jsonValue()},
                "totalMs":${packet.playbackTotalMs.jsonValue()}
              },
              "perceivedLatency":{
                "ttfaMs":${packet.ttfaMs.jsonValue()},
                "localAckKind":${packet.localAckKind.jsonValue()},
                "postCaptureAckDelayMs":${packet.postCaptureAckDelayMs.jsonValue()},
                "breakdown":{
                  "routeSelectMs":${packet.ttfaRouteSelectMs.jsonValue()},
                  "captureReadMs":${packet.ttfaCaptureReadMs.jsonValue()},
                  "postCaptureDispatchMs":${packet.ttfaPostCaptureDispatchMs.jsonValue()},
                  "backendWaitAfterReleaseMs":${packet.ttfaBackendWaitAfterReleaseMs.jsonValue()}
                },
                "localAckStartMs":${packet.localAckStartMs.jsonValue()},
                "localAckTotalMs":${packet.localAckTotalMs.jsonValue()},
                "assistantPlaybackStartMs":${packet.assistantPlaybackStartMs.jsonValue()},
                "backendResponseReadyMs":${packet.backendResponseReadyMs.jsonValue()}
              },
              "verdict":{
                "provider":${packet.provider.jsonValue()},
                "transcriptSource":${packet.transcriptSource.jsonValue()},
                "sttProvider":${packet.sttProvider.jsonValue()},
                "sttStatus":${packet.sttStatus.jsonValue()},
                "pass1Status":${packet.pass1Status.jsonValue()},
                "pass1Ready":${packet.pass1Ready.jsonValue()},
                "transcriptLength":${packet.transcriptLength.jsonValue()},
                "assistantTextLength":${packet.assistantTextLength.jsonValue()}
              },
              "totals":{
                "turnTotalMs":${packet.turnTotalMs.jsonValue()},
                "routeSelectMs":${packet.routeSelectMs.jsonValue()},
                "routeReleaseMs":${packet.routeReleaseMs.jsonValue()}
              }
            }
        """.trimIndent().replace("\n", "").replace("  ", "")
    }
}

class HttpStreamingVoiceClient(
    endpointUrl: String,
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 60_000
) : StreamingVoiceClient {
    private val streamEndpointUrl = normalizeVoiceStreamEndpoint(endpointUrl)

    override suspend fun sendStreamingVoiceTurn(
        pcm16Mono16k: ByteArray,
        routeEvidence: RouteEvidence
    ): VoiceTurnResult = withContext(Dispatchers.IO) {
        val connection = (URL(streamEndpointUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMillis
            readTimeout = readTimeoutMillis
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/x-ndjson")
        }
        try {
            val totalStarted = System.nanoTime()
            val buildStarted = System.nanoTime()
            val body = buildVoiceTurnRequestBody(pcm16Mono16k, routeEvidence)
            val bodyBytes = body.toByteArray(Charsets.UTF_8)
            val requestBuildMs = elapsedMs(buildStarted)
            val uploadStarted = System.nanoTime()
            connection.outputStream.use { it.write(bodyBytes) }
            val uploadMs = elapsedMs(uploadStarted)
            val responseCodeStarted = System.nanoTime()
            val responseCode = connection.responseCode
            val responseCodeWaitMs = elapsedMs(responseCodeStarted)
            val responseReadStarted = System.nanoTime()
            val responseBody = if (responseCode in 200..299) {
                connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } else {
                val errorBody = connection.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                throw XanderVoiceClientException("Otoxan streaming voice endpoint returned HTTP $responseCode: $errorBody")
            }
            val responseReadMs = elapsedMs(responseReadStarted)
            val parseStarted = System.nanoTime()
            val voiceTurnBody = extractCompletedVoiceTurnJson(responseBody)
            parseVoiceTurnResult(
                responseBody = voiceTurnBody,
                httpStatusCode = responseCode,
                requestBytes = bodyBytes.size,
                responseBytes = responseBody.toByteArray(Charsets.UTF_8).size,
                requestBuildMs = requestBuildMs,
                uploadMs = uploadMs,
                responseCodeWaitMs = responseCodeWaitMs,
                responseReadMs = responseReadMs,
                responseParseMs = elapsedMs(parseStarted),
                clientBackendRoundTripMs = elapsedMs(totalStarted)
            )
        } catch (error: XanderVoiceClientException) {
            throw error
        } catch (error: Exception) {
            throw XanderVoiceClientException("Otoxan streaming voice turn failed: ${error.message ?: error::class.java.simpleName}", error)
        } finally {
            connection.disconnect()
        }
    }
}

class StreamingXanderVoiceClient(
    private val streamingVoiceClient: StreamingVoiceClient,
    private val fallbackClient: XanderVoiceClient
) : XanderVoiceClient {
    override suspend fun sendVoiceTurn(pcm16Mono16k: ByteArray, routeEvidence: RouteEvidence): VoiceTurnResult {
        return streamingVoiceClient.sendStreamingVoiceTurn(pcm16Mono16k, routeEvidence)
    }

    override suspend fun postVoiceTurnMetrics(packet: VoiceTurnTelemetryPacket): VoiceTurnTelemetryResult {
        return fallbackClient.postVoiceTurnMetrics(packet)
    }

    override suspend fun fetchRecentVoiceTurnMetrics(limit: Int): VoiceTurnTelemetryHistoryResult {
        return fallbackClient.fetchRecentVoiceTurnMetrics(limit)
    }
}

class StubXanderVoiceClient : XanderVoiceClient {
    override suspend fun sendVoiceTurn(
        pcm16Mono16k: ByteArray,
        routeEvidence: RouteEvidence
    ): VoiceTurnResult {
        return VoiceTurnResult(
            transcript = "Stub transcript: ${pcm16Mono16k.size} bytes captured through ${routeEvidence.inputName}",
            assistantText = "No Xander endpoint configured. Rebuild with XANDER_VOICE_ENDPOINT to enable the backend turn.",
            provider = "stub",
            transcriptSource = "stub",
            sttProvider = "stub",
            sttStatus = "not-run",
            pass1Status = "stub-not-real-speech",
            pass1Ready = false
        )
    }

    override suspend fun postVoiceTurnMetrics(packet: VoiceTurnTelemetryPacket): VoiceTurnTelemetryResult {
        return VoiceTurnTelemetryResult(ok = true, recordId = "stub")
    }

    override suspend fun fetchRecentVoiceTurnMetrics(limit: Int): VoiceTurnTelemetryHistoryResult {
        return VoiceTurnTelemetryHistoryResult(ok = true, count = 0, records = emptyList())
    }
}

fun createXanderVoiceClient(
    endpointUrl: String,
    endpointPolicy: XanderVoiceEndpointPolicy = XanderVoiceEndpointPolicy(),
    streamingConfig: StreamingVoiceClientConfig = StreamingVoiceClientConfig()
): XanderVoiceClient {
    return if (endpointUrl.isBlank()) {
        StubXanderVoiceClient()
    } else if (streamingConfig.enabled) {
        val fallbackClient = HttpXanderVoiceClient(
            endpointUrl = normalizeVoiceTurnEndpoint(endpointUrl),
            connectTimeoutMillis = endpointPolicy.connectTimeoutMillis,
            readTimeoutMillis = endpointPolicy.readTimeoutMillis,
            metricsTimeoutMillis = endpointPolicy.metricsTimeoutMillis
        )
        StreamingXanderVoiceClient(
            streamingVoiceClient = HttpStreamingVoiceClient(
                endpointUrl = streamingConfig.endpointUrl.ifBlank { endpointUrl },
                connectTimeoutMillis = endpointPolicy.connectTimeoutMillis,
                readTimeoutMillis = endpointPolicy.readTimeoutMillis
            ),
            fallbackClient = fallbackClient
        )
    } else {
        HttpXanderVoiceClient(
            endpointUrl = normalizeVoiceTurnEndpoint(endpointUrl),
            connectTimeoutMillis = endpointPolicy.connectTimeoutMillis,
            readTimeoutMillis = endpointPolicy.readTimeoutMillis,
            metricsTimeoutMillis = endpointPolicy.metricsTimeoutMillis
        )
    }
}

private fun buildVoiceTurnRequestBody(pcm16Mono16k: ByteArray, routeEvidence: RouteEvidence): String {
    return """
        {
          "format":"pcm_s16le_16khz_mono",
          "pcm16Mono16kBase64":"${Base64.getEncoder().encodeToString(pcm16Mono16k)}",
          "routeEvidence":{
            "inputName":"${routeEvidence.inputName.jsonEscape()}",
            "inputType":"${routeEvidence.inputType.jsonEscape()}",
            "outputName":"${routeEvidence.outputName.jsonEscape()}",
            "outputType":"${routeEvidence.outputType.jsonEscape()}",
            "wearableActive":${routeEvidence.wearableActive},
            "message":"${routeEvidence.message.jsonEscape()}"
          }
        }
    """.trimIndent().replace("\n", "").replace("  ", "")
}

private fun parseVoiceTurnResult(
    responseBody: String,
    httpStatusCode: Int,
    requestBytes: Int,
    responseBytes: Int,
    requestBuildMs: Long,
    uploadMs: Long,
    responseCodeWaitMs: Long,
    responseReadMs: Long,
    responseParseMs: Long,
    clientBackendRoundTripMs: Long
): VoiceTurnResult {
    val ttsPcm = responseBody.optionalJsonString("ttsPcm16Mono16kBase64")?.let { decodeTtsPcm(it) }
    return VoiceTurnResult(
        transcript = responseBody.requiredJsonString("transcript"),
        assistantText = responseBody.requiredJsonString("assistantText"),
        ttsPcm16Mono16k = ttsPcm,
        bytesReceived = responseBody.optionalJsonInt("bytesReceived"),
        provider = responseBody.optionalJsonString("provider"),
        transcriptSource = responseBody.optionalJsonString("transcriptSource"),
        sttProvider = responseBody.optionalJsonString("sttProvider"),
        sttStatus = responseBody.optionalJsonString("sttStatus"),
        sttLatencyMs = responseBody.optionalJsonInt("sttLatencyMs"),
        primarySttStatus = responseBody.optionalJsonString("primarySttStatus"),
        primarySttMs = responseBody.optionalJsonInt("primarySttMs"),
        primarySttProvider = responseBody.optionalJsonString("primarySttProvider"),
        fallbackSttStatus = responseBody.optionalJsonString("fallbackSttStatus"),
        fallbackSttMs = responseBody.optionalJsonInt("fallbackSttMs"),
        fallbackSttProvider = responseBody.optionalJsonString("fallbackSttProvider"),
        sttBudgetRemainingMs = responseBody.optionalJsonInt("sttBudgetRemainingMs"),
        pass1Status = responseBody.optionalJsonString("pass1Status"),
        pass1Ready = responseBody.optionalJsonBoolean("pass1Ready"),
        audioFormat = responseBody.optionalJsonString("audioFormat"),
        audioDurationMs = responseBody.optionalJsonInt("durationMs"),
        audioPeak = responseBody.optionalJsonInt("peak"),
        audioRms = responseBody.optionalJsonDouble("rms"),
        backendTotalMs = responseBody.optionalJsonInt("backendTotalMs"),
        decodePcmMs = responseBody.optionalJsonInt("decodePcmMs"),
        audioStatsMs = responseBody.optionalJsonInt("audioStatsMs"),
        transcriptTotalMs = responseBody.optionalJsonInt("transcriptTotalMs"),
        xanderSessionMs = responseBody.optionalJsonInt("xanderSessionMs"),
        xanderFastMs = responseBody.optionalJsonInt("xanderFastMs"),
        xanderFastStatus = responseBody.optionalJsonInt("xanderFastStatus"),
        xanderFastTimedOut = responseBody.optionalJsonInt("xanderFastTimedOut"),
        xanderFallbackSessionStatus = responseBody.optionalJsonInt("xanderFallbackSessionStatus"),
        xanderFallbackSkipped = responseBody.optionalJsonInt("xanderFallbackSkipped"),
        responseBuildMs = responseBody.optionalJsonInt("responseBuildMs"),
        httpStatusCode = httpStatusCode,
        requestBytes = requestBytes,
        responseBytes = responseBytes,
        requestBuildMs = requestBuildMs,
        uploadMs = uploadMs,
        responseCodeWaitMs = responseCodeWaitMs,
        responseReadMs = responseReadMs,
        responseParseMs = responseParseMs,
        clientBackendRoundTripMs = clientBackendRoundTripMs
    )
}

private fun extractCompletedVoiceTurnJson(ndjson: String): String {
    ndjson.lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .forEach { line ->
            val event = JSONObject(line)
            if (event.optString("type") == "response.completed") {
                val voiceTurn = event.optJSONObject("voiceTurn")
                    ?: throw XanderVoiceClientException("Otoxan stream response.completed missing voiceTurn")
                return voiceTurn.toString()
            }
        }
    throw XanderVoiceClientException("Otoxan stream ended without response.completed voiceTurn event")
}

fun normalizeVoiceTurnEndpoint(endpointUrl: String): String {
    val trimmed = endpointUrl.trim().trimEnd('/')
    if (trimmed.isBlank()) return ""
    return if (trimmed.endsWith("/voice-turn")) trimmed else "$trimmed/voice-turn"
}

fun normalizeVoiceStreamEndpoint(endpointUrl: String): String {
    val trimmed = endpointUrl.trim().trimEnd('/')
    if (trimmed.isBlank()) return ""
    return if (trimmed.endsWith("/voice-stream")) {
        trimmed
    } else if (trimmed.endsWith("/voice-turn")) {
        trimmed.removeSuffix("/voice-turn") + "/voice-stream"
    } else {
        "$trimmed/voice-stream"
    }
}


private fun JSONObject?.optStringOrNull(name: String): String? {
    if (this == null || isNull(name)) return null
    return optString(name).takeIf { it.isNotBlank() }
}

private fun JSONObject?.optLongOrNull(name: String): Long? {
    if (this == null || isNull(name)) return null
    return optLong(name)
}

private fun JSONObject?.optIntOrNull(name: String): Int? {
    if (this == null || isNull(name)) return null
    return optInt(name)
}

private fun String.requiredJsonString(fieldName: String): String {
    return optionalJsonString(fieldName)
        ?: throw XanderVoiceClientException("Xander voice response missing '$fieldName'")
}

private fun String.optionalJsonString(fieldName: String): String? {
    val regex = Regex("\\\"${Regex.escape(fieldName)}\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"")
    return regex.find(this)?.groupValues?.get(1)?.jsonUnescape()
}

private fun String.optionalJsonInt(fieldName: String): Int? {
    val regex = Regex("\\\"${Regex.escape(fieldName)}\\\"\\s*:\\s*(-?\\d+)")
    return regex.find(this)?.groupValues?.get(1)?.toIntOrNull()
}

private fun String.optionalJsonDouble(fieldName: String): Double? {
    val regex = Regex("\\\"${Regex.escape(fieldName)}\\\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)")
    return regex.find(this)?.groupValues?.get(1)?.toDoubleOrNull()
}

private fun String.optionalJsonBoolean(fieldName: String): Boolean? {
    val regex = Regex("\\\"${Regex.escape(fieldName)}\\\"\\s*:\\s*(true|false)")
    return regex.find(this)?.groupValues?.get(1)?.toBooleanStrictOrNull()
}

private fun elapsedMs(startedNanos: Long): Long = ((System.nanoTime() - startedNanos) / 1_000_000L).coerceAtLeast(0L)

private fun decodeTtsPcm(encoded: String): ByteArray {
    return try {
        Base64.getDecoder().decode(encoded)
    } catch (error: IllegalArgumentException) {
        throw XanderVoiceClientException("Xander voice response field 'ttsPcm16Mono16kBase64' is not valid base64", error)
    }
}

private fun String?.jsonValue(): String = this?.let { "\"${it.jsonEscape()}\"" } ?: "null"

private fun Number?.jsonValue(): String = this?.toString() ?: "null"

private fun Boolean?.jsonValue(): String = this?.toString() ?: "null"

private fun String.jsonEscape(): String = buildString {
    for (char in this@jsonEscape) {
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(char)
        }
    }
}

private fun String.jsonUnescape(): String = buildString {
    var index = 0
    while (index < this@jsonUnescape.length) {
        val char = this@jsonUnescape[index]
        if (char == '\\' && index + 1 < this@jsonUnescape.length) {
            when (val escaped = this@jsonUnescape[index + 1]) {
                'n' -> append('\n')
                'r' -> append('\r')
                't' -> append('\t')
                '\\' -> append('\\')
                '"' -> append('"')
                'u' -> {
                    val hexStart = index + 2
                    val hexEnd = index + 6
                    if (hexEnd <= this@jsonUnescape.length) {
                        val codePoint = this@jsonUnescape.substring(hexStart, hexEnd).toIntOrNull(radix = 16)
                        if (codePoint != null) {
                            append(codePoint.toChar())
                            index += 6
                            continue
                        }
                    }
                    append('u')
                }
                else -> append(escaped)
            }
            index += 2
        } else {
            append(char)
            index += 1
        }
    }
}
