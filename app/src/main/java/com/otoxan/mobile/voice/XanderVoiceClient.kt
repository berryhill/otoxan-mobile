package com.otoxan.mobile.voice

interface XanderVoiceClient {
    suspend fun sendVoiceTurn(pcm16Mono16k: ByteArray, routeEvidence: RouteEvidence): VoiceTurnResult
}

data class VoiceTurnResult(
    val transcript: String,
    val assistantText: String,
    val ttsPcm16Mono16k: ByteArray? = null
)

class StubXanderVoiceClient : XanderVoiceClient {
    override suspend fun sendVoiceTurn(
        pcm16Mono16k: ByteArray,
        routeEvidence: RouteEvidence
    ): VoiceTurnResult {
        return VoiceTurnResult(
            transcript = "Stub transcript: ${pcm16Mono16k.size} bytes captured through ${routeEvidence.inputName}",
            assistantText = "Route proof received. Xander backend integration is next."
        )
    }
}
