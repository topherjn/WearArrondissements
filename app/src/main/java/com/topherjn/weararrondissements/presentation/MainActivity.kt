package com.topherjn.weararrondissements.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val LOCATION_PERMISSION_REQUEST_CODE = 321
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private val _locationFlow = MutableStateFlow<Pair<Double?, Double?>?>(null)
    val locationFlow: StateFlow<Pair<Double?, Double?>?> = _locationFlow.asStateFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setTheme(android.R.style.Theme_DeviceDefault)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            getWearLocation()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        }

        setContent {
            val locationState: State<Pair<Double?, Double?>?> = locationFlow.collectAsState(initial = null)
            val postalCodeState = remember { mutableStateOf<String?>(null) }
            WearApp(locationState = locationState, postalCodeState = postalCodeState) { lat, lon ->
                performReverseGeocoding(lat, lon, postalCodeState)
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        when (requestCode) {
            LOCATION_PERMISSION_REQUEST_CODE -> {
                if ((grantResults.isNotEmpty() &&
                            grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    getWearLocation()
                } else {
                    Log.d("WearLocation", "Wear OS location permission denied.")
                    _locationFlow.value = null
                    // Handle denial in UI if needed
                }
                return
            }
            else -> {
                super.onRequestPermissionsResult(requestCode, permissions, grantResults)
            }
        }
    }

    private val locationRequest = LocationRequest.Builder(1000L)
        .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)
        .setWaitForAccurateLocation(false)
        .setMinUpdateIntervalMillis(500L)
        .setMaxUpdateDelayMillis(2000L)
        .build()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            locationResult.lastLocation?.let { location ->
                _locationFlow.value = Pair(location.latitude, location.longitude)
                fusedLocationClient.removeLocationUpdates(this)
            }
        }
    }

    private fun getWearLocation() {
        try {
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location ->
                    if (location != null) {
                        _locationFlow.value = Pair(location.latitude, location.longitude)
                    } else {
                        Log.d("WearLocation", "Wear OS: Last known location was null, requesting current.")
                        requestCurrentLocation()
                    }
                }
                .addOnFailureListener { e ->
                    Log.e("WearLocation", "Wear OS: Failed to get last known location: ${e.localizedMessage}")
                    requestCurrentLocation()
                }
        } catch (securityException: SecurityException) {
            Log.e("WearLocation", "Wear OS: Security exception while getting location: $securityException")
        }
    }

    private fun requestCurrentLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, null)
        } else {
            Log.d("WearLocation", "Location permission not granted to request updates.")
            // Optionally, handle the case where permission is not available
        }
    }

    private fun performReverseGeocoding(latitude: Double, longitude: Double, postalCodeState: androidx.compose.runtime.MutableState<String?>) {
        val geocoder = Geocoder(this, Locale.getDefault())
        try {
            CoroutineScope(Dispatchers.IO).launch {
                val addresses = geocoder.getFromLocation(latitude, longitude, 1)
                launch(Dispatchers.Main) {
                    if (addresses?.isNotEmpty() == true) {
                        val address = addresses[0]
                        val postalCode = address.postalCode
                        postalCodeState.value = postalCode
                        Log.d("WearLocation", "Wear OS Postal Code: ${postalCodeState.value}")

                        // Extract arrondissement if it's a Paris postal code
                        if (postalCode?.startsWith("75") == true && postalCode.length >= 2) {
                            val arrondissementString = postalCode.takeLast(2)
                            val arrondissement = arrondissementString.toIntOrNull()
                            if (arrondissement != null) {
                                postalCodeState.value = arrondissement.toString()
                                Log.d("WearLocation", "Wear OS Arrondissement: ${postalCodeState.value}")
                            } else {
                                Log.d("WearLocation", "Wear OS: Could not parse arrondissement from postal code.")
                            }
                        } else {
                            Log.d("WearLocation", "Wear OS: Not a Paris postal code or too short.")
                        }
                        // Next step: Display the arrondissement
                    } else {
                        postalCodeState.value = "No postal code found"
                        Log.d("WearLocation", "Wear OS: No postal code found for coordinates.")
                    }
                }
            }
        } catch (e: Exception) {
            postalCodeState.value = "Geocoder error"
            Log.e("WearLocation", "Wear OS Geocoder exception: ${e.localizedMessage}")
        }
    }
}

@Composable
fun WearApp(
    locationState: State<Pair<Double?, Double?>?>,
    postalCodeState: androidx.compose.runtime.MutableState<String?>,
    onLocationChanged: (Double, Double) -> Unit // Add this parameter
) {
    Log.d("WearApp", "WearApp Composable Recomposed: Postal Code = ${postalCodeState.value}")
    val typography = androidx.wear.compose.material.MaterialTheme.typography
    MaterialTheme {
        Scaffold(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colors.background),
                contentAlignment = Alignment.Center
            ) {
                TimeText()
                val arrondissement = remember(postalCodeState.value) {
                    postalCodeState.value?.takeIf { it.startsWith("75") }?.takeLast(2)?.toIntOrNull()

                }

                LaunchedEffect(locationState.value) {
                    locationState.value?.let {
                        if (it.first != null && it.second != null) {
                            onLocationChanged(it.first!!, it.second!!)
                        }
                    }
                }

                if (arrondissement != null) {
                    Text(
                        text = "$arrondissement",
                        style = typography.display1
                    )
                } else if (postalCodeState.value != null) {
                    Text(
                        text = postalCodeState.value!!,
                        style = typography.display1.copy(
                            fontSize = 96.sp
                        )
                    )
                } else if (locationState.value != null) {
                    Text(text = "Locating...", style = typography.body1)
                } else {
                    Text(text = "Waiting for location", style = typography.body1)
                }
            }
        }
    }
}