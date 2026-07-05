package com.otoxan.mobile.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

class SpeechPlayback {
    fun playProofTone() {
        val sampleRate = SAMPLE_RATE_16K
        val seconds = 1
        val samples = ShortArray(sampleRate * seconds)
        for (i in samples.indices) {
            samples[i] = (sin(2.0 * PI * 660.0 * i / sampleRate) * Short.MAX_VALUE * 0.45).toInt().toShort()
        }
        playPcm16Mono16k(samples.toLittleEndianPcm16())
    }

    fun playPcm16Mono16k(pcm16Mono16k: ByteArray) {
        require(pcm16Mono16k.isNotEmpty()) { "PCM playback buffer is empty" }
        require(pcm16Mono16k.size % BYTES_PER_PCM16_MONO_FRAME == 0) { "PCM playback buffer must be 16-bit aligned" }

        val audioFormat = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE_16K)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE_16K,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = max(pcm16Mono16k.size, minBuffer)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        try {
            track.setVolume(AudioTrack.getMaxVolume())
            track.play()
            var written = 0
            while (written < pcm16Mono16k.size) {
                val count = track.write(
                    pcm16Mono16k,
                    written,
                    pcm16Mono16k.size - written,
                    AudioTrack.WRITE_BLOCKING
                )
                if (count <= 0) error("AudioTrack.write failed with code $count")
                written += count
            }
            waitForQueuedAudio(track, pcm16Mono16k.size / BYTES_PER_PCM16_MONO_FRAME)
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }
}

private const val SAMPLE_RATE_16K = 16_000
private const val BYTES_PER_PCM16_MONO_FRAME = 2

private fun waitForQueuedAudio(track: AudioTrack, targetFrames: Int) {
    val expectedMillis = (targetFrames * 1_000L) / SAMPLE_RATE_16K
    val deadline = System.currentTimeMillis() + expectedMillis + 750L
    while (System.currentTimeMillis() < deadline) {
        if (track.playbackHeadPosition >= targetFrames) return
        Thread.sleep(20L)
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
