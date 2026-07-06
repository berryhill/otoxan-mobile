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
