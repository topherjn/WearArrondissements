package com.topherjn.weararrondissements.presentation

import android.Manifest
import android.R
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Text


// In MainActivity.kt
class MainActivity : ComponentActivity() {
    private val locationViewModel: LocationViewModel by viewModels() // This remains the same

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_DeviceDefault)

        locationViewModel.initialize() // Call initialize without applicationContext

        setContent {
            WearAppRoot(viewModel = locationViewModel)
        }
    }
}

// In WearAppRoot Composable (or wherever you make these calls)
@Composable
fun WearAppRoot(viewModel: LocationViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    // No longer need 'LocalContext.current' for these specific ViewModel calls

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        Log.d("WearAppRoot", "Permission result received: $isGranted")
        viewModel.onPermissionResult(isGranted) // Call without context
    }

    LaunchedEffect(uiState.permissionGranted, uiState.permissionRequested) {
        if (!uiState.permissionGranted && !uiState.permissionRequested) {
            Log.d("WearAppRoot", "Launching permission request for ACCESS_FINE_LOCATION.")
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    MaterialTheme {
        Scaffold( /* ... */ ) {
            Box( /* ... */ ) {
                WearAppContent(
                    uiState = uiState,
                    onRetry = {
                        Log.d("WearAppRoot", "Retry action triggered.")
                        viewModel.checkPermissionAndFetchLocation() // Call without context
                    }
                )
            }
        }
    }
}

@Composable
fun WearAppContent(uiState: UiState, onRetry: () -> Unit) {

}

