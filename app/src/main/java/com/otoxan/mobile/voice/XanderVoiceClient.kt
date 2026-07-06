package com.otoxan.mobile.voice

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface XanderVoiceClient {
    suspend fun sendVoiceTurn(pcm16Mono16k: ByteArray, routeEvidence: RouteEvidence): VoiceTurnResult
}

data class VoiceTurnResult(
    val transcript: String,
    val assistantText: String,
    val ttsPcm16Mono16k: ByteArray? = null,
    val bytesReceived: Int? = null,
    val provider: String? = null,
    val transcriptSource: String? = null,
    val sttStatus: String? = null,
    val sttLatencyMs: Int? = null,
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

class HttpXanderVoiceClient(
    private val endpointUrl: String,
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 60_000
) : XanderVoiceClient {
    override suspend fun sendVoiceTurn(
        pcm16Mono16k: ByteArray,
        routeEvidence: RouteEvidence
    ): VoiceTurnResult = withContext(Dispatchers.IO) {
        val connection = (URL(endpointUrl).openConnection() as HttpURLConnection).apply {
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
            val body = buildRequestBody(pcm16Mono16k, routeEvidence)
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
            val ttsPcm = responseBody.optionalJsonString("ttsPcm16Mono16kBase64")?.let {
                decodeTtsPcm(it)
            }
            val result = VoiceTurnResult(
                transcript = responseBody.requiredJsonString("transcript"),
                assistantText = responseBody.requiredJsonString("assistantText"),
                ttsPcm16Mono16k = ttsPcm,
                bytesReceived = responseBody.optionalJsonInt("bytesReceived"),
                provider = responseBody.optionalJsonString("provider"),
                transcriptSource = responseBody.optionalJsonString("transcriptSource"),
                sttStatus = responseBody.optionalJsonString("sttStatus"),
                sttLatencyMs = responseBody.optionalJsonInt("sttLatencyMs"),
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
                responseBuildMs = responseBody.optionalJsonInt("responseBuildMs"),
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

    private fun buildRequestBody(pcm16Mono16k: ByteArray, routeEvidence: RouteEvidence): String {
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
            sttStatus = "not-run",
            pass1Status = "stub-not-real-speech",
            pass1Ready = false
        )
    }
}

fun createXanderVoiceClient(endpointUrl: String): XanderVoiceClient {
    return if (endpointUrl.isBlank()) {
        StubXanderVoiceClient()
    } else {
        HttpXanderVoiceClient(endpointUrl = endpointUrl)
    }
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
