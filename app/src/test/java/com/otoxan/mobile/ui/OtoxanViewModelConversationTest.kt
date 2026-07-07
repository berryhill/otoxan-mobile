package com.otoxan.mobile.ui

import com.otoxan.mobile.voice.AudioRouteController
import com.otoxan.mobile.voice.RouteEvidence
import com.otoxan.mobile.voice.VoiceCaptureConfig
import com.otoxan.mobile.voice.VoiceCaptureResult
import com.otoxan.mobile.voice.VoiceCaptureSource
import com.otoxan.mobile.voice.VoicePlaybackSink
import com.otoxan.mobile.voice.VoiceTurnResult
import com.otoxan.mobile.voice.VoiceTurnTelemetryHistoryResult
import com.otoxan.mobile.voice.VoiceTurnTelemetryPacket
import com.otoxan.mobile.voice.VoiceTurnTelemetryResult
import com.otoxan.mobile.voice.XanderVoiceClient
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OtoxanViewModelConversationTest {
    private val dispatcher: TestDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun conversationSession_noSpeechDoesNotInterruptLoopAndNextSpeechTurnCompletes() = runBlocking {
        val capture = FakeCaptureSource(
            VoiceCaptureResult(
                pcm16Mono16k = byteArrayOf(),
                maxCaptureMillis = 12_000,
                minCaptureMillis = 1,
                actualCapturedMillis = 12_000,
                stopReason = "no_speech",
                speechDetected = false
            ),
            speechCapture()
        )
        val client = FakeVoiceClient()
        val viewModel = testViewModel(capture = capture, client = client)

        viewModel.onPermissionResult(true)
        viewModel.startConversationSession()
        waitUntil { viewModel.uiState.value.conversationTurnCount == 1 }
        val completedState = viewModel.uiState.value
        viewModel.endConversationSession()
        waitUntil { viewModel.uiState.value.conversationActive.not() }

        assertEquals(1, client.sendCalls.get())
        assertEquals(2, capture.calls.get())
        assertEquals(1, completedState.conversationTurnCount)
        assertEquals("hello from ray-ban", completedState.transcript)
        assertEquals("fallbackEnabled=true; fallbackDeadline=0.5s; fallbackStatus=0; fallbackSkipped=0; fallbackTimedOut=0; fallbackFailure=recovered-after-fast-parser-empty", completedState.recoveryEvidenceText)
        assertTrue(completedState.lastEvidence.contains("recovery=fallbackEnabled=true"))
        assertNull(completedState.lastError)
    }

    @Test
    fun conversationSession_backendFailureKeepsSessionActiveAndRetriesNextTurn() = runBlocking {
        val capture = FakeCaptureSource(speechCapture(), speechCapture())
        val client = FakeVoiceClient(
            firstSendFailure = IllegalStateException("backend transient failure")
        )
        val viewModel = testViewModel(capture = capture, client = client)

        viewModel.onPermissionResult(true)
        viewModel.startConversationSession()
        waitUntil { viewModel.uiState.value.conversationTurnCount == 1 }
        val completedState = viewModel.uiState.value
        viewModel.endConversationSession()
        waitUntil { viewModel.uiState.value.conversationActive.not() }

        assertEquals(2, client.sendCalls.get())
        assertEquals(2, capture.calls.get())
        assertEquals(1, completedState.conversationTurnCount)
        assertEquals("hello from ray-ban", completedState.transcript)
        assertFalse(completedState.wearableRouteActive)
        assertTrue(completedState.lastEvidence.contains("Voice loop ok"))
        assertTrue(completedState.telemetryHistory.last().success)
    }

    private fun testViewModel(
        capture: FakeCaptureSource,
        client: FakeVoiceClient
    ): OtoxanViewModel = OtoxanViewModel(
        audioRouter = FakeRouteController(),
        micCapture = capture,
        speechPlayback = FakePlaybackSink(),
        xanderVoiceClient = client,
        conversationSuccessDelayMillis = 5_000L,
        conversationRetryDelayMillis = 1L,
        noSpeechRetryDelayMillis = 1L,
        routeReleaseSettleMillis = 0L
    )

    private fun speechCapture(): VoiceCaptureResult = VoiceCaptureResult(
        pcm16Mono16k = ByteArray(640) { index -> if (index % 2 == 0) 0x10 else 0x27 },
        maxCaptureMillis = 20,
        minCaptureMillis = 1,
        actualCapturedMillis = 20,
        stopReason = "speech_silence",
        speechDetected = true,
        firstSpeechDetectedMillis = 1,
        lastSpeechDetectedMillis = 10
    )

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 2_000L
        while (System.currentTimeMillis() < deadline) {
            dispatcher.scheduler.advanceUntilIdle()
            if (condition()) return
            Thread.sleep(10L)
        }
        error("condition was not met before timeout")
    }
}

private class FakeRouteController : AudioRouteController {
    private val selected = RouteEvidence(
        inputName = "Ray-Ban Meta",
        inputType = "TYPE_BLE_HEADSET",
        outputName = "Ray-Ban Meta",
        outputType = "TYPE_BLE_HEADSET",
        wearableActive = true,
        message = "fake wearable selected"
    )
    private val released = RouteEvidence.default("fake route released")
    private var last = selected

    override fun inspectAndSelectWearable(): RouteEvidence {
        last = selected
        return selected
    }

    override fun currentEvidence(): RouteEvidence = last

    override fun clearRoute(): RouteEvidence {
        last = released
        return released
    }
}

private class FakeCaptureSource(vararg captures: VoiceCaptureResult) : VoiceCaptureSource {
    private val queue = ConcurrentLinkedQueue(captures.toList())
    val calls = AtomicInteger(0)

    override fun recordPcmForMillis(durationMillis: Long): ByteArray =
        recordPcmUntilSpeechSilence().pcm16Mono16k

    override fun recordPcmUntilSpeechSilence(
        config: VoiceCaptureConfig,
        onChunkPeak: (peak: Int, capturedMillis: Long, speechDetected: Boolean) -> Unit
    ): VoiceCaptureResult {
        calls.incrementAndGet()
        val capture = queue.poll() ?: error("unexpected capture call")
        onChunkPeak(if (capture.speechDetected) 10_000 else 0, capture.actualCapturedMillis, capture.speechDetected)
        return capture
    }
}

private class FakePlaybackSink : VoicePlaybackSink {
    override fun playProofTone() = Unit
    override fun playAckEarcon() = Unit
    override fun playPcm16Mono16k(pcm16Mono16k: ByteArray) = Unit
    override fun speakText(text: String) = Unit
    override fun warmUpTextToSpeech() = Unit
    override fun shutdown() = Unit
}

private class FakeVoiceClient(
    private val firstSendFailure: Throwable? = null
) : XanderVoiceClient {
    val sendCalls = AtomicInteger(0)

    override suspend fun sendVoiceTurn(pcm16Mono16k: ByteArray, routeEvidence: RouteEvidence): VoiceTurnResult {
        val call = sendCalls.incrementAndGet()
        firstSendFailure?.takeIf { call == 1 }?.let { throw it }
        return VoiceTurnResult(
            transcript = "hello from ray-ban",
            assistantText = "route recovered",
            bytesReceived = pcm16Mono16k.size,
            provider = "xander-session",
            transcriptSource = "moonshine-local",
            sttProvider = "moonshine-local",
            sttStatus = "success",
            sttLatencyMs = 42,
            pass1Status = "real-speech-proven",
            pass1Ready = true,
            mobileFastSessionFallbackEnabled = true,
            mobileFastSessionFallbackHardTimeoutSeconds = 0.5,
            xanderFallbackSessionStatus = 0,
            xanderFallbackSkipped = 0,
            xanderFallbackTimedOut = 0,
            xanderFallbackFailureReason = "recovered-after-fast-parser-empty",
            httpStatusCode = 200,
            requestBytes = pcm16Mono16k.size,
            responseBytes = 128,
            backendTotalMs = 50,
            xanderSessionMs = 60
        )
    }

    override suspend fun postVoiceTurnMetrics(packet: VoiceTurnTelemetryPacket): VoiceTurnTelemetryResult =
        VoiceTurnTelemetryResult(ok = true, recordId = "test-record")

    override suspend fun fetchRecentVoiceTurnMetrics(limit: Int): VoiceTurnTelemetryHistoryResult =
        VoiceTurnTelemetryHistoryResult(ok = true, count = 0, records = emptyList())
}
