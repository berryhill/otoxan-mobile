package com.otoxan.mobile.voice

import android.media.AudioAttributes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechPlaybackRoutePolicyTest {
    @Test
    fun playbackDoesNotUseVoiceCommunicationUsage() {
        val policy = playbackRoutePolicy()

        assertNotEquals(AudioAttributes.USAGE_VOICE_COMMUNICATION, policy.usage)
        assertEquals(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY, policy.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH, policy.contentType)
    }

    @Test
    fun ackEarconPcm_isShortAlignedAndBounded() {
        val pcm = ackEarconPcm16Mono16k()

        assertTrue(pcm.isNotEmpty())
        assertEquals(0, pcm.size % 2)
        assertTrue(pcm.size in 3_000..4_000)
        val peak = pcm.asIterable()
            .chunked(2) { bytes ->
                (((bytes[1].toInt() and 0xFF) shl 8) or (bytes[0].toInt() and 0xFF)).toShort().toInt()
            }
            .maxOf { kotlin.math.abs(it) }
        assertTrue("ack peak should stay below 30%", peak <= (Short.MAX_VALUE * 0.30).toInt())
    }
}
