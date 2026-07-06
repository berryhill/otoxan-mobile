package com.otoxan.mobile.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

class SpeechPlayback(private val context: Context? = null) {
    private val audioManager: AudioManager? = context?.getSystemService(AudioManager::class.java)
    private val ttsLock = Any()
    private var sharedTts: TextToSpeech? = null
    private var sharedTtsReady: CountDownLatch? = null
    private var sharedTtsInitStatus: Int = TextToSpeech.ERROR

    fun playProofTone() {
        val sampleRate = SAMPLE_RATE_16K
        val seconds = 1
        val samples = ShortArray(sampleRate * seconds)
        for (i in samples.indices) {
            samples[i] = (sin(2.0 * PI * 660.0 * i / sampleRate) * Short.MAX_VALUE * 0.45).toInt().toShort()
        }
        playPcm16Mono16k(samples.toLittleEndianPcm16())
    }

    fun playAckEarcon() {
        playPcm16Mono16k(ackEarconPcm16Mono16k())
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
            .setAudioAttributes(playbackAudioAttributes())
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        try {
            withTransientPlaybackFocus {
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
            }
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    fun speakText(text: String) {
        val appContext = requireNotNull(context) { "TextToSpeech playback requires Android context" }
        val cleanText = text.trim()
        require(cleanText.isNotEmpty()) { "TextToSpeech text is empty" }

        val tts = obtainTextToSpeech(appContext)
        withTransientPlaybackFocus {
            val done = CountDownLatch(1)
            val utteranceId = "otoxan-${UUID.randomUUID()}"
            var speakError: String? = null
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) { done.countDown() }

                @Deprecated("Deprecated in Android API")
                override fun onError(utteranceId: String?) {
                    speakError = "TextToSpeech playback failed"
                    done.countDown()
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    speakError = "TextToSpeech playback failed with code $errorCode"
                    done.countDown()
                }
            })

            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            }
            val result = tts.speak(cleanText, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            require(result == TextToSpeech.SUCCESS) { "TextToSpeech speak failed with status $result" }
            val maxWaitSeconds = (cleanText.length / 14).coerceIn(2, 6).toLong()
            require(done.await(maxWaitSeconds, TimeUnit.SECONDS)) { "TextToSpeech playback timed out after ${maxWaitSeconds}s" }
            speakError?.let { error(it) }
        }
    }

    fun warmUpTextToSpeech() {
        val appContext = context ?: return
        runCatching { obtainTextToSpeech(appContext) }
    }

    fun shutdown() {
        synchronized(ttsLock) {
            runCatching { sharedTts?.stop() }
            runCatching { sharedTts?.shutdown() }
            sharedTts = null
            sharedTtsReady = null
            sharedTtsInitStatus = TextToSpeech.ERROR
        }
    }

    private fun obtainTextToSpeech(appContext: Context): TextToSpeech {
        val ready: CountDownLatch
        synchronized(ttsLock) {
            sharedTts?.let { return it }
            ready = CountDownLatch(1)
            sharedTtsReady = ready
            sharedTtsInitStatus = TextToSpeech.ERROR
            sharedTts = TextToSpeech(appContext) { status ->
                synchronized(ttsLock) { sharedTtsInitStatus = status }
                ready.countDown()
            }
        }
        require(ready.await(2, TimeUnit.SECONDS)) { "TextToSpeech engine did not initialize" }
        val status = synchronized(ttsLock) { sharedTtsInitStatus }
        require(status == TextToSpeech.SUCCESS) { "TextToSpeech init failed with status $status" }
        val tts = synchronized(ttsLock) { requireNotNull(sharedTts) }
        tts.language = Locale.US
        choosePreferredVoice(tts)?.let { tts.voice = it }
        tts.setSpeechRate(1.04f)
        tts.setPitch(1.0f)
        tts.setAudioAttributes(playbackAudioAttributes())
        return tts
    }

    private fun choosePreferredVoice(tts: TextToSpeech): android.speech.tts.Voice? = runCatching {
        tts.voices
            ?.filter { voice ->
                voice.locale.language == Locale.US.language &&
                    voice.locale.country == Locale.US.country &&
                    !voice.isNetworkConnectionRequired
            }
            ?.sortedWith(
                compareBy<android.speech.tts.Voice> { voice -> voice.latency }
                    .thenByDescending { voice -> voice.quality }
            )
            ?.firstOrNull()
    }.getOrNull()

    private fun withTransientPlaybackFocus(block: () -> Unit) {
        val manager = audioManager ?: return block()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(playbackAudioAttributes())
                .setWillPauseWhenDucked(false)
                .build()
            try {
                manager.requestAudioFocus(request)
                block()
            } finally {
                manager.abandonAudioFocusRequest(request)
            }
        } else {
            @Suppress("DEPRECATION")
            try {
                @Suppress("DEPRECATION")
                manager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
                block()
            } finally {
                @Suppress("DEPRECATION")
                manager.abandonAudioFocus(null)
            }
        }
    }
}

internal fun playbackAudioAttributes(): AudioAttributes = AudioAttributes.Builder()
    .setUsage(playbackRoutePolicy().usage)
    .setContentType(playbackRoutePolicy().contentType)
    .build()

internal data class PlaybackRoutePolicy(
    val usage: Int,
    val contentType: Int
)

internal fun playbackRoutePolicy(): PlaybackRoutePolicy = PlaybackRoutePolicy(
    usage = AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY,
    contentType = AudioAttributes.CONTENT_TYPE_SPEECH
)

private const val SAMPLE_RATE_16K = 16_000
private const val BYTES_PER_PCM16_MONO_FRAME = 2

internal fun ackEarconPcm16Mono16k(durationMillis: Int = 110, frequencyHz: Double = 880.0): ByteArray {
    val sampleCount = ((SAMPLE_RATE_16K * durationMillis) / 1_000).coerceAtLeast(1)
    val samples = ShortArray(sampleCount)
    for (i in samples.indices) {
        val envelope = when {
            i < SAMPLE_RATE_16K / 200 -> i / (SAMPLE_RATE_16K / 200.0)
            i > samples.lastIndex - SAMPLE_RATE_16K / 200 -> (samples.lastIndex - i).coerceAtLeast(0) / (SAMPLE_RATE_16K / 200.0)
            else -> 1.0
        }.coerceIn(0.0, 1.0)
        samples[i] = (sin(2.0 * PI * frequencyHz * i / SAMPLE_RATE_16K) * Short.MAX_VALUE * 0.20 * envelope).toInt().toShort()
    }
    return samples.toLittleEndianPcm16()
}

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
