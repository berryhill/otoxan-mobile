package com.otoxan.mobile.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun OtoxanApp(
    state: OtoxanUiState,
    onRefreshRoute: () -> Unit,
    onRecordFiveSeconds: () -> Unit,
    onPlayTest: () -> Unit,
    onClearRoute: () -> Unit,
    onBackendSelfTest: () -> Unit,
    onTogglePlaybackMode: () -> Unit
) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF2563EB),
            secondary = Color(0xFF0F172A),
            background = Color(0xFFF8FAFC),
            surface = Color.White
        )
    ) {
        OtoxanScreen(
            state = state,
            onRefreshRoute = onRefreshRoute,
            onRecordFiveSeconds = onRecordFiveSeconds,
            onPlayTest = onPlayTest,
            onClearRoute = onClearRoute,
            onBackendSelfTest = onBackendSelfTest,
            onTogglePlaybackMode = onTogglePlaybackMode
        )
    }
}
