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
        playPcm16Mono16k(samples.toLittleEndianPcm16())
    }

    fun playPcm16Mono16k(pcm16Mono16k: ByteArray) {
        val sampleRate = 16_000
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
            .setBufferSizeInBytes(pcm16Mono16k.size)
            .build()

        try {
            track.play()
            track.write(pcm16Mono16k, 0, pcm16Mono16k.size)
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }
}

private fun ShortArray.toLittleEndianPcm16(): ByteArray {
    val bytes = ByteArray(size * 2)
    forEachIndexed { index, sample ->
        val value = sample.toInt()
        bytes[index * 2] = (value and 0xFF).toByte()
        bytes[index * 2 + 1] = ((value ushr 8) and 0xFF).toByte()
    }
    return bytes
}
