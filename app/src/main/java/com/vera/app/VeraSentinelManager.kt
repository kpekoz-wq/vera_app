package com.vera.app

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class SentinelSeverity {
    CRITICAL, // Emergency/immediate (Overheating, Overcharging, Alternator died, Catastrophic Misfire)
    WARNING,  // Stored/Pending DTC, High Fuel Trim, Trans Overheat, Active Misfire
    ADVISORY, // Scheduled report, informational threshold reached
    INFO
}

data class SentinelAlert(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val severity: SentinelSeverity,
    val title: String,
    val message: String,
    val spokenText: String,
    val parameter: String? = null,
    val valueFormatted: String? = null
)

data class ThresholdWatcher(
    val id: String = UUID.randomUUID().toString(),
    val parameter: String,        // e.g. "rpm", "speed", "coolant", "fuel_trim", "voltage"
    val condition: String,        // ">", "<", ">=", "<="
    val threshold: Double,
    val customMessage: String? = null,
    var isArmed: Boolean = true,
    val hysteresis: Double = 0.0,
    var lastTriggered: Long = 0L
)

data class ScheduledWatcher(
    val id: String = UUID.randomUUID().toString(),
    val parameter: String,        // e.g. "fuel_trim", "rpm", "speed", "coolant", "all"
    val intervalSeconds: Int,
    val durationSeconds: Int,     // 0 = indefinite until cancelled
    val maxCount: Int,
    var executedCount: Int = 0,
    var job: Job? = null
)

/**
 * Autonomous In-Vehicle Sentinel Watchdog and Telemetry Scheduler.
 *
 * Runs continuously in the background of active drives:
 * 1. Anomaly Sentinel: Evaluates live ECU metrics, Mode 03 (Confirmed DTCs), Mode 07 (Pending DTCs),
 *    Mode 06 (Cylinder Misfires), and Mode 22 (Trans Temp) against master-technician out-of-bounds rules.
 * 2. Scheduled Telemetry Reports: Wakes up on recurring intervals (e.g. read fuel trims every 20s)
 *    and speaks the update aloud to the driver.
 * 3. Custom Threshold Alerts: Watches for driver-specified triggers (e.g. RPM > 2000, Coolant > 220°F)
 *    with intelligent hysteresis arming to prevent repetitive alert spam.
 */
class VeraSentinelManager(
    private val scope: CoroutineScope,
    private val onAlertTriggered: (SentinelAlert) -> Unit
) {
    companion object {
        private const val TAG = "VeraSentinel"
    }

    private val _alerts = MutableStateFlow<List<SentinelAlert>>(emptyList())
    val alerts: StateFlow<List<SentinelAlert>> = _alerts.asStateFlow()

    private val scheduledWatchers = ConcurrentHashMap<String, ScheduledWatcher>()
    private val thresholdWatchers = ConcurrentHashMap<String, ThresholdWatcher>()

    // Cooldown trackers to prevent repetitive voice spam while driving
    private val alertCooldowns = ConcurrentHashMap<String, Long>()
    private val announcedConfirmedDtcs = ConcurrentHashMap.newKeySet<String>()
    private val announcedPendingDtcs = ConcurrentHashMap.newKeySet<String>()

    private var connectionStartTime: Long = 0L
    private var lastEvaluatedTime: Long = 0L

    fun resetSession() {
        cancelAllWatchers()
        alertCooldowns.clear()
        announcedConfirmedDtcs.clear()
        announcedPendingDtcs.clear()
        connectionStartTime = 0L
        _alerts.value = emptyList()
        Log.i(TAG, "Sentinel session reset.")
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 1. SCHEDULED PERIODIC TELEMETRY REPORTS
    // ─────────────────────────────────────────────────────────────────────────────

    fun schedulePeriodicReport(
        parameter: String,
        intervalSeconds: Int,
        durationSeconds: Int = 60,
        repeatCount: Int = 0,
        currentUnit: UnitSystem,
        getTelemetrySnapshot: () -> Map<String, String>
    ): JSONObject {
        val safeInterval = intervalSeconds.coerceAtLeast(5)
        val calcMaxCount = when {
            repeatCount > 0 -> repeatCount
            durationSeconds > 0 -> (durationSeconds / safeInterval).coerceAtLeast(1)
            else -> 3 // Default 3 iterations
        }
        val safeDuration = if (durationSeconds > 0) durationSeconds else (calcMaxCount * safeInterval)

        val watcherId = "periodic_${parameter.lowercase().replace(" ", "_")}"
        scheduledWatchers[watcherId]?.job?.cancel()

        val watcher = ScheduledWatcher(
            id = watcherId,
            parameter = parameter,
            intervalSeconds = safeInterval,
            durationSeconds = safeDuration,
            maxCount = calcMaxCount
        )

        watcher.job = scope.launch(Dispatchers.Default) {
            Log.i(TAG, "Started periodic watcher: $watcherId (every ${safeInterval}s for ${safeDuration}s, max=$calcMaxCount)")
            while (isActive && watcher.executedCount < watcher.maxCount) {
                delay(safeInterval * 1000L)
                if (!isActive) break

                watcher.executedCount++
                val isFinal = watcher.executedCount >= watcher.maxCount
                val snapshot = getTelemetrySnapshot()

                val paramKey = parameter.lowercase()
                val (readingTitle, readingText, spokenText) = when {
                    paramKey.contains("fuel") || paramKey.contains("trim") -> {
                        val trim = snapshot["fuel_trim"] ?: "+0.0%"
                        Triple("Fuel Trim Update", "Fuel Trim: $trim", "Periodic update: Fuel trim is $trim.")
                    }
                    paramKey.contains("rpm") -> {
                        val rpm = snapshot["rpm"] ?: "0 RPM"
                        Triple("RPM Update", "Engine RPM: $rpm", "Periodic update: Engine speed is $rpm.")
                    }
                    paramKey.contains("coolant") || paramKey.contains("temp") -> {
                        val temp = snapshot["coolant"] ?: "195°F"
                        Triple("Coolant Update", "Coolant Temp: $temp", "Periodic update: Engine coolant temperature is $temp.")
                    }
                    paramKey.contains("speed") -> {
                        val speed = snapshot["speed"] ?: "0 mph"
                        Triple("Speed Update", "Vehicle Speed: $speed", "Periodic update: Vehicle speed is $speed.")
                    }
                    paramKey.contains("trans") -> {
                        val trans = snapshot["trans_temp"] ?: "180°F"
                        Triple("Transmission Temp Update", "Transmission Temp: $trans", "Periodic update: Transmission temperature is $trans.")
                    }
                    paramKey.contains("volt") -> {
                        val volt = snapshot["voltage"] ?: "14.1V"
                        Triple("Voltage Update", "Battery Voltage: $volt", "Periodic update: Battery charging voltage is $volt.")
                    }
                    else -> {
                        val rpm = snapshot["rpm"] ?: "0"
                        val speed = snapshot["speed"] ?: "0"
                        val temp = snapshot["coolant"] ?: ""
                        Triple("Telemetry Update", "RPM: $rpm, Speed: $speed, Coolant: $temp", "Periodic update: RPM is $rpm, speed is $speed, coolant is $temp.")
                    }
                }

                val finalSuffix = if (isFinal) " This completes your scheduled report." else ""
                val alert = SentinelAlert(
                    severity = SentinelSeverity.ADVISORY,
                    title = readingTitle,
                    message = readingText + if (isFinal) " (Final report)" else " (${watcher.executedCount}/${watcher.maxCount})",
                    spokenText = spokenText + finalSuffix,
                    parameter = parameter,
                    valueFormatted = readingText
                )

                postAlert(alert)
                if (isFinal) break
            }
            scheduledWatchers.remove(watcherId)
            Log.i(TAG, "Completed scheduled watcher: $watcherId")
        }

        scheduledWatchers[watcherId] = watcher

        return JSONObject().apply {
            put("status", "SCHEDULED")
            put("parameter", parameter)
            put("interval_seconds", safeInterval)
            put("total_duration_seconds", safeDuration)
            put("report_count", calcMaxCount)
            put("message", "I have scheduled a report for $parameter every $safeInterval seconds for the next $safeDuration seconds.")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 2. CUSTOM THRESHOLD WATCHERS
    // ─────────────────────────────────────────────────────────────────────────────

    fun setThresholdAlert(
        parameter: String,
        condition: String,
        threshold: Double,
        customMessage: String? = null,
        currentUnit: UnitSystem
    ): JSONObject {
        val cleanParam = parameter.lowercase().trim()
        val watcherId = "thresh_${cleanParam}_${condition}_${threshold.toInt()}"

        // Calculate hysteresis to prevent repetitive bounce
        val hysteresis = when {
            cleanParam.contains("rpm") -> 150.0
            cleanParam.contains("coolant") || cleanParam.contains("temp") -> 5.0
            cleanParam.contains("speed") -> 3.0
            cleanParam.contains("fuel") || cleanParam.contains("trim") -> 3.0
            cleanParam.contains("volt") -> 0.3
            else -> threshold * 0.05
        }

        val watcher = ThresholdWatcher(
            id = watcherId,
            parameter = cleanParam,
            condition = condition,
            threshold = threshold,
            customMessage = customMessage,
            hysteresis = hysteresis,
            isArmed = true
        )

        thresholdWatchers[watcherId] = watcher
        Log.i(TAG, "Set threshold alert: $cleanParam $condition $threshold (hysteresis=$hysteresis)")

        return JSONObject().apply {
            put("status", "ACTIVE")
            put("parameter", parameter)
            put("condition", condition)
            put("threshold", threshold)
            put("message", "Watching for $parameter $condition $threshold. I will alert you the moment this threshold is crossed.")
        }
    }

    fun cancelAlerts(parameter: String? = null): JSONObject {
        val target = parameter?.lowercase()?.trim() ?: "all"
        var cancelledCount = 0

        if (target == "all" || target.isBlank()) {
            cancelledCount += scheduledWatchers.size + thresholdWatchers.size
            cancelAllWatchers()
        } else {
            val scheduledToRemove = scheduledWatchers.filter { it.key.contains(target) || it.value.parameter.contains(target, ignoreCase = true) }
            scheduledToRemove.forEach { (k, w) ->
                w.job?.cancel()
                scheduledWatchers.remove(k)
                cancelledCount++
            }
            val threshToRemove = thresholdWatchers.filter { it.key.contains(target) || it.value.parameter.contains(target, ignoreCase = true) }
            threshToRemove.forEach { (k, _) ->
                thresholdWatchers.remove(k)
                cancelledCount++
            }
        }

        return JSONObject().apply {
            put("status", "CANCELLED")
            put("cancelled_count", cancelledCount)
            put("message", if (cancelledCount > 0) "Cancelled $cancelledCount active alerts for $target." else "No active alerts were found matching $target.")
        }
    }

    private fun cancelAllWatchers() {
        scheduledWatchers.values.forEach { it.job?.cancel() }
        scheduledWatchers.clear()
        thresholdWatchers.clear()
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 3. AUTONOMOUS ANOMALY SENTINEL (RUNS ON EVERY LIVE TELEMETRY TICK)
    // ─────────────────────────────────────────────────────────────────────────────

    fun evaluateTelemetry(
        metrics: BluetoothObdManager.VehicleMetrics,
        streams: List<LiveStreamData>,
        dtcDetails: List<DtcDetails>,
        mode06Results: List<Mode06TestResult>,
        mode22Streams: List<Mode22StreamData>,
        unitSystem: UnitSystem,
        isConnected: Boolean,
        roadContext: RoadContext? = null
    ) {
        if (!isConnected) {
            connectionStartTime = 0L
            return
        }

        val now = System.currentTimeMillis()
        if (connectionStartTime == 0L) {
            connectionStartTime = now
        }
        val driveSeconds = (now - connectionStartTime) / 1000L

        // Throttle full evaluation to at most once every 400ms to preserve battery/CPU
        if (now - lastEvaluatedTime < 400L) return
        lastEvaluatedTime = now

        val isMetric = unitSystem == UnitSystem.METRIC
        val rpm = metrics.rpm
        val speedMph = (metrics.speed * 0.621371).toInt()
        val coolantF = (metrics.coolantTemp * 1.8 + 32).toInt()
        val coolantC = metrics.coolantTemp
        val fuelTrim = metrics.fuelTrim

        val voltageStream = streams.find { it.pid.pidHex == "42" }?.rawValue
            ?: streams.find { it.pid.name.contains("Voltage", ignoreCase = true) }?.rawValue
            ?: 14.1

        val intakeAirStream = streams.find { it.pid.pidHex == "0F" }?.rawValue ?: metrics.intakeAirTemp.toDouble()
        val intakeF = (intakeAirStream * 1.8 + 32).toInt()

        val transStream = mode22Streams.find { it.pid.name.contains("Transmission", ignoreCase = true) }
        val transTempF: Int? = transStream?.let {
            if (it.pid.unit == "°C") (it.rawValue * 1.8 + 32).toInt() else it.rawValue.toInt()
        }

        // ── RULE 1: CONFIRMED / ACTIVE DTCs (MODE 03 - CHECK ENGINE LIGHT TRIGGER) ──
        for (d in dtcDetails) {
            val cat = d.category
            val isConfirmed = cat.contains("Stored", ignoreCase = true) || cat.contains("Active", ignoreCase = true)
            if (isConfirmed && !announcedConfirmedDtcs.contains(d.code)) {
                announcedConfirmedDtcs.add(d.code)
                postAlert(
                    SentinelAlert(
                        severity = SentinelSeverity.CRITICAL,
                        title = "Check Engine Light: ${d.code}",
                        message = "Confirmed code ${d.code}: ${d.description}. (Check Engine Light Active)",
                        spokenText = "Check Engine Light Alert: Confirmed trouble code ${d.code} detected: ${d.description}.",
                        parameter = "DTC_CONFIRMED",
                        valueFormatted = d.code
                    )
                )
            }
        }

        // ── RULE 2: PENDING DTCs (MODE 07 - EARLY WARNING BEFORE LIGHT TURNS ON) ──
        for (d in dtcDetails) {
            val cat = d.category
            val isPending = cat.contains("Pending", ignoreCase = true)
            if (isPending && !announcedPendingDtcs.contains(d.code) && !announcedConfirmedDtcs.contains(d.code)) {
                announcedPendingDtcs.add(d.code)
                postAlert(
                    SentinelAlert(
                        severity = SentinelSeverity.WARNING,
                        title = "Early Warning: Pending ${d.code}",
                        message = "Pending code ${d.code}: ${d.description}. (Dashboard light is not on yet)",
                        spokenText = "Early Warning: The computer just logged a pending code, ${d.code}: ${d.description}. The check engine light is not on yet, but I am tracking it.",
                        parameter = "DTC_PENDING",
                        valueFormatted = d.code
                    )
                )
            }
        }

        // ── RULE 3: MODE 06 CYLINDER MISFIRE ANOMALY ──
        for (m in mode06Results) {
            val isMisfire = m.category.contains("Misfire", ignoreCase = true)
            if (isMisfire && (m.testValue > 3.0 || m.status == Mode06Status.FAIL)) {
                val cooldownKey = "misfire_${m.name}"
                if (canFireAlert(cooldownKey, 60_000L)) {
                    postAlert(
                        SentinelAlert(
                            severity = SentinelSeverity.WARNING,
                            title = "Misfire Detected: ${m.name}",
                            message = "${m.name} logged ${m.testValue.toInt()} misfires under load",
                            spokenText = "Heads up: I am catching a misfire spike on ${m.name} under load. Ease off the throttle to protect your catalytic converter.",
                            parameter = "MISFIRE",
                            valueFormatted = "${m.testValue.toInt()} counts"
                        )
                    )
                }
            }
        }

        // ── RULE 4: ALTERNATOR OVERCHARGING (CRITICAL: > 15.2V) ──
        if (voltageStream > 15.2 && rpm > 400) {
            if (canFireAlert("voltage_overcharge", 45_000L)) {
                val voltFmt = String.format(java.util.Locale.US, "%.1fV", voltageStream)
                postAlert(
                    SentinelAlert(
                        severity = SentinelSeverity.CRITICAL,
                        title = "Alternator Overcharging!",
                        message = "Charging voltage spiked to $voltFmt (>15.2V safe limit). Risk of blowing vehicle electronics.",
                        spokenText = "Emergency: Battery voltage spiked to $voltFmt! The alternator regulator is overcharging and can fry vehicle electronics. Find a safe place to pull over and shut down.",
                        parameter = "VOLTAGE",
                        valueFormatted = voltFmt
                    )
                )
            }
        }

        // ── RULE 5: ALTERNATOR OFFLINE / DEAD BATTERY (CRITICAL: < 12.3V WITH ENGINE RUNNING) ──
        if (voltageStream < 12.3 && rpm > 550) {
            if (canFireAlert("alternator_offline", 60_000L)) {
                val voltFmt = String.format(java.util.Locale.US, "%.1fV", voltageStream)
                postAlert(
                    SentinelAlert(
                        severity = SentinelSeverity.CRITICAL,
                        title = "Alternator Offline / Battery Drain",
                        message = "Voltage dropped to $voltFmt with engine running. Broken belt or dead alternator.",
                        spokenText = "Alternator Alert: Battery voltage dropped to $voltFmt while the engine is running. Your charging system is offline; you are running on reserve battery power alone.",
                        parameter = "VOLTAGE",
                        valueFormatted = voltFmt
                    )
                )
            }
        }

        // ── RULE 6: COOLANT OVERHEATING & THERMAL RUNAWAY ──
        val isOverheatCritical = if (isMetric) coolantC >= 113 else coolantF >= 235
        val isOverheatWarning = if (isMetric) coolantC >= 107 else coolantF >= 225

        if (isOverheatCritical) {
            if (canFireAlert("coolant_critical", 30_000L)) {
                val tempText = if (isMetric) "$coolantC°C" else "$coolantF°F"
                postAlert(
                    SentinelAlert(
                        severity = SentinelSeverity.CRITICAL,
                        title = "Engine Overheating Critical!",
                        message = "Coolant temperature reached $tempText. Extreme head gasket risk.",
                        spokenText = "Emergency: Engine coolant reached $tempText! Pull over safely and shut the engine down immediately to prevent head gasket damage.",
                        parameter = "COOLANT",
                        valueFormatted = tempText
                    )
                )
            }
        } else if (isOverheatWarning) {
            if (canFireAlert("coolant_warning", 60_000L)) {
                val tempText = if (isMetric) "$coolantC°C" else "$coolantF°F"
                postAlert(
                    SentinelAlert(
                        severity = SentinelSeverity.WARNING,
                        title = "Coolant Running Hot",
                        message = "Coolant temperature reached $tempText.",
                        spokenText = "Thermal Warning: Engine coolant reached $tempText and is running unusually hot. Keep an eye on your temperature gauge.",
                        parameter = "COOLANT",
                        valueFormatted = tempText
                    )
                )
            }
        }

        // ── RULE 7: STUCK-OPEN THERMOSTAT (COLD RUNNING ENGINE) ──
        val isThermostatCold = if (isMetric) coolantC < 70 else coolantF < 158
        if (driveSeconds > 600 && isThermostatCold && speedMph > 20) {
            if (canFireAlert("thermostat_stuck", 300_000L)) {
                val tempText = if (isMetric) "$coolantC°C" else "$coolantF°F"
                postAlert(
                    SentinelAlert(
                        severity = SentinelSeverity.ADVISORY,
                        title = "Thermostat Stuck Open",
                        message = "Running for ${driveSeconds / 60}m but coolant is only $tempText. Stuck in cold enrichment.",
                        spokenText = "Thermostat Advisory: Your engine has been running for ten minutes, but coolant is only $tempText. Your thermostat appears stuck open, which wastes fuel and keeps the engine in cold enrichment.",
                        parameter = "THERMOSTAT",
                        valueFormatted = tempText
                    )
                )
            }
        }

        // ── RULE 8: SEVERE LEAN VACUUM LEAK (FUEL TRIM > +20%) ──
        if (fuelTrim >= 20) {
            if (canFireAlert("fuel_trim_lean", 90_000L)) {
                postAlert(
                    SentinelAlert(
                        severity = SentinelSeverity.WARNING,
                        title = "Severe Lean Condition (Vacuum Leak)",
                        message = "Combined fuel trim reached +$fuelTrim%. Computer dumping fuel for unmetered air.",
                        spokenText = "Fuel Trim Alert: Combined fuel trims reached plus $fuelTrim percent. The computer is dumping fuel to compensate for unmetered air—likely an intake vacuum leak or dirty MAF.",
                        parameter = "FUEL_TRIM",
                        valueFormatted = "+$fuelTrim%"
                    )
                )
            }
        } else if (fuelTrim <= -20) {
            if (canFireAlert("fuel_trim_rich", 90_000L)) {
                postAlert(
                    SentinelAlert(
                        severity = SentinelSeverity.WARNING,
                        title = "Severe Rich Condition (Over-Fueling)",
                        message = "Combined fuel trim dropped to $fuelTrim%. Computer cutting fuel.",
                        spokenText = "Fuel Trim Alert: Combined fuel trims dropped to $fuelTrim percent. The computer is cutting fuel to the limit—likely a leaking fuel injector or saturated EVAP purge valve.",
                        parameter = "FUEL_TRIM",
                        valueFormatted = "$fuelTrim%"
                    )
                )
            }
        }

        // ── RULE 9: TRANSMISSION FLUID OVERHEATING (MODE 22) ──
        if (transTempF != null && transTempF >= 225) {
            if (canFireAlert("trans_temp_hot", 90_000L)) {
                val transFormatted = if (isMetric) "${((transTempF - 32) / 1.8).toInt()}°C" else "$transTempF°F"
                postAlert(
                    SentinelAlert(
                        severity = SentinelSeverity.WARNING,
                        title = "Transmission Fluid Overheating",
                        message = "Transmission fluid reached $transFormatted (max safe 220°F).",
                        spokenText = "Transmission Alert: Transmission fluid temperature reached $transFormatted. Lock out overdrive or ease back on engine load to let the fluid cool.",
                        parameter = "TRANS_TEMP",
                        valueFormatted = transFormatted
                    )
                )
            }
        }

        // ── RULE 10: INTAKE AIR HEAT SOAK (> 145°F) ──
        if (intakeF >= 145) {
            if (canFireAlert("intake_heat_soak", 180_000L)) {
                val iatText = if (isMetric) "${((intakeF - 32) / 1.8).toInt()}°C" else "$intakeF°F"
                postAlert(
                    SentinelAlert(
                        severity = SentinelSeverity.ADVISORY,
                        title = "Intake Air Heat Soak",
                        message = "Intake temperature reached $iatText. Timing will be retarded.",
                        spokenText = "Intake Heat Soak: Intake air temp reached $iatText. The computer is pulling ignition timing to prevent spark knock, causing sluggish throttle response.",
                        parameter = "INTAKE_TEMP",
                        valueFormatted = iatText
                    )
                )
            }
        }

        // ── RULE 11: UPHILL CLIMB STRESS & MISFIRE CORRELATOR (STEEP / MODERATE CLIMB) ──
        if (roadContext != null && (roadContext.roadGradePercent >= 4.0 || roadContext.inclineStatus == InclineStatus.STEEP_CLIMB || roadContext.inclineStatus == InclineStatus.MODERATE_CLIMB)) {
            val roadName = if (roadContext.streetName.isNotBlank() && roadContext.streetName != "Locating road..." && roadContext.streetName != "Locating...") roadContext.streetName else "this incline"
            val gradeFmt = String.format(java.util.Locale.US, "%.1f", roadContext.roadGradePercent)

            // A. Cylinder Misfires Under Incline Combustion Chamber Pressure
            val failingMisfire = mode06Results.find { 
                it.category.contains("Misfire", ignoreCase = true) && 
                (it.status == Mode06Status.FAIL || (it.testValue > 5.0 && it.name.contains("Cylinder", ignoreCase = true)))
            }
            if (failingMisfire != null && canFireAlert("uphill_misfire_${failingMisfire.name}", 75_000L)) {
                val cylName = failingMisfire.name.replace("Misfires", "").trim()
                postAlert(
                    SentinelAlert(
                        severity = SentinelSeverity.WARNING,
                        title = "Uphill Misfire: $cylName",
                        message = "Misfires detected on $cylName while climbing $roadName at +$gradeFmt% grade (${metrics.engineLoad}% load).",
                        spokenText = "Uphill Diagnostic Alert: I'm catching misfires on $cylName while pulling up $roadName at a $gradeFmt percent grade. Under combustion chamber pressure, the ignition coil or spark plug is breaking down.",
                        parameter = "UPHILL_MISFIRE",
                        valueFormatted = "${failingMisfire.testValue.toInt()} misfires"
                    )
                )
            }

            // B. Severe Fuel Starvation Under Incline Load (Fuel Trim >= +16%)
            if (fuelTrim >= 16 && canFireAlert("uphill_lean_starvation", 90_000L)) {
                postAlert(
                    SentinelAlert(
                        severity = SentinelSeverity.WARNING,
                        title = "Uphill Lean Condition (+${fuelTrim}%)",
                        message = "Fuel trims surged to +$fuelTrim% climbing $roadName at +$gradeFmt% grade.",
                        spokenText = "Fuel Delivery Warning: Climbing $roadName at a $gradeFmt percent grade, combined fuel trims reached plus $fuelTrim percent. The computer is dumping extra fuel to prevent detonation under uphill load. Check fuel filter or pump delivery.",
                        parameter = "UPHILL_FUEL_TRIM",
                        valueFormatted = "+$fuelTrim%"
                    )
                )
            }

            // C. Uphill Transmission Temperature Surge
            if (transTempF != null && transTempF >= 215 && canFireAlert("uphill_trans_surge", 90_000L)) {
                val transFormatted = if (isMetric) "${((transTempF - 32) / 1.8).toInt()}°C" else "$transTempF°F"
                postAlert(
                    SentinelAlert(
                        severity = SentinelSeverity.WARNING,
                        title = "Uphill Transmission Overheat",
                        message = "Transmission fluid reached $transFormatted on sustained $gradeFmt% climb on $roadName.",
                        spokenText = "Transmission Thermal Alert: Pulling up $roadName at a $gradeFmt percent grade, transmission fluid reached $transFormatted. Drop to a lower gear or ease the throttle to reduce torque converter heat.",
                        parameter = "UPHILL_TRANS_TEMP",
                        valueFormatted = transFormatted
                    )
                )
            }
        }

        // ── RULE 12: DOWNHILL COASTING & THERMAL OVERCOOLING WATCHDOG ──
        if (roadContext != null && (roadContext.roadGradePercent <= -4.0 || roadContext.inclineStatus == InclineStatus.STEEP_DESCENT || roadContext.inclineStatus == InclineStatus.MODERATE_DESCENT)) {
            val roadName = if (roadContext.streetName.isNotBlank() && roadContext.streetName != "Locating road..." && roadContext.streetName != "Locating...") roadContext.streetName else "this descent"
            if (coolantF < 160 && driveSeconds >= 600 && canFireAlert("downhill_overcooling", 240_000L)) {
                val tempText = if (isMetric) "$coolantC°C" else "$coolantF°F"
                postAlert(
                    SentinelAlert(
                        severity = SentinelSeverity.ADVISORY,
                        title = "Downhill Cooling Drop ($tempText)",
                        message = "Coolant temperature dropped to $tempText while descending $roadName.",
                        spokenText = "Thermostat Advisory: Descending $roadName, engine coolant dropped to $tempText. Your thermostat is likely sticking slightly open, overcooling the engine on downhills without combustion heat.",
                        parameter = "DOWNHILL_COOLING",
                        valueFormatted = tempText
                    )
                )
            }
        }

        // ── RULE 13: EVALUATE USER CUSTOM THRESHOLD WATCHERS ──
        for ((_, watcher) in thresholdWatchers) {
            evaluateSingleThresholdWatcher(watcher, rpm, speedMph, coolantF, fuelTrim, voltageStream, isMetric)
        }
    }

    private fun evaluateSingleThresholdWatcher(
        watcher: ThresholdWatcher,
        rpm: Int,
        speedMph: Int,
        coolantF: Int,
        fuelTrim: Int,
        voltage: Double,
        isMetric: Boolean
    ) {
        val currentValue: Double = when {
            watcher.parameter.contains("rpm") -> rpm.toDouble()
            watcher.parameter.contains("speed") -> if (isMetric) (speedMph * 1.60934) else speedMph.toDouble()
            watcher.parameter.contains("coolant") || watcher.parameter.contains("temp") -> if (isMetric) ((coolantF - 32) / 1.8) else coolantF.toDouble()
            watcher.parameter.contains("fuel") || watcher.parameter.contains("trim") -> fuelTrim.toDouble()
            watcher.parameter.contains("volt") -> voltage
            else -> return
        }

        val isCrossed = when (watcher.condition) {
            ">" -> currentValue > watcher.threshold
            ">=" -> currentValue >= watcher.threshold
            "<" -> currentValue < watcher.threshold
            "<=" -> currentValue <= watcher.threshold
            else -> currentValue > watcher.threshold
        }

        if (watcher.isArmed && isCrossed) {
            watcher.isArmed = false
            watcher.lastTriggered = System.currentTimeMillis()

            val unitLabel = when {
                watcher.parameter.contains("rpm") -> "RPM"
                watcher.parameter.contains("speed") -> if (isMetric) "km/h" else "mph"
                watcher.parameter.contains("coolant") -> if (isMetric) "°C" else "°F"
                watcher.parameter.contains("fuel") -> "%"
                watcher.parameter.contains("volt") -> "V"
                else -> ""
            }

            val fmtVal = String.format(java.util.Locale.US, "%,.0f %s", currentValue, unitLabel).trim()
            val msg = watcher.customMessage ?: "${watcher.parameter.uppercase()} reached $fmtVal (threshold ${watcher.threshold.toInt()} $unitLabel)"
            val spoken = watcher.customMessage ?: "Threshold alert: ${watcher.parameter} reached $fmtVal."

            postAlert(
                SentinelAlert(
                    severity = SentinelSeverity.ADVISORY,
                    title = "Threshold Reached: ${watcher.parameter.uppercase()}",
                    message = msg,
                    spokenText = spoken,
                    parameter = watcher.parameter,
                    valueFormatted = fmtVal
                )
            )
        } else if (!watcher.isArmed) {
            // Re-arm when value drops back past the hysteresis gap
            val isReArmed = when (watcher.condition) {
                ">", ">=" -> currentValue < (watcher.threshold - watcher.hysteresis)
                "<", "<=" -> currentValue > (watcher.threshold + watcher.hysteresis)
                else -> currentValue < (watcher.threshold - watcher.hysteresis)
            }
            if (isReArmed) {
                watcher.isArmed = true
                Log.d(TAG, "Re-armed threshold watcher for ${watcher.parameter} (val=$currentValue)")
            }
        }
    }

    private fun canFireAlert(key: String, cooldownMs: Long): Boolean {
        val now = System.currentTimeMillis()
        val last = alertCooldowns[key] ?: 0L
        if (now - last > cooldownMs) {
            alertCooldowns[key] = now
            return true
        }
        return false
    }

    private fun postAlert(alert: SentinelAlert) {
        val currentList = _alerts.value.toMutableList()
        currentList.add(0, alert)
        _alerts.value = currentList.take(50) // Keep last 50 alerts in history

        Log.i(TAG, "[SENTINEL ALERT] [${alert.severity}] ${alert.title}: ${alert.message}")
        onAlertTriggered(alert)
    }
}
