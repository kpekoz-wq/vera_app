package com.vera.app

import android.annotation.SuppressLint
import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Real-time Geographic and Road Topography Snapshot.
 */
data class RoadContext(
    val streetName: String = "Locating...",
    val neighborhoodOrCity: String = "",
    val fullAddress: String = "",
    val altitudeMeters: Double = 0.0,
    val altitudeFeet: Double = 0.0,
    val roadGradePercent: Double = 0.0, // e.g. +6.5 for uphill climb, -4.0 for downhill descent
    val inclineStatus: InclineStatus = InclineStatus.LEVEL,
    val headingCompass: String = "N",   // N, NE, E, SE, S, SW, W, NW
    val speedMph: Double = 0.0,
    val speedKmh: Double = 0.0,
    val isDemoSimulated: Boolean = false
) {
    val formattedTopography: String
        get() = buildString {
            if (streetName.isNotBlank() && streetName != "Locating...") {
                append(streetName)
                if (neighborhoodOrCity.isNotBlank()) append(", $neighborhoodOrCity")
                append(" • ")
            }
            append(inclineStatus.description)
            if (abs(roadGradePercent) >= 1.5) {
                append(String.format(Locale.US, " (%+.1f%% grade)", roadGradePercent))
            }
            append(" • Elevation ${altitudeFeet.roundToInt()} ft")
            if (speedMph >= 3.0) {
                append(" • Heading $headingCompass")
            }
        }
}

enum class InclineStatus(val description: String) {
    STEEP_CLIMB("Steep Uphill Climb"),
    MODERATE_CLIMB("Moderate Incline"),
    MILD_CLIMB("Slight Incline"),
    LEVEL("Level Road"),
    MILD_DESCENT("Slight Descent"),
    MODERATE_DESCENT("Moderate Downhill"),
    STEEP_DESCENT("Steep Downhill Descent")
}

/**
 * Real-Time Location, Altitude, and Road Topography Manager.
 *
 * Tracks GPS location, barometric/GPS elevation, and calculates dynamic road grade (slope)
 * to provide geographic context to VERA (e.g. "Climbing 7% grade on Jones St").
 */
class VeraLocationManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "VeraLocationManager"
        private const val LOCATION_INTERVAL_MS = 2000L
        private const val FASTEST_INTERVAL_MS = 1000L
        private const val MIN_DISTANCE_FOR_GRADE_METERS = 12.0
    }

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val geocoder: Geocoder by lazy {
        Geocoder(context, Locale.getDefault())
    }

    private val _roadContext = MutableStateFlow(RoadContext())
    val roadContext: StateFlow<RoadContext> = _roadContext.asStateFlow()

    private var isTracking = false
    private var isDemoMode = false

    // Historical window for noise-resistant road grade calculation
    private val locationHistory = mutableListOf<Location>()
    private var smoothedGrade = 0.0

    // Geocoding throttling
    private var lastGeocodedLocation: Location? = null
    private var lastGeocodedTime: Long = 0L
    private var currentGeocodedStreet: String = "Locating road..."
    private var currentGeocodedCity: String = ""

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            processNewLocation(location)
        }
    }

    @SuppressLint("MissingPermission")
    fun startTracking() {
        if (isTracking) return
        isTracking = true

        try {
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_INTERVAL_MS)
                .setMinUpdateIntervalMillis(FASTEST_INTERVAL_MS)
                .setMinUpdateDistanceMeters(2.0f)
                .build()

            fusedLocationClient.requestLocationUpdates(
                request,
                locationCallback,
                Looper.getMainLooper()
            )
            Log.i(TAG, "GPS & Road Topography tracking started.")
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission not granted: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start location updates: ${e.message}", e)
        }
    }

    fun stopTracking() {
        if (!isTracking) return
        isTracking = false
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            Log.i(TAG, "GPS tracking stopped.")
        } catch (e: Exception) {
            Log.e(TAG, "Error removing location updates: ${e.message}", e)
        }
    }

    fun setDemoMode(enabled: Boolean, dirtyDirty: Boolean = false) {
        isDemoMode = enabled
        if (enabled) {
            // Provide realistic hill climb telemetry in demo mode (e.g. San Francisco's Jones St)
            val demoGrade = if (dirtyDirty) 7.8 else 5.2
            _roadContext.value = RoadContext(
                streetName = "Jones St",
                neighborhoodOrCity = "San Francisco, CA",
                fullAddress = "Jones St & Sacramento St, San Francisco, CA",
                altitudeMeters = 115.0,
                altitudeFeet = 377.0,
                roadGradePercent = demoGrade,
                inclineStatus = InclineStatus.STEEP_CLIMB,
                headingCompass = "North",
                speedMph = 24.0,
                speedKmh = 38.6,
                isDemoSimulated = true
            )
            Log.i(TAG, "Demo road topography enabled: Jones St (+${demoGrade}% grade)")
        } else {
            // Restore actual GPS if available
            locationHistory.clear()
            smoothedGrade = 0.0
        }
    }

    private fun processNewLocation(location: Location) {
        if (isDemoMode) return // Demo overrides live GPS

        val speedMps = if (location.hasSpeed()) location.speed else 0f
        val speedMph = (speedMps * 2.23694).toDouble()
        val speedKmh = (speedMps * 3.6).toDouble()
        val altMeters = if (location.hasAltitude()) location.altitude else 0.0
        val altFeet = altMeters * 3.28084
        val heading = if (location.hasBearing()) degreesToCompass(location.bearing) else "N"

        // 1. Calculate Road Grade (% Incline) with Noise Suppression
        val grade = calculateRoadGrade(location)

        val status = when {
            grade >= 6.0 -> InclineStatus.STEEP_CLIMB
            grade >= 3.0 -> InclineStatus.MODERATE_CLIMB
            grade >= 1.5 -> InclineStatus.MILD_CLIMB
            grade <= -6.0 -> InclineStatus.STEEP_DESCENT
            grade <= -3.0 -> InclineStatus.MODERATE_DESCENT
            grade <= -1.5 -> InclineStatus.MILD_DESCENT
            else -> InclineStatus.LEVEL
        }

        // 2. Reverse Geocode (Throttled: every 50m or 10s)
        triggerReverseGeocodeIfNeeded(location)

        _roadContext.value = RoadContext(
            streetName = currentGeocodedStreet,
            neighborhoodOrCity = currentGeocodedCity,
            altitudeMeters = altMeters,
            altitudeFeet = altFeet,
            roadGradePercent = grade,
            inclineStatus = status,
            headingCompass = heading,
            speedMph = speedMph,
            speedKmh = speedKmh,
            isDemoSimulated = false
        )
    }

    /**
     * Calculates the slope/grade percentage: (ΔAltitude / HorizontalDistance) * 100
     * Uses a multi-point historical window to avoid instantaneous GPS altitude jitter.
     */
    private fun calculateRoadGrade(current: Location): Double {
        if (!current.hasAltitude()) return 0.0

        locationHistory.add(current)
        if (locationHistory.size > 15) {
            locationHistory.removeAt(0)
        }

        // Find the oldest location in history that is at least MIN_DISTANCE_FOR_GRADE_METERS away
        val baseline = locationHistory.firstOrNull { older ->
            current.distanceTo(older) >= MIN_DISTANCE_FOR_GRADE_METERS
        } ?: return smoothedGrade

        val horizontalDistance = current.distanceTo(baseline)
        val altitudeDelta = current.altitude - baseline.altitude

        if (horizontalDistance < MIN_DISTANCE_FOR_GRADE_METERS) {
            return smoothedGrade
        }

        val rawGrade = (altitudeDelta / horizontalDistance) * 100.0
        // Clamp raw grade to realistic vehicle roadway ranges (-30% to +30%)
        val clampedGrade = rawGrade.coerceIn(-30.0, 30.0)

        // Exponential smoothing filter (alpha = 0.4)
        smoothedGrade = if (abs(smoothedGrade) < 0.01) {
            clampedGrade
        } else {
            (0.4 * clampedGrade) + (0.6 * smoothedGrade)
        }

        return (smoothedGrade * 10.0).roundToInt() / 10.0
    }

    private fun triggerReverseGeocodeIfNeeded(location: Location) {
        val now = System.currentTimeMillis()
        val last = lastGeocodedLocation

        val shouldGeocode = last == null ||
                (now - lastGeocodedTime >= 10_000L && location.distanceTo(last) >= 40.0f)

        if (!shouldGeocode) return

        lastGeocodedLocation = location
        lastGeocodedTime = now

        scope.launch(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    geocoder.getFromLocation(
                        location.latitude,
                        location.longitude,
                        1
                    ) { addresses ->
                        if (addresses.isNotEmpty()) {
                            parseAddress(addresses[0])
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                    if (!addresses.isNullOrEmpty()) {
                        parseAddress(addresses[0])
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Geocoding error: ${e.message}")
            }
        }
    }

    private fun parseAddress(address: Address) {
        val street = address.thoroughfare ?: address.featureName ?: "Main St"
        val locality = address.locality ?: address.subAdminArea ?: ""
        val state = address.adminArea ?: ""

        currentGeocodedStreet = street
        currentGeocodedCity = if (locality.isNotBlank() && state.isNotBlank()) "$locality, $state" else locality

        // Update active context with resolved road
        val curr = _roadContext.value
        _roadContext.value = curr.copy(
            streetName = currentGeocodedStreet,
            neighborhoodOrCity = currentGeocodedCity,
            fullAddress = address.getAddressLine(0) ?: "$street, $locality"
        )
    }

    private fun degreesToCompass(degrees: Float): String {
        val normalized = (degrees % 360 + 360) % 360
        val directions = arrayOf("North", "Northeast", "East", "Southeast", "South", "Southwest", "West", "Northwest")
        val index = ((normalized + 22.5) / 45.0).toInt() % 8
        return directions[index]
    }
}
