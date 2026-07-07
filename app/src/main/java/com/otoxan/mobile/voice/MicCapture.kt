package com.otoxan.mobile.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import kotlin.math.abs

interface VoiceCaptureSource {
    fun recordPcmForMillis(durationMillis: Long = 5_000): ByteArray
    fun recordPcmUntilSpeechSilence(
        config: VoiceCaptureConfig = VoiceCaptureConfig(),
        onChunkPeak: (peak: Int, capturedMillis: Long, speechDetected: Boolean) -> Unit = { _, _, _ -> }
    ): VoiceCaptureResult
}

class MicCapture : VoiceCaptureSource {
    @SuppressLint("MissingPermission")
    override fun recordPcmForMillis(durationMillis: Long): ByteArray {
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
    override fun recordPcmUntilSpeechSilence(
        config: VoiceCaptureConfig,
        onChunkPeak: (peak: Int, capturedMillis: Long, speechDetected: Boolean) -> Unit
    ): VoiceCaptureResult {
        validateCaptureConfig(config)
        val maxBytes = expectedPcmBytes(config.maxMillis)
        val chunkBytes = expectedPcmBytes(config.chunkMillis)
        val recorder = createRecorder(chunkBytes)

        val output = ByteArrayOutputStream(maxBytes)
        val chunk = ByteArray(chunkBytes)
        var stopReason = "max_duration"
        var speechStarted = false
        var lastSpeechMs = 0L
        var firstSpeechMs: Long? = null
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
                    if (firstSpeechMs == null) firstSpeechMs = capturedMs
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
                speechDetected = speechStarted,
                firstSpeechDetectedMillis = firstSpeechMs,
                lastSpeechDetectedMillis = if (speechStarted) lastSpeechMs else null
            )
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }

    @SuppressLint("MissingPermission")
    fun openPersistentConversationRecorder(
        config: VoiceCaptureConfig = conversationVoiceCaptureConfig()
    ): PersistentConversationRecorder {
        validateCaptureConfig(config)
        return PersistentConversationRecorder(config, createRecorder(expectedPcmBytes(config.chunkMillis)))
    }

    private fun validateCaptureConfig(config: VoiceCaptureConfig) {
        require(config.maxMillis >= config.minMillis) { "maxMillis must be >= minMillis" }
        require(config.chunkMillis in 20..500) { "chunkMillis must be between 20 and 500" }
    }

    private fun createRecorder(chunkBytes: Int): AudioRecord {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        require(minBuffer > 0) { "AudioRecord minimum buffer unavailable: $minBuffer" }

        return AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuffer * 2, chunkBytes * 4))
            .build()
            .also { recorder ->
                require(recorder.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord failed to initialize" }
            }
    }
}

class PersistentConversationRecorder internal constructor(
    private val config: VoiceCaptureConfig,
    private val recorder: AudioRecord
) : AutoCloseable {
    private val chunkBytes = expectedPcmBytes(config.chunkMillis)
    private val chunk = ByteArray(chunkBytes)
    private var closed = false

    init {
        recorder.startRecording()
        require(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "AudioRecord did not enter recording state" }
    }

    fun captureNextUtterance(
        keepListening: () -> Boolean,
        onChunkPeak: (peak: Int, capturedMillis: Long, speechDetected: Boolean) -> Unit = { _, _, _ -> }
    ): VoiceCaptureResult {
        require(!closed) { "Persistent conversation recorder is closed" }
        val output = ByteArrayOutputStream(expectedPcmBytes(config.maxMillis))
        var stopReason = "max_duration"
        var speechStarted = false
        var lastSpeechMs = 0L
        var firstSpeechMs: Long? = null
        var listenedMs = 0L
        var utteranceMs = 0L

        while (keepListening() && listenedMs < config.maxMillis) {
            val read = recorder.read(chunk, 0, chunk.size)
            if (read <= 0) {
                stopReason = "read_end"
                break
            }
            val chunkMs = pcmBytesToMillis(read)
            listenedMs += chunkMs
            val chunkPeak = chunk.peakPcm16Amplitude(read)
            if (chunkPeak >= config.speechPeakAmplitude) {
                if (firstSpeechMs == null) firstSpeechMs = listenedMs
                speechStarted = true
                lastSpeechMs = utteranceMs + chunkMs
            }
            if (speechStarted) {
                output.write(chunk, 0, read)
                utteranceMs = pcmBytesToMillis(output.size())
            }
            onChunkPeak(chunkPeak, if (speechStarted) utteranceMs else listenedMs, speechStarted)
            if (
                speechStarted &&
                utteranceMs >= config.minMillis &&
                utteranceMs - lastSpeechMs >= config.silenceAfterSpeechMillis
            ) {
                stopReason = "speech_silence"
                break
            }
        }

        if (!keepListening()) {
            stopReason = "cancelled"
        } else if (!speechStarted && listenedMs >= config.maxMillis) {
            stopReason = "no_speech"
        }

        val pcm = output.toByteArray()
        return VoiceCaptureResult(
            pcm16Mono16k = pcm,
            maxCaptureMillis = config.maxMillis,
            minCaptureMillis = config.minMillis,
            actualCapturedMillis = if (speechStarted) pcmBytesToMillis(pcm.size) else listenedMs,
            stopReason = stopReason,
            speechDetected = speechStarted,
            firstSpeechDetectedMillis = firstSpeechMs,
            lastSpeechDetectedMillis = if (speechStarted) lastSpeechMs else null
        )
    }

    fun drainBufferedAudio(durationMillis: Long = 250) {
        require(!closed) { "Persistent conversation recorder is closed" }
        val drainUntil = System.nanoTime() + durationMillis * 1_000_000L
        while (System.nanoTime() < drainUntil) {
            val read = recorder.read(chunk, 0, chunk.size, AudioRecord.READ_NON_BLOCKING)
            if (read <= 0) break
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { recorder.stop() }
        recorder.release()
    }
}

data class VoiceCaptureConfig(
    val maxMillis: Long = 5_000,
    val minMillis: Long = 1_200,
    val silenceAfterSpeechMillis: Long = 900,
    val speechPeakAmplitude: Int = 256,
    val chunkMillis: Long = 100
)

data class ConversationCaptureTuning(
    val evidenceGateEnabled: Boolean = false,
    val maxMillis: Int = 12_000,
    val minMillis: Int = 700,
    val silenceAfterSpeechMillis: Int = 450,
    val speechPeakAmplitude: Int = 900,
    val chunkMillis: Int = 100
)

fun conversationVoiceCaptureConfig(
    tuning: ConversationCaptureTuning = ConversationCaptureTuning()
): VoiceCaptureConfig {
    val defaults = ConversationCaptureTuning()
    val gated = if (tuning.evidenceGateEnabled) tuning else defaults
    return VoiceCaptureConfig(
        maxMillis = gated.maxMillis.toLong(),
        minMillis = gated.minMillis.toLong(),
        silenceAfterSpeechMillis = gated.silenceAfterSpeechMillis.toLong(),
        speechPeakAmplitude = gated.speechPeakAmplitude,
        chunkMillis = gated.chunkMillis.toLong()
    )
}

data class VoiceCaptureResult(
    val pcm16Mono16k: ByteArray,
    val maxCaptureMillis: Long,
    val minCaptureMillis: Long,
    val actualCapturedMillis: Long,
    val stopReason: String,
    val speechDetected: Boolean,
    val firstSpeechDetectedMillis: Long? = null,
    val lastSpeechDetectedMillis: Long? = null
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
