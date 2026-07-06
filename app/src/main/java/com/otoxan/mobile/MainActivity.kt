package com.otoxan.mobile

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.otoxan.mobile.ui.OtoxanApp
import com.otoxan.mobile.ui.OtoxanViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val viewModel: OtoxanViewModel = viewModel(
                factory = OtoxanViewModel.factory(applicationContext)
            )
            val state by viewModel.uiState.collectAsState()
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { result ->
                viewModel.onPermissionResult(result.values.all { it })
            }

            LaunchedEffect(Unit) {
                permissionLauncher.launch(requiredPermissions())
            }

            OtoxanApp(
                state = state,
                onRefreshRoute = viewModel::refreshRoute,
                onStartSession = viewModel::startConversationSession,
                onEndSession = viewModel::endConversationSession,
                onRecordFiveSeconds = viewModel::recordFiveSecondProof,
                onPlayTest = viewModel::playRouteProof,
                onClearRoute = viewModel::clearRoute,
                onBackendSelfTest = viewModel::runBackendSelfTest,
                onTogglePlaybackMode = viewModel::togglePlaybackMode
            )
        }
    }
}

private fun requiredPermissions(): Array<String> {
    return buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }.toTypedArray()
}
