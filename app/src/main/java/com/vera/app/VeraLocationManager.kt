package com.vera.app

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
import kotlin.math.atan2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Real-time Geographic and Road Topography Snapshot with Vehicle Motion Awareness.
 */
data class RoadContext(
    val streetName: String = "Locating...",
    val neighborhoodOrCity: String = "",
    val fullAddress: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val altitudeMeters: Double = 0.0,
    val altitudeFeet: Double = 0.0,
    val roadGradePercent: Double = 0.0, // e.g. +6.5 for uphill climb, -4.0 for downhill descent
    val inclineStatus: InclineStatus = InclineStatus.LEVEL,
    val headingCompass: String = "N",   // N, NE, E, SE, S, SW, W, NW
    val speedMph: Double = 0.0,
    val speedKmh: Double = 0.0,
    val isMoving: Boolean = false,
    val phoneAccelMps2: Double = 0.0,
    val isDemoSimulated: Boolean = false
) {
    val motionDescription: String
        get() = if (isMoving) "Driving (${speedMph.roundToInt()} mph)" else "Stationary / Parked (0 mph)"

    val formattedTopography: String
        get() = buildString {
            if (streetName.isNotBlank() && streetName != "Locating...") {
                append(streetName)
                if (neighborhoodOrCity.isNotBlank()) append(", $neighborhoodOrCity")
                append(" • ")
            }
            if (isMoving) {
                append(inclineStatus.description)
                if (abs(roadGradePercent) >= 1.5) {
                    append(String.format(Locale.US, " (%+.1f%% grade)", roadGradePercent))
                }
                append(" • Elevation ${altitudeFeet.roundToInt()} ft")
                if (speedMph >= 3.0) {
                    append(" • Moving ${speedMph.roundToInt()} mph $headingCompass")
                }
            } else {
                append(inclineStatus.stationaryDescription)
                if (abs(roadGradePercent) >= 1.5) {
                    append(String.format(Locale.US, " (%+.1f%% grade)", roadGradePercent))
                }
                append(" • Elevation ${altitudeFeet.roundToInt()} ft • Stationary (0 mph)")
            }
        }
}

enum class InclineStatus(val description: String, val stationaryDescription: String) {
    STEEP_CLIMB("Steep Uphill Climb", "Parked on Steep Incline"),
    MODERATE_CLIMB("Moderate Incline", "Stationary on Incline"),
    MILD_CLIMB("Slight Incline", "Stationary on Slight Incline"),
    LEVEL("Level Road", "Parked / Stationary (Level)"),
    MILD_DESCENT("Slight Descent", "Stationary on Slight Slope"),
    MODERATE_DESCENT("Moderate Downhill", "Stationary on Downhill Slope"),
    STEEP_DESCENT("Steep Downhill Descent", "Parked on Steep Downhill Slope")
}

/**
 * Real-Time Location, Altitude, Road Topography, and Motion Manager.
 *
 * Tracks GPS location, barometric/GPS elevation, vehicle/phone speed, and dynamic road grade.
 * Features stationary shielding to prevent parked driveway GPS jitter from causing false hill climb verdicts.
 */
class VeraLocationManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val isObdConnected: () -> Boolean = { false },
    private val getVehicleSpeedKmh: () -> Int = { 0 }
) : SensorEventListener {
    companion object {
        private const val TAG = "VeraLocationManager"
        private const val LOCATION_INTERVAL_MS = 2000L
        private const val FASTEST_INTERVAL_MS = 1000L
    }

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val geocoder: Geocoder by lazy {
        Geocoder(context, Locale.getDefault())
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    // Optional hardware sensors: accelerometer, linear acceleration, barometer, and gravity
    private val linearAccelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val rawAccelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val pressureSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PRESSURE)
    private val gravitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: rawAccelSensor

    val hasAccelerometer: Boolean get() = linearAccelSensor != null || rawAccelSensor != null
    val hasBarometer: Boolean get() = pressureSensor != null

    @Volatile private var currentPhoneAccelMps2 = 0.0
    @Volatile private var recentDynamicAccelVariance = 0.0
    @Volatile private var latestBaroAltitudeMeters: Double = 0.0
    @Volatile private var hasBarometerReading: Boolean = false
    @Volatile private var smoothedPitchDegrees: Double = 0.0

    private val _roadContext = MutableStateFlow(RoadContext())
    val roadContext: StateFlow<RoadContext> = _roadContext.asStateFlow()

    private var isTracking = false
    private var isDemoMode = false

    // Historical window for noise-resistant road grade calculation
    private data class AltitudeHistoryPoint(
        val location: Location,
        val altitudeMeters: Double,
        val timestampMs: Long
    )
    private val altitudeHistory = mutableListOf<AltitudeHistoryPoint>()
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

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_PRESSURE -> {
                val hpa = event.values[0]
                if (hpa in 300.0f..1150.0f) {
                    // Standard hypsometric formula for barometric altitude in meters (0.1m relative resolution)
                    val alt = 44330.0 * (1.0 - (hpa / 1013.25).toDouble().pow(0.190295))
                    latestBaroAltitudeMeters = alt
                    hasBarometerReading = true
                }
            }
            Sensor.TYPE_GRAVITY -> {
                val x = event.values[0].toDouble()
                val y = event.values[1].toDouble()
                val z = event.values[2].toDouble()
                val norm = sqrt(x * x + z * z)
                if (norm > 0.1) {
                    val pitch = Math.toDegrees(atan2(y, norm))
                    smoothedPitchDegrees = (0.85 * smoothedPitchDegrees) + (0.15 * pitch)
                }
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val mag = sqrt((x * x + y * y + z * z).toDouble())
                currentPhoneAccelMps2 = mag
                recentDynamicAccelVariance = (0.85 * recentDynamicAccelVariance) + (0.15 * mag)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0].toDouble()
                val y = event.values[1].toDouble()
                val z = event.values[2].toDouble()
                val totalMag = sqrt(x * x + y * y + z * z)
                val dynMag = abs(totalMag - 9.80665)
                currentPhoneAccelMps2 = dynMag
                recentDynamicAccelVariance = (0.85 * recentDynamicAccelVariance) + (0.15 * dynMag)

                // Fallback pitch calculation when TYPE_GRAVITY is not present
                val norm = sqrt(x * x + z * z)
                if (norm > 0.1) {
                    val pitch = Math.toDegrees(atan2(y, norm))
                    smoothedPitchDegrees = (0.85 * smoothedPitchDegrees) + (0.15 * pitch)
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

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

            if (linearAccelSensor != null) {
                sensorManager?.registerListener(this, linearAccelSensor, SensorManager.SENSOR_DELAY_NORMAL)
            }
            if (rawAccelSensor != null && rawAccelSensor != linearAccelSensor) {
                sensorManager?.registerListener(this, rawAccelSensor, SensorManager.SENSOR_DELAY_NORMAL)
            }
            if (pressureSensor != null) {
                sensorManager?.registerListener(this, pressureSensor, SensorManager.SENSOR_DELAY_NORMAL)
            }
            if (gravitySensor != null && gravitySensor != rawAccelSensor && gravitySensor != linearAccelSensor) {
                sensorManager?.registerListener(this, gravitySensor, SensorManager.SENSOR_DELAY_NORMAL)
            }

            Log.i(TAG, "GPS, Barometer & Road Topography tracking started (hasBaro=$hasBarometer).")
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
            sensorManager?.unregisterListener(this)
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
                latitude = 37.7925,
                longitude = -122.4132,
                altitudeMeters = 115.0,
                altitudeFeet = 377.0,
                roadGradePercent = demoGrade,
                inclineStatus = InclineStatus.STEEP_CLIMB,
                headingCompass = "North",
                speedMph = 24.0,
                speedKmh = 38.6,
                isMoving = true,
                phoneAccelMps2 = 0.8,
                isDemoSimulated = true
            )
            Log.i(TAG, "Demo road topography enabled: Jones St (+${demoGrade}% grade)")
        } else {
            // Restore actual GPS if available
            altitudeHistory.clear()
            smoothedGrade = 0.0
        }
    }

    private fun processNewLocation(location: Location) {
        if (isDemoMode) return // Demo overrides live GPS

        val obdConnected = isObdConnected()
        val obdSpeedKmh = if (obdConnected) getVehicleSpeedKmh() else 0
        val obdSpeedMph = obdSpeedKmh * 0.621371

        val rawGpsSpeedMps = if (location.hasSpeed()) location.speed else 0f
        val gpsSpeedMph = (rawGpsSpeedMps * 2.23694).toDouble()

        // Fused Speedometer:
        // Prefer OBD2 wheel/transmission speed if available and positive.
        // Fallback instantly to Phone GPS speedometer if OBD speed is zero or still initializing.
        val speedMph = if (obdConnected && obdSpeedKmh > 0) obdSpeedMph else gpsSpeedMph
        val speedKmh = if (obdConnected && obdSpeedKmh > 0) obdSpeedKmh.toDouble() else (rawGpsSpeedMps * 3.6).toDouble()

        // Fused Motion Determination:
        // Vehicle is MOVING if either:
        // 1. OBD speedometer reports >= 2 km/h (~1.2 mph), OR
        // 2. Phone GPS speedometer reports >= 2.5 mph.
        // Never let an unpolled or lagging OBD reading hold the motion state at 0 mph while the car is moving.
        val isMoving = (obdConnected && obdSpeedKmh >= 2) || (gpsSpeedMph >= 2.5)

        val altMeters = if (location.hasAltitude()) location.altitude else 0.0
        val altFeet = altMeters * 3.28084
        val heading = if (location.hasBearing() && isMoving) degreesToCompass(location.bearing) else "N"

        // 1. Calculate Road Grade (% Incline) with Noise Suppression & Stationary Drift Shield
        val grade = calculateRoadGrade(location, isMoving)

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
            latitude = location.latitude,
            longitude = location.longitude,
            altitudeMeters = altMeters,
            altitudeFeet = altFeet,
            roadGradePercent = grade,
            inclineStatus = status,
            headingCompass = heading,
            speedMph = if (isMoving) speedMph else 0.0,
            speedKmh = if (isMoving) speedKmh else 0.0,
            isMoving = isMoving,
            phoneAccelMps2 = currentPhoneAccelMps2,
            isDemoSimulated = false
        )
    }

    /**
     * Calculates the slope/grade percentage: (ΔAltitude / HorizontalDistance) * 100
     * Uses a multi-point historical window to avoid instantaneous GPS altitude jitter.
     * Features stationary shielding: does NOT compute grade from stationary GPS drift.
     */
    private fun calculateRoadGrade(current: Location, isMoving: Boolean): Double {
        val currentAlt = if (hasBarometerReading) latestBaroAltitudeMeters else if (current.hasAltitude()) current.altitude else return smoothedGrade

        // CRITICAL: When stationary (sitting in driveway or parked),
        // stationary GPS drift causes false 5-15% grades. We only calculate
        // dynamic driving grade when the vehicle is in motion.
        if (!isMoving) {
            return smoothedGrade
        }

        altitudeHistory.add(AltitudeHistoryPoint(current, currentAlt, System.currentTimeMillis()))
        if (altitudeHistory.size > 25) {
            altitudeHistory.removeAt(0)
        }

        // Barometer has high 0.1m relative resolution so 20m baseline is fast & responsive.
        // Fallback GPS needs at least 45m baseline to suppress vertical satellite jitter.
        val minDistanceMeters = if (hasBarometerReading) 20.0 else 45.0

        val baseline = altitudeHistory.firstOrNull { older ->
            current.distanceTo(older.location) >= minDistanceMeters
        } ?: return smoothedGrade

        val horizontalDistance = current.distanceTo(baseline.location).toDouble()
        val altitudeDelta = currentAlt - baseline.altitudeMeters

        if (horizontalDistance < minDistanceMeters) {
            return smoothedGrade
        }

        var rawGrade = (altitudeDelta / horizontalDistance) * 100.0

        // Sensor fusion with vehicle/phone pitch:
        // When vehicle is pitched nose-up on a hill (> +2.0° pitch), reject negative slope jitter!
        // When vehicle is pitched nose-down on a descent (< -2.0° pitch), reject positive slope jitter!
        if (smoothedPitchDegrees > 2.0 && rawGrade < 0.0) {
            rawGrade = 0.0
        } else if (smoothedPitchDegrees < -2.0 && rawGrade > 0.0) {
            rawGrade = 0.0
        }

        // Clamp raw grade to realistic vehicle roadway ranges (-30% to +30%)
        val clampedGrade = rawGrade.coerceIn(-30.0, 30.0)

        // Exponential smoothing filter
        smoothedGrade = if (abs(smoothedGrade) < 0.01) {
            clampedGrade
        } else {
            (0.25 * clampedGrade) + (0.75 * smoothedGrade)
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
