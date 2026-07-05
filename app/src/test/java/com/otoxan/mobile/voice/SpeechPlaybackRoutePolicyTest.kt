package com.otoxan.mobile.voice

import android.media.AudioAttributes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SpeechPlaybackRoutePolicyTest {
    @Test
    fun playbackDoesNotUseVoiceCommunicationUsage() {
        val policy = playbackRoutePolicy()

        assertNotEquals(AudioAttributes.USAGE_VOICE_COMMUNICATION, policy.usage)
        assertEquals(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY, policy.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH, policy.contentType)
    }
}
