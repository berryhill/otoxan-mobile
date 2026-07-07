package com.otoxan.mobile.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmCaptureStatsTest {
    @Test
    fun isUsableCapture_rejectsEmptyZeroAndVeryShortPcm() {
        assertFalse(isUsableVoiceCapture(ByteArray(0), expectedBytes = 160_000))
        assertFalse(isUsableVoiceCapture(ByteArray(160_000), expectedBytes = 160_000))
        assertFalse(isUsableVoiceCapture(ByteArray(100), expectedBytes = 160_000))
    }

    @Test
    fun isUsableCapture_acceptsLongNonSilentPcm() {
        val pcm = ByteArray(160_000)
        for (index in pcm.indices step 2) {
            pcm[index] = 0x00
            pcm[index + 1] = 0x20
        }

        assertTrue(isUsableVoiceCapture(pcm, expectedBytes = 160_000))
    }

    @Test
    fun shouldSubmitVoiceTurn_rejectsIdleConversationCaptureEvenWithRouteNoise() {
        val pcm = ByteArray(160_000)
        for (index in pcm.indices step 2) {
            pcm[index] = 0x00
            pcm[index + 1] = 0x02
        }
        val capture = VoiceCaptureResult(
            pcm16Mono16k = pcm,
            maxCaptureMillis = 5_000,
            minCaptureMillis = 1_200,
            actualCapturedMillis = 5_000,
            stopReason = "max_duration",
            speechDetected = false
        )

        assertFalse(shouldSubmitVoiceTurn(capture, minimumBytes = expectedPcmBytes(1_200), requireSpeechDetected = true))
        assertTrue(shouldSubmitVoiceTurn(capture, minimumBytes = expectedPcmBytes(1_200), requireSpeechDetected = false))
    }

    @Test
    fun shouldSubmitVoiceTurn_rejectsPersistentNoSpeechWindowWithoutStoppingSession() {
        val capture = VoiceCaptureResult(
            pcm16Mono16k = ByteArray(0),
            maxCaptureMillis = 12_000,
            minCaptureMillis = 700,
            actualCapturedMillis = 12_000,
            stopReason = "no_speech",
            speechDetected = false
        )

        assertFalse(shouldSubmitVoiceTurn(capture, minimumBytes = expectedPcmBytes(700), requireSpeechDetected = true))
        assertEquals("no_speech", capture.stopReason)
    }

    @Test
    fun conversationVoiceCaptureConfig_extendsIdleListenAndRaisesSpeechThreshold() {
        val config = conversationVoiceCaptureConfig()

        assertEquals(12_000, config.maxMillis)
        assertEquals(700, config.minMillis)
        assertEquals(450, config.silenceAfterSpeechMillis)
        assertEquals(900, config.speechPeakAmplitude)
    }

    @Test
    fun conversationVoiceCaptureConfig_ignoresTuningUntilEvidenceGateIsEnabled() {
        val config = conversationVoiceCaptureConfig(
            ConversationCaptureTuning(
                evidenceGateEnabled = false,
                maxMillis = 20_000,
                minMillis = 1_500,
                silenceAfterSpeechMillis = 1_200,
                speechPeakAmplitude = 2_500,
                chunkMillis = 200
            )
        )

        assertEquals(12_000, config.maxMillis)
        assertEquals(700, config.minMillis)
        assertEquals(450, config.silenceAfterSpeechMillis)
        assertEquals(900, config.speechPeakAmplitude)
        assertEquals(100, config.chunkMillis)
    }

    @Test
    fun conversationVoiceCaptureConfig_appliesBoundedTuningBehindEvidenceGate() {
        val config = conversationVoiceCaptureConfig(
            ConversationCaptureTuning(
                evidenceGateEnabled = true,
                maxMillis = 15_000,
                minMillis = 900,
                silenceAfterSpeechMillis = 650,
                speechPeakAmplitude = 1_100,
                chunkMillis = 50
            )
        )

        assertEquals(15_000, config.maxMillis)
        assertEquals(900, config.minMillis)
        assertEquals(650, config.silenceAfterSpeechMillis)
        assertEquals(1_100, config.speechPeakAmplitude)
        assertEquals(50, config.chunkMillis)
    }

    @Test
    fun pcmMillisConversion_usesMono16kPcmContract() {
        assertEquals(160_000, expectedPcmBytes(5_000))
        assertEquals(1_200, pcmBytesToMillis(expectedPcmBytes(1_200)))
    }

    @Test
    fun peakAmplitude_respectsReadLimitForReusableCaptureChunks() {
        val pcm = ByteArray(8)
        pcm[0] = 0x00
        pcm[1] = 0x01
        pcm[2] = 0x00
        pcm[3] = 0x02
        pcm[4] = 0x00
        pcm[5] = 0x7F

        assertEquals(512, pcm.peakPcm16Amplitude(limit = 4))
        assertEquals(32512, pcm.peakPcm16Amplitude())
    }
}
