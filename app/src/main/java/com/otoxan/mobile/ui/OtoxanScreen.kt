package com.otoxan.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun OtoxanScreen(
    state: OtoxanUiState,
    onRefreshRoute: () -> Unit,
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

        Button(
            onClick = onRecordFiveSeconds,
            enabled = state.permissionState == PermissionState.Granted && state.wearableRouteActive
        ) {
            Text("Talk to Xander")
        }

        Button(onClick = onPlayTest) {
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
            Text("Session: ${state.sessionState}")
            Text("Turn stage: ${state.turnStage}")
            Text("Input: ${state.selectedInputName} (${state.selectedInputType})")
            Text("Output: ${state.selectedOutputName} (${state.selectedOutputType})")
            Spacer(Modifier.height(4.dp))
            Text("Evidence: ${state.lastEvidence}", style = MaterialTheme.typography.bodySmall)
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
            Text("Hermes STT: ${state.sttStatus ?: "unknown"}${state.sttLatencyMs?.let { " in ${it}ms" } ?: ""}")
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
