package com.otoxan.mobile.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

class MicCapture {
    @SuppressLint("MissingPermission")
    fun recordPcmForMillis(durationMillis: Long = 5_000): ByteArray {
        val sampleRate = 16_000
        val bytesPerSample = 2
        val expectedBytes = (sampleRate * bytesPerSample * durationMillis / 1_000).toInt()
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
