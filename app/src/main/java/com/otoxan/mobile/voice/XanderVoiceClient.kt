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
    val ttsPcm16Mono16k: ByteArray? = null
)

class XanderVoiceClientException(message: String, cause: Throwable? = null) : IOException(message, cause)

class HttpXanderVoiceClient(
    private val endpointUrl: String,
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 30_000
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
            val body = buildRequestBody(pcm16Mono16k, routeEvidence)
            connection.outputStream.use { output ->
                output.write(body.toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            val responseBody = if (responseCode in 200..299) {
                connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } else {
                val errorBody = connection.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                throw XanderVoiceClientException("Xander voice endpoint returned HTTP $responseCode: $errorBody")
            }

            VoiceTurnResult(
                transcript = responseBody.requiredJsonString("transcript"),
                assistantText = responseBody.requiredJsonString("assistantText"),
                ttsPcm16Mono16k = responseBody.optionalJsonString("ttsPcm16Mono16kBase64")?.let {
                    Base64.getDecoder().decode(it)
                }
            )
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
            assistantText = "No Xander endpoint configured. Set XANDER_VOICE_ENDPOINT to enable the backend turn."
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
                else -> append(escaped)
            }
            index += 2
        } else {
            append(char)
            index += 1
        }
    }
}
