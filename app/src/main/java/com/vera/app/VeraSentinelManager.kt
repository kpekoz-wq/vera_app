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
    val isOneShot: Boolean = false,
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
    var lastPhraseIndex: Int = -1,
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

    // Charging-system debounce state: a single bad/zero sample, a cranking sag or a startup
    // transient must never be reported as a failed alternator.
    private var engineRunningSinceMs: Long = 0L
    private var lowVoltageSinceMs: Long = 0L
    private var highVoltageSinceMs: Long = 0L

    fun resetSession() {
        cancelAllWatchers()
        alertCooldowns.clear()
        announcedConfirmedDtcs.clear()
        announcedPendingDtcs.clear()
        connectionStartTime = 0L
        engineRunningSinceMs = 0L
        lowVoltageSinceMs = 0L
        highVoltageSinceMs = 0L
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

        val paramNormalized = when {
            parameter.contains("fuel", ignoreCase = true) || parameter.contains("trim", ignoreCase = true) -> "fuel_trim"
            parameter.contains("rpm", ignoreCase = true) || parameter.contains("tach", ignoreCase = true) -> "rpm"
            parameter.contains("speed", ignoreCase = true) || parameter.contains("mph", ignoreCase = true) || parameter.contains("kmh", ignoreCase = true) -> "speed"
            parameter.contains("coolant", ignoreCase = true) || parameter.contains("temp", ignoreCase = true) -> "coolant"
            parameter.contains("trans", ignoreCase = true) -> "trans_temp"
            parameter.contains("volt", ignoreCase = true) || parameter.contains("battery", ignoreCase = true) -> "voltage"
            else -> parameter.lowercase().trim().replace(" ", "_")
        }
        val watcherId = "periodic_$paramNormalized"

        // Cancel and remove any existing active watcher for this parameter or category to prevent duplicates
        val existingCollisions = scheduledWatchers.filter {
            it.key == watcherId || it.value.parameter.contains(paramNormalized, ignoreCase = true) || paramNormalized.contains(it.value.parameter, ignoreCase = true)
        }
        for ((k, oldWatcher) in existingCollisions) {
            oldWatcher.job?.cancel()
            scheduledWatchers.remove(k)
            Log.i(TAG, "Cancelled previous colliding watcher: $k before starting $watcherId")
        }

        val watcher = ScheduledWatcher(
            id = watcherId,
            parameter = paramNormalized,
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

                val (readingTriple, newPhraseIdx) = generateSituationalReading(
                    parameter = parameter,
                    snapshot = snapshot,
                    lastIndex = watcher.lastPhraseIndex
                )
                watcher.lastPhraseIndex = newPhraseIdx
                val (readingTitle, readingText, spokenText) = readingTriple

                val finalSignoffs = listOf(
                    " That wraps up this check—let me know if you want me to keep tracking it.",
                    " That's the last report on that run. Holler if you want another look.",
                    " All set on this check. Let me know if you need anything else monitored.",
                    " That finishes up our scheduled check. Everything's logged.",
                    " That's the final reading for this stretch."
                )
                val finalSuffix = if (isFinal && watcher.maxCount > 1) {
                    finalSignoffs.random()
                } else ""
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
            put("status", "SCHEDULED_ACTIVE_BACKGROUND_SERVICE")
            put("parameter", parameter)
            put("interval_seconds", safeInterval)
            put("total_duration_seconds", safeDuration)
            put("report_count", calcMaxCount)
            put("message", "I'll keep an eye on your $parameter and give you an update every $safeInterval seconds for the next $safeDuration seconds.")
            put("instruction_for_ai", "Acknowledge in ONE brief sentence (e.g. 'Got it, watching your $parameter every $safeInterval seconds.'). DO NOT output any sensor readings or loop in this turn! The background watchdog service will speak each reading separately at each future interval.")
        }
    }

    fun hasActiveWatcher(parameter: String): Boolean {
        val clean = parameter.lowercase().trim()
        return scheduledWatchers.values.any { w ->
            w.job?.isActive == true && (clean == "all" || w.parameter.contains(clean, ignoreCase = true) || clean.contains(w.parameter, ignoreCase = true))
        }
    }

    private fun pickNaturalPhrase(pool: List<String>, lastIndex: Int): Pair<String, Int> {
        if (pool.isEmpty()) return "" to 0
        val candidates = pool.indices.filter { it != lastIndex }
        val chosen = if (candidates.isNotEmpty()) candidates.random() else pool.indices.random()
        return pool[chosen] to chosen
    }

    private fun generateSituationalReading(
        parameter: String,
        snapshot: Map<String, String>,
        lastIndex: Int
    ): Pair<Triple<String, String, String>, Int> {
        val paramKey = parameter.lowercase()
        return when {
            paramKey.contains("fuel") || paramKey.contains("trim") -> {
                val trim = snapshot["fuel_trim"] ?: "+0.0%"
                val trimVal = trim.replace("%", "").replace("+", "").trim().toDoubleOrNull() ?: 0.0
                val pool = when {
                    trimVal > 4.0 -> listOf(
                        "PCM is adding a little extra fuel, trims are at $trim.",
                        "Trims are creeping slightly positive at $trim.",
                        "Fuel delivery is trimming up, currently at $trim.",
                        "Computer is compensating with a bit more fuel at $trim.",
                        "Watching trims: they're sitting on the positive side at $trim.",
                        "Slightly lean condition being corrected, trims are $trim.",
                        "Trims are hovering around $trim right now.",
                        "Fuel trim is up at $trim, keeping an eye on it.",
                        "PCM is giving it a bit more fuel, reading $trim.",
                        "Trims are positive at $trim, watching if they settle under load."
                    )
                    trimVal < -4.0 -> listOf(
                        "Computer is pulling a little fuel, trims are at $trim.",
                        "Trims are dipping negative, sitting at $trim.",
                        "PCM is trimming out some fuel, currently at $trim.",
                        "Slightly rich mixture being dialed back, trims at $trim.",
                        "Trims are running negative at $trim right now.",
                        "Fuel delivery is trimming back down, reading $trim.",
                        "Computer's cutting back fuel slightly, trims are $trim.",
                        "Trims are hovering on the negative side at $trim.",
                        "Pulling fuel at $trim, keeping an eye on it.",
                        "Trims are at $trim as the computer balances the mixture."
                    )
                    else -> listOf(
                        "Fuel trims are sitting right at $trim, dead-on where they belong.",
                        "Trims are nice and flat at $trim, running clean.",
                        "Fuel delivery is balanced, holding at $trim.",
                        "Computer's happy—fuel trims are hovering around $trim.",
                        "Right in the sweet spot on fuel delivery at $trim.",
                        "Trims look great, sitting steady at $trim.",
                        "Everything's dialed in nicely, fuel trim is $trim.",
                        "PCM isn't having to correct anything, trims are at $trim.",
                        "Trims are clean and steady at $trim.",
                        "Fuel trims are holding right around $trim."
                    )
                }
                val (phrase, chosenIdx) = pickNaturalPhrase(pool, lastIndex)
                Triple("Fuel Trim", "Fuel Trim: $trim", phrase) to chosenIdx
            }
            paramKey.contains("rpm") -> {
                val rpm = snapshot["rpm"] ?: "0 RPM"
                val rpmVal = rpm.replace("RPM", "").replace(",", "").trim().toDoubleOrNull() ?: 0.0
                val pool = when {
                    rpmVal < 1050.0 -> listOf(
                        "Idling smooth and steady at $rpm.",
                        "Idle is settled right in at $rpm.",
                        "Sitting at a calm idle, $rpm.",
                        "Idle speed is right where it belongs at $rpm.",
                        "Resting easy at idle, turning $rpm.",
                        "Engine's idling comfortably at $rpm.",
                        "Smooth idle on the tach, reading $rpm.",
                        "Tach is settled down at $rpm.",
                        "Engine speed is holding at a steady $rpm idle.",
                        "Currently idling at $rpm."
                    )
                    rpmVal > 2800.0 -> listOf(
                        "Tach is up to $rpm right now.",
                        "Engine's spinning at $rpm under load.",
                        "You're pulling $rpm.",
                        "Engine speed is up at $rpm.",
                        "Revving at $rpm right now.",
                        "Tach is reading $rpm.",
                        "Working the engine a bit, tach is at $rpm.",
                        "Spinning at $rpm.",
                        "Holding $rpm under throttle.",
                        "Engine's up at $rpm."
                    )
                    else -> listOf(
                        "Cruising along at $rpm.",
                        "Tach is sitting right around $rpm.",
                        "Engine's humming smoothly at $rpm.",
                        "Holding steady at $rpm.",
                        "Turning $rpm right now.",
                        "Engine speed is right at $rpm.",
                        "You're at $rpm on the tach.",
                        "Operating right in the power band at $rpm.",
                        "Tach is holding around $rpm.",
                        "Engine's turning about $rpm under this driving load."
                    )
                }
                val (phrase, chosenIdx) = pickNaturalPhrase(pool, lastIndex)
                Triple("Engine RPM", "Engine RPM: $rpm", phrase) to chosenIdx
            }
            paramKey.contains("coolant") || paramKey.contains("temp") -> {
                val temp = snapshot["coolant"] ?: "195°F"
                val tempVal = temp.replace("°F", "").replace("°C", "").trim().toDoubleOrNull() ?: 195.0
                val isF = !temp.contains("°C")
                val isHot = (isF && tempVal > 218.0) || (!isF && tempVal > 103.0)
                val isWarming = (isF && tempVal < 170.0) || (!isF && tempVal < 77.0)
                val pool = when {
                    isHot -> listOf(
                        "Coolant is running a bit on the warm side at $temp.",
                        "Watching temp closely, it's up to $temp.",
                        "Coolant gauge is reading a little elevated at $temp.",
                        "Engine's running warm right now at $temp.",
                        "Temp is creeping up, currently at $temp.",
                        "Watching coolant temp: it's at $temp.",
                        "Coolant is sitting higher than usual at $temp.",
                        "Keeping an eye on cooling: we're at $temp.",
                        "Temp gauge is showing $temp.",
                        "Engine's warm at $temp, making sure it stabilizes."
                    )
                    isWarming -> listOf(
                        "Engine is still warming up, coolant is at $temp.",
                        "Still coming up to operating temperature, currently $temp.",
                        "Coolant's climbing as it warms, sitting at $temp.",
                        "Engine's still on the cooler side at $temp.",
                        "Warming up nicely, coolant gauge is at $temp.",
                        "Temp is at $temp, still reaching full operating temperature.",
                        "Coolant is at $temp and rising normally.",
                        "Still getting up to temp, reading $temp.",
                        "Coolant's at $temp right now.",
                        "Engine warming cycle: currently at $temp."
                    )
                    else -> listOf(
                        "Coolant is right at normal operating temp, sitting at $temp.",
                        "Temp gauge looks solid, holding steady at $temp.",
                        "Engine temp is right in the sweet spot at $temp.",
                        "Coolant's sitting pretty at $temp.",
                        "Cooling system is doing its job, sitting at $temp.",
                        "Temp is completely nominal at $temp.",
                        "Looking at the gauge, coolant is at $temp.",
                        "Temperature is right where we want it, $temp.",
                        "Thermostat's holding it right at $temp.",
                        "Engine's fully warmed up and sitting at $temp."
                    )
                }
                val (phrase, chosenIdx) = pickNaturalPhrase(pool, lastIndex)
                Triple("Coolant Temp", "Coolant Temp: $temp", phrase) to chosenIdx
            }
            paramKey.contains("speed") -> {
                val speed = snapshot["speed"] ?: "0 mph"
                val speedVal = speed.replace("mph", "").replace("km/h", "").trim().toDoubleOrNull() ?: 0.0
                val pool = if (speedVal <= 1.0) {
                    listOf(
                        "Sitting stopped right now.",
                        "At a complete standstill, zero speed.",
                        "Vehicle is stationary at a stop.",
                        "Stopped at zero speed right now.",
                        "Sitting parked or stopped.",
                        "At a standstill.",
                        "Vehicle's stopped right now.",
                        "Holding at a full stop.",
                        "Zero speed, stationary.",
                        "Sitting stopped right now."
                    )
                } else {
                    listOf(
                        "Cruising at $speed.",
                        "Speed is holding at $speed.",
                        "Rolling along at $speed.",
                        "Speedometer is reading $speed.",
                        "Holding a steady $speed.",
                        "You're at $speed right now.",
                        "Traveling at $speed.",
                        "Speed is currently $speed.",
                        "Maintaining $speed.",
                        "Cruising smoothly at $speed."
                    )
                }
                val (phrase, chosenIdx) = pickNaturalPhrase(pool, lastIndex)
                Triple("Vehicle Speed", "Vehicle Speed: $speed", phrase) to chosenIdx
            }
            paramKey.contains("trans") -> {
                val trans = snapshot["trans_temp"] ?: ""
                val transVal = trans.replace("°F", "").replace("°C", "").trim().toDoubleOrNull() ?: 180.0
                val isF = !trans.contains("°C")
                val isElevated = (isF && transVal > 200.0) || (!isF && transVal > 93.0)
                val pool = if (trans.isBlank()) {
                    listOf("I'm not getting a transmission temperature reading from this car, so I can't report it.")
                } else if (isElevated) {
                    listOf(
                        "Transmission is running a bit warm at $trans.",
                        "Trans fluid temp is slightly elevated at $trans.",
                        "Watching transmission temp: it's up to $trans.",
                        "Transmission fluid is at $trans right now.",
                        "Keeping an eye on the trans cooler, reading $trans.",
                        "Transmission temp is on the higher side at $trans.",
                        "Fluid temp is climbing, currently at $trans.",
                        "Watching the transmission: sitting at $trans.",
                        "Trans temp is $trans, keeping an eye on it.",
                        "Transmission is working a bit warm at $trans."
                    )
                } else {
                    listOf(
                        "Transmission temp looks solid at $trans.",
                        "Fluid is running cool and happy at $trans.",
                        "Trans temp is holding steady at $trans.",
                        "Transmission is sitting at $trans, well within normal range.",
                        "Fluid temperature is right where it needs to be at $trans.",
                        "Transmission is running nice and cool at $trans.",
                        "Trans fluid looks healthy at $trans.",
                        "Glancing at the transmission gauge, it's at $trans.",
                        "Trans temp is completely normal at $trans.",
                        "Holding steady at $trans on the transmission."
                    )
                }
                val (phrase, chosenIdx) = pickNaturalPhrase(pool, lastIndex)
                Triple("Transmission Temp", "Transmission Temp: $trans", phrase) to chosenIdx
            }
            paramKey.contains("volt") -> {
                val volt = snapshot["voltage"] ?: ""
                val voltVal = volt.replace("V", "").trim().toDoubleOrNull() ?: 14.1
                val pool = if (volt.isBlank()) {
                    listOf("I'm not getting a battery voltage reading from the car right now.")
                } else if (voltVal < 13.2) {
                    listOf(
                        "Voltage is sitting a bit low at $volt.",
                        "Battery is reading $volt, charging output looks low.",
                        "System voltage is down at $volt right now.",
                        "Voltage is at $volt, keep an eye on the charging system.",
                        "Alternator isn't putting out high voltage, sitting at $volt.",
                        "Electrical bus is reading $volt.",
                        "Battery voltage is resting at $volt.",
                        "Reading $volt on the electrical system.",
                        "Voltage is lower than normal at $volt.",
                        "Watching battery voltage: currently at $volt."
                    )
                } else {
                    listOf(
                        "Alternator is charging nicely at $volt.",
                        "Battery and charging system are solid at $volt.",
                        "System voltage is holding strong at $volt.",
                        "Alternator output is healthy at $volt.",
                        "Charging system is right on the money at $volt.",
                        "Voltage is looking great at $volt.",
                        "Electrical system is steady at $volt.",
                        "Voltage is sitting right at $volt.",
                        "Alternator's doing its job, showing $volt.",
                        "Solid 12-volt bus reading $volt right now."
                    )
                }
                val (phrase, chosenIdx) = pickNaturalPhrase(pool, lastIndex)
                Triple("Battery Voltage", "Battery Voltage: $volt", phrase) to chosenIdx
            }
            else -> {
                val rpm = snapshot["rpm"] ?: "0"
                val speed = snapshot["speed"] ?: "0"
                val temp = snapshot["coolant"] ?: ""
                val pool = listOf(
                    "You're rolling at $speed, tach is reading $rpm, and coolant's sitting at $temp.",
                    "Speed is $speed, turning $rpm, with the temp gauge at $temp.",
                    "Holding $speed at $rpm, and engine temp looks good at $temp.",
                    "Cruising at $speed, engine's humming at $rpm, coolant at $temp.",
                    "Dashboard check: $speed, $rpm on the tach, and coolant's at $temp.",
                    "Currently at $speed, turning $rpm, and engine is running at $temp.",
                    "Sitting at $speed and $rpm, with coolant steady at $temp.",
                    "Gauges look healthy: $speed, $rpm, and temp is $temp.",
                    "Running smooth at $speed, $rpm, and coolant's right at $temp.",
                    "Telemetry looks clean: $speed on the speedometer, $rpm, coolant holding at $temp."
                )
                val (phrase, chosenIdx) = pickNaturalPhrase(pool, lastIndex)
                Triple("Live Telemetry", "RPM: $rpm, Speed: $speed, Coolant: $temp", phrase) to chosenIdx
            }
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
        currentUnit: UnitSystem,
        isOneShot: Boolean? = null
    ): JSONObject {
        val cleanParam = when {
            parameter.contains("fuel", ignoreCase = true) || parameter.contains("trim", ignoreCase = true) -> "fuel_trim"
            parameter.contains("rpm", ignoreCase = true) || parameter.contains("tach", ignoreCase = true) -> "rpm"
            parameter.contains("speed", ignoreCase = true) -> "speed"
            parameter.contains("coolant", ignoreCase = true) || parameter.contains("temp", ignoreCase = true) -> "coolant"
            parameter.contains("volt", ignoreCase = true) -> "voltage"
            parameter.contains("tire", ignoreCase = true) || parameter.contains("tpms", ignoreCase = true) -> "tire_pressure"
            else -> parameter.lowercase().trim()
        }

        val cleanCondition = when {
            condition.equals("abs_gt", ignoreCase = true) || condition.equals("outside", ignoreCase = true) || condition.equals("past", ignoreCase = true) -> "abs_gt"
            cleanParam == "fuel_trim" && customMessage != null && (customMessage.contains("-") || customMessage.contains("past", ignoreCase = true) || customMessage.contains("outside", ignoreCase = true)) -> "abs_gt"
            else -> condition.trim()
        }

        // Detect if this is inherently a one-shot notification (e.g. "let me know when you see me moving")
        val isSpeedMovementWatch = cleanParam == "speed" && (threshold <= 5.0 || condition == ">" || condition == ">=")
        val isExplicitOneShot = isOneShot ?: (isSpeedMovementWatch || (customMessage != null && (
            customMessage.contains("moving", ignoreCase = true) ||
            customMessage.contains("started", ignoreCase = true) ||
            customMessage.contains("once", ignoreCase = true) ||
            customMessage.contains("let me know when", ignoreCase = true)
        )))

        // Cancel any existing watcher for the same parameter to prevent duplicate/conflicting watchers
        val existing = thresholdWatchers.filter { it.value.parameter == cleanParam }
        existing.forEach { (k, _) -> thresholdWatchers.remove(k) }

        val watcherId = "thresh_${cleanParam}_${cleanCondition}_${threshold.toInt()}"

        // Calculate hysteresis to prevent repetitive bounce
        val hysteresis = when {
            cleanParam.contains("rpm") -> 100.0
            cleanParam.contains("coolant") || cleanParam.contains("temp") -> 3.0
            cleanParam.contains("speed") -> 2.0
            cleanParam.contains("fuel") || cleanParam.contains("trim") -> 0.5 // 0.5% hysteresis for trims allows reliable re-arming
            cleanParam.contains("volt") -> 0.2
            cleanParam.contains("tire") || cleanParam.contains("tpms") -> 2.0
            else -> kotlin.math.abs(threshold) * 0.05
        }

        val watcher = ThresholdWatcher(
            id = watcherId,
            parameter = cleanParam,
            condition = cleanCondition,
            threshold = threshold,
            customMessage = customMessage,
            isOneShot = isExplicitOneShot,
            hysteresis = hysteresis,
            isArmed = true
        )

        thresholdWatchers[watcherId] = watcher
        Log.i(TAG, "Set threshold alert: $cleanParam $cleanCondition $threshold (isOneShot=$isExplicitOneShot, hysteresis=$hysteresis)")

        val conditionHuman = if (cleanCondition == "abs_gt") "past +/-" else cleanCondition
        return JSONObject().apply {
            put("status", "ACTIVE")
            put("parameter", cleanParam)
            put("condition", cleanCondition)
            put("threshold", threshold)
            put("message", "Watching for $cleanParam $conditionHuman $threshold. I will alert you the moment this threshold is crossed.")
        }
    }

    fun cancelAlerts(parameter: String? = null): JSONObject {
        val rawTarget = parameter?.lowercase()?.trim() ?: "all"
        var cancelledCount = 0

        val isGlobal = rawTarget.isBlank() || rawTarget == "all" ||
                rawTarget.contains("everything") || rawTarget.contains("all")

        val isUpdateOrReport = rawTarget.contains("update") || rawTarget.contains("report")
        val isThresholdOrAlert = rawTarget.contains("threshold") || rawTarget.contains("limit") || rawTarget.contains("alert")

        val cleanParam = when {
            rawTarget.contains("fuel") || rawTarget.contains("trim") -> "fuel_trim"
            rawTarget.contains("rpm") || rawTarget.contains("tach") -> "rpm"
            rawTarget.contains("speed") -> "speed"
            rawTarget.contains("coolant") || rawTarget.contains("temp") -> "coolant"
            rawTarget.contains("volt") -> "voltage"
            else -> ""
        }

        if (isGlobal || (isUpdateOrReport && cleanParam.isEmpty() && !isThresholdOrAlert)) {
            // Driver requested global cancel or cancelling all updates/reports
            if (isGlobal) {
                cancelledCount += scheduledWatchers.size + thresholdWatchers.size
                cancelAllWatchers()
            } else {
                scheduledWatchers.forEach { (_, w) -> w.job?.cancel() }
                cancelledCount += scheduledWatchers.size
                scheduledWatchers.clear()
            }
        } else {
            val scheduledToRemove = scheduledWatchers.filter { (k, w) ->
                (cleanParam.isNotEmpty() && (k.contains(cleanParam) || w.parameter.contains(cleanParam))) ||
                k.contains(rawTarget) || w.parameter.contains(rawTarget) ||
                isUpdateOrReport
            }
            scheduledToRemove.forEach { (k, w) ->
                w.job?.cancel()
                scheduledWatchers.remove(k)
                cancelledCount++
            }

            val threshToRemove = thresholdWatchers.filter { (k, w) ->
                (cleanParam.isNotEmpty() && (k.contains(cleanParam) || w.parameter.contains(cleanParam))) ||
                k.contains(rawTarget) || w.parameter.contains(rawTarget) ||
                (isThresholdOrAlert && cleanParam.isEmpty())
            }
            threshToRemove.forEach { (k, _) ->
                thresholdWatchers.remove(k)
                cancelledCount++
            }

            // Fallback: If nothing was matched but the user asked to cancel/stop, clear everything to avoid stranded loops
            if (cancelledCount == 0 && (rawTarget.contains("stop") || rawTarget.contains("cancel"))) {
                cancelledCount += scheduledWatchers.size + thresholdWatchers.size
                cancelAllWatchers()
            }
        }

        return JSONObject().apply {
            put("status", "CANCELLED")
            put("cancelled_count", cancelledCount)
            put("message", if (cancelledCount > 0) "Cancelled $cancelledCount active alerts or reports." else "No active alerts were found matching $rawTarget.")
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
        val fuelTrimExact = streams.find { it.pid.pidHex == "06" }?.rawValue
            ?: streams.find { it.pid.name.contains("Fuel Trim", ignoreCase = true) }?.rawValue
            ?: fuelTrim.toDouble()

        // Only trust a voltage that has really arrived from the ECU recently and is physically plausible.
        // (Streams are created with rawValue = 0.0 before the first sample, which used to look like "0 V".)
        val voltageStreamData = streams.find { it.pid.pidHex == "42" }
            ?: streams.find { it.pid.name.contains("Voltage", ignoreCase = true) }
        val voltageSampleOk = voltageStreamData != null &&
            (voltageStreamData.history.isNotEmpty() || voltageStreamData.formattedValue != "--") &&
            now - voltageStreamData.lastUpdated < 15_000L
        val voltage: Double? = voltageStreamData?.rawValue?.takeIf { voltageSampleOk && it in 6.0..20.0 }
        val voltageStream: Double = voltage ?: Double.NaN // NaN never satisfies a threshold comparison

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

        // Track steady engine running so cranking / startup transients are ignored.
        if (rpm > 550) {
            if (engineRunningSinceMs == 0L) engineRunningSinceMs = now
        } else if (rpm < 300) {
            engineRunningSinceMs = 0L
        }
        val engineSettled = engineRunningSinceMs != 0L && now - engineRunningSinceMs >= 30_000L

        // ── RULE 4: ALTERNATOR OVERCHARGING (CRITICAL: > 15.2V sustained 10s) ──
        if (voltage != null && voltage > 15.2 && engineSettled) {
            if (highVoltageSinceMs == 0L) highVoltageSinceMs = now
        } else {
            highVoltageSinceMs = 0L
        }
        if (highVoltageSinceMs != 0L && now - highVoltageSinceMs >= 10_000L) {
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

        // ── RULE 5: ALTERNATOR OFFLINE / DEAD BATTERY (CRITICAL: < 12.3V sustained 20s, engine steadily running) ──
        // A dropped/zero sample (voltage == null), cranking sag or the first 30s after start never counts.
        if (voltage != null && voltage < 12.3 && engineSettled) {
            if (lowVoltageSinceMs == 0L) lowVoltageSinceMs = now
        } else {
            lowVoltageSinceMs = 0L
        }
        if (lowVoltageSinceMs != 0L && now - lowVoltageSinceMs >= 20_000L) {
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
        val currentSpeedMph = (metrics.speed * 0.621371).toInt()
        val isDriving = currentSpeedMph >= 4 || ((roadContext?.speedMph ?: 0.0) >= 4.0) || (roadContext?.isMoving == true)

        if (isDriving && roadContext != null && (roadContext.roadGradePercent >= 4.0 || roadContext.inclineStatus == InclineStatus.STEEP_CLIMB || roadContext.inclineStatus == InclineStatus.MODERATE_CLIMB)) {
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
        if (isDriving && roadContext != null && (roadContext.roadGradePercent <= -4.0 || roadContext.inclineStatus == InclineStatus.STEEP_DESCENT || roadContext.inclineStatus == InclineStatus.MODERATE_DESCENT)) {
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

        // ── RULE 14: TPMS TIRE PRESSURE MONITORING & CHASSIS WARNINGS (MODE 22) ──
        val tpmsStreams = mode22Streams.filter { it.pid.category.equals("Chassis", ignoreCase = true) || it.pid.name.contains("Tire Pressure", ignoreCase = true) }
        for (tpms in tpmsStreams) {
            val psi = tpms.rawValue
            // Passenger car tire pressures range from 30 to 42 PSI. If a tire is detected between 1 and 28.5 PSI:
            if (psi in 1.0..28.5) {
                val alertKey = "tpms_low_${tpms.pid.pidHex}"
                if (canFireAlert(alertKey, 300_000L)) { // Alert once every 5 minutes per low tire
                    val tireName = when {
                        tpms.pid.name.contains("FL", ignoreCase = true) -> "Front Left"
                        tpms.pid.name.contains("FR", ignoreCase = true) -> "Front Right"
                        tpms.pid.name.contains("RL", ignoreCase = true) -> "Rear Left"
                        tpms.pid.name.contains("RR", ignoreCase = true) -> "Rear Right"
                        else -> tpms.pid.name
                    }
                    val psiFormatted = String.format(java.util.Locale.US, "%.0f PSI", psi)
                    postAlert(
                        SentinelAlert(
                            severity = SentinelSeverity.WARNING,
                            title = "Low Tire Pressure: $tireName",
                            message = "$tireName tire is reading $psiFormatted, which is below the recommended pressure threshold.",
                            spokenText = "Tire Pressure Warning: Your $tireName tire is low at $psiFormatted. Check tire pressure soon.",
                            parameter = "TPMS_LOW_PRESSURE",
                            valueFormatted = psiFormatted
                        )
                    )
                }
            }
        }

        // ── RULE 13: EVALUATE USER CUSTOM THRESHOLD WATCHERS ──
        val minTirePsi = tpmsStreams.map { it.rawValue }.filter { it > 1.0 }.minOrNull() ?: Double.NaN
        for ((_, watcher) in thresholdWatchers) {
            evaluateSingleThresholdWatcher(watcher, rpm, speedMph, coolantF, fuelTrimExact, voltageStream, minTirePsi, isMetric)
        }
    }

    private fun evaluateSingleThresholdWatcher(
        watcher: ThresholdWatcher,
        rpm: Int,
        speedMph: Int,
        coolantF: Int,
        fuelTrim: Double,
        voltage: Double,
        minTirePsi: Double,
        isMetric: Boolean
    ) {
        val currentValue: Double = when {
            watcher.parameter.contains("rpm") -> rpm.toDouble()
            watcher.parameter.contains("speed") -> if (isMetric) (speedMph * 1.60934) else speedMph.toDouble()
            watcher.parameter.contains("coolant") || watcher.parameter.contains("temp") -> if (isMetric) ((coolantF - 32) / 1.8) else coolantF.toDouble()
            watcher.parameter.contains("fuel") || watcher.parameter.contains("trim") -> fuelTrim
            watcher.parameter.contains("volt") -> voltage
            watcher.parameter.contains("tire") || watcher.parameter.contains("tpms") -> minTirePsi
            else -> return
        }

        val cleanCond = watcher.condition.lowercase().trim()
        val isCrossed = when (cleanCond) {
            ">" -> currentValue > watcher.threshold
            ">=" -> currentValue >= watcher.threshold
            "<" -> currentValue < watcher.threshold
            "<=" -> currentValue <= watcher.threshold
            "abs_gt", "outside", "past" -> kotlin.math.abs(currentValue) > kotlin.math.abs(watcher.threshold)
            else -> currentValue > watcher.threshold
        }

        val now = System.currentTimeMillis()

        if (watcher.isArmed && isCrossed) {
            // Prevent burst triggers within 6 seconds
            if (now - watcher.lastTriggered < 6_000L) {
                return
            }

            watcher.isArmed = false
            watcher.lastTriggered = now

            val unitLabel = when {
                watcher.parameter.contains("rpm") -> "RPM"
                watcher.parameter.contains("speed") -> if (isMetric) "km/h" else "mph"
                watcher.parameter.contains("coolant") -> if (isMetric) "°C" else "°F"
                watcher.parameter.contains("fuel") -> "%"
                watcher.parameter.contains("volt") -> "V"
                watcher.parameter.contains("tire") || watcher.parameter.contains("tpms") -> "PSI"
                else -> ""
            }

            val fmtVal = when {
                watcher.parameter.contains("fuel") || watcher.parameter.contains("trim") ->
                    String.format(java.util.Locale.US, "%+.1f%%", currentValue)
                watcher.parameter.contains("volt") ->
                    String.format(java.util.Locale.US, "%.1f V", currentValue)
                else ->
                    String.format(java.util.Locale.US, "%,.0f %s", currentValue, unitLabel).trim()
            }

            val (msg, spoken) = if (watcher.customMessage != null) {
                watcher.customMessage to watcher.customMessage
            } else {
                when {
                    watcher.parameter.contains("fuel") || watcher.parameter.contains("trim") -> {
                        val direction = if (currentValue >= 0) "lean" else "rich"
                        val text = "Fuel trim excursion: reading $fmtVal ($direction), crossing your ${watcher.threshold.toInt()}% threshold."
                        text to text
                    }
                    else -> {
                        val text = "Threshold alert: ${watcher.parameter} reached $fmtVal."
                        "${watcher.parameter.uppercase()} reached $fmtVal (threshold ${watcher.threshold.toInt()} $unitLabel)" to text
                    }
                }
            }

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

            if (watcher.isOneShot) {
                thresholdWatchers.remove(watcher.id)
                Log.i(TAG, "One-shot threshold alert fired and removed: ${watcher.id}")
            }
        } else if (!watcher.isArmed && !watcher.isOneShot) {
            // Re-arm when value drops back past the hysteresis gap (only for recurring watchers, never one-shots)
            val isReArmed = when (cleanCond) {
                ">", ">=" -> currentValue < (watcher.threshold - watcher.hysteresis)
                "<", "<=" -> currentValue > (watcher.threshold + watcher.hysteresis)
                "abs_gt", "outside", "past" -> kotlin.math.abs(currentValue) < (kotlin.math.abs(watcher.threshold) - watcher.hysteresis)
                else -> currentValue < (watcher.threshold - watcher.hysteresis)
            }
            if (isReArmed) {
                watcher.isArmed = true
                Log.d(TAG, "Re-armed threshold watcher for ${watcher.parameter} (val=$currentValue, thresh=${watcher.threshold}, hyst=${watcher.hysteresis})")
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
