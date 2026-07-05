package com.otoxan.mobile.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

class SpeechPlayback {
    fun playProofTone() {
        val sampleRate = 16_000
        val seconds = 1
        val samples = ShortArray(sampleRate * seconds)
        for (i in samples.indices) {
            samples[i] = (sin(2.0 * PI * 440.0 * i / sampleRate) * Short.MAX_VALUE * 0.25).toInt().toShort()
        }

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(samples.size * 2)
            .build()

        try {
            track.play()
            track.write(samples, 0, samples.size)
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }
}
