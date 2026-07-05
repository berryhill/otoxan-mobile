package com.otoxan.mobile.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.abs

class MicCapture {
    @SuppressLint("MissingPermission")
    fun recordPcmForMillis(durationMillis: Long = 5_000): ByteArray {
        val sampleRate = 16_000
        val bytesPerSample = 2
        val expectedBytes = expectedPcmBytes(durationMillis)
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        require(minBuffer > 0) { "AudioRecord minimum buffer unavailable: $minBuffer" }

        val recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(minBuffer * 2)
            .build()

        require(recorder.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord failed to initialize" }

        val output = ByteArray(expectedBytes)
        try {
            recorder.startRecording()
            require(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "AudioRecord did not enter recording state" }
            var offset = 0
            while (offset < output.size) {
                val read = recorder.read(output, offset, output.size - offset)
                if (read <= 0) break
                offset += read
            }
            return output.copyOf(offset)
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }
}

fun expectedPcmBytes(durationMillis: Long): Int {
    val sampleRate = 16_000
    val bytesPerSample = 2
    return (sampleRate * bytesPerSample * durationMillis / 1_000).toInt()
}

fun isUsableVoiceCapture(
    pcm16Mono16k: ByteArray,
    expectedBytes: Int,
    minimumExpectedRatio: Double = 0.8,
    minimumPeakAmplitude: Int = 128
): Boolean {
    if (pcm16Mono16k.size < (expectedBytes * minimumExpectedRatio).toInt()) return false
    if (pcm16Mono16k.size < 2 || pcm16Mono16k.size % 2 != 0) return false
    return pcm16Mono16k.peakPcm16Amplitude() >= minimumPeakAmplitude
}

fun ByteArray.peakPcm16Amplitude(): Int {
    var peak = 0
    var index = 0
    while (index + 1 < size) {
        val low = this[index].toInt() and 0xFF
        val high = this[index + 1].toInt()
        val sample = ((high shl 8) or low).toShort().toInt()
        peak = maxOf(peak, abs(sample))
        index += 2
    }
    return peak
}
