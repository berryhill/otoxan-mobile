package com.otoxan.mobile.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import kotlin.math.abs

class MicCapture {
    @SuppressLint("MissingPermission")
    fun recordPcmForMillis(durationMillis: Long = 5_000): ByteArray {
        return recordPcmUntilSpeechSilence(
            VoiceCaptureConfig(
                maxMillis = durationMillis,
                minMillis = durationMillis,
                silenceAfterSpeechMillis = durationMillis,
                chunkMillis = 100
            )
        ).pcm16Mono16k
    }

    @SuppressLint("MissingPermission")
    fun recordPcmUntilSpeechSilence(
        config: VoiceCaptureConfig = VoiceCaptureConfig(),
        onChunkPeak: (peak: Int, capturedMillis: Long, speechDetected: Boolean) -> Unit = { _, _, _ -> }
    ): VoiceCaptureResult {
        require(config.maxMillis >= config.minMillis) { "maxMillis must be >= minMillis" }
        require(config.chunkMillis in 20..500) { "chunkMillis must be between 20 and 500" }
        val maxBytes = expectedPcmBytes(config.maxMillis)
        val chunkBytes = expectedPcmBytes(config.chunkMillis)
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        require(minBuffer > 0) { "AudioRecord minimum buffer unavailable: $minBuffer" }

        val recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuffer * 2, chunkBytes * 2))
            .build()

        require(recorder.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord failed to initialize" }

        val output = ByteArrayOutputStream(maxBytes)
        val chunk = ByteArray(chunkBytes)
        var stopReason = "max_duration"
        var speechStarted = false
        var lastSpeechMs = 0L
        try {
            recorder.startRecording()
            require(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "AudioRecord did not enter recording state" }
            while (output.size() < maxBytes) {
                val bytesRemaining = maxBytes - output.size()
                val read = recorder.read(chunk, 0, minOf(chunk.size, bytesRemaining))
                if (read <= 0) {
                    stopReason = "read_end"
                    break
                }
                output.write(chunk, 0, read)
                val capturedMs = pcmBytesToMillis(output.size())
                val chunkPeak = chunk.peakPcm16Amplitude(read)
                if (chunkPeak >= config.speechPeakAmplitude) {
                    speechStarted = true
                    lastSpeechMs = capturedMs
                }
                onChunkPeak(chunkPeak, capturedMs, speechStarted)
                if (
                    capturedMs >= config.minMillis &&
                    speechStarted &&
                    capturedMs - lastSpeechMs >= config.silenceAfterSpeechMillis
                ) {
                    stopReason = "speech_silence"
                    break
                }
            }
            val pcm = output.toByteArray()
            return VoiceCaptureResult(
                pcm16Mono16k = pcm,
                maxCaptureMillis = config.maxMillis,
                minCaptureMillis = config.minMillis,
                actualCapturedMillis = pcmBytesToMillis(pcm.size),
                stopReason = stopReason,
                speechDetected = speechStarted
            )
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }
}

data class VoiceCaptureConfig(
    val maxMillis: Long = 5_000,
    val minMillis: Long = 1_200,
    val silenceAfterSpeechMillis: Long = 900,
    val speechPeakAmplitude: Int = 256,
    val chunkMillis: Long = 100
)

fun conversationVoiceCaptureConfig(): VoiceCaptureConfig {
    return VoiceCaptureConfig(
        maxMillis = 12_000,
        minMillis = 700,
        silenceAfterSpeechMillis = 850,
        speechPeakAmplitude = 900,
        chunkMillis = 100
    )
}

data class VoiceCaptureResult(
    val pcm16Mono16k: ByteArray,
    val maxCaptureMillis: Long,
    val minCaptureMillis: Long,
    val actualCapturedMillis: Long,
    val stopReason: String,
    val speechDetected: Boolean
)

private const val SAMPLE_RATE = 16_000
private const val BYTES_PER_SAMPLE = 2

fun expectedPcmBytes(durationMillis: Long): Int {
    return (SAMPLE_RATE * BYTES_PER_SAMPLE * durationMillis / 1_000).toInt()
}

fun pcmBytesToMillis(byteCount: Int): Long {
    return byteCount.toLong() * 1_000 / (SAMPLE_RATE * BYTES_PER_SAMPLE)
}

fun shouldSubmitVoiceTurn(
    capture: VoiceCaptureResult,
    minimumBytes: Int,
    requireSpeechDetected: Boolean
): Boolean {
    if (requireSpeechDetected && !capture.speechDetected) return false
    return isUsableVoiceCapture(capture.pcm16Mono16k, minimumBytes)
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

fun ByteArray.peakPcm16Amplitude(limit: Int = size): Int {
    var peak = 0
    var index = 0
    val safeLimit = minOf(limit, size)
    while (index + 1 < safeLimit) {
        val low = this[index].toInt() and 0xFF
        val high = this[index + 1].toInt()
        val sample = ((high shl 8) or low).toShort().toInt()
        peak = maxOf(peak, abs(sample))
        index += 2
    }
    return peak
}
