package com.topherjn.weararrondissements.presentation

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application // Import Application
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel // Import AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await // Ensure this import for .await()
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale

// Data class to hold all UI relevant state
data class UiState(
    val displayValue: String = "Waiting...", // Main text to display (arrondissement, postal code, or message)
    val subText: String? = null,      // Optional sub-text (e.g., "Arrondissement")
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val permissionRequested: Boolean = false,
    val permissionGranted: Boolean = false
)

class LocationViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var geocoder: Geocoder

    private var locationCallback: LocationCallback? = null

    // Define location request parameters
    private val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000L) // Interval: 10 seconds
        .setWaitForAccurateLocation(false)
        .setMinUpdateIntervalMillis(5000L)
        .setMaxUpdateDelayMillis(20000L)
        .build()

    fun initialize() {
        if (::fusedLocationClient.isInitialized) return // Avoid re-initialization

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(getApplication())
        geocoder = Geocoder(getApplication(), Locale.getDefault())
        checkPermissionAndFetchLocation()
    }

    fun checkPermissionAndFetchLocation() {
        Log.d("LocationViewModel", "Checking permission and fetching location.")
        if (ContextCompat.checkSelfPermission(getApplication<Application>().applicationContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            _uiState.value = _uiState.value.copy(permissionGranted = true, permissionRequested = true, isLoading = true, displayValue = "Locating...")
            fetchLocation()
        } else {
            // Permission not granted, UI will trigger request if permissionRequested is false
            _uiState.value = _uiState.value.copy(permissionGranted = false, permissionRequested = _uiState.value.permissionRequested, isLoading = false, displayValue = "Permission needed")
            Log.d("LocationViewModel", "Location permission not granted.")
        }
    }

    fun onPermissionResult(isGranted: Boolean) {
        _uiState.value = _uiState.value.copy(permissionRequested = true) // Mark that a permission attempt was made
        if (isGranted) {
            _uiState.value = _uiState.value.copy(permissionGranted = true, isLoading = true, displayValue = "Locating...")
            fetchLocation()
        } else {
            _uiState.value = _uiState.value.copy(permissionGranted = false, isLoading = false, displayValue = "Permission denied", errorMessage = "Location permission is required.")
            Log.d("LocationViewModel", "Location permission denied by user.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun fetchLocation() {
        if (!_uiState.value.permissionGranted) {
            Log.w("LocationViewModel", "FetchLocation called without permission.")
            // Update UI state to reflect that permission is still needed.
            _uiState.value = _uiState.value.copy(isLoading = false, displayValue = "Permission needed", errorMessage = "Grant permission to find location.")
            return
        }

        // Ensure location callback is null before starting to avoid multiple callbacks if fetchLocation is called again
        stopLocationUpdates() // Clear any existing callback first

        _uiState.value = _uiState.value.copy(isLoading = true, displayValue = "Locating...", errorMessage = null) // Reset error message

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val lastLocation = fusedLocationClient.lastLocation.await()
                if (lastLocation != null) {
                    Log.d("LocationViewModel", "Last known location: Lat=${lastLocation.latitude}, Lon=${lastLocation.longitude}")
                    processGeocoding(lastLocation.latitude, lastLocation.longitude)
                    // For this app, one good location fix is often enough.
                    // If you wanted to ensure it's very recent, you might still request a new one.
                } else {
                    Log.d("LocationViewModel", "Last known location is null. Requesting current location.")
                    locationCallback = object : LocationCallback() {
                        override fun onLocationResult(locationResult: LocationResult) {
                            locationResult.lastLocation?.let { location ->
                                Log.d("LocationViewModel", "Current Location via callback: Lat=${location.latitude}, Lon=${location.longitude}")
                                viewModelScope.launch { // Switch to viewModelScope for state update
                                    processGeocoding(location.latitude, location.longitude)
                                }
                                stopLocationUpdates() // Stop updates after the first successful fix
                            } ?: Log.d("LocationViewModel", "LocationResult.lastLocation is null in callback")
                        }
                    }
                    // Request location updates on the main thread's looper
                    withContext(Dispatchers.Main.immediate) {
                        if (ContextCompat.checkSelfPermission(getApplication<Application>().applicationContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback!!, Looper.getMainLooper())
                            Log.d("LocationViewModel", "Requested location updates.")
                        } else {
                            Log.e("LocationViewModel", "Permission check failed before requesting updates within callback setup.")
                            _uiState.value = _uiState.value.copy(isLoading = false, displayValue = "Permission lost", errorMessage = "Permission was lost before update request.")
                        }
                    }
                }
            } catch (e: SecurityException) {
                Log.e("LocationViewModel", "SecurityException while getting location: ${e.message}")
                _uiState.value = _uiState.value.copy(isLoading = false, displayValue = "Permission error", errorMessage = "Security error fetching location.")
            } catch (e: Exception) {
                Log.e("LocationViewModel", "Exception while getting location: ${e.message}", e)
                _uiState.value = _uiState.value.copy(isLoading = false, displayValue = "Location error", errorMessage = "Could not retrieve location.")
            }
        }
    }

    private suspend fun processGeocoding(latitude: Double, longitude: Double) {
        Log.d("LocationViewModel", "Processing geocoding for Lat: $latitude, Lon: $longitude")
        try {
            @Suppress("DEPRECATION") // Geocoder.getFromLocation is deprecated on API 33+
            val addresses = withContext(Dispatchers.IO) {
                // Ensure geocoder is initialized
                if (!::geocoder.isInitialized) {
                    geocoder = Geocoder(getApplication(), Locale.getDefault())
                }
                geocoder.getFromLocation(latitude, longitude, 1)
            }

            if (!addresses.isNullOrEmpty()) {
                val address = addresses[0]
                val postalCode = address.postalCode
                Log.d("LocationViewModel", "Reverse Geocoded Postal Code: $postalCode")
                //TODO: use geofencing to restrict to Paris, France
                if (postalCode?.startsWith("75") == true && postalCode.length >= 2) {
                    // Paris postal codes like 75001, 75016. The last two digits are the arrondissement.
                    val arrondissementString = postalCode.takeLast(2)
                    val arrondissement = arrondissementString.toIntOrNull()
                    if (arrondissement != null) {
                        _uiState.value = _uiState.value.copy(
                            displayValue = arrondissement.toString(),
                            subText = "Arrondissement",
                            isLoading = false,
                            errorMessage = null
                        )
                        Log.d("LocationViewModel", "Arrondissement: $arrondissement")
                    } else {
                        _uiState.value = _uiState.value.copy(
                            displayValue = postalCode,
                            subText = "Postal Code",
                            isLoading = false,
                            errorMessage = "Could not parse arrondissement from $postalCode."
                        )
                        Log.w("LocationViewModel", "Could not parse arrondissement from postal code: $postalCode")
                    }
                } else {
                    _uiState.value = _uiState.value.copy(
                        displayValue = postalCode ?: "N/A",
                        subText = if (postalCode != null) "Postal Code" else null,
                        isLoading = false,
                        errorMessage = if (postalCode == null) "No postal code found." else "Not a Paris, FR postal code."
                    )
                    Log.d("LocationViewModel", "Not a Paris postal code or no postal code found: $postalCode")
                }
            } else {
                _uiState.value = _uiState.value.copy(isLoading = false, displayValue = "Not found", errorMessage = "No address found for the current location.")
                Log.d("LocationViewModel", "No address found for coordinates.")
            }
        } catch (e: IOException) {
            Log.e("LocationViewModel", "Geocoder IOException: ${e.localizedMessage}", e)
            _uiState.value = _uiState.value.copy(isLoading = false, displayValue = "Network Error", errorMessage = "Geocoder service unavailable. Check internet connection.")
        } catch (e: IllegalArgumentException) {
            Log.e("LocationViewModel", "Geocoder IllegalArgumentException: ${e.localizedMessage}", e)
            _uiState.value = _uiState.value.copy(isLoading = false, displayValue = "Invalid Coords", errorMessage = "Invalid location coordinates for geocoding.")
        } catch (e: Exception) {
            Log.e("LocationViewModel", "Geocoder general exception: ${e.localizedMessage}", e)
            _uiState.value = _uiState.value.copy(isLoading = false, displayValue = "Error", errorMessage = "An error occurred during geocoding.")
        }
    }

    fun stopLocationUpdates() {
        locationCallback?.let {
            Log.d("LocationViewModel", "Stopping location updates.")
            // Ensure fusedLocationClient is initialized before trying to use it
            if (::fusedLocationClient.isInitialized) {
                try {
                    fusedLocationClient.removeLocationUpdates(it)
                } catch (e: Exception) {
                    Log.e("LocationViewModel", "Error removing location updates: ${e.message}", e)
                }
            }
            locationCallback = null // Clear the callback
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopLocationUpdates() // Ensure updates are stopped when ViewModel is cleared
        Log.d("LocationViewModel", "ViewModel cleared and location updates stopped.")
    }
}