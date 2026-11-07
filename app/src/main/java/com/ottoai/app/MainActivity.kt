@file:OptIn(ExperimentalMaterial3Api::class)

package com.ottoai.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ottoai.app.ui.theme.OttoAITheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        checkAndRequestPermissions()

        setContent {
            OttoAITheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    OttoMainScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }
}

// -----------------------------------------------------------------------------
// Helper UI Composable Components & Guidance
// -----------------------------------------------------------------------------

fun generateChatTranscriptText(
    messages: List<ChatMessage>,
    vehicleDetails: String?,
    vin: String?,
    odometer: String? = null
): String {
    val timeFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
    val timestamp = timeFormat.format(java.util.Date())

    val sb = StringBuilder()
    sb.appendLine("==================================================")
    sb.appendLine("        OttoAI Diagnostic Log & Chat Transcript   ")
    sb.appendLine("==================================================")
    sb.appendLine("Generated: $timestamp")
    sb.appendLine("Vehicle Details: ${vehicleDetails ?: "Unknown Vehicle"}")
    sb.appendLine("VIN: ${vin ?: "N/A"}")
    if (!odometer.isNullOrBlank()) {
        sb.appendLine("Odometer: $odometer")
    }
    sb.appendLine("==================================================")
    sb.appendLine()

    if (messages.isEmpty()) {
        sb.appendLine("(No chat messages recorded yet.)")
    } else {
        for ((idx, msg) in messages.withIndex()) {
            val senderLabel = when (msg.sender) {
                "User" -> "YOU"
                "OttoAI" -> "OTTOAI MECHANIC"
                else -> msg.sender.uppercase()
            }
            sb.appendLine("[${idx + 1}] $senderLabel:")
            sb.appendLine(msg.text)
            sb.appendLine("--------------------------------------------------")
        }
    }
    return sb.toString()
}

@Composable
fun DownloadSaveIcon(
    tint: Color = MaterialTheme.colorScheme.secondary,
    modifier: Modifier = Modifier.size(16.dp)
) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val strokeWidth = 2.dp.toPx()

        // Downward Arrow Stem
        drawLine(
            color = tint,
            start = Offset(w * 0.5f, h * 0.15f),
            end = Offset(w * 0.5f, h * 0.65f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
        // Left Arrowhead
        drawLine(
            color = tint,
            start = Offset(w * 0.28f, h * 0.43f),
            end = Offset(w * 0.5f, h * 0.65f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
        // Right Arrowhead
        drawLine(
            color = tint,
            start = Offset(w * 0.72f, h * 0.43f),
            end = Offset(w * 0.5f, h * 0.65f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
        // Horizontal Baseline at bottom
        drawLine(
            color = tint,
            start = Offset(w * 0.2f, h * 0.85f),
            end = Offset(w * 0.8f, h * 0.85f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
    }
}

@Composable
fun ModernAnalyticsIcon(
    tint: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier.size(16.dp)
) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val barWidth = w * 0.22f

        drawRoundRect(
            color = tint.copy(alpha = 0.55f),
            topLeft = Offset(w * 0.08f, h * 0.45f),
            size = Size(barWidth, h * 0.45f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.06f)
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.39f, h * 0.15f),
            size = Size(barWidth, h * 0.75f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.06f)
        )
        drawRoundRect(
            color = tint.copy(alpha = 0.75f),
            topLeft = Offset(w * 0.7f, h * 0.3f),
            size = Size(barWidth, h * 0.6f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.06f)
        )
    }
}

@Composable
fun ModernAttachmentIcon(
    tint: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier.size(16.dp)
) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.09f

        val path = Path().apply {
            moveTo(w * 0.2f, h * 0.1f)
            lineTo(w * 0.62f, h * 0.1f)
            lineTo(w * 0.82f, h * 0.3f)
            lineTo(w * 0.82f, h * 0.9f)
            lineTo(w * 0.2f, h * 0.9f)
            close()
        }
        drawPath(path = path, color = tint.copy(alpha = 0.2f))
        drawPath(path = path, color = tint, style = Stroke(width = stroke, cap = StrokeCap.Round))

        val fold = Path().apply {
            moveTo(w * 0.62f, h * 0.1f)
            lineTo(w * 0.62f, h * 0.3f)
            lineTo(w * 0.82f, h * 0.3f)
        }
        drawPath(path = fold, color = tint, style = Stroke(width = stroke, cap = StrokeCap.Round))
    }
}

@Composable
fun ModernPhotoIcon(
    tint: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier.size(16.dp)
) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.08f

        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.1f, h * 0.15f),
            size = Size(w * 0.8f, h * 0.7f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.1f),
            style = Stroke(width = stroke)
        )
        drawCircle(
            color = tint,
            radius = w * 0.1f,
            center = Offset(w * 0.32f, h * 0.38f)
        )
        val mountain = Path().apply {
            moveTo(w * 0.2f, h * 0.72f)
            lineTo(w * 0.45f, h * 0.46f)
            lineTo(w * 0.6f, h * 0.62f)
            lineTo(w * 0.72f, h * 0.5f)
            lineTo(w * 0.8f, h * 0.72f)
        }
        drawPath(path = mountain, color = tint, style = Stroke(width = stroke, cap = StrokeCap.Round))
    }
}

@Composable
fun FlatWhiteLightningIcon(modifier: Modifier = Modifier.size(9.dp)) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.58f, 0f)
            lineTo(w * 0.12f, h * 0.55f)
            lineTo(w * 0.48f, h * 0.55f)
            lineTo(w * 0.42f, h)
            lineTo(w * 0.88f, h * 0.42f)
            lineTo(w * 0.52f, h * 0.42f)
            close()
        }
        drawPath(path = path, color = Color.White)
    }
}

fun getFileName(context: Context, uri: Uri): String {
    var result: String? = null
    if (uri.scheme == "content") {
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        result = cursor.getString(index)
                    }
                }
            }
        } catch (_: Exception) {}
    }
    if (result.isNullOrBlank()) {
        val path = uri.path
        if (path != null) {
            val cut = path.lastIndexOf('/')
            if (cut != -1) {
                result = path.substring(cut + 1)
            }
        }
    }
    return result ?: "attachment"
}

fun formatMode22ValueWithUnit(
    rawValue: Double,
    pid: Mode22PidDefinition,
    unitSystem: UnitSystem
): Pair<String, String> {
    return when (pid.unit) {
        "°C" -> {
            if (unitSystem == UnitSystem.SAE) {
                val fahr = (rawValue * 1.8) + 32.0
                Pair(String.format(java.util.Locale.US, "%.1f", fahr), "°F")
            } else {
                Pair(String.format(java.util.Locale.US, "%.1f", rawValue), "°C")
            }
        }
        "kPa" -> {
            if (unitSystem == UnitSystem.SAE) {
                val psi = rawValue * 0.145038
                Pair(String.format(java.util.Locale.US, "%.1f", psi), "psi")
            } else {
                Pair(String.format(java.util.Locale.US, "%.1f", rawValue), "kPa")
            }
        }
        "psi" -> {
            if (unitSystem == UnitSystem.METRIC) {
                val kpa = rawValue * 6.89476
                Pair(String.format(java.util.Locale.US, "%.1f", kpa), "kPa")
            } else {
                Pair(String.format(java.util.Locale.US, "%.1f", rawValue), "psi")
            }
        }
        "RPM" -> Pair(String.format(java.util.Locale.US, "%.0f", rawValue), "RPM")
        "V" -> Pair(String.format(java.util.Locale.US, "%.2f", rawValue), "V")
        "Ratio" -> Pair(String.format(java.util.Locale.US, "%.2f", rawValue), "Ratio")
        "%" -> Pair(String.format(java.util.Locale.US, "%.1f", rawValue), "%")
        "°" -> Pair(String.format(java.util.Locale.US, "%.1f", rawValue), "°")
        "counts" -> Pair(String.format(java.util.Locale.US, "%.0f", rawValue), "counts")
        "km" -> {
            if (unitSystem == UnitSystem.SAE) {
                val miles = rawValue * 0.621371
                Pair(String.format(java.util.Locale.US, "%,.0f", miles), "miles")
            } else {
                Pair(String.format(java.util.Locale.US, "%,.0f", rawValue), "km")
            }
        }
        else -> {
            Pair(String.format(java.util.Locale.US, "%.1f", rawValue), if (pid.unit.isBlank()) "value" else pid.unit)
        }
    }
}

fun formatObdbValueWithUnit(
    valObj: Any?,
    sig: Obdb.Signal?,
    sigId: String,
    unitSystem: UnitSystem
): Pair<String, String> {
    if (valObj == null) return Pair("--", "")
    val rawVal = (valObj as? Number)?.toDouble()
    val idLower = sigId.lowercase()
    val rawUnit = (sig?.unit?.lowercase()?.trim()?.takeIf { it.isNotBlank() }) ?: when {
        idLower.contains("rpm") -> "rpm"
        idLower.contains("temp") || idLower.contains("coolant") || idLower.contains("intake") || idLower.contains("ambient") -> "celsius"
        idLower.contains("fuel") || idLower.contains("level") || idLower.contains("charge") || idLower.contains("percent") || idLower.contains("soc") -> "percent"
        idLower.contains("pressure") || idLower.contains("psi") || idLower.contains("kpa") || idLower.contains("tire") || idLower.contains("baro") -> "psi"
        idLower.contains("speed") -> "kilometersperhour"
        idLower.contains("odo") || idLower.contains("distance") || idLower.contains("trip") -> "kilometers"
        idLower.contains("volt") -> "volts"
        idLower.contains("angle") || idLower.contains("deg") -> "degrees"
        else -> ""
    }

    if (rawVal != null) {
        return when (rawUnit) {
            "celsius", "°c" -> {
                if (unitSystem == UnitSystem.SAE) {
                    val fahr = (rawVal * 1.8) + 32.0
                    Pair(String.format(java.util.Locale.US, "%.1f", fahr), "°F")
                } else {
                    Pair(String.format(java.util.Locale.US, "%.1f", rawVal), "°C")
                }
            }
            "kilometers", "km" -> {
                if (unitSystem == UnitSystem.SAE) {
                    val miles = rawVal * 0.621371
                    Pair(String.format(java.util.Locale.US, "%.0f", miles), "miles")
                } else {
                    Pair(String.format(java.util.Locale.US, "%.0f", rawVal), "km")
                }
            }
            "kilometersperhour", "km/h" -> {
                if (unitSystem == UnitSystem.SAE) {
                    val mph = rawVal * 0.621371
                    Pair(String.format(java.util.Locale.US, "%.0f", mph), "mph")
                } else {
                    Pair(String.format(java.util.Locale.US, "%.0f", rawVal), "km/h")
                }
            }
            "kilopascal", "kpa" -> {
                if (unitSystem == UnitSystem.SAE) {
                    val psi = rawVal * 0.145038
                    Pair(String.format(java.util.Locale.US, "%.1f", psi), "psi")
                } else {
                    Pair(String.format(java.util.Locale.US, "%.1f", rawVal), "kPa")
                }
            }
            "psi" -> {
                if (unitSystem == UnitSystem.METRIC) {
                    val kpa = rawVal * 6.89476
                    Pair(String.format(java.util.Locale.US, "%.1f", kpa), "kPa")
                } else {
                    Pair(String.format(java.util.Locale.US, "%.1f", rawVal), "psi")
                }
            }
            "percent", "%" -> Pair(String.format(java.util.Locale.US, "%.1f", rawVal), "%")
            "volts", "v" -> Pair(String.format(java.util.Locale.US, "%.2f", rawVal), "V")
            "degrees", "°" -> Pair(String.format(java.util.Locale.US, "%.1f", rawVal), "°")
            "rpm" -> Pair(String.format(java.util.Locale.US, "%.0f", rawVal), "RPM")
            "seconds", "sec" -> Pair(String.format(java.util.Locale.US, "%.0f", rawVal), "sec")
            "offon" -> Pair(if (rawVal > 0) "ON" else "OFF", "")
            "scalar", "count", "counts" -> Pair(String.format(java.util.Locale.US, "%.0f", rawVal), "")
            else -> Pair(String.format(java.util.Locale.US, "%.1f", rawVal), rawUnit)
        }
    } else {
        val strVal = valObj.toString()
        if (rawUnit == "offon") {
            val isOff = strVal == "0" || strVal.equals("OFF", ignoreCase = true)
            return Pair(if (isOff) "OFF" else "ON", "")
        }
        return Pair(strVal, rawUnit)
    }
}

fun formatMode06ValueWithUnit(rawValue: Double, unit: String, unitSystem: UnitSystem): Pair<String, String> {
    return when (unit) {
        "°C" -> {
            if (unitSystem == UnitSystem.SAE) {
                val fahr = (rawValue * 1.8) + 32.0
                Pair(String.format(java.util.Locale.US, "%.0f", fahr), "°F")
            } else {
                Pair(String.format(java.util.Locale.US, "%.0f", rawValue), "°C")
            }
        }
        "kPa" -> {
            if (unitSystem == UnitSystem.SAE) {
                val psi = rawValue * 0.145038
                Pair(String.format(java.util.Locale.US, "%.1f", psi), "psi")
            } else {
                Pair(String.format(java.util.Locale.US, "%.1f", rawValue), "kPa")
            }
        }
        "Pa" -> {
            if (unitSystem == UnitSystem.SAE) {
                val psi = rawValue / 6894.76
                Pair(String.format(java.util.Locale.US, "%.2f", psi), "psi")
            } else {
                Pair(String.format(java.util.Locale.US, "%.0f", rawValue), "Pa")
            }
        }
        else -> {
            Pair(String.format(java.util.Locale.US, "%.0f", rawValue), unit)
        }
    }
}

fun getDtcDiagnosticGuidance(code: String, description: String = ""): String {
    return when {
        code.startsWith("MISFIRE") -> "Mode 06 Cylinder Misfire Monitor. Practical diagnostic steps:\n1. Swap ignition coil with a neighboring clean cylinder to see if misfire follows coil.\n2. Inspect spark plug gap and electrode condition.\n3. Check fuel injector pulse and vacuum leaks around intake manifold."
        code == "P0300" -> "Random/Multiple Cylinder Misfire. Check main intake vacuum leaks, fuel pressure, EGR valve sticking open, or mass air flow sensor contamination."
        code.startsWith("P03") -> "Single Cylinder Ignition/Misfire Fault. Inspect spark plug, swap coil pack to adjacent cylinder, check fuel injector connector and cylinder compression."
        code == "P0171" || code == "P0174" -> "System Too Lean. Common causes: Vacuum leak (cracked intake boot, PCV valve), dirty MAF sensor, low fuel pressure, or clogged fuel injectors."
        code == "P0172" || code == "P0175" -> "System Too Rich. Common causes: Leaking fuel injector, faulty fuel pressure regulator, dirty air filter, or failing O2 sensor."
        code == "P0420" || code == "P0430" -> "Catalyst System Efficiency Below Threshold. Inspect downstream O2 sensor output, exhaust leaks upstream of cat, or failing catalytic converter."
        code.startsWith("P04") -> "Emissions/EVAP System Fault. Check gas cap seal, EVAP purge solenoid valve, vent solenoid, and vacuum lines."
        else -> "Diagnostic trouble code recorded by engine control unit. Use OttoAI Assistant to analyze live telemetry and specific repair procedures."
    }
}

fun getStreamDiagnosticGuidance(pidHex: String): String {
    return when (pidHex) {
        "04" -> "Engine Load measures the percentage of maximum air charge. At idle, normal load is typically 15-35%. High load at idle indicates mechanical resistance or accessory drag."
        "05" -> "Engine Coolant Temp normal operating range is 85°C to 105°C (185°F to 220°F). Temps over 110°C indicate potential thermostat failure, low coolant, or fan malfunction."
        "06", "07", "08", "09" -> "Fuel Trims indicate ECU fuel corrections. Normal range is ±10%. Positive trim (>+10%) indicates lean condition (vacuum leak, dirty MAF). Negative trim (<-10%) indicates rich condition."
        "0C" -> "Engine RPM indicates crankshaft rotational speed. Normal warm idle is 600-900 RPM. Fluctuations indicate vacuum leaks, misfires, or IAC valve issues."
        "0D" -> "Vehicle Speed indicates speed output from transmission or wheel speed sensors."
        "0F", "46" -> "Intake & Ambient Air Temperature. High intake air temp reduces air density and timing advance."
        "10" -> "MAF measures mass airflow rate into engine in grams/sec. Rule of thumb: MAF g/s at idle roughly equals engine displacement in Liters (e.g. 2.0L engine ≈ 2.0 g/s at idle)."
        "11", "45", "47", "48" -> "Throttle Position measures throttle plate opening angle from 0% (closed) to 100% (WOT)."
        "24", "25", "34", "44" -> "Air-Fuel Ratio (Lambda). Stoichiometric ideal mixture is 14.7 A/F. Ratios >14.7 indicate lean mixture; <14.7 indicate rich mixture."
        "42" -> "Control Module Power Supply Voltage. Normal charging system voltage is 13.5V - 14.5V with engine running."
        else -> "Live telemetry stream reading directly from vehicle ECU via Mode 01 PID $pidHex."
    }
}

@Composable
fun SleekMetricTile(
    label: String,
    value: String,
    unit: String,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .height(66.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 4.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                fontSize = 9.sp,
                lineHeight = 10.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = value,
                fontSize = 14.sp,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )

            Text(
                text = if (unit.isNotBlank()) unit else " ",
                fontSize = 9.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1
            )
        }
    }
}


@Composable
fun TapeDeckRecordButton(
    isRecording: Boolean,
    durationSeconds: Int,
    sampleCount: Int,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit
) {
    if (!isRecording) {
        // Tape Deck Idle Record Button
        Surface(
            onClick = onStartRecording,
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFF2C1E1E),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFD32F2F).copy(alpha = 0.8f)),
            modifier = Modifier.padding(1.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Red Record Circle Dot
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(Color(0xFFFF1744), CircleShape)
                )
                Text(
                    text = "REC",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    color = Color(0xFFFF8A80)
                )
            }
        }
    } else {
        // Active Red RECORDING Indicator with Pulsing Light & Live Clock
        val infiniteTransition = rememberInfiniteTransition(label = "recPulseTransition")

        val pulseAlpha by infiniteTransition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(500, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "recDotPulse"
        )

        val minutes = durationSeconds / 60
        val seconds = durationSeconds % 60
        val timeFormatted = String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)

        Surface(
            onClick = onStopRecording,
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFFB71C1C),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF5252)),
            modifier = Modifier.padding(1.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Pulsing White/Red Light Dot
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .graphicsLayer { alpha = pulseAlpha }
                        .background(Color.White, CircleShape)
                )

                Text(
                    text = "REC $timeFormatted",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
fun Mode06MonitorCard(m06: Mode06TestResult, unitSystem: UnitSystem = UnitSystem.SAE) {
    val statusBgColor = when (m06.status) {
        Mode06Status.PASS -> Color(0xFF1B5E20)
        Mode06Status.WARNING -> Color(0xFFE65100)
        Mode06Status.FAIL -> Color(0xFFB71C1C)
        Mode06Status.UNKNOWN -> MaterialTheme.colorScheme.surfaceVariant
    }
    val statusTextColor = when (m06.status) {
        Mode06Status.PASS -> Color(0xFF81C784)
        Mode06Status.WARNING -> Color(0xFFFFB74D)
        Mode06Status.FAIL -> Color(0xFFFF8A80)
        Mode06Status.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val (formattedVal, displayUnit) = formatMode06ValueWithUnit(m06.testValue, m06.unit, unitSystem)

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = m06.category.uppercase(),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "MID $${m06.midHex}",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = statusBgColor
                ) {
                    Text(
                        text = m06.status.name,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = statusTextColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = m06.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Test Value & Limits Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Measured: $formattedVal $displayUnit",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (m06.status == Mode06Status.FAIL) Color(0xFFFF5252) else MaterialTheme.colorScheme.primary
                )

                if (m06.maxLimit != null) {
                    val (minStr, _) = formatMode06ValueWithUnit(m06.minLimit ?: 0.0, m06.unit, unitSystem)
                    val (maxStr, _) = formatMode06ValueWithUnit(m06.maxLimit, m06.unit, unitSystem)
                    Text(
                        text = "Min: $minStr | Max: $maxStr $displayUnit",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Visual Gauge Bar
            if (m06.maxLimit != null && m06.maxLimit > 0.0) {
                Spacer(modifier = Modifier.height(6.dp))
                val fraction = ((m06.testValue - (m06.minLimit ?: 0.0)) / (m06.maxLimit - (m06.minLimit ?: 0.0))).coerceIn(0.0, 1.0).toFloat()
                val barColor = when {
                    m06.status == Mode06Status.FAIL -> Color(0xFFFF5252)
                    fraction >= 0.85f -> Color(0xFFFFB74D)
                    else -> Color(0xFF81C784)
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fraction)
                            .background(barColor)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = m06.description,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun FullScreenMode06InspectorView(
    results: List<Mode06TestResult>,
    unitSystem: UnitSystem = UnitSystem.SAE,
    onBack: () -> Unit,
    onScan: () -> Unit
) {
    var activeCategory by remember { mutableStateOf("ALL") }

    val categories = remember(results) {
        listOf("ALL") + results.map { it.category }.distinct()
    }

    val filteredResults = remember(results, activeCategory) {
        if (activeCategory == "ALL") results else results.filter { it.category == activeCategory }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            // Top Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(
                    onClick = onBack,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text("← Dashboard", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }

                FilledTonalButton(
                    onClick = onScan,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text("Scan Mode 06", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "On-Board Diagnostic Monitor Inspector",
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "Mode 06 Component Test Results & Limits",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Category Filter Chips Row
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(categories) { cat ->
                    val isSelected = cat == activeCategory
                    FilterChip(
                        selected = isSelected,
                        onClick = { activeCategory = cat },
                        label = { Text(cat, fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (filteredResults.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No monitor tests available in this category. Tap 'Scan Mode 06' to evaluate ECU monitors.",
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredResults) { m06 ->
                        Mode06MonitorCard(m06, unitSystem)
                    }
                }
            }
        }
    }
}

@Composable
fun RealTimeSensorLineChart(
    history: List<StreamSample>,
    pid: ObdPidDefinition,
    unitSystem: UnitSystem,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    val timeFormat = remember { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US) }

    val convertedValues = remember(history, unitSystem) {
        history.map { sample ->
            val (valStr, _) = formatValueWithUnit(sample.value, pid, unitSystem)
            valStr.toDoubleOrNull() ?: sample.value
        }
    }

    LaunchedEffect(history.size) {
        if (!scrollState.isScrollInProgress && scrollState.maxValue > 0) {
            scrollState.scrollTo(scrollState.maxValue)
        }
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Live Telemetry Graph",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (scrollState.value >= scrollState.maxValue - 20) "LIVE CRAWLING • Swipe to scroll history" else "INSPECTING HISTORY",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (scrollState.value >= scrollState.maxValue - 20) Color(0xFF4CAF50) else Color(0xFFFFB74D)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (scrollState.value < scrollState.maxValue - 30) {
                        Surface(
                            onClick = {
                                coroutineScope.launch {
                                    scrollState.animateScrollTo(scrollState.maxValue)
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.padding(end = 6.dp)
                        ) {
                            Text(
                                text = "Jump to Live →",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                    Text(
                        text = "${history.size} pts",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            if (history.size < 2) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Streaming live data points to plot graph...",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                val minVal = (convertedValues.minOrNull() ?: 0.0)
                val maxVal = (convertedValues.maxOrNull() ?: 1.0)
                val range = if (maxVal == minVal) 1.0 else (maxVal - minVal)
                val primaryColor = MaterialTheme.colorScheme.primary

                val sampleSpacingDp = 24.dp
                val density = androidx.compose.ui.platform.LocalDensity.current
                val sampleSpacingPx = with(density) { sampleSpacingDp.toPx() }
                val chartWidthPx = (history.size - 1) * sampleSpacingPx
                val chartWidthDp = with(density) { chartWidthPx.toDp() }

                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .padding(bottom = 20.dp, top = 4.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = String.format(java.util.Locale.US, "%.1f", maxVal),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = String.format(java.util.Locale.US, "%.1f", minVal),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 36.dp)
                            .horizontalScroll(scrollState)
                    ) {
                        Column(
                            modifier = Modifier
                                .width(maxOf(280.dp, chartWidthDp))
                                .fillMaxHeight()
                        ) {
                            androidx.compose.foundation.Canvas(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .padding(vertical = 4.dp)
                            ) {
                                val w = size.width
                                val h = size.height

                                val gridLineColor = Color.Gray.copy(alpha = 0.15f)
                                for (step in 0..4) {
                                    val y = h * (step / 4.0f)
                                    drawLine(
                                        color = gridLineColor,
                                        start = Offset(0f, y),
                                        end = Offset(w, y),
                                        strokeWidth = 1f
                                    )
                                }

                                val path = Path()
                                convertedValues.forEachIndexed { idx, valPoint ->
                                    val normY = ((valPoint - minVal) / range).toFloat()
                                    val x = idx * sampleSpacingPx
                                    val y = h - (normY * h)

                                    if (idx == 0) {
                                        path.moveTo(x, y)
                                    } else {
                                        path.lineTo(x, y)
                                    }
                                }

                                drawPath(
                                    path = path,
                                    color = primaryColor,
                                    style = Stroke(
                                        width = 2.5.dp.toPx(),
                                        cap = StrokeCap.Round
                                    )
                                )

                                val lastIdx = convertedValues.size - 1
                                val lastNormY = ((convertedValues[lastIdx] - minVal) / range).toFloat()
                                val lastX = lastIdx * sampleSpacingPx
                                val lastY = h - (lastNormY * h)

                                drawCircle(
                                    color = primaryColor,
                                    radius = 5.dp.toPx(),
                                    center = Offset(lastX, lastY)
                                )
                                drawCircle(
                                    color = Color.White,
                                    radius = 2.5.dp.toPx(),
                                    center = Offset(lastX, lastY)
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(18.dp)
                            ) {
                                history.forEachIndexed { idx, sample ->
                                    if (idx % 3 == 0 || idx == history.size - 1) {
                                        val timeStr = timeFormat.format(java.util.Date(sample.timestamp))
                                        val xDp = with(density) { (idx * sampleSpacingPx).toDp() }
                                        Text(
                                            text = timeStr,
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (idx == history.size - 1) primaryColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                            modifier = Modifier.offset(x = maxOf(0.dp, xDp - 20.dp))
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FullScreenStreamDetailView(
    stream: LiveStreamData,
    unitSystem: UnitSystem,
    onBack: () -> Unit
) {
    val (formattedVal, formattedUnit) = formatValueWithUnit(stream.rawValue, stream.pid, unitSystem)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(
                    onClick = onBack,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text("← Back to Dashboard", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "Mode 01 PID 01${stream.pid.pidHex}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }

                        Surface(
                            color = Color(0xFF1B5E20),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "LIVE STREAMING",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFF81C784),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = stream.pid.name,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Text(
                        text = stream.pid.description,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "$formattedVal $formattedUnit",
                        fontSize = 42.sp,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            RealTimeSensorLineChart(
                history = stream.history,
                pid = stream.pid,
                unitSystem = unitSystem,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Mechanic Diagnostic Guidance",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = getStreamDiagnosticGuidance(stream.pid.pidHex),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Main Diagnostic App Screen
// -----------------------------------------------------------------------------

@SuppressLint("MissingPermission")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OttoMainScreen(
    modifier: Modifier = Modifier,
    viewModel: GeminiAgentViewModel = viewModel()
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val availableDevices = remember(expanded) { viewModel.getAvailableDevices(context) }
    var unitDropdownExpanded by remember { mutableStateOf(false) }

    val demoCleanLabel = "🚗 Demo: Clean ECU (Healthy Vehicle)"
    val demoDirtyLabel = "⚠️ Demo: Dirty ECU (Cyl 5 Misfire & P0305)"

    var selectedDeviceText by remember { 
        mutableStateOf(
            if (availableDevices.isNotEmpty()) availableDevices[0].name
            else demoCleanLabel
        )
    }
    var macAddress by remember { 
        mutableStateOf(
            if (availableDevices.isNotEmpty()) availableDevices[0].address
            else "DEMO_SIMULATOR_CLEAN"
        ) 
    }
    var useManualInput by remember { mutableStateOf(false) }

    var userPrompt by remember { mutableStateOf("") }
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val metrics by viewModel.obdManager.metrics.collectAsState()
    val supportedStreams by viewModel.supportedStreams.collectAsState()
    val vehicleDetails by viewModel.vehicleDetails.collectAsState()
    val vehicleVin by viewModel.vehicleVin.collectAsState()
    val vehicleOdometerKm by viewModel.vehicleOdometerKm.collectAsState()
    val troubleCodes by viewModel.troubleCodes.collectAsState()
    val dtcDetails by viewModel.dtcDetails.collectAsState()
    val mode06Results by viewModel.mode06Results.collectAsState()
    val mode22Streams by viewModel.mode22Streams.collectAsState()
    val unitSystem by viewModel.unitSystem.collectAsState()
    val obdbLoadedResult by viewModel.obdbLoadedResult.collectAsState()
    val obdbSignalResults by viewModel.obdbSignalResults.collectAsState()

    val odoFormatted = remember(vehicleOdometerKm, unitSystem) {
        vehicleOdometerKm?.let { km ->
            if (unitSystem == UnitSystem.SAE) {
                val miles = (km * 0.621371).toInt()
                "%,d mi".format(miles)
            } else {
                "%,d km".format(km.toInt())
            }
        }
    }

    val isConnecting by viewModel.isConnecting.collectAsState()
    val isScanningDtcs by viewModel.isScanningDtcs.collectAsState()
    val hasScannedDtcs by viewModel.hasScannedDtcs.collectAsState()
    val isScanningMode06 by viewModel.isScanningMode06.collectAsState()
    val isScanningMode22 by viewModel.isScanningMode22.collectAsState()
    val isScanningObdb by viewModel.isScanningObdb.collectAsState()
    val isSendingMessage by viewModel.isSendingMessage.collectAsState()
    val isTtsEnabled by viewModel.isTtsEnabled.collectAsState()
    val isSpeaking by viewModel.isSpeaking.collectAsState()
    val isLiveSessionActive by viewModel.isLiveSessionActive.collectAsState()
    val isHandsFreeVoiceActive by viewModel.isHandsFreeVoiceActive.collectAsState()
    val isGeminiSpeaking by viewModel.isGeminiSpeaking.collectAsState()
    val liveVoiceName by viewModel.liveVoiceName.collectAsState()
    val savedSessions by viewModel.savedSessions.collectAsState()
    val currentSessionId by viewModel.currentSessionId.collectAsState()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.initSessionManager(context)
    }

    DisposableEffect(isLiveSessionActive) {
        val activity = context as? Activity
        if (isLiveSessionActive) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.toggleHandsFreeVoiceMode()
        } else {
            Toast.makeText(context, "Microphone permission is required for voice mode.", Toast.LENGTH_SHORT).show()
        }
    }

    var isEcuProgrammingExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(context) {
        viewModel.obdManager.setLogFileDirectory(context.getExternalFilesDir(null))
        viewModel.initObdbRepository(context)
        viewModel.initTts(context)
        viewModel.initLiveStreamManager(context)
    }

    // USB OTG Hot-Plug & Auto-Permission Receiver for Wired Adapters (Vgate vLinker FS USB)
    DisposableEffect(context) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
                val action = intent?.action ?: return
                when (action) {
                    "com.ottoai.app.USB_PERMISSION" -> {
                        val granted = intent.getBooleanExtra(android.hardware.usb.UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        if (granted) {
                            Toast.makeText(context, "🔌 USB Permission granted! Connecting to Vgate vLinker FS...", Toast.LENGTH_SHORT).show()
                            viewModel.connectToCar(macAddress, context)
                        } else {
                            Toast.makeText(context, "USB Permission denied.", Toast.LENGTH_SHORT).show()
                        }
                    }
                    android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        Toast.makeText(context, "🔌 Wired USB OBD2 Adapter Connected!", Toast.LENGTH_SHORT).show()
                        val available = viewModel.getAvailableDevices(context)
                        val usbDev = available.find { it.isUsb }
                        if (usbDev != null) {
                            selectedDeviceText = usbDev.name
                            macAddress = usbDev.address
                        }
                    }
                    android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        Toast.makeText(context, "🔌 Wired USB OBD2 Adapter Disconnected.", Toast.LENGTH_SHORT).show()
                        viewModel.disconnectFromCar()
                    }
                }
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction("com.ottoai.app.USB_PERMISSION")
            addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        androidx.core.content.ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {}
        }
    }

    val saveFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            try {
                val transcript = generateChatTranscriptText(viewModel.messages, vehicleDetails, vehicleVin, odoFormatted)
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(transcript.toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(context, "Chat log saved successfully!", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                android.util.Log.e("OttoAI", "Error saving chat file", e)
                Toast.makeText(context, "Failed to save chat file: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    var pendingCsvResult by remember { mutableStateOf<RecordedCsvResult?>(null) }
    var showSaveDiscardDialog by remember { mutableStateOf(false) }
    var stoppedCsvResult by remember { mutableStateOf<RecordedCsvResult?>(null) }

    val saveCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        val result = pendingCsvResult
        if (uri != null && result != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(result.csvContent.toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(context, "Live data CSV saved (${result.sampleCount} samples)!", Toast.LENGTH_LONG).show()
                viewModel.onCsvSavedSuccessfully(result)
            } catch (e: Exception) {
                android.util.Log.e("OttoAI", "Error saving CSV file", e)
                Toast.makeText(context, "Failed to save CSV file: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
        pendingCsvResult = null
    }

    var attachedFiles by remember { mutableStateOf<List<ChatAttachment>>(emptyList()) }
    var showAttachmentMenu by remember { mutableStateOf(false) }
    var showDongleLogDialog by remember { mutableStateOf(false) }

    var chatCardTopY by remember { mutableFloatStateOf(0f) }
    var chatCardHeight by remember { mutableFloatStateOf(0f) }
    var inputBarHeightPx by remember { mutableFloatStateOf(0f) }

    // Live ELM327 Command & Error Trace Dialog
    if (showDongleLogDialog) {
        val rawLogText = viewModel.getRawElmTraceText()
        val elmLogList by viewModel.elmCommandLog.collectAsState()

        AlertDialog(
            onDismissRequest = { showDongleLogDialog = false },
            title = {
                Text(text = "Live ELM327 Command & Error Trace", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            },
            text = {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF1E1E1E)
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp)
                    ) {
                        items(elmLogList) { line ->
                            Text(
                                text = line,
                                fontSize = 10.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                color = if (line.contains("ERROR") || line.contains("7F") || line.contains("NODATA")) Color(0xFFFF8A80) else Color(0xFF81C784)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDongleLogDialog = false
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText("ELM327 Trace Log", rawLogText)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Copied raw ELM327 trace to clipboard!", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Copy Trace Log", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        showDongleLogDialog = false
                        viewModel.sendMessageToAgent(
                            userText = "Analyze this raw ELM327 Bluetooth OBD2 command and response trace log. Diagnose any CAN protocol or header response anomalies:\n\n$rawLogText"
                        )
                    }
                ) {
                    Text("Analyze Trace 🤖")
                }
            }
        )
    }



    // Multi-File & CSV Launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            for (uri in uris) {
                val fileName = getFileName(context, uri)
                val isImg = fileName.endsWith(".jpg", ignoreCase = true) ||
                            fileName.endsWith(".jpeg", ignoreCase = true) ||
                            fileName.endsWith(".png", ignoreCase = true) ||
                            fileName.endsWith(".webp", ignoreCase = true)

                if (isImg) {
                    val base64 = viewModel.uriToBase64(context, uri)
                    attachedFiles = attachedFiles + ChatAttachment(
                        fileName = fileName,
                        isImage = true,
                        uri = uri,
                        base64Image = base64
                    )
                } else {
                    val textContent = try {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            stream.bufferedReader().readText()
                        }
                    } catch (e: Exception) {
                        null
                    }
                    if (!textContent.isNullOrBlank()) {
                        attachedFiles = attachedFiles + ChatAttachment(
                            fileName = fileName,
                            isImage = false,
                            uri = uri,
                            textContent = textContent
                        )
                    } else {
                        Toast.makeText(context, "Could not read text content from $fileName", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    if (showSaveDiscardDialog && stoppedCsvResult != null) {
        val result = stoppedCsvResult!!
        AlertDialog(
            onDismissRequest = {
                showSaveDiscardDialog = false
            },
            title = {
                Text(
                    text = "Recording Stopped",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            },
            text = {
                Text(
                    text = "Captured ${result.sampleCount} telemetry samples (${String.format(java.util.Locale.US, "%.1f", result.durationSeconds)}s duration).\n\nWhat would you like to do with this live data log?",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Save Button
                    OutlinedButton(
                        onClick = {
                            showSaveDiscardDialog = false
                            pendingCsvResult = result
                            stoppedCsvResult = null
                            saveCsvLauncher.launch(result.defaultFilename)
                        }
                    ) {
                        Text("Save", fontWeight = FontWeight.Bold)
                    }

                    // Analyze Button (Saves and attaches CSV to Chat for analysis)
                    Button(
                        onClick = {
                            showSaveDiscardDialog = false
                            pendingCsvResult = result
                            stoppedCsvResult = null

                            val newAttachment = ChatAttachment(
                                fileName = result.defaultFilename,
                                isImage = false,
                                textContent = result.csvContent
                            )
                            val updatedAttachments = attachedFiles + newAttachment
                            attachedFiles = updatedAttachments

                            viewModel.onCsvAttachedForAnalysis(result)
                            saveCsvLauncher.launch(result.defaultFilename)

                            viewModel.sendMessageToAgent(
                                userText = "Please analyze my recorded drive session data log. Summarize key telemetry metrics, identify any anomalies or issues, and provide your diagnostic conclusions.",
                                attachments = updatedAttachments
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("Analyze", fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showSaveDiscardDialog = false
                        stoppedCsvResult = null
                        Toast.makeText(context, "Recorded session data discarded.", Toast.LENGTH_SHORT).show()
                        viewModel.onDiscardRecordedData()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFF5252))
                ) {
                    Text("Discard", fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    fun shareChatTranscript() {
        val transcript = generateChatTranscriptText(viewModel.messages, vehicleDetails, vehicleVin, odoFormatted)
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, transcript)
            putExtra(Intent.EXTRA_SUBJECT, "OttoAI Diagnostic Log - ${vehicleDetails ?: "Vehicle"}")
            type = "text/plain"
        }
        val shareIntent = Intent.createChooser(sendIntent, "Share OttoAI Diagnostic Chat")
        context.startActivity(shareIntent)
    }

    fun saveChatToTextFile() {
        val timeStr = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val defaultFilename = "OttoAI_ChatLog_$timeStr.txt"
        saveFileLauncher.launch(defaultFilename)
    }


    var selectedDtcForDetail by remember { mutableStateOf<DtcDetails?>(null) }
    var selectedStreamForDetail by remember { mutableStateOf<LiveStreamData?>(null) }
    var selectedMode06ForDetail by remember { mutableStateOf<Mode06TestResult?>(null) }
    var showFullMode06Inspector by remember { mutableStateOf(false) }
    var selectedDashboardCategory by remember { mutableStateOf("ALL") }
    var isDtcExpanded by remember { mutableStateOf(false) }
    var isLiveStreamsExpanded by remember { mutableStateOf(true) }
    var isMode06Expanded by remember { mutableStateOf(false) }
    var isMode22Expanded by remember { mutableStateOf(false) }
    val carouselListState = rememberLazyListState()
    val chatListState = rememberLazyListState()

    LaunchedEffect(viewModel.messages.size) {
        if (viewModel.messages.isNotEmpty()) {
            chatListState.animateScrollToItem(viewModel.messages.size - 1)
        }
    }

    val vehicleMakeTitle = remember(vehicleDetails) {
        if (!vehicleDetails.isNullOrBlank()) {
            val parts = vehicleDetails!!.split("•")
            if (parts.isNotEmpty() && parts[0].isNotBlank()) parts[0].trim() else "Vehicle"
        } else "Vehicle"
    }
    val mode22SectionTitle = "$vehicleMakeTitle Custom PIDs"

    val isConnected = connectionStatus == "Connected"

    if (showFullMode06Inspector) {
        FullScreenMode06InspectorView(
            results = mode06Results,
            unitSystem = unitSystem,
            onBack = { showFullMode06Inspector = false },
            onScan = { viewModel.scanMode06Monitors() }
        )
        return
    }

    if (selectedStreamForDetail != null) {
        val activeStream = supportedStreams.find { it.pid.pidHex == selectedStreamForDetail?.pid?.pidHex } ?: selectedStreamForDetail!!
        FullScreenStreamDetailView(
            stream = activeStream,
            unitSystem = unitSystem,
            onBack = { selectedStreamForDetail = null }
        )
        return
    }

    val mainScrollState = rememberScrollState()

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(320.dp),
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                drawerContentColor = MaterialTheme.colorScheme.onSurface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    // Drawer Header
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Chat,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "OttoAI Chats",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        IconButton(onClick = { coroutineScope.launch { drawerState.close() } }) {
                            Icon(Icons.Default.Close, contentDescription = "Close Sidebar")
                        }
                    }

                    // + New Chat Button
                    FilledTonalButton(
                        onClick = {
                            viewModel.createNewChatSession()
                            coroutineScope.launch { drawerState.close() }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("New Diagnostic Chat", fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "Saved Drive Sessions",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )

                    if (savedSessions.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No saved drive sessions yet.\nChats are automatically logged in your history.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(savedSessions, key = { it.id }) { session ->
                                val isCurrent = session.id == currentSessionId
                                Surface(
                                    onClick = {
                                        viewModel.loadChatSession(session.id)
                                        coroutineScope.launch { drawerState.close() }
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    border = if (isCurrent) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = session.title,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = session.formattedDate,
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            val metaText = buildList {
                                                session.vehicleDetails?.let { add(it.split("•").first().trim()) }
                                                session.odometerFormatted?.let { add(it) }
                                                add("${session.messages.size} msgs")
                                                if (session.dtcs.isNotEmpty()) add("${session.dtcs.size} DTC")
                                            }.joinToString(" • ")

                                            Text(
                                                text = metaText,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = if (session.dtcs.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                            )
                                        }

                                        IconButton(
                                            onClick = { viewModel.deleteChatSession(session.id) },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.DeleteOutline,
                                                contentDescription = "Delete Session",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    ) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            // 1. PINNED TOP HEADER (Always Fixed at Top of Screen)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { coroutineScope.launch { drawerState.open() } },
                        modifier = Modifier.size(36.dp).padding(end = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "Open Chat History Sidebar",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    Column {
                        Text(
                            text = "OttoAI Diagnostic",
                            fontSize = 19.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        if (isConnected && (!vehicleDetails.isNullOrBlank() || !odoFormatted.isNullOrBlank())) {
                            val rawDetails = vehicleDetails ?: "Connected Vehicle"
                            val parts = rawDetails.split("•").map { it.trim() }
                            val (makeModelYearText, vinText) = if (parts.size >= 2) {
                                val lastPart = parts.last()
                                if (lastPart.startsWith("VIN:", ignoreCase = true)) {
                                    Pair(parts.dropLast(1).joinToString(" • "), lastPart)
                                } else {
                                    Pair(rawDetails, "")
                                }
                            } else {
                                Pair(rawDetails, "")
                            }

                            Text(
                                text = makeModelYearText,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            val subDetails = buildList {
                                if (vinText.isNotBlank()) add(vinText)
                                if (!odoFormatted.isNullOrBlank()) add("ODO: $odoFormatted")
                            }.joinToString(" • ")

                            if (subDetails.isNotBlank()) {
                                Text(
                                    text = subDetails,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            Text(
                                text = "Autonomous Automotive Agent",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isConnected) {
                    FilledTonalButton(
                        onClick = { viewModel.disconnectFromCar() },
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.padding(end = 6.dp)
                    ) {
                        Text("Disconnect", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Connection Status Pill
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (isConnected) Color(0xFF1B5E20) else Color(0xFF3E2723),
                    contentColor = if (isConnected) Color(0xFF81C784) else Color(0xFFFF8A80),
                    modifier = Modifier.border(
                        width = 1.dp,
                        color = if (isConnected) Color(0xFF4CAF50) else Color(0xFFE53935),
                        shape = RoundedCornerShape(20.dp)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (isConnected) Color(0xFF4CAF50) else Color(0xFFE53935))
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = connectionStatus,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // 2. UNIFIED SINGLE SCROLL COLUMN (Section Headers + Chat Card)
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val availableHeight = this.maxHeight
            val density = LocalDensity.current
            val screenHeightPx = with(density) { availableHeight.toPx() }
            val scrollY = mainScrollState.value.toFloat()

            val chatHeaderTopOnScreen = chatCardTopY - scrollY
            val inputBarTranslationY = if (inputBarHeightPx > 0f && chatCardTopY > 0f) {
                (chatHeaderTopOnScreen - (screenHeightPx - inputBarHeightPx)).coerceAtLeast(0f)
            } else {
                0f
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(mainScrollState)
            ) {

        // 2. Dongle Box Header Card (Hidden when connected)
        if (!isConnected) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                )
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (useManualInput) {
                            OutlinedTextField(
                                value = macAddress,
                                onValueChange = { macAddress = it },
                                label = { Text("ELM327 MAC / USB ID", fontSize = 11.sp) },
                                textStyle = TextStyle(fontSize = 12.sp),
                                shape = RoundedCornerShape(10.dp),
                                singleLine = true,
                                trailingIcon = {
                                    TextButton(onClick = { useManualInput = false }) {
                                        Text("Devices 📋", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            ExposedDropdownMenuBox(
                                expanded = expanded,
                                onExpandedChange = { expanded = !expanded },
                                modifier = Modifier.weight(1f)
                            ) {
                                OutlinedTextField(
                                    value = selectedDeviceText,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("Select OBD2 Dongle (Wired/Bluetooth)", fontSize = 11.sp) },
                                    textStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                                    shape = RoundedCornerShape(10.dp),
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                                    modifier = Modifier
                                        .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true)
                                        .fillMaxWidth()
                                )
                                ExposedDropdownMenu(
                                    expanded = expanded,
                                    onDismissRequest = { expanded = false }
                                ) {
                                    // Option 1: Virtual Clean ECU Simulator
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                demoCleanLabel,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        },
                                        onClick = {
                                            selectedDeviceText = demoCleanLabel
                                            macAddress = "DEMO_SIMULATOR_CLEAN"
                                            useManualInput = false
                                            expanded = false
                                        }
                                    )

                                    // Option 2: Virtual Dirty ECU Simulator (Faulty Cyl 5 Misfire & P0305)
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                demoDirtyLabel,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFFFF5252)
                                            )
                                        },
                                        onClick = {
                                            selectedDeviceText = demoDirtyLabel
                                            macAddress = "DEMO_SIMULATOR_DIRTY"
                                            useManualInput = false
                                            expanded = false
                                        }
                                    )

                                    if (availableDevices.isNotEmpty()) {
                                        HorizontalDivider(
                                            modifier = Modifier.padding(vertical = 4.dp),
                                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                                        )

                                        availableDevices.forEach { device ->
                                            DropdownMenuItem(
                                                text = { 
                                                    Text(
                                                        device.name, 
                                                        fontSize = 12.sp,
                                                        fontWeight = if (device.isUsb) FontWeight.ExtraBold else FontWeight.Normal,
                                                        color = if (device.isUsb) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                                    ) 
                                                },
                                                onClick = {
                                                    selectedDeviceText = device.name
                                                    macAddress = device.address
                                                    expanded = false
                                                }
                                            )
                                        }
                                    }

                                    HorizontalDivider(
                                        modifier = Modifier.padding(vertical = 4.dp),
                                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                                    )

                                    // Option 3: Enter MAC Manually
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "✏️ Enter MAC Address Manually...",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        },
                                        onClick = {
                                            useManualInput = true
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        FilledTonalButton(
                            onClick = { viewModel.connectToCar(macAddress, context) },
                            enabled = !isConnecting,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            if (isConnecting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Connecting...", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            } else {
                                Text("Connect", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
        }

        // 3.0 Scan DTC's Section (First header on page, above Live Data Streams)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { isDtcExpanded = !isDtcExpanded }
                .padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Scan DTC's",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (isDtcExpanded) " ▲" else " ▼",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                val subheaderText = when {
                    troubleCodes.isNotEmpty() -> "${troubleCodes.size} Faults Detected"
                    hasScannedDtcs -> "ECU Status: CLEAN (0 Faults)"
                    else -> "Diagnostic Trouble Codes"
                }
                val subheaderColor = when {
                    troubleCodes.isNotEmpty() -> Color(0xFFFF5252)
                    hasScannedDtcs -> Color(0xFF4CAF50)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(
                    text = subheaderText,
                    fontSize = 10.sp,
                    fontWeight = if (hasScannedDtcs || troubleCodes.isNotEmpty()) FontWeight.Bold else FontWeight.Normal,
                    color = subheaderColor
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(
                    onClick = { viewModel.scanTroubleCodes() },
                    enabled = !isScanningDtcs,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    if (isScanningDtcs) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Scanning...", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Text("Scan", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = isDtcExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column {
                Spacer(modifier = Modifier.height(4.dp))
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    )
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        if (dtcDetails.isNotEmpty()) {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(dtcDetails) { dtc ->
                                    Surface(
                                        onClick = { selectedDtcForDetail = dtc },
                                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.border(0.5.dp, Color(0xFFFF5252), RoundedCornerShape(8.dp))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = dtc.code,
                                                fontWeight = FontWeight.ExtraBold,
                                                fontSize = 12.sp,
                                                color = Color(0xFFFF5252)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = "[${dtc.category}]",
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        } else if (hasScannedDtcs) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                color = Color(0xFF1B5E20).copy(alpha = 0.15f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF4CAF50).copy(alpha = 0.5f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = androidx.compose.foundation.shape.CircleShape,
                                        color = Color(0xFF4CAF50)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier
                                                .padding(2.dp)
                                                .size(13.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = "NO DIAGNOSTIC TROUBLE CODES FOUND",
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 11.sp,
                                            color = Color(0xFF4CAF50)
                                        )
                                        Text(
                                            text = "ECU Status: Clean • All active, pending & permanent DTC modes clear",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        } else {
                            Text(
                                text = "Diagnostic Trouble Codes (DTCs) logged by the ECU. Tap 'Scan' to perform a complete diagnostic trouble code scan across active, pending, and permanent modes.",
                                fontSize = 10.sp,
                                lineHeight = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 3. Two Rows Horizontal Scrolling Live Data Carousel with Pulldown Unit Selector & Twirldown Toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.clickable { isLiveStreamsExpanded = !isLiveStreamsExpanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isConnected && supportedStreams.isNotEmpty()) "Live Streams (${supportedStreams.size})" else "Live Data Streams",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (isLiveStreamsExpanded) " ▲" else " ▼",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(6.dp))

                // Pulldown Unit Selector (SAE vs Metric)
                Box {
                    Surface(
                        onClick = { unitDropdownExpanded = true },
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.padding(1.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (unitSystem == UnitSystem.SAE) "SAE (US)" else "Metric (ISO)",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(" ▾", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                    }

                    DropdownMenu(
                        expanded = unitDropdownExpanded,
                        onDismissRequest = { unitDropdownExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("SAE / Imperial (°F, mph, psi)", fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                            onClick = {
                                viewModel.setUnitSystem(UnitSystem.SAE)
                                unitDropdownExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Metric / ISO (°C, km/h, kPa)", fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                            onClick = {
                                viewModel.setUnitSystem(UnitSystem.METRIC)
                                unitDropdownExpanded = false
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Tape Deck Record Button / RECORDING Indicator
                val isRecording by viewModel.isRecording.collectAsState()
                val recordingDurationSec by viewModel.recordingDurationSeconds.collectAsState()
                val recordingSampleCount by viewModel.recordingSampleCount.collectAsState()

                TapeDeckRecordButton(
                    isRecording = isRecording,
                    durationSeconds = recordingDurationSec,
                    sampleCount = recordingSampleCount,
                    onStartRecording = { viewModel.startRecording() },
                    onStopRecording = {
                        val csvResult = viewModel.stopRecording()
                        if (csvResult.sampleCount > 0) {
                            stoppedCsvResult = csvResult
                            showSaveDiscardDialog = true
                        } else {
                            Toast.makeText(context, "No telemetry data recorded.", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }

            Text(
                text = if (isConnected) "Tap to inspect • Swipe ←" else "Disconnected",
                fontSize = 10.sp,
                color = if (isConnected) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        AnimatedVisibility(
            visible = isLiveStreamsExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column {
                Spacer(modifier = Modifier.height(4.dp))

                // Chunk live streams into pairs for 2 rows in horizontal carousel
                val streamTileChunks = remember(supportedStreams, metrics, isConnected, unitSystem) {
                    if (isConnected && supportedStreams.isNotEmpty()) {
                        supportedStreams.chunked(2)
                    } else {
                        emptyList()
                    }
                }

                if (isConnected && supportedStreams.isNotEmpty()) {
                    LazyRow(
                        state = carouselListState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(streamTileChunks) { columnItems ->
                            Column(
                                modifier = Modifier.width(102.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                columnItems.forEach { stream ->
                                    val (formattedVal, formattedUnit) = formatValueWithUnit(stream.rawValue, stream.pid, unitSystem)
                                    SleekMetricTile(
                                        label = stream.pid.name,
                                        value = formattedVal,
                                        unit = formattedUnit,
                                        onClick = {
                                            if (!carouselListState.isScrollInProgress) {
                                                selectedStreamForDetail = stream
                                            }
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(66.dp)
                                    )
                                }
                                if (columnItems.size < 2) {
                                    Spacer(modifier = Modifier.height(66.dp))
                                }
                            }
                        }
                    }
                } else {
                    // Default placeholder grid when disconnected or before stream discovery
                    val speedUnit = if (unitSystem == UnitSystem.SAE) "mph" else "km/h"
                    val tempUnit = if (unitSystem == UnitSystem.SAE) "°F" else "°C"

                    val displaySpeed = if (isConnected) {
                        if (unitSystem == UnitSystem.SAE) String.format(java.util.Locale.US, "%.0f", metrics.speed * 0.621371) else "${metrics.speed}"
                    } else "--"

                    val displayCoolant = if (isConnected) {
                        if (unitSystem == UnitSystem.SAE) String.format(java.util.Locale.US, "%.0f", (metrics.coolantTemp * 1.8) + 32) else "${metrics.coolantTemp}"
                    } else "--"

                    val displayIntake = if (isConnected) {
                        if (unitSystem == UnitSystem.SAE) String.format(java.util.Locale.US, "%.0f", (metrics.intakeAirTemp * 1.8) + 32) else "${metrics.intakeAirTemp}"
                    } else "--"

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SleekMetricTile("RPM", if (isConnected) "${metrics.rpm}" else "--", "RPM", modifier = Modifier.weight(1f))
                        SleekMetricTile("Speed", displaySpeed, speedUnit, modifier = Modifier.weight(1f))
                        SleekMetricTile("Coolant", displayCoolant, tempUnit, modifier = Modifier.weight(1f))
                        SleekMetricTile("Load", if (isConnected) "${metrics.engineLoad}" else "--", "%", modifier = Modifier.weight(1f))
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SleekMetricTile("Fuel Trim", if (isConnected) "${metrics.fuelTrim}" else "--", "%", modifier = Modifier.weight(1f))
                        SleekMetricTile("MAF", if (isConnected) String.format("%.1f", metrics.maf) else "--", "g/s", modifier = Modifier.weight(1f))
                        SleekMetricTile("Intake", displayIntake, tempUnit, modifier = Modifier.weight(1f))
                        SleekMetricTile("Throttle", if (isConnected) "${metrics.throttlePosition}" else "--", "%", modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 3.5 Mode 06 Monitors Panel Header (Flush Left)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { isMode06Expanded = !isMode06Expanded }
                .padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Mode 06 Monitors",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (isMode06Expanded) " ▲" else " ▼",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = if (mode06Results.isEmpty()) "Component Tests & Limits" else "${mode06Results.size} Monitors Evaluated",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    onClick = { showFullMode06Inspector = true },
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.padding(end = 6.dp)
                ) {
                    Text(
                        text = "Inspector 🔍",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                FilledTonalButton(
                    onClick = { viewModel.scanMode06Monitors() },
                    enabled = !isScanningMode06,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    if (isScanningMode06) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Scanning...", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Text("Scan Mode 06", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = isMode06Expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column {
                Spacer(modifier = Modifier.height(4.dp))
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    )
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        if (mode06Results.isNotEmpty()) {
                            // Dashboard Category Chips
                            val categories = listOf("ALL", "Misfire", "Catalyst", "O2 Sensor", "EVAP System", "EGR / VVT")
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(categories) { cat ->
                                    val isSelected = cat == selectedDashboardCategory
                                    Surface(
                                        onClick = { selectedDashboardCategory = cat },
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        Text(
                                            text = cat,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            val filteredTiles = remember(mode06Results, selectedDashboardCategory) {
                                if (selectedDashboardCategory == "ALL") mode06Results else mode06Results.filter { it.category == selectedDashboardCategory }
                            }

                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(filteredTiles) { m06 ->
                                    val statusBgColor = when (m06.status) {
                                        Mode06Status.PASS -> Color(0xFF1B5E20)
                                        Mode06Status.WARNING -> Color(0xFFE65100)
                                        Mode06Status.FAIL -> Color(0xFFB71C1C)
                                        Mode06Status.UNKNOWN -> MaterialTheme.colorScheme.surfaceVariant
                                    }
                                    val statusTextColor = when (m06.status) {
                                        Mode06Status.PASS -> Color(0xFF81C784)
                                        Mode06Status.WARNING -> Color(0xFFFFB74D)
                                        Mode06Status.FAIL -> Color(0xFFFF8A80)
                                        Mode06Status.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }

                                    Surface(
                                        onClick = { selectedMode06ForDetail = m06 },
                                        shape = RoundedCornerShape(10.dp),
                                        color = MaterialTheme.colorScheme.surface,
                                        border = androidx.compose.foundation.BorderStroke(0.5.dp, statusTextColor.copy(alpha = 0.5f)),
                                        modifier = Modifier.width(105.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(8.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Text(
                                                text = m06.name.replace("Misfires", "").replace("Cylinder", "Cyl").replace("Monitor", "").trim(),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = "${m06.testValue.toInt()}",
                                                fontSize = 18.sp,
                                                fontWeight = FontWeight.Black,
                                                color = if (m06.status == Mode06Status.FAIL) Color(0xFFFF5252) else MaterialTheme.colorScheme.onSurface
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = statusBgColor
                                            ) {
                                                Text(
                                                    text = m06.status.name,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = statusTextColor,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            Text(
                                text = "Mode 06 evaluates on-board ECU component monitors (misfires, catalyst, O2 sensors, EVAP). Tap 'Scan Mode 06' or 'Inspector 🔍' to view test results.",
                                fontSize = 10.sp,
                                lineHeight = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 3.8 [Make/Model] Custom PIDs Panel Header (Flush Left)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { isMode22Expanded = !isMode22Expanded }
                .padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = mode22SectionTitle,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (isMode22Expanded) " ▲" else " ▼",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = if (mode22Streams.isEmpty()) "Mode 22 Enhanced PIDs (TFT, Oil Temp, Pressure)" else "${mode22Streams.size} Parameters Active",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(
                    onClick = { viewModel.scanMode22Parameters() },
                    enabled = !isScanningMode22,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    if (isScanningMode22) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Scanning...", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Text("Scan Mode 22", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = isMode22Expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column {
                Spacer(modifier = Modifier.height(4.dp))
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    )
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        val loadedCmds = (obdbLoadedResult as? ObdbRepository.LoadResult.Loaded)?.commands ?: emptyList()
                        val allObdbSignals = loadedCmds.flatMap { it.signals }.associateBy { it.id }
                        val hasCustomPids = mode22Streams.isNotEmpty() || obdbSignalResults.isNotEmpty()

                        if (hasCustomPids) {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(mode22Streams) { stream ->
                                    val (valStr, unitStr) = formatMode22ValueWithUnit(stream.rawValue, stream.pid, unitSystem)
                                    SleekMetricTile(
                                        label = stream.pid.name,
                                        value = valStr,
                                        unit = unitStr,
                                        modifier = Modifier
                                            .width(102.dp)
                                            .height(66.dp)
                                    )
                                }
                                items(obdbSignalResults.entries.toList()) { (sigId, valObj) ->
                                    val sig = allObdbSignals[sigId]
                                    val sigName = sig?.name ?: sigId
                                    val (formattedVal, formattedUnit) = formatObdbValueWithUnit(valObj, sig, sigId, unitSystem)
                                    SleekMetricTile(
                                        label = sigName,
                                        value = formattedVal,
                                        unit = formattedUnit,
                                        modifier = Modifier
                                            .width(102.dp)
                                            .height(66.dp)
                                    )
                                }
                            }
                        } else {
                            Text(
                                text = "Enhanced Custom PIDs read manufacturer-specific parameters (Transmission Temp, Oil Pressure, Odometer, Fuel Level %, Tire Pressures). Tap 'Scan Mode 22' to query ECU.",
                                fontSize = 10.sp,
                                lineHeight = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))



        // 4. Integrated AI Mechanic & Diagnostics Container Card (Aircraft Carrier Hook & Stretch)
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .height(availableHeight)
                .onGloballyPositioned { coordinates ->
                    chatCardTopY = coordinates.positionInParent().y
                    chatCardHeight = coordinates.size.height.toFloat()
                }
                .border(
                    width = 1.5.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(18.dp)
                ),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
            ),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp)
        ) {
            val inputBarHeightDp = with(LocalDensity.current) { if (inputBarHeightPx > 0f) inputBarHeightPx.toDp() else 80.dp }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(availableHeight)
                    .padding(10.dp)
                    .padding(bottom = inputBarHeightDp + 10.dp)
            ) {
                // Header & Share/Save Action Buttons Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f, fill = false)) {
                        Text(
                            text = "AI Mechanic & Diagnostics",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Interactive Assistant & Diagnostic Log",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Share Chat Icon Button
                        Surface(
                            onClick = { shareChatTranscript() },
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = "Share Chat Transcript",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        // Save / Download .txt File Icon Button
                        Surface(
                            onClick = { saveChatToTextFile() },
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                DownloadSaveIcon(
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        // Saved Chats Sidebar Icon Button
                        Surface(
                            onClick = { coroutineScope.launch { drawerState.open() } },
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.History,
                                    contentDescription = "Saved Drive Chats",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        // View Dongle Log Icon Button (🔌)
                        Surface(
                            onClick = { showDongleLogDialog = true },
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("🔌", fontSize = 14.sp)
                            }
                        }
                    }
                }

                // Scanned DTC Badges Banner
                if (dtcDetails.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(dtcDetails) { dtc ->
                            Surface(
                                onClick = { selectedDtcForDetail = dtc },
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.border(0.5.dp, Color(0xFFFF5252), RoundedCornerShape(8.dp))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = dtc.code,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 12.sp,
                                        color = Color(0xFFFF5252)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "[${dtc.category}]",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Scrollable Chat Message History Box
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (isLiveSessionActive || isHandsFreeVoiceActive) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                                    .clickable(enabled = isGeminiSpeaking) {
                                        viewModel.interruptLivePlayback()
                                    },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isGeminiSpeaking) Color(0xFF2E7D32) else Color(0xFF1B5E20)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Mic,
                                        contentDescription = "Gemini Live Active",
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (isGeminiSpeaking) "🔊 Gemini Speaking (Tap to Mute/Skip)" else "🎙️ 2-Way Gemini Live Active (Hands-Free)",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = "Voice: $liveVoiceName",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Color.White.copy(alpha = 0.85f)
                                    )
                                }
                            }
                        }

                        LazyColumn(
                            state = chatListState,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            contentPadding = PaddingValues(bottom = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            reverseLayout = false
                        ) {
                        items(viewModel.messages, key = { it.id }) { msg ->
                            val isUser = msg.sender == "User"
                            val isError = msg.sender == "Error"
                            val isSystem = msg.sender == "System"

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                            ) {
                                Surface(
                                    shape = when {
                                        isUser -> RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp)
                                        else -> RoundedCornerShape(14.dp, 14.dp, 14.dp, 4.dp)
                                    },
                                    color = when {
                                        isUser -> MaterialTheme.colorScheme.primary
                                        isError -> Color(0xFF3E1010)
                                        isSystem -> MaterialTheme.colorScheme.surfaceVariant
                                        else -> MaterialTheme.colorScheme.surfaceVariant
                                    },
                                    contentColor = when {
                                        isUser -> MaterialTheme.colorScheme.onPrimary
                                        isError -> Color(0xFFFF8A80)
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier
                                        .widthIn(max = 280.dp)
                                        .border(
                                            width = if (isError) 1.dp else 0.dp,
                                            color = if (isError) Color(0xFFFF5252) else Color.Transparent,
                                            shape = RoundedCornerShape(14.dp)
                                        )
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text(
                                            text = when (msg.sender) {
                                                "User" -> "You"
                                                "OttoAI" -> "OttoAI Mechanic"
                                                else -> msg.sender
                                            },
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp,
                                            color = if (isUser) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f) else MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = msg.text,
                                            fontSize = 13.sp,
                                            lineHeight = 17.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                    }
                }
            }
        }
    }

    // 5. PINNED INPUT CONTAINER + BOTTOM CHAT OUTLINE FRAME
    Surface(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                inputBarHeightPx = coordinates.size.height.toFloat()
            }
            .graphicsLayer {
                translationY = inputBarTranslationY
            }
            .border(
                width = 1.5.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                shape = RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp, topStart = 8.dp, topEnd = 8.dp)
            ),
        shape = RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp, topStart = 8.dp, topEnd = 8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
        tonalElevation = 6.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {


        // Attached Files & Photos Preview Chips Bar
        if (attachedFiles.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(attachedFiles, key = { it.id }) { att ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.primary)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (att.isImage) {
                                ModernPhotoIcon(tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(13.dp))
                            } else {
                                ModernAttachmentIcon(tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(13.dp))
                            }
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = att.fileName,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 140.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "✕",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.clickable {
                                    attachedFiles = attachedFiles.filter { it.id != att.id }
                                }
                            )
                        }
                    }
                }
            }
        }

        // Single Horizontal Baseline Divider Line
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
            thickness = 1.dp,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        // Input Row with '+' attachment button, borderless text input, and send button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // '+' Attachment Button (Popup Menu for Photos & Files)
            Box {
                Surface(
                    onClick = { showAttachmentMenu = true },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Attach File or Photo",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                DropdownMenu(
                    expanded = showAttachmentMenu,
                    onDismissRequest = { showAttachmentMenu = false }
                ) {
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ModernAnalyticsIcon(tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Inject Live Data Snapshot", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                        },
                        onClick = {
                            showAttachmentMenu = false
                            viewModel.sendMessageToAgent(userText = "Analyze current live vehicle telemetry, DTCs, and Mode 06 monitors.")
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("🔌", fontSize = 14.sp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Analyze Dongle CAN Trace", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
                            }
                        },
                        onClick = {
                            showAttachmentMenu = false
                            val trace = viewModel.getRawElmTraceText()
                            viewModel.sendMessageToAgent(
                                userText = "Analyze this raw ELM327 Bluetooth OBD2 command and response trace log. Diagnose any CAN protocol or header response anomalies:\n\n$trace"
                            )
                        }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ModernAttachmentIcon(tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Attach Photos & Files", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        },
                        onClick = {
                            showAttachmentMenu = false
                            filePickerLauncher.launch(arrayOf("*/*"))
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Borderless/Single Line Input Field resting on the horizontal baseline
            TextField(
                value = userPrompt,
                onValueChange = { userPrompt = it },
                placeholder = { Text("Ask OttoAI Mechanic or attach logs/files...", fontSize = 12.sp) },
                singleLine = true,
                textStyle = TextStyle(fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                modifier = Modifier.weight(1f)
            )



            Spacer(modifier = Modifier.width(4.dp))

            // Hands-Free 2-Way Voice Streaming Button (Gemini Live)
            Surface(
                onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        Toast.makeText(context, "Microphone permission required for voice mode.", Toast.LENGTH_SHORT).show()
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else if (isGeminiSpeaking) {
                        viewModel.interruptLivePlayback()
                    } else {
                        viewModel.toggleHandsFreeVoiceMode()
                    }
                },
                shape = RoundedCornerShape(18.dp),
                color = if (isLiveSessionActive || isHandsFreeVoiceActive) {
                    if (isGeminiSpeaking) Color(0xFF00796B) else Color(0xFF2E7D32)
                } else MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.height(36.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Hands-Free Gemini Live Voice Mode",
                        tint = if (isLiveSessionActive || isHandsFreeVoiceActive) Color.White else MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = when {
                            isGeminiSpeaking -> "Speaking"
                            isLiveSessionActive || isHandsFreeVoiceActive -> "Live"
                            else -> "Voice"
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (isLiveSessionActive || isHandsFreeVoiceActive) Color.White else MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.width(4.dp))

            Button(
                onClick = {
                    if (userPrompt.isNotBlank() || attachedFiles.isNotEmpty()) {
                        viewModel.stopSpeaking()
                        // Collapse upper accordions so chat window expands
                        isLiveStreamsExpanded = false
                        isDtcExpanded = false
                        isMode06Expanded = false
                        isMode22Expanded = false

                        val promptText = if (userPrompt.isBlank()) {
                            val hasImages = attachedFiles.any { it.isImage }
                            val hasFiles = attachedFiles.any { !it.isImage }
                            when {
                                hasImages && hasFiles -> "Analyze these attached files and photos."
                                hasFiles -> "Analyze these attached files/logs."
                                else -> "Analyze these attached photos."
                            }
                        } else userPrompt

                        viewModel.sendMessageToAgent(
                            userText = promptText,
                            attachments = attachedFiles
                        )
                        userPrompt = ""
                        attachedFiles = emptyList()
                    }
                },
                enabled = !isSendingMessage,
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) {
                if (isSendingMessage) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("Send", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
}

    if (selectedDtcForDetail != null) {
        val dtc = selectedDtcForDetail!!
        AlertDialog(
            onDismissRequest = { selectedDtcForDetail = null },
            title = {
                Text(text = "${dtc.code} (${dtc.category})", fontWeight = FontWeight.Bold)
            },
            text = {
                Column {
                    Text(text = dtc.description, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(text = getDtcDiagnosticGuidance(dtc.code, dtc.description), fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedDtcForDetail = null }) {
                    Text("Close", fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    if (selectedMode06ForDetail != null) {
        val m06 = selectedMode06ForDetail!!
        AlertDialog(
            onDismissRequest = { selectedMode06ForDetail = null },
            title = {
                Text(text = "Mode 06: ${m06.name}", fontWeight = FontWeight.Bold)
            },
            text = {
                Column {
                    Text(text = "Current Value: ${m06.testValue.toInt()} ${m06.unit}", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                    Text(text = "Status: ${m06.status.name}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = m06.description, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Mode 06 test results track non-continuous diagnostic monitors evaluated by the vehicle ECU during driving cycles. High cylinder misfires indicate ignition coil, spark plug, fuel injector, or compression issues.",
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedMode06ForDetail = null }) {
                    Text("Close", fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}
}
}
