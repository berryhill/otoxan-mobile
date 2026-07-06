package com.otoxan.mobile.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun OtoxanScreen(
    state: OtoxanUiState,
    onRefreshRoute: () -> Unit,
    onStartSession: () -> Unit,
    onEndSession: () -> Unit,
    onRecordFiveSeconds: () -> Unit,
    onPlayTest: () -> Unit,
    onClearRoute: () -> Unit,
    onBackendSelfTest: () -> Unit,
    onTogglePlaybackMode: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Otoxan Mobile",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "MVP 1: tap to talk through the selected wearable audio route.",
            style = MaterialTheme.typography.bodyMedium
        )

        RouteTruthCard(state)
        VoiceActivityCard(state)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onRefreshRoute) {
                Text("Check audio route")
            }
            OutlinedButton(onClick = onClearRoute) {
                Text("Clear route")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBackendSelfTest) {
                Text("Backend self-test")
            }
            OutlinedButton(onClick = onTogglePlaybackMode) {
                Text("Playback: ${state.playbackMode.label}")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onStartSession,
                enabled = state.permissionState == PermissionState.Granted && !state.conversationActive
            ) {
                Text("Start Xander session")
            }
            OutlinedButton(
                onClick = onEndSession,
                enabled = state.conversationActive
            ) {
                Text("End session")
            }
        }

        OutlinedButton(
            onClick = onRecordFiveSeconds,
            enabled = state.permissionState == PermissionState.Granted && !state.conversationActive
        ) {
            Text("Single turn test")
        }

        Button(onClick = onPlayTest, enabled = !state.conversationActive) {
            Text("Play route proof")
        }

        EvidenceBlock("Transcript", state.transcript.ifBlank { "No sample captured yet" })
        VoiceLoopEvidenceCard(state)
        LatencyCard(state)
        OperatorDebugCard(state)
        EvidenceBlock("Assistant response", state.assistantResponse.ifBlank { "Configure XANDER_VOICE_ENDPOINT for a real backend turn" })

        state.lastError?.let { error ->
            EvidenceBlock("Error", error)
        }
    }
}

@Composable
private fun RouteTruthCard(state: OtoxanUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Route status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(if (state.wearableRouteActive) "Wearable route active" else "Phone/default route or no wearable route")
            Text("Permission: ${state.permissionState}")
            Text("Session: ${state.sessionState}${if (state.conversationActive) " (${state.conversationTurnCount} turns)" else ""}")
            Text("Turn stage: ${state.turnStage}")
            Text("Input: ${state.selectedInputName} (${state.selectedInputType})")
            Text("Output: ${state.selectedOutputName} (${state.selectedOutputType})")
            Spacer(Modifier.height(4.dp))
            Text("Evidence: ${state.lastEvidence}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun VoiceActivityCard(state: OtoxanUiState) {
    val stage = state.turnStage.lowercase()
    val activityActive = state.voiceActivityActive
    val listening = activityActive && ("listening" in stage || "speak now" in stage || "hearing you" in stage || "capture" in stage)
    val hearingVoice = listening && state.liveSpeechDetected
    val speaking = activityActive && ("speaking" in stage || "playing" in stage)
    val thinking = activityActive && ("thinking" in stage || "xander" in stage || "backend" in stage || "releasing" in stage)
    val activePulse = listening || hearingVoice || speaking || thinking
    val label = when {
        hearingVoice -> "Hearing your voice"
        listening -> "Listening — talk now"
        speaking -> "Xander is speaking"
        thinking -> "Thinking"
        state.conversationActive -> "Session active"
        state.sessionState == VoiceSessionState.RecordingTest -> "Single turn active"
        state.sessionState == VoiceSessionState.PlayingTest -> "Route proof active"
        else -> "Session idle"
    }
    val infinite = rememberInfiniteTransition(label = "voice-activity")
    val bar1 by infinite.animateFloat(
        initialValue = 0.25f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(animation = tween(420), repeatMode = RepeatMode.Reverse),
        label = "voice-bar-1"
    )
    val bar2 by infinite.animateFloat(
        initialValue = 0.55f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(animation = tween(520), repeatMode = RepeatMode.Reverse),
        label = "voice-bar-2"
    )
    val bar3 by infinite.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(animation = tween(360), repeatMode = RepeatMode.Reverse),
        label = "voice-bar-3"
    )
    val liveBoost = state.liveVoiceLevel.coerceIn(0f, 1f)
    val bars = if (activePulse) {
        listOf(
            (bar1 * 0.45f + liveBoost * 0.55f).coerceIn(0.12f, 1f),
            (bar2 * 0.35f + liveBoost * 0.65f).coerceIn(0.12f, 1f),
            (bar3 * 0.25f + liveBoost * 0.75f).coerceIn(0.12f, 1f),
            (bar2 * 0.35f + liveBoost * 0.65f).coerceIn(0.12f, 1f),
            (bar1 * 0.45f + liveBoost * 0.55f).coerceIn(0.12f, 1f)
        )
    } else {
        listOf(0.22f, 0.22f, 0.22f, 0.22f, 0.22f)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (activityActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.height(48.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                bars.forEach { level ->
                    Spacer(
                        modifier = Modifier
                            .width(8.dp)
                            .height((12f + (level * 34f)).dp)
                            .background(
                                color = if (activityActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                shape = RoundedCornerShape(8.dp)
                            )
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(state.turnStage, style = MaterialTheme.typography.bodySmall)
                if (activityActive) {
                    Text("Mic peak: ${state.liveVoicePeak}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun VoiceLoopEvidenceCard(state: OtoxanUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Voice loop proof", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Pass 1: ${if (state.pass1Ready == true) "REAL SPEECH PROVEN" else state.pass1Status ?: "not proven yet"}")
            Text("Provider: ${state.provider ?: "not contacted"}")
            Text("Transcript source: ${state.transcriptSource ?: "unknown"}")
            Text("STT: ${state.sttProvider ?: "unknown"} / ${state.sttStatus ?: "unknown"}${state.sttLatencyMs?.let { " in ${it}ms" } ?: ""}")
            Text("Captured: ${state.capturedBytes}/${state.expectedCaptureBytes} bytes")
            Text("Capture usable: ${state.captureUsable?.toString() ?: "unknown"}; peak=${state.capturePeakAmplitude}")
            Text("Backend received: ${state.backendBytesReceived?.toString() ?: "unknown"} bytes")
            Text("Backend audio: ${state.audioFormat ?: "unknown"}; duration=${state.backendAudioDurationMs?.toString() ?: "unknown"}ms; peak=${state.backendAudioPeak?.toString() ?: "unknown"}; rms=${state.backendAudioRms?.toString() ?: "unknown"}")
            Text("TTS PCM: ${state.ttsBytes} bytes")
        }
    }
}

@Composable
private fun LatencyCard(state: OtoxanUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Latency", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Total turn: ${state.turnTotalMs?.let { "${it}ms" } ?: "unknown"}")
            Text("Route select: ${state.routeSelectMs?.let { "${it}ms" } ?: "unknown"}; release: ${state.routeReleaseMs?.let { "${it}ms" } ?: "unknown"}")
            Text("Capture: expected=${state.captureExpectedMs?.let { "${it}ms" } ?: "unknown"}; actual=${state.captureReadMs?.let { "${it}ms" } ?: "unknown"}")
            Text("Backend: client=${state.backendRoundTripMs?.let { "${it}ms" } ?: "unknown"}; server=${state.backendTotalMs?.let { "${it}ms" } ?: "unknown"}; STT=${state.sttLatencyMs?.let { "${it}ms" } ?: "unknown"}; transcript=${state.transcriptTotalMs?.let { "${it}ms" } ?: "unknown"}; Xander=${state.xanderSessionMs?.let { "${it}ms" } ?: "not run"}")
            Text("HTTP: status=${state.httpStatusCode?.toString() ?: "unknown"}; build=${state.requestBuildMs?.let { "${it}ms" } ?: "unknown"}; upload=${state.uploadMs?.let { "${it}ms" } ?: "unknown"}; wait=${state.responseCodeWaitMs?.let { "${it}ms" } ?: "unknown"}; read=${state.responseReadMs?.let { "${it}ms" } ?: "unknown"}; parse=${state.responseParseMs?.let { "${it}ms" } ?: "unknown"}")
            Text("Payloads: request=${state.requestBytes?.toString() ?: "unknown"} bytes; response=${state.responseBytes?.toString() ?: "unknown"} bytes; tts=${state.ttsBytes} bytes")
            Text("Playback: kind=${state.playbackKind}; total=${state.playbackTotalMs?.let { "${it}ms" } ?: "unknown"}")
        }
    }
}


@Composable
private fun OperatorDebugCard(state: OtoxanUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Operator debug", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Endpoint: ${state.voiceEndpoint}")
            Text("Backend self-test: ${state.backendSelfTestStatus}")
            Text("Telemetry: ${state.telemetryStatus}")
            Text("Route release policy: ${state.routeReleasePolicy}")
            Text("Playback mode: ${state.playbackMode.label}")
            Text("Playback policy: ${state.playbackPolicy}")
            Text("Physical acceptance: if normal mode wedges Meta, switch to Silent after capture and retest.")
        }
    }
}

@Composable
private fun EvidenceBlock(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(body, style = MaterialTheme.typography.bodySmall)
    }
}
