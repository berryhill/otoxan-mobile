package com.otoxan.mobile.voice

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
}
