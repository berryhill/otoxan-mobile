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
    onClearRoute: () -> Unit
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
            text = "MVP 0: prove Ray-Ban Meta voice routing before assistant complexity.",
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

        Button(
            onClick = onRecordFiveSeconds,
            enabled = state.permissionState == PermissionState.Granted && state.wearableRouteActive
        ) {
            Text("Record 5 second route proof")
        }

        Button(onClick = onPlayTest) {
            Text("Play route proof")
        }

        EvidenceBlock("Transcript", state.transcript.ifBlank { "No sample captured yet" })
        EvidenceBlock("Assistant seam", state.assistantResponse.ifBlank { "Backend seam is stubbed for MVP 0" })

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
            Text("Input: ${state.selectedInputName} (${state.selectedInputType})")
            Text("Output: ${state.selectedOutputName} (${state.selectedOutputType})")
            Spacer(Modifier.height(4.dp))
            Text("Evidence: ${state.lastEvidence}", style = MaterialTheme.typography.bodySmall)
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
