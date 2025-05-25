package com.topherjn.weararrondissements.presentation

import android.Manifest
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size // For Icon size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*

// In MainActivity.kt
class MainActivity : ComponentActivity() {
    private val locationViewModel: LocationViewModel by viewModels() // This remains the same

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setTheme(android.R.style.Theme_DeviceDefault)

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
    val typography = MaterialTheme.typography

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp), // Add some padding around the content
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when {
            // 1. Loading State
            uiState.isLoading -> {
                CircularProgressIndicator(modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Locating...", // Or uiState.displayValue if it's "Locating..."
                    style = typography.title2,
                    textAlign = TextAlign.Center
                )
            }

            // 2. Error State (includes permission explicitly denied after a request attempt)
            uiState.errorMessage != null || (!uiState.permissionGranted && uiState.permissionRequested) -> {
                Icon(
                    imageVector = if (!uiState.permissionGranted && uiState.permissionRequested) Icons.Filled.LocationOff else Icons.Filled.ErrorOutline,
                    contentDescription = if (!uiState.permissionGranted && uiState.permissionRequested) "Permission Denied" else "Error",
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colors.error // Use error color for the icon as well
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = uiState.errorMessage ?: "Permission was denied.", // Prioritize specific error message from ViewModel
                    style = typography.title3, // Slightly smaller for error messages perhaps
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colors.error
                )
                // Optionally, if displayValue also contains a user-friendly status for the error
                if (uiState.errorMessage != null && uiState.displayValue != uiState.errorMessage &&
                    (uiState.displayValue.contains("Error", ignoreCase = true) || uiState.displayValue.contains("Permission", ignoreCase = true) || uiState.displayValue.contains("denied", ignoreCase = true))) {
                    Text(
                        text = uiState.displayValue,
                        style = typography.caption1,
                        textAlign = TextAlign.Center
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onRetry, // onRetry should re-trigger permission check or location fetch
                    colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.error) // Error-themed button
                ) {
                    Text(if (!uiState.permissionGranted && uiState.permissionRequested) "Grant Permission" else "Try Again")
                }
            }

            // 3. Permission Needed (Initial state before first request OR if permission was simply not granted yet and no specific error message)
            !uiState.permissionGranted && (uiState.displayValue == "Permission needed" || uiState.displayValue == "Waiting...") -> {
                Icon(
                    imageVector = Icons.Filled.LocationOff,
                    contentDescription = "Location Permission Needed",
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "Location Access Required", style = typography.title2, textAlign = TextAlign.Center)
                Text(text = "This app needs your location to find the arrondissement.", style = typography.body1, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp))
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onRetry) { // onRetry should trigger permission request
                    Text("Grant Permission")
                }
            }

            // 4. Successful Data Display (Arrondissement or Postal Code)
            // Check for non-empty displayValue and specific subTexts, and ensure no error and not loading
            uiState.displayValue.isNotEmpty() &&
                    ((uiState.subText == "Arrondissement") || (uiState.subText == "Postal Code")) && !uiState.isLoading -> {
                uiState.subText?.let {
                    Text(
                        text = it,
                        style = typography.caption1,
                        textAlign = TextAlign.Center
                    )
                }

                Text(
                    text = uiState.displayValue,
                    style = typography.display1.copy(
                        fontSize = when {
                            // Heuristic: if it's likely a postal code (longer) or a multi-digit number that's not a typical arrondissement
                            uiState.displayValue.length > 2 -> 66.sp
                            else -> 96.sp // For typical 1 or 2 digit arrondissements
                        }
                    ),
                    textAlign = TextAlign.Center
                )
            }

            // 5. Default/Fallback State (e.g., "Waiting...", "Not found" without error, or other neutral messages)
            else -> {
                Icon(
                    imageVector = Icons.Filled.HelpOutline, // Generic icon
                    contentDescription = "Status",
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = uiState.displayValue, // Show the current displayValue (e.g., "Waiting...", "Not found")
                    style = typography.title2,
                    textAlign = TextAlign.Center
                )
                // Optionally, add a button if it makes sense for this state
                if (uiState.displayValue == "Waiting..." || uiState.displayValue == "Not found" || uiState.displayValue.startsWith("Ready", ignoreCase = true)) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onRetry) {
                        Text(if(uiState.displayValue == "Not found") "Try Again" else "Find Location")
                    }
                }
            }
        }
    }
}