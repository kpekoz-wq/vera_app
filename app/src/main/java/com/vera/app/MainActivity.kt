@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.vera.app

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
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PauseCircleOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
import com.vera.app.ui.theme.VeraTheme
import kotlinx.coroutines.launch
import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
import android.graphics.drawable.AnimatedImageDrawable
import android.widget.ImageView
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.viewinterop.AndroidView

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        checkAndRequestPermissions()

        setContent {
            VeraTheme {
                var showSplash by remember { mutableStateOf(true) }

                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(3500)
                    showSplash = false
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                        VeraMainScreen(modifier = Modifier.padding(innerPadding))
                    }

                    AnimatedVisibility(
                        visible = showSplash,
                        enter = fadeIn(),
                        exit = fadeOut(animationSpec = tween(600))
                    ) {
                        VeraSplashScreen(
                            onDismiss = { showSplash = false }
                        )
                    }
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
        }

        // Always request fine & coarse location for Road Topography & Incline Navigation
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
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

@Composable
fun VeraSplashScreen(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable { onDismiss() }
    ) {
        // 0. Instant static frame 0 poster image - guarantees zero blank delay on cold start
        Image(
            painter = painterResource(id = R.drawable.vera_poster),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // 1. Background Animated Graphic Layer (Instant native ImageDecoder AnimatedImageDrawable)
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            val source = ImageDecoder.createSource(ctx.resources, R.drawable.vera_intro_anim)
                            val drawable = ImageDecoder.decodeDrawable(source)
                            setImageDrawable(drawable)
                            if (drawable is AnimatedImageDrawable) {
                                drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                                drawable.start()
                            } else if (drawable is Animatable) {
                                (drawable as Animatable).start()
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        )

        // 2. Dark Solid Overlay with 50% Transparency
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF070D18).copy(alpha = 0.50f))
        )

        // 3. Centered Branding: Logo, Large Bold VERA, and Glowing Subtitle
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Centered Logo / Emblem
            Image(
                painter = painterResource(id = R.drawable.vera_logo),
                contentDescription = "VERA Shield Emblem",
                modifier = Modifier
                    .size(160.dp)
                    .graphicsLayer {
                        shadowElevation = 24f
                    }
            )

            Spacer(modifier = Modifier.height(18.dp))

            // Large Bold Sans-Serif VERA with Cyan Glow
            Text(
                text = "VERA",
                fontSize = 44.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.SansSerif,
                letterSpacing = 4.sp,
                color = Color.White,
                textAlign = TextAlign.Center,
                style = TextStyle(
                    shadow = Shadow(
                        color = Color(0xFF00E5FF),
                        offset = Offset(0f, 0f),
                        blurRadius = 28f
                    )
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Smaller VEHICLE EVAL & REPAIR ASSISTANT in white text with glowing edges
            Text(
                text = "VEHICLE EVAL & REPAIR ASSISTANT",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.SansSerif,
                letterSpacing = 2.sp,
                color = Color.White,
                textAlign = TextAlign.Center,
                style = TextStyle(
                    shadow = Shadow(
                        color = Color(0xFF00E5FF),
                        offset = Offset(0f, 0f),
                        blurRadius = 14f
                    )
                )
            )
        }
    }
}

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
    sb.appendLine("        VERA Diagnostic Log & Chat Transcript     ")
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
                "VERA", "OttoAI" -> "VERA AI"
                "VERA (Live)", "OttoAI (Live)" -> "VERA (LIVE)"
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
    unitSystem: UnitSystem,
    isVehicleMoving: Boolean = false
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
        "°F" -> {
            if (unitSystem == UnitSystem.METRIC) {
                val c = (rawValue - 32.0) / 1.8
                Pair(String.format(java.util.Locale.US, "%.1f", c), "°C")
            } else {
                Pair(String.format(java.util.Locale.US, "%.1f", rawValue), "°F")
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
        "bar" -> {
            if (unitSystem == UnitSystem.SAE) {
                val psi = rawValue * 14.5038
                Pair(String.format(java.util.Locale.US, "%.1f", psi), "psi")
            } else {
                Pair(String.format(java.util.Locale.US, "%.2f", rawValue), "bar")
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
        "km" -> {
            if (unitSystem == UnitSystem.SAE) {
                val miles = rawValue * 0.621371
                Pair(String.format(java.util.Locale.US, "%,.0f", miles), "miles")
            } else {
                Pair(String.format(java.util.Locale.US, "%,.0f", rawValue), "km")
            }
        }
        "miles", "mi" -> {
            if (unitSystem == UnitSystem.METRIC) {
                val km = rawValue * 1.60934
                Pair(String.format(java.util.Locale.US, "%,.0f", km), "km")
            } else {
                Pair(String.format(java.util.Locale.US, "%,.0f", rawValue), "miles")
            }
        }
        "km/h" -> {
            if (unitSystem == UnitSystem.SAE) {
                val mph = rawValue * 0.621371
                Pair(String.format(java.util.Locale.US, "%.0f", mph), "mph")
            } else {
                Pair(String.format(java.util.Locale.US, "%.0f", rawValue), "km/h")
            }
        }
        "mph" -> {
            if (unitSystem == UnitSystem.METRIC) {
                val kmh = rawValue * 1.60934
                Pair(String.format(java.util.Locale.US, "%.0f", kmh), "km/h")
            } else {
                Pair(String.format(java.util.Locale.US, "%.0f", rawValue), "mph")
            }
        }
        "Nm" -> {
            if (unitSystem == UnitSystem.SAE) {
                val lbft = rawValue * 0.737562
                Pair(String.format(java.util.Locale.US, "%.0f", lbft), "lb-ft")
            } else {
                Pair(String.format(java.util.Locale.US, "%.0f", rawValue), "Nm")
            }
        }
        "mm" -> {
            if (unitSystem == UnitSystem.SAE) {
                val inches = rawValue * 0.0393701
                Pair(String.format(java.util.Locale.US, "%.2f", inches), "in")
            } else {
                Pair(String.format(java.util.Locale.US, "%.1f", rawValue), "mm")
            }
        }
        "RPM" -> Pair(String.format(java.util.Locale.US, "%.0f", rawValue), "RPM")
        "V" -> Pair(String.format(java.util.Locale.US, "%.2f", rawValue), "V")
        "Ratio" -> Pair(String.format(java.util.Locale.US, "%.2f", rawValue), "Ratio")
        "%" -> Pair(String.format(java.util.Locale.US, "%.1f", rawValue), "%")
        "°" -> Pair(String.format(java.util.Locale.US, "%.1f", rawValue), "°")
        "Gear" -> {
            val gearInt = rawValue.toInt()
            val gearLabel = when (gearInt) {
                0 -> if (!isVehicleMoving) "Neutral (Cmd 0)" else "Neutral"
                1 -> "1st"
                2 -> if (!isVehicleMoving) "Park (Cmd 2)" else "2nd"
                3 -> "3rd"
                4 -> "4th"
                5 -> "5th"
                6 -> "6th"
                7 -> "7th"
                8 -> "8th"
                9 -> "9th"
                10 -> "10th"
                else -> "$gearInt"
            }
            Pair(gearLabel, "Gear")
        }
        "counts" -> Pair(String.format(java.util.Locale.US, "%.0f", rawValue), "counts")
        "ppm" -> Pair(String.format(java.util.Locale.US, "%.0f", rawValue), "ppm")
        "mg/stroke" -> Pair(String.format(java.util.Locale.US, "%.1f", rawValue), "mg/stroke")
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
    val nameLower = (sig?.name ?: "").lowercase()
    val rawUnit = (sig?.unit?.lowercase()?.trim()?.takeIf { it.isNotBlank() }) ?: when {
        idLower.contains("rpm") || nameLower.contains("rpm") -> "rpm"
        idLower.contains("temp") || idLower.contains("coolant") || idLower.contains("intake") || idLower.contains("ambient") || nameLower.contains("temp") -> "celsius"
        idLower.contains("fuel") || idLower.contains("level") || idLower.contains("charge") || idLower.contains("percent") || idLower.contains("soc") || nameLower.contains("soc") -> "percent"
        idLower.contains("pressure") || idLower.contains("psi") || idLower.contains("kpa") || idLower.contains("tire") || idLower.contains("baro") -> "psi"
        idLower.contains("speed") || nameLower.contains("speed") -> "kilometersperhour"
        idLower.contains("odo") || idLower.contains("distance") || idLower.contains("trip") -> "kilometers"
        idLower.contains("volt") || nameLower.contains("voltage") -> "volts"
        idLower.contains("curr") || nameLower.contains("current") -> "amperes"
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
            "fahrenheit", "°f" -> {
                if (unitSystem == UnitSystem.METRIC) {
                    val c = (rawVal - 32.0) / 1.8
                    Pair(String.format(java.util.Locale.US, "%.1f", c), "°C")
                } else {
                    Pair(String.format(java.util.Locale.US, "%.1f", rawVal), "°F")
                }
            }
            "kilometers", "km" -> {
                if (unitSystem == UnitSystem.SAE) {
                    val miles = rawVal * 0.621371
                    Pair(String.format(java.util.Locale.US, "%,.0f", miles), "miles")
                } else {
                    Pair(String.format(java.util.Locale.US, "%,.0f", rawVal), "km")
                }
            }
            "miles", "mi" -> {
                if (unitSystem == UnitSystem.METRIC) {
                    val km = rawVal * 1.60934
                    Pair(String.format(java.util.Locale.US, "%,.0f", km), "km")
                } else {
                    Pair(String.format(java.util.Locale.US, "%,.0f", rawVal), "miles")
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
            "milesperhour", "mph" -> {
                if (unitSystem == UnitSystem.METRIC) {
                    val kmh = rawVal * 1.60934
                    Pair(String.format(java.util.Locale.US, "%.0f", kmh), "km/h")
                } else {
                    Pair(String.format(java.util.Locale.US, "%.0f", rawVal), "mph")
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
            "bar" -> {
                if (unitSystem == UnitSystem.SAE) {
                    Pair(String.format(java.util.Locale.US, "%.1f", rawVal * 14.5038), "psi")
                } else {
                    Pair(String.format(java.util.Locale.US, "%.2f", rawVal), "bar")
                }
            }
            "percent", "%" -> Pair(String.format(java.util.Locale.US, "%.1f", rawVal), "%")
            "volts", "v" -> Pair(String.format(java.util.Locale.US, "%.2f", rawVal), "V")
            "amperes", "amps", "a" -> Pair(String.format(java.util.Locale.US, "%.1f", rawVal), "A")
            "milliamperes", "ma" -> Pair(String.format(java.util.Locale.US, "%.0f", rawVal), "mA")
            "watts", "w" -> Pair(String.format(java.util.Locale.US, "%.0f", rawVal), "W")
            "kilowatts", "kw" -> Pair(String.format(java.util.Locale.US, "%.1f", rawVal), "kW")
            "ohms", "ohm" -> Pair(String.format(java.util.Locale.US, "%.1f", rawVal), "Ω")
            "newtonmeters", "nm" -> {
                if (unitSystem == UnitSystem.SAE) {
                    Pair(String.format(java.util.Locale.US, "%.0f", rawVal * 0.737562), "lb-ft")
                } else {
                    Pair(String.format(java.util.Locale.US, "%.0f", rawVal), "Nm")
                }
            }
            "gramspersecond", "g/s" -> Pair(String.format(java.util.Locale.US, "%.1f", rawVal), "g/s")
            "milliseconds", "ms" -> Pair(String.format(java.util.Locale.US, "%.1f", rawVal), "ms")
            "degrees", "°", "deg" -> Pair(String.format(java.util.Locale.US, "%.1f", rawVal), "°")
            "rpm" -> Pair(String.format(java.util.Locale.US, "%.0f", rawVal), "RPM")
            "seconds", "sec", "s" -> Pair(String.format(java.util.Locale.US, "%.0f", rawVal), "sec")
            "offon", "onoff", "boolean", "bool" -> Pair(if (rawVal > 0.0) "ON" else "OFF", "STATUS")
            "noyes", "yesno" -> Pair(if (rawVal > 0.0) "YES" else "NO", "STATUS")
            "scalar", "count", "counts", "none", "" -> Pair(String.format(java.util.Locale.US, "%.0f", rawVal), "")
            else -> Pair(String.format(java.util.Locale.US, "%.1f", rawVal), rawUnit)
        }
    } else {
        val strVal = valObj.toString().trim()
        if (strVal.isBlank() || strVal.equals("null", ignoreCase = true) || strVal == "--") {
            return Pair("--", "")
        }
        if (rawUnit == "offon" || rawUnit == "onoff") {
            val isOff = strVal == "0" || strVal == "0.0" || strVal.equals("OFF", ignoreCase = true) || strVal.equals("FALSE", ignoreCase = true)
            return Pair(if (isOff) "OFF" else "ON", "STATUS")
        }
        if (rawUnit == "noyes" || rawUnit == "yesno") {
            val isNo = strVal == "0" || strVal == "0.0" || strVal.equals("NO", ignoreCase = true) || strVal.equals("FALSE", ignoreCase = true)
            return Pair(if (isNo) "NO" else "YES", "STATUS")
        }
        return Pair(strVal, if (rawUnit in listOf("offon", "noyes", "yesno", "onoff", "none")) "" else rawUnit)
    }
}

enum class CustomPidCategory(val displayName: String) {
    HYBRID_EV("Hybrid / HV Battery"),
    POWERTRAIN("Engine & Fuel"),
    TRANSMISSION("Transmission"),
    ELECTRICAL("12V & Electrical"),
    CHASSIS_TPMS("Brakes & Tires"),
    CLIMATE_BODY("Climate & Body"),
    STATUS_SWITCHES("Switches & Status")
}

enum class PidHealthStatus {
    NORMAL,
    MARGINAL,
    OUT_OF_RANGE
}

data class CustomPidItem(
    val key: String,
    val name: String,
    val formattedValue: String,
    val unit: String,
    val category: CustomPidCategory,
    val isBooleanStatus: Boolean,
    val rawPidId: String,
    val healthStatus: PidHealthStatus = PidHealthStatus.NORMAL,
    val normalRangeLabel: String? = null
)

/**
 * Evaluates a Custom PID against professional scan-tool operating thresholds (Snap-on / Autel style)
 * and returns (PidHealthStatus, expectedNormalRangeString).
 */
fun evaluateCustomPidHealth(
    name: String,
    rawPidId: String,
    formattedValue: String,
    unit: String,
    category: CustomPidCategory
): Pair<PidHealthStatus, String?> {
    val numVal = formattedValue.replace(",", "").toDoubleOrNull() ?: return Pair(PidHealthStatus.NORMAL, null)
    val text = "$name $rawPidId".lowercase()
    val u = unit.lowercase()

    // 1. Transmission Fluid Temp (TFT / ATF)
    if ((text.contains("trans") || text.contains("atf") || text.contains("tft") || text.contains("cvt")) && (u.contains("f") || u.contains("c"))) {
        return if (u.contains("f")) {
            val status = when {
                numVal > 235.0 -> PidHealthStatus.OUT_OF_RANGE
                numVal > 215.0 -> PidHealthStatus.MARGINAL
                else -> PidHealthStatus.NORMAL
            }
            Pair(status, "Spec: 140–215 °F")
        } else {
            val status = when {
                numVal > 113.0 -> PidHealthStatus.OUT_OF_RANGE
                numVal > 102.0 -> PidHealthStatus.MARGINAL
                else -> PidHealthStatus.NORMAL
            }
            Pair(status, "Spec: 60–102 °C")
        }
    }

    // 2. Engine Oil Temp (EOT)
    if (text.contains("oil") && (text.contains("temp") || text.contains("eot")) && (u.contains("f") || u.contains("c"))) {
        return if (u.contains("f")) {
            val status = when {
                numVal > 245.0 -> PidHealthStatus.OUT_OF_RANGE
                numVal > 230.0 -> PidHealthStatus.MARGINAL
                else -> PidHealthStatus.NORMAL
            }
            Pair(status, "Spec: 180–230 °F")
        } else {
            val status = when {
                numVal > 118.0 -> PidHealthStatus.OUT_OF_RANGE
                numVal > 110.0 -> PidHealthStatus.MARGINAL
                else -> PidHealthStatus.NORMAL
            }
            Pair(status, "Spec: 82–110 °C")
        }
    }

    // 3. Cylinder Head Temp / Engine Coolant Temp
    if ((text.contains("cylinder head") || text.contains("cht") || text.contains("coolant")) && (u.contains("f") || u.contains("c"))) {
        return if (u.contains("f")) {
            val status = when {
                numVal > 235.0 -> PidHealthStatus.OUT_OF_RANGE
                numVal > 220.0 -> PidHealthStatus.MARGINAL
                else -> PidHealthStatus.NORMAL
            }
            Pair(status, "Spec: 180–220 °F")
        } else {
            val status = when {
                numVal > 113.0 -> PidHealthStatus.OUT_OF_RANGE
                numVal > 104.0 -> PidHealthStatus.MARGINAL
                else -> PidHealthStatus.NORMAL
            }
            Pair(status, "Spec: 82–104 °C")
        }
    }

    // 4. Hybrid / HV Battery & MG1/MG2 Inverter Temperatures
    if (category == CustomPidCategory.HYBRID_EV && (u.contains("f") || u.contains("c"))) {
        val isInverterOrMotor = text.contains("mg1") || text.contains("mg2") || text.contains("inverter") || text.contains("motor")
        return if (isInverterOrMotor) {
            if (u.contains("f")) {
                val status = when {
                    numVal > 195.0 -> PidHealthStatus.OUT_OF_RANGE
                    numVal > 170.0 -> PidHealthStatus.MARGINAL
                    else -> PidHealthStatus.NORMAL
                }
                Pair(status, "Spec: < 170 °F")
            } else {
                val status = when {
                    numVal > 90.0 -> PidHealthStatus.OUT_OF_RANGE
                    numVal > 77.0 -> PidHealthStatus.MARGINAL
                    else -> PidHealthStatus.NORMAL
                }
                Pair(status, "Spec: < 77 °C")
            }
        } else {
            if (u.contains("f")) {
                val status = when {
                    numVal > 122.0 -> PidHealthStatus.OUT_OF_RANGE
                    numVal > 108.0 -> PidHealthStatus.MARGINAL
                    else -> PidHealthStatus.NORMAL
                }
                Pair(status, "Spec: 60–108 °F")
            } else {
                val status = when {
                    numVal > 50.0 -> PidHealthStatus.OUT_OF_RANGE
                    numVal > 42.0 -> PidHealthStatus.MARGINAL
                    else -> PidHealthStatus.NORMAL
                }
                Pair(status, "Spec: 15–42 °C")
            }
        }
    }

    // 5. 12V Aux Battery / Control Module Voltage
    if (u == "v" && (category == CustomPidCategory.ELECTRICAL || text.contains("12v") || text.contains("aux") || text.contains("bcm") || text.contains("battery voltage"))) {
        val status = when {
            numVal < 11.7 || numVal > 15.2 -> PidHealthStatus.OUT_OF_RANGE
            numVal < 12.3 || numVal > 14.8 -> PidHealthStatus.MARGINAL
            else -> PidHealthStatus.NORMAL
        }
        return Pair(status, "Spec: 12.4–14.8 V")
    }

    // 6. Ignition Knock Retard & VCT Cam Phaser Timing Error (only for timing angle in degrees, NOT raw knock sensor voltage/noise)
    val isDegrees = u == "°" || u == "deg" || u == "degrees"
    val isTimingRetardOrPhaserError = isDegrees && (
        (text.contains("knock") && (text.contains("retard") || text.contains("timing") || text.contains("kr"))) ||
        (text.contains("phaser") && text.contains("error")) ||
        (text.contains("vct") && text.contains("error"))
    )
    if (isTimingRetardOrPhaserError) {
        val absVal = kotlin.math.abs(numVal)
        val status = when {
            absVal > 3.5 -> PidHealthStatus.OUT_OF_RANGE
            absVal > 1.2 -> PidHealthStatus.MARGINAL
            else -> PidHealthStatus.NORMAL
        }
        return Pair(status, "Spec: 0.0–1.2°")
    }

    // 7. Torque Converter Slip
    if (text.contains("slip") && u == "rpm") {
        val absVal = kotlin.math.abs(numVal)
        val status = when {
            absVal > 90.0 -> PidHealthStatus.OUT_OF_RANGE
            absVal > 35.0 -> PidHealthStatus.MARGINAL
            else -> PidHealthStatus.NORMAL
        }
        return Pair(status, "Spec: 0–35 RPM")
    }

    // 8. Engine Oil Pressure
    if (text.contains("oil") && text.contains("pressure")) {
        return if (u == "psi") {
            val status = when {
                numVal < 12.0 -> PidHealthStatus.OUT_OF_RANGE
                numVal < 22.0 -> PidHealthStatus.MARGINAL
                else -> PidHealthStatus.NORMAL
            }
            Pair(status, "Spec: 25–65 psi")
        } else {
            val status = when {
                numVal < 80.0 -> PidHealthStatus.OUT_OF_RANGE
                numVal < 150.0 -> PidHealthStatus.MARGINAL
                else -> PidHealthStatus.NORMAL
            }
            Pair(status, "Spec: 170–450 kPa")
        }
    }

    // 9. Fuel Rail Pressure (Low-pressure port injection)
    if (text.contains("fuel rail") && u == "psi" && numVal < 120.0) {
        val status = when {
            numVal < 22.0 || numVal > 75.0 -> PidHealthStatus.OUT_OF_RANGE
            numVal < 30.0 || numVal > 65.0 -> PidHealthStatus.MARGINAL
            else -> PidHealthStatus.NORMAL
        }
        return Pair(status, "Spec: 32–60 psi")
    }

    // 10. Tire Pressure (TPMS)
    if ((text.contains("tire") || text.contains("tpms")) && (u == "psi" || u == "bar" || u == "kpa")) {
        return if (u == "psi") {
            val status = when {
                numVal < 24.0 || numVal > 46.0 -> PidHealthStatus.OUT_OF_RANGE
                numVal < 28.5 || numVal > 40.0 -> PidHealthStatus.MARGINAL
                else -> PidHealthStatus.NORMAL
            }
            Pair(status, "Spec: 30–38 psi")
        } else if (u == "bar") {
            val status = when {
                numVal < 1.65 || numVal > 3.2 -> PidHealthStatus.OUT_OF_RANGE
                numVal < 1.95 || numVal > 2.8 -> PidHealthStatus.MARGINAL
                else -> PidHealthStatus.NORMAL
            }
            Pair(status, "Spec: 2.1–2.6 bar")
        } else {
            val status = when {
                numVal < 165.0 || numVal > 320.0 -> PidHealthStatus.OUT_OF_RANGE
                numVal < 195.0 || numVal > 280.0 -> PidHealthStatus.MARGINAL
                else -> PidHealthStatus.NORMAL
            }
            Pair(status, "Spec: 210–260 kPa")
        }
    }

    // 11. 12V Battery State of Charge & Engine Oil Life Remaining
    if ((text.contains("12v") && text.contains("soc")) || (text.contains("battery") && text.contains("soc") && category == CustomPidCategory.ELECTRICAL)) {
        val status = when {
            numVal < 45.0 -> PidHealthStatus.OUT_OF_RANGE
            numVal < 65.0 -> PidHealthStatus.MARGINAL
            else -> PidHealthStatus.NORMAL
        }
        return Pair(status, "Spec: 70–100%")
    }
    if (text.contains("oil life") && u == "%") {
        val status = when {
            numVal <= 5.0 -> PidHealthStatus.OUT_OF_RANGE
            numVal <= 15.0 -> PidHealthStatus.MARGINAL
            else -> PidHealthStatus.NORMAL
        }
        return Pair(status, "Spec: > 15%")
    }
    if (text.contains("fuel pump") && text.contains("duty") && u == "%") {
        val status = when {
            numVal > 90.0 -> PidHealthStatus.OUT_OF_RANGE
            numVal > 75.0 -> PidHealthStatus.MARGINAL
            else -> PidHealthStatus.NORMAL
        }
        return Pair(status, "Spec: 25–65%")
    }

    return Pair(PidHealthStatus.NORMAL, null)
}

fun isHybridOrEvVehicle(vehicleDetails: String?, obdbRepoName: String? = null): Boolean {
    val combined = "${vehicleDetails ?: ""} ${obdbRepoName ?: ""}".lowercase()
    val hybridKeywords = listOf(
        "prius", "hybrid", "prime", "ct200h", "es300h", "rx400h", "rx450h", "nx300h", "ux250h",
        "rav4 hv", "camry hv", "highlander hv", "avalon hv", "corolla hv", "sienna", "venza",
        "volt", "bolt", "leaf", "tesla", "mach-e", "lightning", "ioniq", "niro", "kona ev",
        "ev6", "id.4", "e-tron", "taycan", "insight", "clarity", "cr-z", "accord hybrid", "cr-v hybrid"
    )
    return hybridKeywords.any { combined.contains(it) }
}

fun buildUnifiedCustomPidList(
    mode22Streams: List<Mode22StreamData>,
    obdbLoadedResult: ObdbRepository.LoadResult?,
    obdbSignalResults: Map<String, Any?>,
    unitSystem: UnitSystem,
    vehicleDetails: String?,
    isMoving: Boolean = false
): List<CustomPidItem> {
    val loaded = obdbLoadedResult as? ObdbRepository.LoadResult.Loaded
    val isHybridOrEv = isHybridOrEvVehicle(vehicleDetails, loaded?.repo)
    val result = mutableListOf<CustomPidItem>()
    val seenNames = mutableSetOf<String>()

    // 1. Mode 22 Enhanced Streams
    for (stream in mode22Streams) {
        val (valStr, unitStr) = formatMode22ValueWithUnit(stream.rawValue, stream.pid, unitSystem, isMoving)
        if (valStr == "--" || valStr.isBlank()) continue
        val catLower = stream.pid.category.lowercase()
        val cat = when {
            catLower == "transmission" -> CustomPidCategory.TRANSMISSION
            catLower == "battery" || catLower == "electrical" -> CustomPidCategory.ELECTRICAL
            catLower == "chassis" || catLower == "tpms" || catLower == "brakes" -> CustomPidCategory.CHASSIS_TPMS
            catLower == "climate" || catLower == "body" -> CustomPidCategory.CLIMATE_BODY
            catLower == "hybrid" -> if (isHybridOrEv) CustomPidCategory.HYBRID_EV else continue
            else -> CustomPidCategory.POWERTRAIN
        }
        val cleanName = stream.pid.name.trim()
        val rawPidId = "${stream.pid.servicePrefix}${stream.pid.pidHex}"
        val (health, specLabel) = evaluateCustomPidHealth(cleanName, rawPidId, valStr, unitStr, cat)
        if (seenNames.add(cleanName.lowercase())) {
            result.add(
                CustomPidItem(
                    key = "M22_${stream.pid.pidHex}",
                    name = cleanName,
                    formattedValue = valStr,
                    unit = unitStr,
                    category = cat,
                    isBooleanStatus = false,
                    rawPidId = rawPidId,
                    healthStatus = health,
                    normalRangeLabel = specLabel
                )
            )
        }
    }

    // 2. OBDb Community Signalset Parameters
    val allSignals = loaded?.commands?.flatMap { it.signals }?.associateBy { it.id } ?: emptyMap()
    for ((sigId, valObj) in obdbSignalResults) {
        if (valObj == null) continue
        val sig = allSignals[sigId]
        val (fmtVal, fmtUnit) = formatObdbValueWithUnit(valObj, sig, sigId, unitSystem)
        if (fmtVal == "--" || fmtVal.isBlank() || fmtVal.equals("null", ignoreCase = true)) continue

        val cleanName = (sig?.name?.takeIf { it.isNotBlank() } ?: sigId).trim()
        val combinedMeta = "${sig?.path ?: ""} $cleanName $sigId".lowercase()
        val isBool = fmtUnit == "STATUS" || fmtVal in setOf("ON", "OFF", "YES", "NO")

        val isHybridSignal = combinedMeta.contains("hybrid") || combinedMeta.contains("hv_") ||
                combinedMeta.contains("mg1") || combinedMeta.contains("mg2") ||
                combinedMeta.contains("inverter") || combinedMeta.contains("block_") ||
                combinedMeta.contains("traction") || combinedMeta.contains("regen") ||
                (combinedMeta.contains("soc") && !combinedMeta.contains("12v") && !combinedMeta.contains("aux"))

        // Never show hybrid battery / MG1 / MG2 parameters on an exclusively gas car
        if (isHybridSignal && !isHybridOrEv) continue

        val category = when {
            isBool -> CustomPidCategory.STATUS_SWITCHES
            isHybridSignal -> CustomPidCategory.HYBRID_EV
            combinedMeta.contains("trans") || combinedMeta.contains("gear") || combinedMeta.contains("atf") ||
                    combinedMeta.contains("cvt") || combinedMeta.contains("clutch") || combinedMeta.contains("slip") ||
                    combinedMeta.contains("tft") -> CustomPidCategory.TRANSMISSION
            combinedMeta.contains("tire") || combinedMeta.contains("tpms") || combinedMeta.contains("wheel") ||
                    combinedMeta.contains("brake") || combinedMeta.contains("abs") || combinedMeta.contains("steer") ||
                    combinedMeta.contains("yaw") -> CustomPidCategory.CHASSIS_TPMS
            combinedMeta.contains("ac_") || combinedMeta.contains("a/c") || combinedMeta.contains("climate") ||
                    combinedMeta.contains("cabin") || combinedMeta.contains("blower") || combinedMeta.contains("evaporator") ||
                    combinedMeta.contains("solar") || combinedMeta.contains("door") || combinedMeta.contains("window") ||
                    combinedMeta.contains("seat") -> CustomPidCategory.CLIMATE_BODY
            combinedMeta.contains("12v") || combinedMeta.contains("aux") || combinedMeta.contains("alternator") ||
                    combinedMeta.contains("volt") || fmtUnit in setOf("V", "A", "mA") -> CustomPidCategory.ELECTRICAL
            else -> CustomPidCategory.POWERTRAIN
        }

        val displayUnit = if (fmtUnit == "STATUS") "" else fmtUnit
        val (health, specLabel) = evaluateCustomPidHealth(cleanName, sigId, fmtVal, displayUnit, category)
        if (seenNames.add(cleanName.lowercase())) {
            result.add(
                CustomPidItem(
                    key = "OBDB_$sigId",
                    name = cleanName,
                    formattedValue = fmtVal,
                    unit = displayUnit,
                    category = category,
                    isBooleanStatus = isBool,
                    rawPidId = sigId,
                    healthStatus = health,
                    normalRangeLabel = specLabel
                )
            )
        }
    }

    return result
}

fun selectDefaultFavoriteCustomPids(
    items: List<CustomPidItem>,
    isHybridOrEv: Boolean,
    maxCount: Int = 8
): Set<String> {
    val numericItems = items.filter { !it.isBooleanStatus }
    if (numericItems.isEmpty()) return items.take(maxCount).map { it.key }.toSet()

    fun scoreItem(item: CustomPidItem): Int {
        val text = "${item.name} ${item.rawPidId}".lowercase()
        val healthBonus = when (item.healthStatus) {
            PidHealthStatus.OUT_OF_RANGE -> 200
            PidHealthStatus.MARGINAL -> 150
            PidHealthStatus.NORMAL -> 0
        }
        val baseScore = if (isHybridOrEv) {
            when {
                text.contains("state of charge") || text.contains("soc") -> 100
                text.contains("mg1") || text.contains("mg2") || text.contains("inverter") -> 95
                (item.category == CustomPidCategory.HYBRID_EV && (item.unit.contains("°") || text.contains("temp"))) -> 92
                (item.category == CustomPidCategory.HYBRID_EV && item.unit in setOf("A", "V", "kW", "W")) -> 88
                text.contains("aux") || text.contains("12v") || (item.category == CustomPidCategory.ELECTRICAL && item.unit == "V") -> 84
                text.contains("trans") || text.contains("atf") || text.contains("coolant") || text.contains("oil temp") -> 80
                text.contains("tire") || text.contains("tpms") || text.contains("brake") -> 75
                text.contains("fuel") -> 70
                item.category == CustomPidCategory.HYBRID_EV -> 65
                item.unit.isNotBlank() -> 50
                else -> 20
            }
        } else {
            when {
                text.contains("transmission") || text.contains("tft") || text.contains("atf") -> 100
                text.contains("oil temp") || text.contains("oil pressure") || text.contains("oil life") -> 96
                text.contains("cylinder head") || text.contains("cht") || text.contains("coolant") -> 92
                text.contains("torque converter") || text.contains("slip") || text.contains("gear") -> 88
                text.contains("battery") || text.contains("voltage") || text.contains("alternator") -> 84
                text.contains("fuel") || text.contains("rail") || text.contains("boost") || text.contains("exhaust") || text.contains("knock") -> 80
                text.contains("tire") || text.contains("tpms") -> 76
                item.category == CustomPidCategory.POWERTRAIN && item.unit.isNotBlank() -> 60
                item.unit.isNotBlank() -> 45
                else -> 20
            }
        }
        return baseScore + healthBonus
    }

    return numericItems
        .sortedByDescending { scoreItem(it) }
        .take(maxCount)
        .map { it.key }
        .toSet()
}

fun selectDefaultLiveStreams(streams: List<LiveStreamData>): Set<String> {
    val keyPids = setOf("0C", "0D", "04", "05", "11", "2F", "10", "0F")
    val found = streams.filter { keyPids.contains(it.pid.pidHex.uppercase()) }.map { it.pid.pidHex }.toSet()
    return if (found.isNotEmpty()) found else streams.take(8).map { it.pid.pidHex }.toSet()
}

fun selectDefaultMode06(monitors: List<Mode06TestResult>): Set<String> {
    val flagged = monitors.filter { it.status != Mode06Status.PASS }.map { it.name }.toSet()
    if (flagged.isNotEmpty()) return flagged
    val misfireOrCat = monitors.filter { it.category.contains("Misfire", ignoreCase = true) || it.category.contains("Catalyst", ignoreCase = true) }.map { it.name }.toSet()
    return if (misfireOrCat.isNotEmpty()) misfireOrCat else monitors.take(8).map { it.name }.toSet()
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
        else -> "Diagnostic trouble code recorded by engine control unit. Use VERA Assistant to analyze live telemetry and specific repair procedures."
    }
}

@Composable
fun DtcAwareMessageText(
    text: String,
    textColor: Color,
    fontSize: TextUnit = 13.sp,
    lineHeight: TextUnit = 17.sp,
    onDtcClick: (String) -> Unit
) {
    val sanitizedText = remember(text) { GeminiAgentViewModel.cleanTranscriptText(text) }
    val dtcRegex = remember { Regex("\\b([PCBU][0-9A-Fa-f]{4})\\b") }
    val detectedDtcs = remember(sanitizedText) {
        dtcRegex.findAll(sanitizedText)
            .map { it.value.uppercase() }
            .distinct()
            .toList()
    }

    val annotatedString = remember(sanitizedText, textColor) {
        buildAnnotatedString {
            var lastIndex = 0
            val matches = dtcRegex.findAll(sanitizedText).toList()
            if (matches.isEmpty()) {
                append(sanitizedText)
            } else {
                for (match in matches) {
                    val start = match.range.first
                    val end = match.range.last + 1
                    if (start > lastIndex) {
                        append(sanitizedText.substring(lastIndex, start))
                    }
                    val code = match.value.uppercase()
                    pushLink(
                        LinkAnnotation.Clickable(
                            tag = "DTC_$code",
                            styles = TextLinkStyles(
                                style = SpanStyle(
                                    color = Color(0xFFFF5252),
                                    fontWeight = FontWeight.ExtraBold,
                                    textDecoration = TextDecoration.Underline
                                )
                            )
                        ) {
                            onDtcClick(code)
                        }
                    )
                    append(match.value)
                    pop()
                    lastIndex = end
                }
                if (lastIndex < sanitizedText.length) {
                    append(sanitizedText.substring(lastIndex))
                }
            }
        }
    }

    Column {
        Text(
            text = annotatedString,
            color = textColor,
            fontSize = fontSize,
            lineHeight = lineHeight
        )

        if (detectedDtcs.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(detectedDtcs) { dtcCode ->
                    Surface(
                        onClick = { onDtcClick(dtcCode) },
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(0.75.dp, Color(0xFFFF5252))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = dtcCode,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 11.sp,
                                color = Color(0xFFFF5252)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Inspect ↗",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
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
    isSelected: Boolean = false,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .height(66.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        border = if (isSelected) {
            androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        } else {
            androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = label,
                    fontSize = 9.sp,
                    lineHeight = 10.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.weight(1f))

                Text(
                    text = value,
                    fontSize = 14.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )

                Text(
                    text = if (unit.isNotBlank()) unit else " ",
                    fontSize = 9.sp,
                    lineHeight = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    modifier = Modifier.offset(y = (-2).dp)
                )

                Spacer(modifier = Modifier.weight(0.7f))
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .size(13.dp)
                )
            }
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
fun Mode06MonitorCard(
    m06: Mode06TestResult,
    unitSystem: UnitSystem = UnitSystem.SAE,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
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
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
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

                Row(verticalAlignment = Alignment.CenterVertically) {
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
                    if (onToggleFavorite != null) {
                        Spacer(modifier = Modifier.width(6.dp))
                        IconButton(
                            onClick = onToggleFavorite,
                            modifier = Modifier.size(28.dp)
                        ) {
                            DashboardStarIcon(
                                isSelected = isFavorite,
                                tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
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
fun FullScreenMode06PickerView(
    results: List<Mode06TestResult>,
    favoriteKeys: Set<String>,
    unitSystem: UnitSystem = UnitSystem.SAE,
    isConnected: Boolean = true,
    isScanning: Boolean = false,
    onToggleFavorite: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearFavorites: () -> Unit,
    onSelectFlagged: () -> Unit,
    onSelectSmartDefaults: () -> Unit,
    onSelectMonitor: ((Mode06TestResult) -> Unit)? = null,
    onBack: () -> Unit,
    onScan: () -> Unit
) {
    BackHandler(onBack = onBack)

    var searchQuery by remember { mutableStateOf("") }
    var activeCategory by remember { mutableStateOf("ALL") }

    val flaggedCount = remember(results) {
        results.count { it.status != Mode06Status.PASS }
    }

    val categories = remember(results, flaggedCount) {
        buildList {
            add("ALL")
            if (flaggedCount > 0) add("FLAGGED")
            add("FAVORITES")
            addAll(results.map { it.category }.distinct())
        }
    }

    val filteredResults = remember(results, activeCategory, favoriteKeys, searchQuery) {
        val q = searchQuery.trim().lowercase()
        results.filter { m ->
            val matchesCat = when (activeCategory) {
                "ALL" -> true
                "FLAGGED" -> m.status != Mode06Status.PASS
                "FAVORITES" -> favoriteKeys.contains(m.name)
                else -> m.category == activeCategory
            }
            val matchesSearch = q.isEmpty() ||
                    m.name.lowercase().contains(q) ||
                    m.category.lowercase().contains(q) ||
                    m.midHex.lowercase().contains(q)
            matchesCat && matchesSearch
        }
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
                    enabled = isConnected && !isScanning,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    if (isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Scanning...", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    } else {
                        Text("Scan Mode 06", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Mode 06 Monitor Catalog",
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "${favoriteKeys.size} Pinned to Dashboard • ${results.size} Evaluated Tests" + (if (flaggedCount > 0) " • $flaggedCount Flagged" else ""),
                fontSize = 11.sp,
                color = if (flaggedCount > 0) Color(0xFFFFCA28) else MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Search Bar
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                border = androidx.compose.foundation.BorderStroke(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search Monitors",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = "Search monitors (Misfire, O2, EVAP, Catalyst)...",
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                            )
                        }
                        androidx.compose.foundation.text.BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            textStyle = TextStyle(
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Medium
                            ),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { searchQuery = "" },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear search",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Quick Actions Row
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                item {
                    Surface(
                        onClick = onSelectSmartDefaults,
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "Auto-Pick Best",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
                if (flaggedCount > 0) {
                    item {
                        Surface(
                            onClick = onSelectFlagged,
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFFE65100).copy(alpha = 0.25f),
                            border = androidx.compose.foundation.BorderStroke(0.8.dp, Color(0xFFFFCA28))
                        ) {
                            Text(
                                text = "Pin Flagged ($flaggedCount)",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFFCA28),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
                item {
                    Surface(
                        onClick = onSelectAll,
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = "Select All",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
                item {
                    Surface(
                        onClick = onClearFavorites,
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = "Clear",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

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

            if (results.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No monitor tests available. Tap 'Scan Mode 06' while connected to evaluate ECU monitors.",
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (filteredResults.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No monitors match \"$searchQuery\" in $activeCategory.",
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
                        Mode06MonitorCard(
                            m06 = m06,
                            unitSystem = unitSystem,
                            isFavorite = favoriteKeys.contains(m06.name),
                            onToggleFavorite = { onToggleFavorite(m06.name) },
                            onClick = if (onSelectMonitor != null) ({ onSelectMonitor(m06) }) else null
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FullScreenLiveStreamPickerView(
    allStreams: List<LiveStreamData>,
    favoriteKeys: Set<String>,
    unitSystem: UnitSystem = UnitSystem.SAE,
    isConnected: Boolean = true,
    onToggleFavorite: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearFavorites: () -> Unit,
    onSelectSmartDefaults: () -> Unit,
    onInspectStream: (LiveStreamData) -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    var searchQuery by remember { mutableStateOf("") }
    var activeTab by remember { mutableStateOf("ALL") }

    val tabs = listOf("ALL", "FAVORITES")

    val filteredStreams = remember(allStreams, favoriteKeys, searchQuery, activeTab) {
        val q = searchQuery.trim().lowercase()
        allStreams.filter { s ->
            val matchesTab = when (activeTab) {
                "ALL" -> true
                "FAVORITES" -> favoriteKeys.contains(s.pid.pidHex)
                else -> true
            }
            val matchesSearch = q.isEmpty() ||
                    s.pid.name.lowercase().contains(q) ||
                    s.pid.description.lowercase().contains(q) ||
                    s.pid.pidHex.lowercase().contains(q) ||
                    s.pid.unit.lowercase().contains(q)
            matchesTab && matchesSearch
        }
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
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Live Data Streams Selector",
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "${favoriteKeys.size} Pinned to Dashboard • ${allStreams.size} Discovered OBD2 Streams",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Search Bar
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                border = androidx.compose.foundation.BorderStroke(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search Streams",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = "Search live streams (RPM, Speed, Temp, MAF, O2)...",
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                            )
                        }
                        androidx.compose.foundation.text.BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            textStyle = TextStyle(
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Medium
                            ),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { searchQuery = "" },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear search",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Quick Actions Row
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                item {
                    Surface(
                        onClick = onSelectSmartDefaults,
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "Auto-Pick Best (8 Gauges)",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
                item {
                    Surface(
                        onClick = onSelectAll,
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = "Select All",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
                item {
                    Surface(
                        onClick = onClearFavorites,
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = "Clear",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Tabs Row
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(tabs) { tab ->
                    val isSelected = tab == activeTab
                    FilterChip(
                        selected = isSelected,
                        onClick = { activeTab = tab },
                        label = { Text(tab, fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (allStreams.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No live streams detected yet. Connect to vehicle OBD2 adapter to discover live sensors.",
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (filteredStreams.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No live streams match \"$searchQuery\" in $activeTab.",
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
                    items(filteredStreams, key = { it.pid.pidHex }) { stream ->
                        val isFav = favoriteKeys.contains(stream.pid.pidHex)
                        val (formattedVal, formattedUnit) = formatValueWithUnit(stream.rawValue, stream.pid, unitSystem)

                        OutlinedCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onInspectStream(stream) },
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.outlinedCardColors(
                                containerColor = if (isFav) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            ),
                            border = if (isFav) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                            else androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text(
                                                text = "PID $${stream.pid.pidHex.uppercase()}",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = stream.pid.name,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    if (stream.pid.description.isNotBlank() && stream.pid.description != stream.pid.name) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = stream.pid.description,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = formattedVal,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Black,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = formattedUnit,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(10.dp))

                                    IconButton(
                                        onClick = { onToggleFavorite(stream.pid.pidHex) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        DashboardStarIcon(
                                            isSelected = isFav,
                                            tint = if (isFav) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                            modifier = Modifier.size(18.dp)
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

data class CanSignalDisplayItem(
    val key: String,
    val name: String,
    val value: String,
    val unit: String,
    val isLive: Boolean
)

@Composable
fun FullScreenAdvancedDynamicsPickerView(
    opendbcSignals: Map<String, OpendbcManager.DecodedOpendbcSignal>,
    favoriteKeys: Set<String>,
    unitSystem: UnitSystem = UnitSystem.SAE,
    isConnected: Boolean = true,
    isScanning: Boolean = false,
    onToggleFavorite: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearFavorites: () -> Unit,
    onSelectSmartDefaults: () -> Unit,
    onInspectSignal: (String, String, String) -> Unit,
    onScan: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    var searchQuery by remember { mutableStateOf("") }
    var activeTab by remember { mutableStateOf("ALL") }

    val spdUnit = if (unitSystem == UnitSystem.SAE) "mph" else "km/h"
    val pressUnit = if (unitSystem == UnitSystem.SAE) "psi" else "bar"

    val standardSignals = remember(unitSystem, isConnected) {
        listOf(
            CanSignalDisplayItem("Steering Angle", "Steering Angle", "--", "deg", false),
            CanSignalDisplayItem("Brake Pressure", "Brake Pressure", "--", pressUnit, false),
            CanSignalDisplayItem("Accelerator", "Accelerator", "--", "%", false),
            CanSignalDisplayItem("Yaw Rate", "Yaw Rate", "--", "deg/s", false),
            CanSignalDisplayItem("Wheel FL", "Wheel FL", "--", spdUnit, false),
            CanSignalDisplayItem("Wheel FR", "Wheel FR", "--", spdUnit, false),
            CanSignalDisplayItem("Wheel RL", "Wheel RL", "--", spdUnit, false),
            CanSignalDisplayItem("Wheel RR", "Wheel RR", "--", spdUnit, false)
        )
    }

    val allSignalItems = remember(opendbcSignals, standardSignals) {
        if (opendbcSignals.isNotEmpty()) {
            opendbcSignals.values.map { sig ->
                val cleanVal = sig.formattedValue.replace(sig.unit, "").trim()
                CanSignalDisplayItem(
                    key = sig.name,
                    name = sig.name,
                    value = cleanVal,
                    unit = sig.unit,
                    isLive = true
                )
            }
        } else {
            standardSignals
        }
    }

    val filteredItems = remember(allSignalItems, favoriteKeys, searchQuery, activeTab) {
        val q = searchQuery.trim().lowercase()
        allSignalItems.filter { item ->
            val matchesTab = when (activeTab) {
                "ALL" -> true
                "FAVORITES" -> favoriteKeys.contains(item.key)
                else -> true
            }
            val matchesSearch = q.isEmpty() ||
                    item.name.lowercase().contains(q) ||
                    item.unit.lowercase().contains(q)
            matchesTab && matchesSearch
        }
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
                    enabled = isConnected && !isScanning,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    if (isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Scanning...", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    } else {
                        Text("Scan CAN Bus", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Advanced Dynamics Signal Selector",
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "${favoriteKeys.size} Pinned to Dashboard • ${allSignalItems.size} CAN Chassis Signals",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Search Bar
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                border = androidx.compose.foundation.BorderStroke(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search Signals",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = "Search CAN signals (Steering, Brake, Wheel, Yaw)...",
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                            )
                        }
                        androidx.compose.foundation.text.BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            textStyle = TextStyle(
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Medium
                            ),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { searchQuery = "" },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear search",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Quick Actions Row
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                item {
                    Surface(
                        onClick = onSelectSmartDefaults,
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "Auto-Pick Best (8 Gauges)",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
                item {
                    Surface(
                        onClick = onSelectAll,
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = "Select All",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
                item {
                    Surface(
                        onClick = onClearFavorites,
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = "Clear",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Tabs Row
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(listOf("ALL", "FAVORITES")) { tab ->
                    val isSelected = tab == activeTab
                    FilterChip(
                        selected = isSelected,
                        onClick = { activeTab = tab },
                        label = { Text(tab, fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (filteredItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No CAN signals match \"$searchQuery\" in $activeTab.",
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
                    items(filteredItems, key = { it.key }) { item ->
                        val isFav = favoriteKeys.contains(item.key)

                        OutlinedCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onInspectSignal(item.name, item.value, item.unit) },
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.outlinedCardColors(
                                containerColor = if (isFav) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            ),
                            border = if (isFav) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                            else androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text(
                                                text = if (item.isLive) "CAN 100Hz" else "CHASSIS",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = item.name,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = item.value,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Black,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = item.unit,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(10.dp))

                                    IconButton(
                                        onClick = { onToggleFavorite(item.key) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        DashboardStarIcon(
                                            isSelected = isFav,
                                            tint = if (isFav) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                            modifier = Modifier.size(18.dp)
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

// -----------------------------------------------------------------------------
// Modern, Clean, Flat Automotive Instrument Cluster / Dashboard Warning Icons
// -----------------------------------------------------------------------------

@Composable
fun DashboardInspectorIcon(
    tint: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier.size(13.dp)
) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.13f
        // Flat circular gauge lens
        drawCircle(
            color = tint,
            radius = w * 0.34f,
            center = Offset(w * 0.42f, h * 0.42f),
            style = Stroke(width = stroke)
        )
        // Gauge needle inside lens
        drawLine(
            color = tint,
            start = Offset(w * 0.42f, h * 0.42f),
            end = Offset(w * 0.56f, h * 0.28f),
            strokeWidth = stroke * 0.9f,
            cap = StrokeCap.Round
        )
        // Diagonal handle
        drawLine(
            color = tint,
            start = Offset(w * 0.67f, h * 0.67f),
            end = Offset(w * 0.90f, h * 0.90f),
            strokeWidth = stroke * 1.15f,
            cap = StrokeCap.Round
        )
    }
}

@Composable
fun DashboardClusterTuneIcon(
    tint: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier.size(13.dp)
) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.12f
        // Three flat horizontal equalizer/cluster selector rails with indicator nodes
        val y1 = h * 0.24f
        val y2 = h * 0.50f
        val y3 = h * 0.76f
        drawLine(tint, Offset(w * 0.12f, y1), Offset(w * 0.88f, y1), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(w * 0.12f, y2), Offset(w * 0.88f, y2), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(w * 0.12f, y3), Offset(w * 0.88f, y3), strokeWidth = stroke, cap = StrokeCap.Round)
        drawCircle(tint, radius = w * 0.13f, center = Offset(w * 0.34f, y1))
        drawCircle(tint, radius = w * 0.13f, center = Offset(w * 0.68f, y2))
        drawCircle(tint, radius = w * 0.13f, center = Offset(w * 0.44f, y3))
    }
}

@Composable
fun DashboardStarIcon(
    isSelected: Boolean,
    tint: Color,
    modifier: Modifier = Modifier.size(16.dp)
) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w * 0.5f
        val cy = h * 0.5f
        val outerR = w * 0.44f
        val innerR = w * 0.19f
        val path = Path()
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) outerR else innerR
            val angle = Math.toRadians((i * 36.0) - 90.0)
            val x = cx + (r * Math.cos(angle)).toFloat()
            val y = cy + (r * Math.sin(angle)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        if (isSelected) {
            drawPath(path, color = tint)
        } else {
            drawPath(path, color = tint, style = Stroke(width = w * 0.10f, cap = StrokeCap.Round))
        }
    }
}

@Composable
fun DashboardAlertTriangleIcon(
    tint: Color,
    modifier: Modifier = Modifier.size(14.dp)
) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.11f
        val tri = Path().apply {
            moveTo(w * 0.50f, h * 0.12f)
            lineTo(w * 0.90f, h * 0.86f)
            lineTo(w * 0.10f, h * 0.86f)
            close()
        }
        drawPath(tri, color = tint, style = Stroke(width = stroke, cap = StrokeCap.Round))
        // Exclamation stem & dot inside master warning triangle
        drawLine(tint, Offset(w * 0.50f, h * 0.38f), Offset(w * 0.50f, h * 0.62f), strokeWidth = stroke * 1.05f, cap = StrokeCap.Round)
        drawCircle(tint, radius = w * 0.065f, center = Offset(w * 0.50f, h * 0.74f))
    }
}

@Composable
fun SendPaperPlaneIcon(
    tint: Color,
    modifier: Modifier = Modifier.size(18.dp)
) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.10f
        // Clean, flat, geometric paper airplane icon pointing top-right
        val wingPath = Path().apply {
            moveTo(w * 0.90f, h * 0.12f) // Nose tip (top-right)
            lineTo(w * 0.10f, h * 0.45f) // Left wing tip
            lineTo(w * 0.42f, h * 0.58f) // Center fold notch
            lineTo(w * 0.55f, h * 0.90f) // Bottom tail tip
            close()
        }
        drawPath(wingPath, color = tint, style = Stroke(width = stroke, cap = StrokeCap.Round))
        // Inner crease line from nose tip to center fold notch
        drawLine(
            color = tint,
            start = Offset(w * 0.90f, h * 0.12f),
            end = Offset(w * 0.42f, h * 0.58f),
            strokeWidth = stroke * 0.95f,
            cap = StrokeCap.Round
        )
    }
}

@Composable
fun ChatEventFlatIcon(
    eventType: String,
    tint: Color,
    modifier: Modifier = Modifier.size(15.dp)
) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.105f
        when (eventType) {
            "CAR" -> {
                // Flat, modern vehicle profile icon
                val body = Path().apply {
                    moveTo(w * 0.08f, h * 0.66f)
                    lineTo(w * 0.08f, h * 0.50f)
                    lineTo(w * 0.22f, h * 0.46f)
                    lineTo(w * 0.34f, h * 0.26f)
                    lineTo(w * 0.68f, h * 0.26f)
                    lineTo(w * 0.82f, h * 0.46f)
                    lineTo(w * 0.92f, h * 0.50f)
                    lineTo(w * 0.92f, h * 0.66f)
                    close()
                }
                drawPath(body, color = tint, style = Stroke(width = stroke, cap = StrokeCap.Round))
                // Wheels
                drawCircle(tint, radius = w * 0.11f, center = Offset(w * 0.27f, h * 0.70f), style = Stroke(width = stroke))
                drawCircle(tint, radius = w * 0.11f, center = Offset(w * 0.73f, h * 0.70f), style = Stroke(width = stroke))
            }
            "SIGNAL" -> {
                // Flat, modern diagnostic pulse / telemetry waveform icon
                val wave = Path().apply {
                    moveTo(w * 0.08f, h * 0.52f)
                    lineTo(w * 0.28f, h * 0.52f)
                    lineTo(w * 0.40f, h * 0.20f)
                    lineTo(w * 0.56f, h * 0.82f)
                    lineTo(w * 0.68f, h * 0.42f)
                    lineTo(w * 0.78f, h * 0.52f)
                    lineTo(w * 0.92f, h * 0.52f)
                }
                drawPath(wave, color = tint, style = Stroke(width = stroke, cap = StrokeCap.Round))
            }
            "REC" -> {
                // Flat recording indicator (outer ring + solid inner core)
                drawCircle(tint, radius = w * 0.38f, center = Offset(w * 0.5f, h * 0.5f), style = Stroke(width = stroke))
                drawCircle(tint, radius = w * 0.18f, center = Offset(w * 0.5f, h * 0.5f))
            }
            "SAVE" -> {
                // Flat telemetry document / snapshot card icon
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.18f, h * 0.12f),
                    size = Size(w * 0.64f, h * 0.76f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.10f, w * 0.10f),
                    style = Stroke(width = stroke)
                )
                drawLine(tint, Offset(w * 0.32f, h * 0.36f), Offset(w * 0.68f, h * 0.36f), strokeWidth = stroke, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.32f, h * 0.52f), Offset(w * 0.68f, h * 0.52f), strokeWidth = stroke, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.32f, h * 0.68f), Offset(w * 0.54f, h * 0.68f), strokeWidth = stroke, cap = StrokeCap.Round)
            }
            "TRASH" -> {
                // Flat minimal bin icon
                drawLine(tint, Offset(w * 0.20f, h * 0.26f), Offset(w * 0.80f, h * 0.26f), strokeWidth = stroke, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.38f, h * 0.16f), Offset(w * 0.62f, h * 0.16f), strokeWidth = stroke, cap = StrokeCap.Round)
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.26f, h * 0.26f),
                    size = Size(w * 0.48f, h * 0.60f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.08f, w * 0.08f),
                    style = Stroke(width = stroke)
                )
            }
            "MODEL" -> {
                // Flat geometric 4-point AI spark / star icon
                val spark = Path().apply {
                    moveTo(w * 0.50f, h * 0.10f)
                    lineTo(w * 0.62f, h * 0.38f)
                    lineTo(w * 0.90f, h * 0.50f)
                    lineTo(w * 0.62f, h * 0.62f)
                    lineTo(w * 0.50f, h * 0.90f)
                    lineTo(w * 0.38f, h * 0.62f)
                    lineTo(w * 0.10f, h * 0.50f)
                    lineTo(w * 0.38f, h * 0.38f)
                    close()
                }
                drawPath(spark, color = tint, style = Stroke(width = stroke, cap = StrokeCap.Round))
            }
            "WARN" -> {
                val tri = Path().apply {
                    moveTo(w * 0.50f, h * 0.14f)
                    lineTo(w * 0.88f, h * 0.84f)
                    lineTo(w * 0.12f, h * 0.84f)
                    close()
                }
                drawPath(tri, color = tint, style = Stroke(width = stroke, cap = StrokeCap.Round))
                drawLine(tint, Offset(w * 0.50f, h * 0.40f), Offset(w * 0.50f, h * 0.62f), strokeWidth = stroke, cap = StrokeCap.Round)
                drawCircle(tint, radius = w * 0.06f, center = Offset(w * 0.50f, h * 0.73f))
            }
            else -> {
                // Flat info badge
                drawCircle(tint, radius = w * 0.38f, center = Offset(w * 0.5f, h * 0.5f), style = Stroke(width = stroke))
                drawCircle(tint, radius = w * 0.06f, center = Offset(w * 0.5f, h * 0.33f))
                drawLine(tint, Offset(w * 0.5f, h * 0.46f), Offset(w * 0.5f, h * 0.70f), strokeWidth = stroke, cap = StrokeCap.Round)
            }
        }
    }
}

@Composable
fun DashboardPidIcon(
    item: CustomPidItem,
    tint: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier.size(16.dp)
) {
    val nameLower = item.name.lowercase()
    val unitLower = item.unit.lowercase()
    val isTemp = unitLower.contains("°") || nameLower.contains("temp") || nameLower.contains("coolant") || nameLower.contains("cht") || nameLower.contains("atf") || nameLower.contains("tft")

    androidx.compose.foundation.Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.10f

        when {
            // 1. Instrument Cluster Thermometer + Fluid Waves (Coolant / ATF / Oil / Inverter Temp)
            isTemp -> {
                drawLine(tint, Offset(w * 0.5f, h * 0.14f), Offset(w * 0.5f, h * 0.58f), strokeWidth = stroke * 1.1f, cap = StrokeCap.Round)
                drawCircle(tint, radius = w * 0.14f, center = Offset(w * 0.5f, h * 0.64f))
                // Two horizontal tick marks on thermometer stem
                drawLine(tint, Offset(w * 0.54f, h * 0.25f), Offset(w * 0.68f, h * 0.25f), strokeWidth = stroke * 0.85f, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.54f, h * 0.40f), Offset(w * 0.68f, h * 0.40f), strokeWidth = stroke * 0.85f, cap = StrokeCap.Round)
                // Flat coolant waves at base
                drawLine(tint, Offset(w * 0.16f, h * 0.86f), Offset(w * 0.84f, h * 0.86f), strokeWidth = stroke * 0.9f, cap = StrokeCap.Round)
            }

            // 2. Hybrid / HV Battery & Electric Drive Ready Lamp (High-Voltage Lightning Emblem)
            item.category == CustomPidCategory.HYBRID_EV -> {
                drawCircle(tint, radius = w * 0.42f, center = Offset(w * 0.5f, h * 0.5f), style = Stroke(width = stroke))
                val bolt = Path().apply {
                    moveTo(w * 0.55f, h * 0.20f)
                    lineTo(w * 0.34f, h * 0.52f)
                    lineTo(w * 0.50f, h * 0.52f)
                    lineTo(w * 0.45f, h * 0.80f)
                    lineTo(w * 0.66f, h * 0.48f)
                    lineTo(w * 0.50f, h * 0.48f)
                    close()
                }
                drawPath(bolt, color = tint)
            }

            // 3. 12V Battery / Charging System Dashboard Warning Lamp
            item.category == CustomPidCategory.ELECTRICAL -> {
                // Battery terminals
                drawRect(tint, topLeft = Offset(w * 0.22f, h * 0.18f), size = Size(w * 0.14f, h * 0.08f))
                drawRect(tint, topLeft = Offset(w * 0.64f, h * 0.18f), size = Size(w * 0.14f, h * 0.08f))
                // Battery body outline
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.12f, h * 0.26f),
                    size = Size(w * 0.76f, h * 0.56f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.08f),
                    style = Stroke(width = stroke)
                )
                // Minus sign (left) and Plus sign (right)
                drawLine(tint, Offset(w * 0.24f, h * 0.54f), Offset(w * 0.38f, h * 0.54f), strokeWidth = stroke, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.62f, h * 0.54f), Offset(w * 0.76f, h * 0.54f), strokeWidth = stroke, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.69f, h * 0.47f), Offset(w * 0.69f, h * 0.61f), strokeWidth = stroke, cap = StrokeCap.Round)
            }

            // 4. Transmission / Drivetrain Planetary Gear Lamp
            item.category == CustomPidCategory.TRANSMISSION -> {
                val cx = w * 0.5f
                val cy = h * 0.5f
                drawCircle(tint, radius = w * 0.28f, center = Offset(cx, cy), style = Stroke(width = stroke * 1.1f))
                drawCircle(tint, radius = w * 0.11f, center = Offset(cx, cy))
                for (i in 0 until 8) {
                    val angle = Math.toRadians(i * 45.0)
                    val x1 = cx + (w * 0.28f * Math.cos(angle)).toFloat()
                    val y1 = cy + (h * 0.28f * Math.sin(angle)).toFloat()
                    val x2 = cx + (w * 0.42f * Math.cos(angle)).toFloat()
                    val y2 = cy + (h * 0.42f * Math.sin(angle)).toFloat()
                    drawLine(tint, Offset(x1, y1), Offset(x2, y2), strokeWidth = stroke * 1.15f, cap = StrokeCap.Round)
                }
            }

            // 5. Brakes / ABS / TPMS Tire Warning Lamp
            item.category == CustomPidCategory.CHASSIS_TPMS -> {
                val cx = w * 0.5f
                val cy = h * 0.5f
                drawCircle(tint, radius = w * 0.32f, center = Offset(cx, cy), style = Stroke(width = stroke))
                // Brake shoe arcs on left & right (! style)
                drawArc(
                    color = tint,
                    startAngle = 140f,
                    sweepAngle = 80f,
                    useCenter = false,
                    topLeft = Offset(w * 0.06f, h * 0.12f),
                    size = Size(w * 0.88f, h * 0.76f),
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
                drawArc(
                    color = tint,
                    startAngle = -40f,
                    sweepAngle = 80f,
                    useCenter = false,
                    topLeft = Offset(w * 0.06f, h * 0.12f),
                    size = Size(w * 0.88f, h * 0.76f),
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
                drawCircle(tint, radius = w * 0.09f, center = Offset(cx, cy))
            }

            // 6. Climate / Cabin Airflow Lamp
            item.category == CustomPidCategory.CLIMATE_BODY -> {
                val cx = w * 0.5f
                val cy = h * 0.5f
                for (i in 0 until 3) {
                    val angle = Math.toRadians(i * 60.0)
                    val dx = (w * 0.38f * Math.cos(angle)).toFloat()
                    val dy = (h * 0.38f * Math.sin(angle)).toFloat()
                    drawLine(tint, Offset(cx - dx, cy - dy), Offset(cx + dx, cy + dy), strokeWidth = stroke, cap = StrokeCap.Round)
                }
            }

            // 7. Binary Switch / Status Indicator Lamp
            item.category == CustomPidCategory.STATUS_SWITCHES -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.12f, h * 0.28f),
                    size = Size(w * 0.76f, h * 0.44f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(h * 0.22f),
                    style = Stroke(width = stroke)
                )
                val knobX = if (item.formattedValue in setOf("ON", "YES")) w * 0.64f else w * 0.36f
                drawCircle(tint, radius = w * 0.13f, center = Offset(knobX, h * 0.5f))
            }

            // 8. Default Powertrain / Check Engine Block Outline Lamp
            else -> {
                val p = Path().apply {
                    moveTo(w * 0.26f, h * 0.32f)
                    lineTo(w * 0.72f, h * 0.32f)
                    lineTo(w * 0.84f, h * 0.44f)
                    lineTo(w * 0.84f, h * 0.72f)
                    lineTo(w * 0.26f, h * 0.72f)
                    lineTo(w * 0.16f, h * 0.60f)
                    lineTo(w * 0.16f, h * 0.42f)
                    close()
                }
                drawPath(p, color = tint, style = Stroke(width = stroke))
                // Top valve cover & front pulley
                drawLine(tint, Offset(w * 0.36f, h * 0.20f), Offset(w * 0.62f, h * 0.20f), strokeWidth = stroke, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.49f, h * 0.20f), Offset(w * 0.49f, h * 0.32f), strokeWidth = stroke)
            }
        }
    }
}

@Composable
fun FullScreenCustomPidPickerView(
    allItems: List<CustomPidItem>,
    favoriteKeys: Set<String>,
    isHybridOrEv: Boolean,
    vehicleTitle: String,
    isConnected: Boolean = true,
    isScanning: Boolean,
    onToggleFavorite: (String) -> Unit,
    onApplySmartDefaults: () -> Unit,
    onSelectQuestionable: () -> Unit,
    onSelectAllGauges: () -> Unit,
    onClearFavorites: () -> Unit,
    onInspectItem: ((CustomPidItem) -> Unit)? = null,
    onScan: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    var searchQuery by remember { mutableStateOf("") }
    var activeTab by remember { mutableStateOf("ALL") }

    val questionableCount = remember(allItems) {
        allItems.count { it.healthStatus != PidHealthStatus.NORMAL }
    }

    val availableTabs = remember(allItems, isHybridOrEv, questionableCount) {
        buildList {
            add("ALL")
            if (questionableCount > 0) {
                add("FLAGGED")
            }
            add("FAVORITES")
            if (isHybridOrEv && allItems.any { it.category == CustomPidCategory.HYBRID_EV }) {
                add(CustomPidCategory.HYBRID_EV.displayName)
            }
            for (cat in listOf(
                CustomPidCategory.POWERTRAIN,
                CustomPidCategory.TRANSMISSION,
                CustomPidCategory.ELECTRICAL,
                CustomPidCategory.CHASSIS_TPMS,
                CustomPidCategory.CLIMATE_BODY,
                CustomPidCategory.STATUS_SWITCHES
            )) {
                if (allItems.any { it.category == cat }) {
                    add(cat.displayName)
                }
            }
        }
    }

    val filteredItems = remember(allItems, favoriteKeys, searchQuery, activeTab) {
        val q = searchQuery.trim().lowercase()
        allItems.filter { item ->
            val matchesTab = when (activeTab) {
                "ALL" -> !item.isBooleanStatus || q.isNotEmpty()
                "FLAGGED" -> item.healthStatus != PidHealthStatus.NORMAL
                "FAVORITES" -> favoriteKeys.contains(item.key)
                else -> item.category.displayName == activeTab
            }
            val matchesSearch = q.isEmpty() ||
                    item.name.lowercase().contains(q) ||
                    item.category.displayName.lowercase().contains(q) ||
                    item.rawPidId.lowercase().contains(q) ||
                    item.unit.lowercase().contains(q)
            matchesTab && matchesSearch
        }
    }

    val groupedItems = remember(filteredItems) {
        filteredItems.groupBy { it.category }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            // Top Navigation Row
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
                    enabled = isConnected && !isScanning,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    if (isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Scanning...", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    } else {
                        Text("Rescan ECU", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Title & Stats Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "$vehicleTitle Custom PID Catalog",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = buildString {
                            append("${favoriteKeys.size} Pinned • ${allItems.size} Valid Signals")
                            if (questionableCount > 0) {
                                append(" • $questionableCount Out of Spec / Marginal")
                            }
                        },
                        fontSize = 11.sp,
                        color = if (questionableCount > 0) Color(0xFFFFCA28) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Vertically Centered Custom Search Bar (Prevents Material3 OutlinedTextField clipping)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                border = androidx.compose.foundation.BorderStroke(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search PIDs",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = "Search sensors (Battery, Temp, Oil, Tire)...",
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                            )
                        }
                        androidx.compose.foundation.text.BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            textStyle = TextStyle(
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Medium
                            ),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { searchQuery = "" },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear search",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Category Filter Tabs
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(availableTabs) { tab ->
                    val isSelected = tab == activeTab
                    val label = when (tab) {
                        "FAVORITES" -> "Favorites (${favoriteKeys.size})"
                        "FLAGGED" -> "Flagged ($questionableCount)"
                        else -> tab
                    }
                    FilterChip(
                        selected = isSelected,
                        onClick = { activeTab = tab },
                        leadingIcon = if (tab == "FLAGGED") {
                            {
                                DashboardAlertTriangleIcon(
                                    tint = if (isSelected) Color(0xFFFF5252) else Color(0xFFFFCA28),
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        } else null,
                        label = { Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                        shape = RoundedCornerShape(10.dp)
                    )
                }
            }

            // Quick Actions Row (Smart Defaults / Select Questionable / All Gauges / Clear)
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item {
                    Surface(
                        onClick = onApplySmartDefaults,
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "Auto-Pick Best",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }

                item {
                    val hasFlagged = questionableCount > 0
                    Surface(
                        onClick = onSelectQuestionable,
                        shape = RoundedCornerShape(8.dp),
                        color = if (hasFlagged) Color(0xFFE65100).copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant,
                        border = if (hasFlagged) androidx.compose.foundation.BorderStroke(0.8.dp, Color(0xFFFFCA28)) else null
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            DashboardAlertTriangleIcon(
                                tint = if (hasFlagged) Color(0xFFFFCA28) else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "Pin Questionable ($questionableCount)",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (hasFlagged) Color(0xFFFFCA28) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                item {
                    Surface(
                        onClick = onSelectAllGauges,
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = "All Gauges",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }

                item {
                    Surface(
                        onClick = onClearFavorites,
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = "Clear",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            if (allItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No Custom PIDs scanned yet.\nTap 'Rescan ECU' while connected to discover all parameters for this vehicle.",
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (filteredItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No parameters match \"$searchQuery\" in $activeTab.",
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
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    groupedItems.forEach { (category, catItems) ->
                        item(key = "hdr_${category.name}") {
                            Text(
                                text = "${category.displayName.uppercase()} (${catItems.size})",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.8.sp,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp, start = 2.dp)
                            )
                        }
                        items(catItems, key = { it.key }) { item ->
                            val isFav = favoriteKeys.contains(item.key)
                            val baseAccentColor = when (item.category) {
                                CustomPidCategory.HYBRID_EV -> Color(0xFF00E676)
                                CustomPidCategory.POWERTRAIN -> Color(0xFF00E5FF)
                                CustomPidCategory.TRANSMISSION -> Color(0xFFFFB74D)
                                CustomPidCategory.ELECTRICAL -> Color(0xFF64B5F6)
                                CustomPidCategory.CHASSIS_TPMS -> Color(0xFFFF8A65)
                                CustomPidCategory.CLIMATE_BODY -> Color(0xFF81D4FA)
                                CustomPidCategory.STATUS_SWITCHES -> Color(0xFFB0BEC5)
                            }
                            val accentColor = when (item.healthStatus) {
                                PidHealthStatus.OUT_OF_RANGE -> Color(0xFFFF5252)
                                PidHealthStatus.MARGINAL -> Color(0xFFFFCA28)
                                PidHealthStatus.NORMAL -> baseAccentColor
                            }
                            val rowSurfaceColor = when {
                                item.healthStatus == PidHealthStatus.OUT_OF_RANGE -> Color(0xFFB71C1C).copy(alpha = 0.18f)
                                item.healthStatus == PidHealthStatus.MARGINAL -> Color(0xFFE65100).copy(alpha = 0.16f)
                                isFav -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            }
                            val rowBorderColor = when {
                                item.healthStatus == PidHealthStatus.OUT_OF_RANGE -> Color(0xFFFF5252).copy(alpha = 0.85f)
                                item.healthStatus == PidHealthStatus.MARGINAL -> Color(0xFFFFCA28).copy(alpha = 0.80f)
                                isFav -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                            }

                            Surface(
                                onClick = { onToggleFavorite(item.key) },
                                shape = RoundedCornerShape(12.dp),
                                color = rowSurfaceColor,
                                border = androidx.compose.foundation.BorderStroke(
                                    width = if (item.healthStatus != PidHealthStatus.NORMAL || isFav) 1.1.dp else 0.5.dp,
                                    color = rowBorderColor
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(32.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(accentColor.copy(alpha = 0.16f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            DashboardPidIcon(
                                                item = item,
                                                tint = accentColor,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(10.dp))

                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = item.name,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    modifier = Modifier.weight(1f, fill = false)
                                                )
                                                if (item.healthStatus != PidHealthStatus.NORMAL) {
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    val badgeBg = if (item.healthStatus == PidHealthStatus.OUT_OF_RANGE) Color(0xFFB71C1C) else Color(0xFFE65100)
                                                    val badgeFg = if (item.healthStatus == PidHealthStatus.OUT_OF_RANGE) Color(0xFFFF8A80) else Color(0xFFFFE082)
                                                    val badgeText = if (item.healthStatus == PidHealthStatus.OUT_OF_RANGE) "OUT OF SPEC" else "MARGINAL"
                                                    Surface(
                                                        shape = RoundedCornerShape(5.dp),
                                                        color = badgeBg
                                                    ) {
                                                        Row(
                                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            DashboardAlertTriangleIcon(
                                                                tint = badgeFg,
                                                                modifier = Modifier.size(10.dp)
                                                            )
                                                            Spacer(modifier = Modifier.width(3.dp))
                                                            Text(
                                                                text = badgeText,
                                                                fontSize = 8.sp,
                                                                fontWeight = FontWeight.ExtraBold,
                                                                color = badgeFg
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                            Text(
                                                text = buildString {
                                                    append("${item.category.displayName} • ${item.rawPidId}")
                                                    if (!item.normalRangeLabel.isNullOrBlank()) {
                                                        append(" • ${item.normalRangeLabel}")
                                                    }
                                                },
                                                fontSize = 10.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                color = if (item.healthStatus != PidHealthStatus.NORMAL) accentColor else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(8.dp))

                                    // Live Reading + Favorite Star
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (item.isBooleanStatus) {
                                            val isPositive = item.formattedValue in setOf("ON", "YES")
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = if (isPositive) Color(0xFF1B5E20) else MaterialTheme.colorScheme.surfaceVariant
                                            ) {
                                                Text(
                                                    text = item.formattedValue,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = if (isPositive) Color(0xFF81C784) else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                                )
                                            }
                                        } else {
                                            val valueTextColor = when (item.healthStatus) {
                                                PidHealthStatus.OUT_OF_RANGE -> Color(0xFFFF5252)
                                                PidHealthStatus.MARGINAL -> Color(0xFFFFCA28)
                                                PidHealthStatus.NORMAL -> MaterialTheme.colorScheme.primary
                                            }
                                            Column(horizontalAlignment = Alignment.End) {
                                                Text(
                                                    text = item.formattedValue,
                                                    fontSize = 15.sp,
                                                    fontWeight = FontWeight.Black,
                                                    color = valueTextColor
                                                )
                                                if (item.unit.isNotBlank()) {
                                                    Text(
                                                        text = item.unit,
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                        }

                                        if (onInspectItem != null) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Surface(
                                                onClick = { onInspectItem(item) },
                                                shape = RoundedCornerShape(6.dp),
                                                color = MaterialTheme.colorScheme.surface,
                                                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
                                            ) {
                                                Text(
                                                    text = "Info",
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.width(10.dp))

                                        DashboardStarIcon(
                                            isSelected = isFav,
                                            tint = if (isFav) Color(0xFFFFD54F) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                            modifier = Modifier.size(18.dp)
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
    BackHandler(onBack = onBack)

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

data class TelemetryTileDetailInfo(
    val sourceBadge: String,
    val statusBadge: String,
    val statusBgColor: Color,
    val statusTextColor: Color,
    val title: String,
    val subtitle: String,
    val formattedValue: String,
    val unit: String,
    val specOrLimitsLabel: String? = null,
    val explanation: String,
    val categoryOrPidInfo: String = ""
)

fun getTelemetryParameterExplanation(
    name: String,
    category: String,
    unit: String,
    sourceBadge: String,
    rawDescription: String = ""
): String {
    val n = "$name $rawDescription".lowercase()
    val u = unit.lowercase()
    return when {
        // Advanced Dynamics / Chassis CAN
        n.contains("steering angle") ->
            "Measures steering wheel rotation angle in degrees directly from the Steering Angle Sensor (SAS) on the high-speed chassis CAN bus.\n\n" +
            "• Normal Reading: 0.0° when driving straight ahead on a level crown road. Turning left/right sweeps smoothly up to ±450–540° at full lock.\n" +
            "• Diagnostic Meaning: If the car drives straight with the wheel off-center (e.g., > ±5°), mechanical toe alignment or SAS zero-point calibration is off, which can trigger Electronic Stability Control (ESC) or VSC warning lights."
        n.contains("brake pressure") || (n.contains("brake") && (u == "psi" || u == "bar" || u == "kpa")) ->
            "Measures hydraulic brake fluid line pressure inside the ABS/ESC modulator or brake booster.\n\n" +
            "• Normal Reading: 0 psi (0 bar) with your foot off the brake pedal. Normal gentle stops produce 150–400 psi; emergency panic braking exceeds 1,200+ psi.\n" +
            "• Diagnostic Meaning: If pressure stays above 15–25 psi with your foot off the pedal, suspect a sticking master cylinder, misadjusted brake pedal pushrod, or restricted brake hose causing dragging brakes."
        n.contains("yaw rate") || n.contains("lateral") ->
            "Measures vehicle rotational rate around its vertical axis (deg/s) or cornering G-force from the Inertial Measurement Unit (IMU).\n\n" +
            "• Normal Reading: 0.0 deg/s while stationary or driving straight.\n" +
            "• Diagnostic Meaning: The ABS/ESC module compares Yaw Rate against Steering Angle and Wheel Speeds to detect oversteer (fishtailing) or understeer (plowing) on slippery roads."
        n.contains("wheel") && (n.contains("fl") || n.contains("fr") || n.contains("rl") || n.contains("rr") || n.contains("speed")) ->
            "Measures individual rotational speed at a single corner wheel via the ABS tone ring and wheel speed sensor.\n\n" +
            "• Normal Reading: All 4 wheels (FL, FR, RL, RR) should match within 0.5 mph while driving straight.\n" +
            "• Diagnostic Meaning: A single wheel dropping to 0 mph or spiking erratically triggers ABS/Traction lights (often a cracked tone ring, rust buildup, failing wheel bearing hub, or damaged sensor harness). A steady 1–2 mph mismatch on one corner can indicate an under-inflated tire or mismatched tire size."
        n.contains("accelerator") || n.contains("pedal") ->
            "Measures driver accelerator pedal position (0% = foot off pedal, 100% = wide-open throttle floor) from dual redundant Hall-effect sensors.\n\n" +
            "• Diagnostic Meaning: Smooth, linear progression from 0% to 100% confirms healthy pedal position sensors. Dead spots or sudden drops cause limp-home mode and throttle actuator codes."

        // Mode 06 Monitors
        sourceBadge.contains("MODE 06") && n.contains("misfire") ->
            "Mode 06 Cylinder Misfire Monitor tracks crankshaft speed slowdowns on each power stroke (both Exponentially Weighted Moving Average and current drive-cycle counts).\n\n" +
            "• Normal Reading: 0 misfires (or well below the manufacturer threshold).\n" +
            "• Diagnostic Meaning: Mode 06 catches intermittent misfires under load (such as a weak ignition coil, worn spark plug, carbon-fouled injector, or failing AFM/MDS lifter) long before enough misfires accumulate to set a Check Engine Light (P0300–P0308)."
        sourceBadge.contains("MODE 06") && n.contains("catalyst") ->
            "Mode 06 Catalytic Converter Monitor compares upstream air/fuel ratio fluctuations against downstream oxygen sensor switching to calculate oxygen storage capacity.\n\n" +
            "• Diagnostic Meaning: A test value creeping close to the failure limit warns that the catalytic converter substrate is losing efficiency (often due to oil burning, rich fuel trims, or exhaust leaks) before P0420/P0430 triggers."
        sourceBadge.contains("MODE 06") && (n.contains("o2") || n.contains("oxygen") || n.contains("lambda")) ->
            "Mode 06 Oxygen / Air-Fuel Ratio Sensor Monitor evaluates sensor voltage thresholds, rich-to-lean transition response time, and internal heater resistance.\n\n" +
            "• Diagnostic Meaning: Sluggish O2 response times cause poor fuel economy, surging, and elevated tailpipe emissions."
        sourceBadge.contains("MODE 06") && n.contains("evap") ->
            "Mode 06 Evaporative Emissions (EVAP) Monitor checks fuel tank vapor sealing integrity against reference leak orifices (typically 0.040\" and 0.020\").\n\n" +
            "• Diagnostic Meaning: Values near the limit indicate a deteriorating gas cap seal, cracked vapor line, or sticking purge/vent solenoid."
        sourceBadge.contains("MODE 06") ->
            "Mode 06 On-Board Diagnostic Component Monitor raw test value evaluated by the vehicle's PCM during non-continuous driving cycle self-tests.\n\n" +
            "• Diagnostic Meaning: Comparing the measured value against the factory Min/Max limits reveals components that are marginal or wearing out before a permanent fault code is stored."

        // Custom PIDs (Mode 22 / OBDb)
        n.contains("knock") && (u == "v" || u.contains("volt")) ->
            "Measures raw piezoelectric acoustic vibration voltage from the engine block knock sensor (0.0–5.0 V).\n\n" +
            "• Normal Reading: 0.2 V to 4.5 V depending on engine RPM and normal valvetrain/piston mechanical acoustics (especially on V6/V8 engines like the Chrysler 3.6L Pentastar).\n" +
            "• Important Distinction: Raw knock sensor voltage is NOT timing retard! A healthy knock sensor generates voltage continuously as it listens to the engine block. Only when the PCM detects a sharp detonation frequency spike does it pull ignition timing (shown separately as 'Knock Retard' in degrees °)."
        n.contains("knock") ->
            "Measures degrees of spark timing pulled back (retarded) by the engine computer when acoustic detonation (pinging/spark knock) is detected.\n\n" +
            "• Normal Reading: 0.0° at idle and steady cruise (brief 0.5–1.5° blips during sudden heavy throttle tip-in are normal).\n" +
            "• Diagnostic Meaning: Sustained knock retard above 3.0–4.0° under load reduces power and indicates low-octane fuel, excessive combustion chamber carbon, lean air/fuel ratio, or elevated intake/coolant temperatures."
        n.contains("trans") && (n.contains("temp") || n.contains("sump") || n.contains("fluid") || n.contains("atf") || n.contains("converter")) ->
            "Measures automatic transmission fluid (ATF) temperature inside the transmission pan, valve body, or torque converter outlet.\n\n" +
            "• Normal Reading: 150–205 °F (65–96 °C) when fully warmed up.\n" +
            "• Diagnostic Meaning: Every 20 °F above 200 °F cuts ATF life in half. Readings over 225 °F (107 °C) indicate heavy towing load, torque converter slip, or a restricted transmission cooler. Over 240 °F (115 °C) risks immediate clutch & seal damage."
        n.contains("slip") && n.contains("torque") ->
            "Measures rotational speed difference (RPM) between the engine crankshaft and transmission input shaft when the torque converter lockup clutch is engaged.\n\n" +
            "• Normal Reading: 0–30 RPM during steady highway lockup.\n" +
            "• Diagnostic Meaning: Slip exceeding 60–100 RPM while lockup is commanded indicates torque converter clutch friction wear, a sticking TCC solenoid, or low transmission line pressure (often felt as highway 'shudder')."
        n.contains("oil") && n.contains("pressure") ->
            "Measures pressurized engine oil gallery pressure supplying the crankshaft bearings, camshafts, and VVT phasers.\n\n" +
            "• Normal Reading: Typically 18–35 psi at hot idle, rising to 45–75 psi at 2,500+ RPM.\n" +
            "• Diagnostic Meaning: Hot idle pressure below 12–15 psi can starve cam phasers (triggering VVT timing codes) and risks rod/main bearing wear."
        (n.contains("oil") || n.contains("cylinder head")) && n.contains("temp") ->
            "Measures engine lubricating oil or cylinder head metal temperature.\n\n" +
            "• Normal Reading: 185–225 °F (85–107 °C) once warmed up (typically 10–15 °F warmer than coolant under highway load).\n" +
            "• Diagnostic Meaning: Temperatures exceeding 245 °F (118 °C) accelerate oil viscosity breakdown."
        n.contains("cam") || n.contains("phaser") || n.contains("vvt") || n.contains("vct") ->
            "Measures Variable Valve Timing (VVT/VCT) camshaft phase angle or camshaft-to-crankshaft offset in degrees.\n\n" +
            "• Normal Reading: Phaser error (Desired minus Actual) should stay near 0.0° (±1.5°).\n" +
            "• Diagnostic Meaning: Large phaser errors or sluggish response point to low/dirty engine oil, a clogged VVT oil control solenoid screen, or timing chain stretch."
        n.contains("tire") || n.contains("tpms") ->
            "Measures individual tire inflation pressure or internal tire air temperature from the wheel-mounted TPMS radio transmitter.\n\n" +
            "• Normal Reading: Typically 32–36 psi cold for passenger cars, minivans, and SUVs (check driver door jamb placard).\n" +
            "• Diagnostic Meaning: Pressures below 26–28 psi illuminate the dashboard TPMS warning light, increase tire shoulder wear, reduce fuel economy, and build excess sidewall heat at highway speeds."
        n.contains("soc") || (n.contains("hybrid") && n.contains("battery")) || n.contains("block") ->
            "Measures High-Voltage (HV) hybrid/EV traction battery State of Charge (%) or individual module block voltages.\n\n" +
            "• Normal Reading: Hybrid SOC normally cycles between 40% and 75%. All HV battery blocks should remain within 0.25–0.30 V of each other under acceleration and regen.\n" +
            "• Diagnostic Meaning: A voltage delta > 0.5 V–0.8 V between the highest and lowest battery block under load indicates a weak/failing NiMH or Li-ion module pair."
        n.contains("inverter") || n.contains("mg1") || n.contains("mg2") || n.contains("motor") ->
            "Measures hybrid/EV electric traction motor-generator (MG1/MG2) or power inverter coolant temperature.\n\n" +
            "• Normal Reading: 95–185 °F (35–85 °C).\n" +
            "• Diagnostic Meaning: Temperatures above 195–210 °F (90–99 °C) often indicate a failing electric inverter water pump or air trapped in the inverter cooling loop."
        n.contains("fuel rail") || n.contains("fuel pressure") ->
            "Measures fuel pressure inside the injector rail.\n\n" +
            "• Normal Reading: Port fuel injection typically runs 45–65 psi; gasoline direct injection (GDI) runs 450–2,900+ psi depending on load.\n" +
            "• Diagnostic Meaning: Low rail pressure under acceleration causes lean fuel trims, hesitation, and high-RPM misfires."
        n.contains("injector") && n.contains("width") ->
            "Measures fuel injector open duration in milliseconds (ms) per combustion cycle.\n\n" +
            "• Normal Reading: 1.8–3.5 ms at warm idle on port-injected engines, scaling up to 12–20 ms at wide-open throttle.\n" +
            "• Diagnostic Meaning: Uneven pulse widths between banks indicate the ECU is compensating for a vacuum leak, exhaust leak, or imbalanced injector flow."
        u == "v" && (n.contains("battery") || n.contains("12v") || n.contains("aux") || n.contains("bcm")) ->
            "Measures 12V electrical system voltage at the battery or control module.\n\n" +
            "• Normal Reading: 13.5–14.7 V with the engine running (alternator/DC-DC converter charging); 12.4–12.8 V resting with engine off.\n" +
            "• Diagnostic Meaning: Running voltage below 13.0 V indicates an undercharging alternator; resting voltage below 12.0 V indicates a discharged or sulfated 12V battery."
        else ->
            if (rawDescription.isNotBlank() && !rawDescription.equals(name, ignoreCase = true)) {
                "$rawDescription\n\nThis vehicle-specific parameter is queried directly from the electronic control module ($category). Use VERA AI below to analyze how this reading correlates with your engine load, RPM, and current driving conditions."
            } else {
                "Vehicle-specific diagnostic parameter ($name) reported by the $category control module. Tap 'Ask VERA AI About This Parameter' below for a tailored analysis of this reading on your vehicle."
            }
    }
}

@Composable
fun FullScreenTelemetryTileDetailView(
    info: TelemetryTileDetailInfo,
    onAskVera: (String) -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

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
            Spacer(modifier = Modifier.height(8.dp))

            // Top Navigation Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(
                    onClick = onBack,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text("← Back", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }

                if (info.categoryOrPidInfo.isNotBlank()) {
                    Text(
                        text = info.categoryOrPidInfo,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Hero Value Card
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
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
                                text = info.sourceBadge,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }

                        Surface(
                            color = info.statusBgColor,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = info.statusBadge,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = info.statusTextColor,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = info.title,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    if (info.subtitle.isNotBlank()) {
                        Text(
                            text = info.subtitle,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 3.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "${info.formattedValue} ${info.unit}".trim(),
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Black,
                        color = if (info.statusBadge.contains("FAIL") || info.statusBadge.contains("OUT OF SPEC")) {
                            Color(0xFFFF5252)
                        } else if (info.statusBadge.contains("WARN") || info.statusBadge.contains("MARGINAL")) {
                            Color(0xFFFFCA28)
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        textAlign = TextAlign.Center
                    )

                    if (!info.specOrLimitsLabel.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
                            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                        ) {
                            Text(
                                text = info.specOrLimitsLabel,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Mechanic Explanation Card
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "What This Data Means & Diagnostic Guidance",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = info.explanation,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Ask VERA AI Quick Action Button
            Button(
                onClick = {
                    val valPart = "${info.formattedValue} ${info.unit}".trim()
                    val specPart = if (!info.specOrLimitsLabel.isNullOrBlank()) " (${info.specOrLimitsLabel})" else ""
                    onAskVera("Explain what my ${info.title} reading of $valPart$specPart means for my vehicle and whether it looks healthy.")
                },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                ChatEventFlatIcon(
                    eventType = "MODEL",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Ask VERA AI About This Reading",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Multi-Stream Real-Time Live Graph View
// -----------------------------------------------------------------------------

data class MultiStreamPlotItem(
    val pidHex: String,
    val name: String,
    val description: String,
    val rawValue: Double,
    val formattedValue: String,
    val unit: String,
    val history: List<StreamSample>,
    val color: Color
)

@Composable
fun MultiStreamLiveGraphView(
    selectedStreamPids: List<String>,
    supportedStreams: List<LiveStreamData>,
    mode22Streams: List<Mode22StreamData>,
    metrics: BluetoothObdManager.VehicleMetrics,
    unitSystem: UnitSystem,
    vehicleDetails: String?,
    vehicleVin: String?,
    odoFormatted: String?,
    isRecording: Boolean,
    durationSeconds: Int,
    sampleCount: Int,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onSaveCsv: (RecordedCsvResult) -> Unit,
    stoppedCsvResult: RecordedCsvResult?,
    onTogglePid: (String) -> Unit,
    onAttachToChat: ((RecordedCsvResult) -> Unit)? = null,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val palette = remember {
        listOf(
            Color(0xFF00E5FF), // Electric Cyan
            Color(0xFFFFC107), // Amber Gold
            Color(0xFFE040FB), // Vibrant Magenta
            Color(0xFF00E676), // Neon Spring Green
            Color(0xFFFF6D00), // Vivid Orange
            Color(0xFFFF5252), // Bright Coral Red
            Color(0xFF40C4FF), // Deep Sky Blue
            Color(0xFFEEFF41)  // Lime Accent
        )
    }

    // Resolve selected PIDs into live stream items with history
    val activePlotItems = remember(selectedStreamPids, supportedStreams, mode22Streams, metrics, unitSystem) {
        selectedStreamPids.mapIndexed { index, pidKey ->
            val color = palette[index % palette.size]
            val supStream = supportedStreams.find { it.pid.pidHex.equals(pidKey, ignoreCase = true) }
            if (supStream != null) {
                val (fmtVal, fmtUnit) = formatValueWithUnit(supStream.rawValue, supStream.pid, unitSystem)
                MultiStreamPlotItem(
                    pidHex = supStream.pid.pidHex,
                    name = supStream.pid.name,
                    description = supStream.pid.description,
                    rawValue = supStream.rawValue,
                    formattedValue = fmtVal,
                    unit = fmtUnit,
                    history = supStream.history,
                    color = color
                )
            } else if (pidKey.startsWith("22_")) {
                val rawPid = pidKey.removePrefix("22_")
                val m22 = mode22Streams.find { it.pid.pidHex.equals(rawPid, ignoreCase = true) }
                if (m22 != null) {
                    val isMoving = metrics.speed > 2
                    val (fmtVal, fmtUnit) = formatMode22ValueWithUnit(m22.rawValue, m22.pid, unitSystem, isMoving)
                    MultiStreamPlotItem(
                        pidHex = pidKey,
                        name = m22.pid.name,
                        description = m22.pid.description,
                        rawValue = m22.rawValue,
                        formattedValue = fmtVal,
                        unit = fmtUnit,
                        history = listOf(StreamSample(m22.rawValue)),
                        color = color
                    )
                } else {
                    MultiStreamPlotItem(pidKey, "PID $pidKey", "", 0.0, "--", "", emptyList(), color)
                }
            } else {
                when (pidKey) {
                    "0C" -> MultiStreamPlotItem("0C", "Engine RPM", "Crankshaft rotational speed", metrics.rpm.toDouble(), "${metrics.rpm}", "RPM", emptyList(), color)
                    "0D" -> {
                        val spdVal = if (unitSystem == UnitSystem.SAE) metrics.speed * 0.621371 else metrics.speed.toDouble()
                        val spdUnit = if (unitSystem == UnitSystem.SAE) "mph" else "km/h"
                        MultiStreamPlotItem("0D", "Vehicle Speed", "Vehicle velocity", metrics.speed.toDouble(), String.format(java.util.Locale.US, "%.0f", spdVal), spdUnit, emptyList(), color)
                    }
                    "05" -> {
                        val cVal = if (unitSystem == UnitSystem.SAE) (metrics.coolantTemp * 1.8) + 32 else metrics.coolantTemp.toDouble()
                        val cUnit = if (unitSystem == UnitSystem.SAE) "°F" else "°C"
                        MultiStreamPlotItem("05", "Coolant Temp", "Engine coolant temperature", metrics.coolantTemp.toDouble(), String.format(java.util.Locale.US, "%.0f", cVal), cUnit, emptyList(), color)
                    }
                    "04" -> MultiStreamPlotItem("04", "Engine Load", "Engine air charge load", metrics.engineLoad.toDouble(), "${metrics.engineLoad}", "%", emptyList(), color)
                    "06" -> MultiStreamPlotItem("06", "Fuel Trim", "Short term fuel trim", metrics.fuelTrim.toDouble(), "${metrics.fuelTrim}", "%", emptyList(), color)
                    "10" -> MultiStreamPlotItem("10", "MAF Sensor", "Mass air flow rate", metrics.maf, String.format(java.util.Locale.US, "%.1f", metrics.maf), "g/s", emptyList(), color)
                    "0F" -> {
                        val iVal = if (unitSystem == UnitSystem.SAE) (metrics.intakeAirTemp * 1.8) + 32 else metrics.intakeAirTemp.toDouble()
                        val iUnit = if (unitSystem == UnitSystem.SAE) "°F" else "°C"
                        MultiStreamPlotItem("0F", "Intake Air Temp", "Intake air temperature", metrics.intakeAirTemp.toDouble(), String.format(java.util.Locale.US, "%.0f", iVal), iUnit, emptyList(), color)
                    }
                    "11" -> MultiStreamPlotItem("11", "Throttle Position", "Throttle plate position", metrics.throttlePosition.toDouble(), "${metrics.throttlePosition}", "%", emptyList(), color)
                    else -> MultiStreamPlotItem(pidKey, "PID $pidKey", "", 0.0, "--", "", emptyList(), color)
                }
            }
        }
    }

    fun shareTelemetryPlot() {
        val sb = StringBuilder()
        sb.appendLine("=========================================")
        sb.appendLine("   VERA MULTI-STREAM TELEMETRY REPORT   ")
        sb.appendLine("=========================================")
        sb.appendLine("Vehicle: ${vehicleDetails ?: "Connected Vehicle"}")
        if (!vehicleVin.isNullOrBlank()) sb.appendLine("VIN: $vehicleVin")
        if (!odoFormatted.isNullOrBlank()) sb.appendLine("Odometer: $odoFormatted")
        sb.appendLine("Recording Duration: ${durationSeconds}s | Recorded Samples: $sampleCount")
        sb.appendLine("Timestamp: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
        sb.appendLine()
        sb.appendLine("--- MONITORED TELEMETRY STREAMS ---")
        activePlotItems.forEach { item ->
            val vals = item.history.map { it.value }
            val minVal = vals.minOrNull() ?: item.rawValue
            val maxVal = vals.maxOrNull() ?: item.rawValue
            val minFmt = String.format(java.util.Locale.US, "%.1f", minVal)
            val maxFmt = String.format(java.util.Locale.US, "%.1f", maxVal)
            sb.appendLine("• ${item.name} (${item.pidHex}): Live: ${item.formattedValue} ${item.unit} [Range: $minFmt - $maxFmt]")
        }
        sb.appendLine()
        if (stoppedCsvResult != null) {
            sb.appendLine("--- RECORDED CSV TELEMETRY DATA ---")
            sb.appendLine(stoppedCsvResult.csvContent)
        }
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, sb.toString())
            putExtra(Intent.EXTRA_SUBJECT, "VERA Telemetry Plot Log - ${activePlotItems.take(3).joinToString(", ") { it.name }}")
            type = "text/plain"
        }
        context.startActivity(Intent.createChooser(sendIntent, "Share or Email VERA Telemetry Plot"))
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            // Header: Back button + Title + REC button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(
                    onClick = onBack,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("← Dashboard", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }

                Text(
                    text = "Multi-Stream Plot",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground
                )

                TapeDeckRecordButton(
                    isRecording = isRecording,
                    durationSeconds = durationSeconds,
                    sampleCount = sampleCount,
                    onStartRecording = onStartRecording,
                    onStopRecording = onStopRecording
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Graph Subtitle & Action Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(if (isRecording) Color(0xFFFF1744) else Color(0xFF00E676), CircleShape)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isRecording) "RECORDING LIVE ($sampleCount pts)" else "${activePlotItems.size} STREAMS MONITORED",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (isRecording) Color(0xFFFF5252) else MaterialTheme.colorScheme.primary
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledTonalButton(
                        onClick = { shareTelemetryPlot() },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Share, contentDescription = "Share", modifier = Modifier.size(13.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Share / Email", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }

                    if (stoppedCsvResult != null) {
                        Button(
                            onClick = { onSaveCsv(stoppedCsvResult) },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text("Save CSV", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Multi-Stream Shared Canvas Line Graph
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(230.dp),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF10141D),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            ) {
                if (activePlotItems.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "No streams selected. Tap and hold tiles to select streams.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp, vertical = 12.dp)
                    ) {
                        val canvasWidth = size.width
                        val canvasHeight = size.height
                        val padY = 16.dp.toPx()
                        val plotHeight = canvasHeight - (2 * padY)

                        // Draw horizontal reference grid lines (25%, 50%, 75%)
                        val gridAlpha = 0.12f
                        drawLine(
                            color = Color.White.copy(alpha = gridAlpha),
                            start = Offset(0f, padY + plotHeight * 0.25f),
                            end = Offset(canvasWidth, padY + plotHeight * 0.25f),
                            strokeWidth = 1.dp.toPx()
                        )
                        drawLine(
                            color = Color.White.copy(alpha = gridAlpha),
                            start = Offset(0f, padY + plotHeight * 0.50f),
                            end = Offset(canvasWidth, padY + plotHeight * 0.50f),
                            strokeWidth = 1.dp.toPx()
                        )
                        drawLine(
                            color = Color.White.copy(alpha = gridAlpha),
                            start = Offset(0f, padY + plotHeight * 0.75f),
                            end = Offset(canvasWidth, padY + plotHeight * 0.75f),
                            strokeWidth = 1.dp.toPx()
                        )

                        // Plot each stream line
                        activePlotItems.forEach { item ->
                            val samples = item.history.takeLast(100)
                            if (samples.size >= 2) {
                                val minVal = samples.minOf { it.value }
                                val maxVal = samples.maxOf { it.value }
                                val range = if (maxVal == minVal) 1.0 else (maxVal - minVal)

                                val path = Path()
                                var lastX = 0f
                                var lastY = 0f

                                samples.forEachIndexed { i, sample ->
                                    val norm = ((sample.value - minVal) / range).toFloat().coerceIn(0f, 1f)
                                    val x = (i.toFloat() / (samples.size - 1)) * canvasWidth
                                    val y = (canvasHeight - padY) - (norm * plotHeight)

                                    if (i == 0) {
                                        path.moveTo(x, y)
                                    } else {
                                        path.lineTo(x, y)
                                    }
                                    lastX = x
                                    lastY = y
                                }

                                drawPath(
                                    path = path,
                                    color = item.color,
                                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                                )

                                // Glowing head dot on latest data point
                                drawCircle(
                                    color = item.color.copy(alpha = 0.35f),
                                    radius = 6.dp.toPx(),
                                    center = Offset(lastX, lastY)
                                )
                                drawCircle(
                                    color = item.color,
                                    radius = 3.dp.toPx(),
                                    center = Offset(lastX, lastY)
                                )
                                drawCircle(
                                    color = Color.White,
                                    radius = 1.2.dp.toPx(),
                                    center = Offset(lastX, lastY)
                                )
                            } else if (samples.size == 1) {
                                val y = padY + (plotHeight * 0.5f)
                                drawLine(
                                    color = item.color,
                                    start = Offset(0f, y),
                                    end = Offset(canvasWidth, y),
                                    strokeWidth = 2.dp.toPx()
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Legend Header
            Text(
                text = "STREAM COLOR LEGEND",
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            // Legend Items List
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(activePlotItems) { item ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        border = BorderStroke(1.dp, item.color.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                // Color Dot Swatch
                                Box(
                                    modifier = Modifier
                                        .size(13.dp)
                                        .background(item.color, CircleShape)
                                        .border(1.dp, Color.White.copy(alpha = 0.6f), CircleShape)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = item.name,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = item.color.copy(alpha = 0.2f)
                                        ) {
                                            Text(
                                                text = "PID ${item.pidHex}",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = item.color,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                    val vals = item.history.map { it.value }
                                    val minV = vals.minOrNull() ?: item.rawValue
                                    val maxV = vals.maxOrNull() ?: item.rawValue
                                    Text(
                                        text = "Min: ${String.format(java.util.Locale.US, "%.1f", minV)} • Max: ${String.format(java.util.Locale.US, "%.1f", maxV)}",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "${item.formattedValue} ${item.unit}",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Black,
                                    color = item.color
                                )
                                IconButton(
                                    onClick = { onTogglePid(item.pidHex) },
                                    modifier = Modifier.size(28.dp).padding(start = 4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Remove stream",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        modifier = Modifier.size(14.dp)
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

// -----------------------------------------------------------------------------
// Main Diagnostic App Screen
// -----------------------------------------------------------------------------

@SuppressLint("MissingPermission")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VeraMainScreen(
    modifier: Modifier = Modifier,
    viewModel: GeminiAgentViewModel = viewModel()
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val availableDevices = remember(expanded) { viewModel.getAvailableDevices(context) }

    val demoCleanLabel = "Demo: Clean ECU (Healthy Vehicle)"
    val demoDirtyLabel = "Demo: Dirty ECU (Cyl 5 Misfire & P0305)"

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
    val metrics by viewModel.effectiveMetrics.collectAsState()
    val supportedStreams by viewModel.effectiveSupportedStreams.collectAsState()
    val vehicleDetails by viewModel.vehicleDetails.collectAsState()
    val vehicleVin by viewModel.vehicleVin.collectAsState()
    val vehicleOdometerKm by viewModel.vehicleOdometerKm.collectAsState()
    val troubleCodes by viewModel.troubleCodes.collectAsState()
    val dtcDetails by viewModel.dtcDetails.collectAsState()
    val mode06Results by viewModel.mode06Results.collectAsState()
    val mode22Streams by viewModel.effectiveMode22Streams.collectAsState()
    val unitSystem by viewModel.unitSystem.collectAsState()
    val obdbLoadedResult by viewModel.obdbLoadedResult.collectAsState()
    val obdbSignalResults by viewModel.obdbSignalResults.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val recordingDurationSec by viewModel.recordingDurationSeconds.collectAsState()
    val recordingSampleCount by viewModel.recordingSampleCount.collectAsState()

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
    val isHighSpeedCanCapable by viewModel.isHighSpeedCanCapable.collectAsState()
    val adapterHardwareName by viewModel.adapterHardwareName.collectAsState()
    val activeOpendbcProfile by viewModel.activeOpendbcProfile.collectAsState()
    val opendbcSignals by viewModel.opendbcSignals.collectAsState()
    val canSniffReport by viewModel.canSniffReport.collectAsState()
    val isSniffingCan by viewModel.isSniffingCan.collectAsState()
    var isOpendbcExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(opendbcSignals.isNotEmpty()) {
        if (opendbcSignals.isNotEmpty()) {
            isOpendbcExpanded = true
        }
    }
    val isSendingMessage by viewModel.isSendingMessage.collectAsState()
    val isTtsEnabled by viewModel.isTtsEnabled.collectAsState()
    val isSpeaking by viewModel.isSpeaking.collectAsState()
    val isLiveSessionActive by viewModel.isLiveSessionActive.collectAsState()
    val isHandsFreeVoiceActive by viewModel.isHandsFreeVoiceActive.collectAsState()
    val isGeminiSpeaking by viewModel.isGeminiSpeaking.collectAsState()
    val followUpSecondsRemaining by viewModel.followUpSecondsRemaining.collectAsState()
    val isWakeWordListening by viewModel.isWakeWordListening.collectAsState()
    val liveVoiceName by viewModel.liveVoiceName.collectAsState()
    val savedSessions by viewModel.savedSessions.collectAsState()
    val currentSessionId by viewModel.currentSessionId.collectAsState()

    val isKillSwitchDeactivated by viewModel.isKillSwitchDeactivated.collectAsState()
    val killSwitchMessage by viewModel.killSwitchMessage.collectAsState()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()

    BackHandler(enabled = drawerState.isOpen) {
        coroutineScope.launch { drawerState.close() }
    }

    LaunchedEffect(Unit) {
        viewModel.initSessionManager(context)
        viewModel.checkKillSwitch(context)
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
        viewModel.initLocationManager(context)
    }

    // USB OTG Hot-Plug & Auto-Permission Receiver for Wired Adapters (Vgate vLinker FS USB)
    DisposableEffect(context) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
                val action = intent?.action ?: return
                when (action) {
                    "com.vera.app.USB_PERMISSION" -> {
                        val granted = intent.getBooleanExtra(android.hardware.usb.UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        if (granted) {
                            Toast.makeText(context, "USB Permission granted! Connecting to Vgate vLinker FS...", Toast.LENGTH_SHORT).show()
                            viewModel.connectToCar(macAddress, context)
                        } else {
                            Toast.makeText(context, "USB Permission denied.", Toast.LENGTH_SHORT).show()
                        }
                    }
                    android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        Toast.makeText(context, "Wired USB OBD2 Adapter Connected!", Toast.LENGTH_SHORT).show()
                        val available = viewModel.getAvailableDevices(context)
                        val usbDev = available.find { it.isUsb }
                        if (usbDev != null) {
                            selectedDeviceText = usbDev.name
                            macAddress = usbDev.address
                        }
                    }
                    android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        Toast.makeText(context, "Wired USB OBD2 Adapter Disconnected.", Toast.LENGTH_SHORT).show()
                        viewModel.disconnectFromCar()
                    }
                }
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction("com.vera.app.USB_PERMISSION")
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
                android.util.Log.e("VERA", "Error saving chat file", e)
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
                android.util.Log.e("VERA", "Error saving CSV file", e)
                Toast.makeText(context, "Failed to save CSV file: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
        pendingCsvResult = null
    }

    // Export Saved Drive Session Download Launchers (Text, CSV, JSON)
    var pendingExportContent by remember { mutableStateOf<String?>(null) }
    var pendingExportMimeType by remember { mutableStateOf("text/plain") }

    val exportSessionTextLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val content = pendingExportContent
        if (uri != null && content != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(content.toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(context, "Session report downloaded successfully!", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                android.util.Log.e("VERA", "Error downloading session report", e)
                Toast.makeText(context, "Failed to save file: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
        pendingExportContent = null
    }

    val exportSessionCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        val content = pendingExportContent
        if (uri != null && content != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(content.toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(context, "Flight telemetry CSV downloaded successfully!", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                android.util.Log.e("VERA", "Error downloading session telemetry CSV", e)
                Toast.makeText(context, "Failed to save CSV: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
        pendingExportContent = null
    }

    val exportSessionJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val content = pendingExportContent
        if (uri != null && content != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(content.toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(context, "Full session JSON downloaded successfully!", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                android.util.Log.e("VERA", "Error downloading session JSON", e)
                Toast.makeText(context, "Failed to save JSON: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
        pendingExportContent = null
    }

    var selectedSessionForDownload by remember { mutableStateOf<ChatSession?>(null) }

    var attachedFiles by remember { mutableStateOf<List<ChatAttachment>>(emptyList()) }
    var showAttachmentMenu by remember { mutableStateOf(false) }

    var chatCardTopY by remember { mutableFloatStateOf(0f) }
    var chatCardHeight by remember { mutableFloatStateOf(0f) }
    var inputBarHeightPx by remember { mutableFloatStateOf(0f) }



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
            putExtra(Intent.EXTRA_SUBJECT, "VERA Diagnostic Log - ${vehicleDetails ?: "Vehicle"}")
            type = "text/plain"
        }
        val shareIntent = Intent.createChooser(sendIntent, "Share VERA Diagnostic Chat")
        context.startActivity(shareIntent)
    }

    fun saveChatToTextFile() {
        val timeStr = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val defaultFilename = "VERA_ChatLog_$timeStr.txt"
        saveFileLauncher.launch(defaultFilename)
    }


    var selectedDtcForDetail by remember { mutableStateOf<DtcDetails?>(null) }
    val onDtcClick: (String) -> Unit = { code ->
        val cleanCode = code.uppercase().trim()
        val found = dtcDetails.find { it.code.equals(cleanCode, ignoreCase = true) }
        if (found != null) {
            selectedDtcForDetail = found
        } else {
            val knownDesc = ObdDtcRegistry.KNOWN_DESCRIPTIONS[cleanCode]
                ?: "Diagnostic Trouble Code $cleanCode"
            selectedDtcForDetail = DtcDetails(cleanCode, "Diagnostic Trouble Code", knownDesc)
        }
    }
    var selectedStreamForDetail by remember { mutableStateOf<LiveStreamData?>(null) }
    var selectedMode06ForDetail by remember { mutableStateOf<Mode06TestResult?>(null) }
    var selectedTileDetail by remember { mutableStateOf<TelemetryTileDetailInfo?>(null) }
    var showMode06Picker by remember { mutableStateOf(false) }
    var showCustomPidPicker by remember { mutableStateOf(false) }
    var showLiveStreamPicker by remember { mutableStateOf(false) }
    var showAdvancedDynamicsPicker by remember { mutableStateOf(false) }
    var selectedDashboardCategory by remember { mutableStateOf("ALL") }
    var isDtcExpanded by remember { mutableStateOf(false) }
    var isLiveStreamsExpanded by remember { mutableStateOf(true) }
    var isMode06Expanded by remember { mutableStateOf(false) }
    var isMode22Expanded by remember { mutableStateOf(false) }
    val selectedStreamPids = remember { mutableStateListOf<String>() }
    var showMultiStreamGraphView by remember { mutableStateOf(false) }
    val carouselListState = rememberLazyListState()
    val chatListState = rememberLazyListState()
    val mainScrollState = rememberScrollState()
    var prevMessageCount by remember { mutableIntStateOf(0) }
    val lastMessageText = viewModel.messages.lastOrNull()?.text ?: ""
    val messagesCount = viewModel.messages.size

    val loadedObdb = obdbLoadedResult as? ObdbRepository.LoadResult.Loaded
    val isHybridOrEv = remember(vehicleDetails, loadedObdb?.repo) {
        isHybridOrEvVehicle(vehicleDetails, loadedObdb?.repo)
    }
    val allCustomPidItems = remember(mode22Streams, obdbLoadedResult, obdbSignalResults, unitSystem, vehicleDetails, metrics.speed) {
        buildUnifiedCustomPidList(
            mode22Streams = mode22Streams,
            obdbLoadedResult = obdbLoadedResult,
            obdbSignalResults = obdbSignalResults,
            unitSystem = unitSystem,
            vehicleDetails = vehicleDetails,
            isMoving = metrics.speed > 2
        )
    }

    val favPrefs = remember(context) {
        context.getSharedPreferences("vera_custom_pid_favorites", android.content.Context.MODE_PRIVATE)
    }
    val vehicleStorageKey = remember(vehicleVin, vehicleDetails) {
        val rawKey = (vehicleVin?.takeIf { it.isNotBlank() } ?: vehicleDetails?.takeIf { it.isNotBlank() } ?: "default")
            .replace(Regex("[^A-Za-z0-9_]"), "_")
        "fav_pids_$rawKey"
    }
    var favoriteCustomPidKeys by remember(vehicleStorageKey) {
        val saved = favPrefs.getStringSet(vehicleStorageKey, null)
        mutableStateOf<Set<String>>(saved ?: emptySet())
    }
    var hasInitializedDefaultsForVehicle by remember(vehicleStorageKey) {
        mutableStateOf(favPrefs.contains(vehicleStorageKey))
    }

    fun updateFavoriteCustomPidKeys(newSet: Set<String>) {
        favoriteCustomPidKeys = newSet
        hasInitializedDefaultsForVehicle = true
        favPrefs.edit().putStringSet(vehicleStorageKey, newSet).apply()
    }

    val liveStreamStorageKey = remember(vehicleStorageKey) { "fav_streams_$vehicleStorageKey" }
    var favoriteLiveStreamKeys by remember(liveStreamStorageKey) {
        val saved = favPrefs.getStringSet(liveStreamStorageKey, null)
        mutableStateOf<Set<String>>(saved ?: emptySet())
    }
    fun updateFavoriteLiveStreamKeys(newSet: Set<String>) {
        favoriteLiveStreamKeys = newSet
        favPrefs.edit().putStringSet(liveStreamStorageKey, newSet).apply()
    }

    val canSignalStorageKey = remember(vehicleStorageKey) { "fav_can_$vehicleStorageKey" }
    var favoriteCanSignalKeys by remember(canSignalStorageKey) {
        val saved = favPrefs.getStringSet(canSignalStorageKey, null)
        mutableStateOf<Set<String>>(saved ?: emptySet())
    }
    fun updateFavoriteCanSignalKeys(newSet: Set<String>) {
        favoriteCanSignalKeys = newSet
        favPrefs.edit().putStringSet(canSignalStorageKey, newSet).apply()
    }

    val mode06StorageKey = remember(vehicleStorageKey) { "fav_m06_$vehicleStorageKey" }
    var favoriteMode06Keys by remember(mode06StorageKey) {
        val saved = favPrefs.getStringSet(mode06StorageKey, null)
        mutableStateOf<Set<String>>(saved ?: emptySet())
    }
    fun updateFavoriteMode06Keys(newSet: Set<String>) {
        favoriteMode06Keys = newSet
        favPrefs.edit().putStringSet(mode06StorageKey, newSet).apply()
    }

    // Automatically select make/model smart defaults when Custom PIDs arrive if user hasn't customized this vehicle yet
    LaunchedEffect(allCustomPidItems.size, vehicleStorageKey, isHybridOrEv) {
        if (allCustomPidItems.isNotEmpty()) {
            val validKeys = allCustomPidItems.map { it.key }.toSet()
            val matchingSaved = favoriteCustomPidKeys.intersect(validKeys)
            if (!hasInitializedDefaultsForVehicle || matchingSaved.isEmpty()) {
                val smartDefaults = selectDefaultFavoriteCustomPids(allCustomPidItems, isHybridOrEv, maxCount = 8)
                updateFavoriteCustomPidKeys(smartDefaults)
            }
        }
    }

    // Running Chat Log: Do NOT force-scroll down to the chat window when new entries or alerts arrive.
    // The chat remains a peaceful running log that the driver can inspect at their own pace without
    // dragging the screen or pulling live gauges and telemetry cards off-screen.

    // Auto-twirl expansion: when Mode 06 or Mode 22 data arrives, automatically expand panels
    // Automatically collapse and clear panels when disconnected or data is reset
    LaunchedEffect(mode06Results.size) {
        if (mode06Results.isNotEmpty()) {
            isMode06Expanded = true
        } else {
            isMode06Expanded = false
        }
    }

    LaunchedEffect(allCustomPidItems.size) {
        if (allCustomPidItems.isNotEmpty()) {
            isMode22Expanded = true
        } else {
            isMode22Expanded = false
        }
    }

    LaunchedEffect(connectionStatus) {
        if (connectionStatus != "Connected") {
            isMode06Expanded = false
            isMode22Expanded = false
            isDtcExpanded = false
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

    fun buildMode06DetailInfo(m06: Mode06TestResult): TelemetryTileDetailInfo {
        val (formattedVal, displayUnit) = formatMode06ValueWithUnit(m06.testValue, m06.unit, unitSystem)
        val statusBg = when (m06.status) {
            Mode06Status.PASS -> Color(0xFF1B5E20)
            Mode06Status.WARNING -> Color(0xFFE65100)
            Mode06Status.FAIL -> Color(0xFFB71C1C)
            Mode06Status.UNKNOWN -> Color(0xFF263238)
        }
        val statusFg = when (m06.status) {
            Mode06Status.PASS -> Color(0xFF81C784)
            Mode06Status.WARNING -> Color(0xFFFFB74D)
            Mode06Status.FAIL -> Color(0xFFFF8A80)
            Mode06Status.UNKNOWN -> Color(0xFFB0BEC5)
        }
        val limitsLabel = if (m06.maxLimit != null) {
            val (minStr, _) = formatMode06ValueWithUnit(m06.minLimit ?: 0.0, m06.unit, unitSystem)
            val (maxStr, _) = formatMode06ValueWithUnit(m06.maxLimit, m06.unit, unitSystem)
            "Manufacturer Limit: Min $minStr | Max $maxStr $displayUnit"
        } else null

        return TelemetryTileDetailInfo(
            sourceBadge = "MODE 06 • MID $${m06.midHex}",
            statusBadge = m06.status.name,
            statusBgColor = statusBg,
            statusTextColor = statusFg,
            title = m06.name,
            subtitle = "${m06.category} Monitor • ${m06.description}",
            formattedValue = formattedVal,
            unit = displayUnit,
            specOrLimitsLabel = limitsLabel,
            explanation = getTelemetryParameterExplanation(
                name = m06.name,
                category = m06.category,
                unit = displayUnit,
                sourceBadge = "MODE 06",
                rawDescription = m06.description
            ),
            categoryOrPidInfo = m06.category
        )
    }

    fun buildCustomPidDetailInfo(item: CustomPidItem): TelemetryTileDetailInfo {
        val statusBg = when (item.healthStatus) {
            PidHealthStatus.OUT_OF_RANGE -> Color(0xFFB71C1C)
            PidHealthStatus.MARGINAL -> Color(0xFFE65100)
            PidHealthStatus.NORMAL -> Color(0xFF1B5E20)
        }
        val statusFg = when (item.healthStatus) {
            PidHealthStatus.OUT_OF_RANGE -> Color(0xFFFF8A80)
            PidHealthStatus.MARGINAL -> Color(0xFFFFE082)
            PidHealthStatus.NORMAL -> Color(0xFF81C784)
        }
        val statusText = when (item.healthStatus) {
            PidHealthStatus.OUT_OF_RANGE -> "OUT OF SPEC"
            PidHealthStatus.MARGINAL -> "MARGINAL"
            PidHealthStatus.NORMAL -> "IN SPEC / NORMAL"
        }
        return TelemetryTileDetailInfo(
            sourceBadge = "CUSTOM PID • ${item.rawPidId}",
            statusBadge = statusText,
            statusBgColor = statusBg,
            statusTextColor = statusFg,
            title = item.name,
            subtitle = "${item.category.displayName} • ${item.rawPidId}",
            formattedValue = item.formattedValue,
            unit = item.unit,
            specOrLimitsLabel = item.normalRangeLabel,
            explanation = getTelemetryParameterExplanation(
                name = item.name,
                category = item.category.displayName,
                unit = item.unit,
                sourceBadge = "CUSTOM PID",
                rawDescription = item.rawPidId
            ),
            categoryOrPidInfo = item.category.displayName
        )
    }

    fun buildAdvancedDynamicsDetailInfo(label: String, valueStr: String, unitStr: String, isLive: Boolean): TelemetryTileDetailInfo {
        return TelemetryTileDetailInfo(
            sourceBadge = "ADVANCED DYNAMICS • 100Hz CAN",
            statusBadge = if (isLive) "LIVE 100Hz" else "STANDBY",
            statusBgColor = if (isLive) Color(0xFF1B5E20) else Color(0xFF263238),
            statusTextColor = if (isLive) Color(0xFF81C784) else Color(0xFFB0BEC5),
            title = label,
            subtitle = activeOpendbcProfile?.platformName ?: "High-Speed Chassis & Powertrain CAN Broadcast",
            formattedValue = valueStr,
            unit = unitStr,
            specOrLimitsLabel = if (isLive) "Decoded in real time from raw CAN bus broadcast frames" else "Requires raw CAN broadcast frames on OBD-II pins 6 & 14",
            explanation = getTelemetryParameterExplanation(
                name = label,
                category = "Advanced Dynamics",
                unit = unitStr,
                sourceBadge = "ADVANCED DYNAMICS"
            ),
            categoryOrPidInfo = "Chassis CAN"
        )
    }

    if (selectedTileDetail != null) {
        val detail = selectedTileDetail!!
        FullScreenTelemetryTileDetailView(
            info = detail,
            onAskVera = { question ->
                selectedTileDetail = null
                showCustomPidPicker = false
                showMode06Picker = false
                showLiveStreamPicker = false
                showAdvancedDynamicsPicker = false
                isLiveStreamsExpanded = false
                isDtcExpanded = false
                isMode06Expanded = false
                isMode22Expanded = false
                isOpendbcExpanded = false
                viewModel.sendMessageToAgent(userText = question)
            },
            onBack = { selectedTileDetail = null }
        )
        return
    }

    if (showCustomPidPicker) {
        BackHandler { showCustomPidPicker = false }
        FullScreenCustomPidPickerView(
            allItems = allCustomPidItems,
            favoriteKeys = favoriteCustomPidKeys,
            isHybridOrEv = isHybridOrEv,
            vehicleTitle = vehicleMakeTitle,
            isConnected = isConnected,
            isScanning = isScanningMode22,
            onToggleFavorite = { key ->
                val updated = if (favoriteCustomPidKeys.contains(key)) {
                    favoriteCustomPidKeys - key
                } else {
                    favoriteCustomPidKeys + key
                }
                updateFavoriteCustomPidKeys(updated)
            },
            onApplySmartDefaults = {
                updateFavoriteCustomPidKeys(selectDefaultFavoriteCustomPids(allCustomPidItems, isHybridOrEv, maxCount = 8))
            },
            onSelectQuestionable = {
                val flaggedKeys = allCustomPidItems.filter { it.healthStatus != PidHealthStatus.NORMAL }.map { it.key }.toSet()
                if (flaggedKeys.isNotEmpty()) {
                    updateFavoriteCustomPidKeys(flaggedKeys)
                }
            },
            onSelectAllGauges = {
                updateFavoriteCustomPidKeys(allCustomPidItems.filter { !it.isBooleanStatus }.map { it.key }.toSet())
            },
            onClearFavorites = {
                updateFavoriteCustomPidKeys(emptySet())
            },
            onInspectItem = { item ->
                selectedTileDetail = buildCustomPidDetailInfo(item)
            },
            onScan = { viewModel.scanMode22Parameters() },
            onBack = { showCustomPidPicker = false }
        )
        return
    }

    if (showLiveStreamPicker) {
        BackHandler { showLiveStreamPicker = false }
        FullScreenLiveStreamPickerView(
            allStreams = supportedStreams,
            favoriteKeys = favoriteLiveStreamKeys,
            unitSystem = unitSystem,
            isConnected = isConnected,
            onToggleFavorite = { key ->
                val updated = if (favoriteLiveStreamKeys.contains(key)) {
                    favoriteLiveStreamKeys - key
                } else {
                    favoriteLiveStreamKeys + key
                }
                updateFavoriteLiveStreamKeys(updated)
            },
            onSelectAll = {
                updateFavoriteLiveStreamKeys(supportedStreams.map { it.pid.pidHex }.toSet())
            },
            onClearFavorites = {
                updateFavoriteLiveStreamKeys(emptySet())
            },
            onSelectSmartDefaults = {
                updateFavoriteLiveStreamKeys(selectDefaultLiveStreams(supportedStreams))
            },
            onInspectStream = { stream ->
                selectedStreamForDetail = stream
            },
            onBack = { showLiveStreamPicker = false }
        )
        return
    }

    if (showAdvancedDynamicsPicker) {
        BackHandler { showAdvancedDynamicsPicker = false }
        val standardKeys = setOf("Steering Angle", "Brake Pressure", "Accelerator", "Yaw Rate", "Wheel FL", "Wheel FR", "Wheel RL", "Wheel RR")
        FullScreenAdvancedDynamicsPickerView(
            opendbcSignals = opendbcSignals,
            favoriteKeys = favoriteCanSignalKeys,
            unitSystem = unitSystem,
            isConnected = isConnected,
            isScanning = isSniffingCan,
            onToggleFavorite = { key ->
                val updated = if (favoriteCanSignalKeys.contains(key)) {
                    favoriteCanSignalKeys - key
                } else {
                    favoriteCanSignalKeys + key
                }
                updateFavoriteCanSignalKeys(updated)
            },
            onSelectAll = {
                val allKeys = if (opendbcSignals.isNotEmpty()) opendbcSignals.keys.toSet() else standardKeys
                updateFavoriteCanSignalKeys(allKeys)
            },
            onClearFavorites = {
                updateFavoriteCanSignalKeys(emptySet())
            },
            onSelectSmartDefaults = {
                val defs = if (opendbcSignals.isNotEmpty()) opendbcSignals.keys.take(8).toSet() else standardKeys
                updateFavoriteCanSignalKeys(defs)
            },
            onInspectSignal = { name, value, unit ->
                selectedTileDetail = buildAdvancedDynamicsDetailInfo(name, value, unit, opendbcSignals.isNotEmpty())
            },
            onScan = { viewModel.scanCanBus() },
            onBack = { showAdvancedDynamicsPicker = false }
        )
        return
    }

    if (showMode06Picker) {
        BackHandler { showMode06Picker = false }
        FullScreenMode06PickerView(
            results = mode06Results,
            favoriteKeys = favoriteMode06Keys,
            unitSystem = unitSystem,
            isConnected = isConnected,
            isScanning = isScanningMode06,
            onToggleFavorite = { key ->
                val updated = if (favoriteMode06Keys.contains(key)) {
                    favoriteMode06Keys - key
                } else {
                    favoriteMode06Keys + key
                }
                updateFavoriteMode06Keys(updated)
            },
            onSelectAll = {
                updateFavoriteMode06Keys(mode06Results.map { it.name }.toSet())
            },
            onClearFavorites = {
                updateFavoriteMode06Keys(emptySet())
            },
            onSelectFlagged = {
                val flagged = mode06Results.filter { it.status != Mode06Status.PASS }.map { it.name }.toSet()
                if (flagged.isNotEmpty()) {
                    updateFavoriteMode06Keys(flagged)
                }
            },
            onSelectSmartDefaults = {
                updateFavoriteMode06Keys(selectDefaultMode06(mode06Results))
            },
            onSelectMonitor = { m06 ->
                selectedTileDetail = buildMode06DetailInfo(m06)
            },
            onBack = { showMode06Picker = false },
            onScan = { viewModel.scanMode06Monitors() }
        )
        return
    }

    if (selectedStreamForDetail != null) {
        BackHandler { selectedStreamForDetail = null }
        val activeStream = supportedStreams.find { it.pid.pidHex == selectedStreamForDetail?.pid?.pidHex } ?: selectedStreamForDetail!!
        FullScreenStreamDetailView(
            stream = activeStream,
            unitSystem = unitSystem,
            onBack = { selectedStreamForDetail = null }
        )
        return
    }

    if (showMultiStreamGraphView) {
        BackHandler { showMultiStreamGraphView = false }
        MultiStreamLiveGraphView(
            selectedStreamPids = selectedStreamPids.toList(),
            supportedStreams = supportedStreams,
            mode22Streams = mode22Streams,
            metrics = metrics,
            unitSystem = unitSystem,
            vehicleDetails = vehicleDetails,
            vehicleVin = vehicleVin,
            odoFormatted = odoFormatted,
            isRecording = isRecording,
            durationSeconds = recordingDurationSec,
            sampleCount = recordingSampleCount,
            onStartRecording = { viewModel.startRecording() },
            onStopRecording = {
                val csvResult = viewModel.stopRecording()
                if (csvResult.sampleCount > 0) {
                    stoppedCsvResult = csvResult
                    pendingCsvResult = csvResult
                }
            },
            onSaveCsv = { result ->
                pendingCsvResult = result
                saveCsvLauncher.launch(result.defaultFilename)
            },
            stoppedCsvResult = stoppedCsvResult ?: viewModel.lastRecordedCsvResult,
            onTogglePid = { pid ->
                if (selectedStreamPids.contains(pid)) {
                    selectedStreamPids.remove(pid)
                } else {
                    selectedStreamPids.add(pid)
                }
            },
            onBack = { showMultiStreamGraphView = false }
        )
        return
    }

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
                                text = "VERA Chats",
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
                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable {
                                                    viewModel.loadChatSession(session.id)
                                                    coroutineScope.launch { drawerState.close() }
                                                }
                                        ) {
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
                                                add("${session.safeMessages.size} msgs")
                                                if (session.safeDtcs.isNotEmpty()) add("${session.safeDtcs.size} DTC")
                                                if (session.hasReplayData) add("🎬 Flight Log (${session.safeRecordedSamples.size} pts)")
                                            }.joinToString(" • ")

                                            Text(
                                                text = metaText,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = if (session.safeDtcs.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                            )
                                        }

                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(start = 8.dp)
                                        ) {
                                            if (session.hasReplayData) {
                                                // Graph Telemetry Flight Data Button
                                                Surface(
                                                    onClick = {
                                                        viewModel.loadChatSession(session.id)
                                                        showMultiStreamGraphView = true
                                                        coroutineScope.launch { drawerState.close() }
                                                    },
                                                    shape = CircleShape,
                                                    color = Color(0xFF003847),
                                                    border = BorderStroke(1.5.dp, Color(0xFF00E5FF).copy(alpha = 0.8f)),
                                                    modifier = Modifier.size(42.dp)
                                                ) {
                                                    Box(contentAlignment = Alignment.Center) {
                                                        TelemetryGraphIcon(
                                                            tint = Color(0xFF00E5FF),
                                                            modifier = Modifier.size(20.dp)
                                                        )
                                                    }
                                                }
                                                Spacer(modifier = Modifier.width(10.dp))
                                            }

                                            // Download Session Button (Export Report / Telemetry CSV / JSON)
                                            Surface(
                                                onClick = {
                                                    selectedSessionForDownload = session
                                                },
                                                shape = CircleShape,
                                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)),
                                                modifier = Modifier.size(42.dp)
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    DownloadSaveIcon(
                                                        tint = MaterialTheme.colorScheme.secondary,
                                                        modifier = Modifier.size(19.dp)
                                                    )
                                                }
                                            }
                                            Spacer(modifier = Modifier.width(10.dp))

                                            Surface(
                                                onClick = {
                                                    viewModel.deleteChatSession(session.id)
                                                    Toast.makeText(context, "Session deleted", Toast.LENGTH_SHORT).show()
                                                },
                                                shape = CircleShape,
                                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                                modifier = Modifier.size(42.dp)
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(
                                                        imageVector = Icons.Default.DeleteOutline,
                                                        contentDescription = "Delete Session",
                                                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Download Session Dialog for External AI Export
                    if (selectedSessionForDownload != null) {
                        val session = selectedSessionForDownload!!
                        val cleanTitle = session.title.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(32)
                        val dateTag = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US).format(java.util.Date(session.timestamp))
                        val hasFlightData = session.hasReplayData

                        AlertDialog(
                            onDismissRequest = { selectedSessionForDownload = null },
                            title = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    DownloadSaveIcon(
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "Download Session Log",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 17.sp
                                    )
                                }
                            },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(
                                        text = "Export \"${session.title}\" for second-opinion analysis in ChatGPT, Claude, Gemini, or other AI models.",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    // Option 1: Formatted AI Diagnostic Report (.txt)
                                    Surface(
                                        onClick = {
                                            val textContent = viewModel.exportSessionToText(session.id)
                                            pendingExportContent = textContent
                                            pendingExportMimeType = "text/plain"
                                            val filename = "VERA_Report_${cleanTitle}_$dateTag.txt"
                                            exportSessionTextLauncher.launch(filename)
                                            selectedSessionForDownload = null
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Chat,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(24.dp)
                                            )
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column {
                                                Text(
                                                    "AI Diagnostic Report (.txt)",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.sp
                                                )
                                                Text(
                                                    "Dialogue, vehicle details, DTCs, and telemetry summary. Ideal for pasting or uploading to any AI.",
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }

                                    // Option 2: High-Speed Flight Telemetry (.csv)
                                    if (hasFlightData) {
                                        Surface(
                                            onClick = {
                                                val csvContent = viewModel.exportSessionToCsv(session.id)
                                                pendingExportContent = csvContent
                                                pendingExportMimeType = "text/csv"
                                                val filename = "VERA_Telemetry_${cleanTitle}_$dateTag.csv"
                                                exportSessionCsvLauncher.launch(filename)
                                                selectedSessionForDownload = null
                                            },
                                            shape = RoundedCornerShape(10.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(12.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                DownloadSaveIcon(
                                                    tint = Color(0xFF00E5FF),
                                                    modifier = Modifier.size(22.dp)
                                                )
                                                Spacer(modifier = Modifier.width(12.dp))
                                                Column {
                                                    Text(
                                                        "Flight Telemetry CSV (.csv)",
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 13.sp
                                                    )
                                                    Text(
                                                        "${session.safeRecordedSamples.size} timestamps of RPM, speed, temps, trims, topography, and Mode 22 data.",
                                                        fontSize = 11.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    // Option 3: Full Session JSON (.json)
                                    Surface(
                                        onClick = {
                                            val jsonContent = viewModel.exportSessionToJson(session.id)
                                            pendingExportContent = jsonContent
                                            pendingExportMimeType = "application/json"
                                            val filename = "VERA_Session_${cleanTitle}_$dateTag.json"
                                            exportSessionJsonLauncher.launch(filename)
                                            selectedSessionForDownload = null
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.DirectionsCar,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.tertiary,
                                                modifier = Modifier.size(24.dp)
                                            )
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column {
                                                Text(
                                                    "Raw Session Archive (.json)",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.sp
                                                )
                                                Text(
                                                    "Complete raw structured JSON backup with all metrics and timestamps.",
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            },
                            confirmButton = {},
                            dismissButton = {
                                TextButton(onClick = { selectedSessionForDownload = null }) {
                                    Text("Cancel")
                                }
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(10.dp))

                    // App Settings: Voice & Units Selectors
                    Text(
                        text = "Preferences",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )

                    var drawerVoiceDropdownExpanded by remember { mutableStateOf(false) }
                    var drawerUnitDropdownExpanded by remember { mutableStateOf(false) }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Voice Selector
                        Box(modifier = Modifier.weight(1f)) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { drawerVoiceDropdownExpanded = true },
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Mic,
                                            contentDescription = "Voice",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Column {
                                            Text(
                                                text = "Voice",
                                                fontSize = 9.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                            )
                                            Text(
                                                text = liveVoiceName,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                    Icon(
                                        imageVector = Icons.Default.ArrowDropDown,
                                        contentDescription = "Select Gemini Voice",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            DropdownMenu(
                                expanded = drawerVoiceDropdownExpanded,
                                onDismissRequest = { drawerVoiceDropdownExpanded = false },
                                modifier = Modifier.heightIn(max = 380.dp)
                            ) {
                                val voices = listOf(
                                    Triple("Aoede", "Warm & engaging female", "Default"),
                                    Triple("Kore", "Approachable & relaxed female", "Friendly"),
                                    Triple("Zephyr", "Bright & clear female", "Warm"),
                                    Triple("Fenrir", "Deep & authoritative male", "Mechanic"),
                                    Triple("Puck", "Upbeat & energetic male", "Expressive"),
                                    Triple("Charon", "Calm & steady male", "Deep Resonant")
                                )

                                voices.forEach { (vName, desc, tag) ->
                                    val isSelected = vName.equals(liveVoiceName, ignoreCase = true)
                                    DropdownMenuItem(
                                        text = {
                                            Column(modifier = Modifier.padding(vertical = 2.dp)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        text = "$vName ($tag)",
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                        fontSize = 13.sp,
                                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                                    )
                                                    if (isSelected) {
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Icon(
                                                            imageVector = Icons.Default.Check,
                                                            contentDescription = "Active",
                                                            tint = MaterialTheme.colorScheme.primary,
                                                            modifier = Modifier.size(14.dp)
                                                        )
                                                    }
                                                }
                                                Text(
                                                    text = desc,
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                                )
                                            }
                                        },
                                        onClick = {
                                            viewModel.setLiveVoiceName(vName)
                                            viewModel.previewVoice(vName)
                                            drawerVoiceDropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        // Units Selector
                        Box(modifier = Modifier.weight(1f)) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { drawerUnitDropdownExpanded = true },
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("📐", fontSize = 12.sp)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Column {
                                            Text(
                                                text = "Units",
                                                fontSize = 9.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                            )
                                            Text(
                                                text = if (unitSystem == UnitSystem.SAE) "SAE (°F, mph)" else "Metric (°C, km/h)",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                    Icon(
                                        imageVector = Icons.Default.ArrowDropDown,
                                        contentDescription = "Select Unit System",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            DropdownMenu(
                                expanded = drawerUnitDropdownExpanded,
                                onDismissRequest = { drawerUnitDropdownExpanded = false }
                            ) {
                                val isSae = unitSystem == UnitSystem.SAE
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Column {
                                                Text("SAE / Imperial", fontWeight = if (isSae) FontWeight.Bold else FontWeight.Medium, fontSize = 13.sp)
                                                Text("°F, mph, psi, inHg", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            if (isSae) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                                            }
                                        }
                                    },
                                    onClick = {
                                        viewModel.setUnitSystem(UnitSystem.SAE)
                                        drawerUnitDropdownExpanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Column {
                                                Text("Metric / ISO", fontWeight = if (!isSae) FontWeight.Bold else FontWeight.Medium, fontSize = 13.sp)
                                                Text("°C, km/h, kPa, bar", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            if (!isSae) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                                            }
                                        }
                                    },
                                    onClick = {
                                        viewModel.setUnitSystem(UnitSystem.METRIC)
                                        drawerUnitDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Feedback & Help Button
                    FilledTonalButton(
                        onClick = {
                            coroutineScope.launch { drawerState.close() }
                            val body = buildString {
                                appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (Android ${android.os.Build.VERSION.RELEASE})")
                                appendLine("App Version: ${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})")
                                if (vehicleDetails != null) appendLine("Vehicle: $vehicleDetails")
                                if (vehicleVin != null) appendLine("VIN: $vehicleVin")
                                appendLine("Status: ${if (isConnected) "Connected to OBD-II" else "Disconnected"}")
                                appendLine()
                                appendLine("Hey Kenan, I have some feedback about Vera:")
                                appendLine()
                            }

                            val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
                                data = Uri.parse("mailto:")
                                putExtra(Intent.EXTRA_EMAIL, arrayOf("kpekoz@gmail.com"))
                                putExtra(Intent.EXTRA_SUBJECT, "VERA Diagnostic App - Beta Feedback")
                                putExtra(Intent.EXTRA_TEXT, body)
                            }
                            try {
                                context.startActivity(Intent.createChooser(emailIntent, "Send Feedback via Email"))
                            } catch (_: Exception) {
                                Toast.makeText(context, "No email client installed to send feedback.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Chat,
                            contentDescription = "Send Feedback",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Send Developer Feedback", fontWeight = FontWeight.Bold, fontSize = 12.sp)
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
                            text = "VERA",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (isConnected) {
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

                            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                                Text(
                                    text = if (makeModelYearText.isNotBlank()) makeModelYearText else "Connected Vehicle",
                                    fontSize = 11.sp,
                                    lineHeight = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = if (vinText.isNotBlank()) vinText else "VIN: Detecting...",
                                    fontSize = 10.sp,
                                    lineHeight = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            Text(
                                text = "Vehicle Evaluation & Repair Assistant",
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
                    color = if (isConnected) Color(0xFF0C2A24) else Color(0xFF261318),
                    contentColor = if (isConnected) Color(0xFF00E676) else Color(0xFFFF8A80),
                    modifier = Modifier.border(
                        width = 1.dp,
                        color = if (isConnected) Color(0xFF00E676).copy(alpha = 0.6f) else Color(0xFFFF5252).copy(alpha = 0.6f),
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
                                .background(if (isConnected) Color(0xFF00E676) else Color(0xFFFF5252))
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

        // Deactivation / Kill Switch Banner
        if (isKillSwitchDeactivated) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Test Build Deactivated",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Test Build Decommissioned",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = killSwitchMessage ?: "This test build of VERA has expired or been disabled by the developer. AI features are offline.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.9f)
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
                .clipToBounds()
        ) {
            val availableHeight = this.maxHeight
            val density = LocalDensity.current
            val screenHeightPx = with(density) { availableHeight.toPx() }
            val scrollY = mainScrollState.value.toFloat()

            val chatHeaderTopOnScreen = chatCardTopY - scrollY
            // Keep the "Text Vera" input container permanently attached to the bottom of the screen,
            // while preserving the dynamic drawer-like extension of the chat card's top & side borders as the user scrolls.
            val inputBarTranslationY = 0f

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(mainScrollState)
            ) {

        // Road Topography & Incline Banner
        val roadContext by viewModel.effectiveRoadContext.collectAsState()
        if (roadContext.streetName.isNotBlank() && roadContext.streetName != "Locating road..." && roadContext.streetName != "Locating..." && (!roadContext.isDemoSimulated || viewModel.obdManager.isDemoMode)) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                        RoundedCornerShape(10.dp)
                    )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Navigation,
                        contentDescription = "Road Topography",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = roadContext.formattedTopography,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }
        }

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
                                label = { Text("MAC / Wi-Fi IP / USB ID", fontSize = 11.sp) },
                                placeholder = { Text("e.g. 192.168.0.10:35000, 00:1D:A5...", fontSize = 11.sp) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                textStyle = TextStyle(fontSize = 12.sp),
                                shape = RoundedCornerShape(10.dp),
                                singleLine = true,
                                trailingIcon = {
                                    TextButton(onClick = { useManualInput = false }) {
                                        Text("Devices", fontSize = 10.sp, fontWeight = FontWeight.Bold)
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
                                    label = { Text("Select OBD2 Dongle (BT / USB / Wi-Fi)", fontSize = 11.sp) },
                                    leadingIcon = {
                                        val (icon, tint) = when {
                                            macAddress == "DEMO_SIMULATOR_CLEAN" || selectedDeviceText == demoCleanLabel ->
                                                Icons.Outlined.DirectionsCar to Color(0xFF00E676)
                                            macAddress == "DEMO_SIMULATOR_DIRTY" || selectedDeviceText == demoDirtyLabel ->
                                                Icons.Outlined.WarningAmber to Color(0xFFFF5252)
                                            macAddress.startsWith("USB:") || selectedDeviceText.contains("USB", ignoreCase = true) ->
                                                Icons.Default.Usb to MaterialTheme.colorScheme.primary
                                            macAddress.startsWith("WIFI:") || selectedDeviceText.contains("Wi-Fi", ignoreCase = true) ->
                                                Icons.Default.Wifi to Color(0xFF00E5FF)
                                            else ->
                                                Icons.Default.Bluetooth to Color(0xFF80D8FF)
                                        }
                                        Icon(
                                            imageVector = icon,
                                            contentDescription = null,
                                            tint = tint,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    },
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
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Outlined.DirectionsCar,
                                                contentDescription = null,
                                                tint = Color(0xFF00E676),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        },
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
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Outlined.WarningAmber,
                                                contentDescription = null,
                                                tint = Color(0xFFFF5252),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        },
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
                                            val (deviceIcon, deviceColor) = when {
                                                device.isUsb -> Icons.Default.Usb to MaterialTheme.colorScheme.primary
                                                device.isWifi -> Icons.Default.Wifi to Color(0xFF00E5FF)
                                                else -> Icons.Default.Bluetooth to Color(0xFF80D8FF)
                                            }
                                            DropdownMenuItem(
                                                leadingIcon = {
                                                    Icon(
                                                        imageVector = deviceIcon,
                                                        contentDescription = null,
                                                        tint = deviceColor,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                },
                                                text = { 
                                                    Text(
                                                        device.name, 
                                                        fontSize = 12.sp,
                                                        fontWeight = if (device.isUsb || device.isWifi) FontWeight.ExtraBold else FontWeight.Normal,
                                                        color = when {
                                                            device.isUsb -> MaterialTheme.colorScheme.primary
                                                            device.isWifi -> Color(0xFF00E5FF)
                                                            else -> MaterialTheme.colorScheme.onSurface
                                                        }
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

                                    // Option 3: Enter MAC / Wi-Fi IP Manually
                                    DropdownMenuItem(
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.Edit,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        },
                                        text = {
                                            Text(
                                                "Enter MAC or Wi-Fi IP Manually...",
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
                    enabled = isConnected && !isScanningDtcs,
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
                                color = Color(0xFF0C2A24).copy(alpha = 0.4f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E676).copy(alpha = 0.5f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = androidx.compose.foundation.shape.CircleShape,
                                        color = Color(0xFF00E676)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = Color(0xFF070D18),
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

        // 3. Two Rows Horizontal Scrolling Live Data Carousel with Record Button & Twirldown Toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(
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
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Tape Deck Record Button / RECORDING Indicator
                    TapeDeckRecordButton(
                        isRecording = isRecording,
                        durationSeconds = recordingDurationSec,
                        sampleCount = recordingSampleCount,
                        onStartRecording = {
                            viewModel.startRecording()
                            if (selectedStreamPids.isNotEmpty()) {
                                showMultiStreamGraphView = true
                            }
                        },
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

                if (isConnected) {
                    Text(
                        text = "Tap: Detail & plot • Tap+hold: Detail & plot many",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF4CAF50),
                        lineHeight = 11.sp
                    )
                } else {
                    Text(
                        text = "Disconnected",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Surface(
                onClick = { showLiveStreamPicker = true },
                shape = RoundedCornerShape(8.dp),
                color = if (isConnected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DashboardClusterTuneIcon(
                        tint = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    val displayedCount = if (favoriteLiveStreamKeys.isNotEmpty()) {
                        supportedStreams.count { favoriteLiveStreamKeys.contains(it.pid.pidHex) }
                    } else supportedStreams.size
                    Text(
                        text = if (supportedStreams.isEmpty()) "Select Streams" else "Select ($displayedCount/${supportedStreams.size})",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        }

        // Multi-Stream Selection Floating Banner
        if (selectedStreamPids.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${selectedStreamPids.size} stream${if (selectedStreamPids.size > 1) "s" else ""} selected",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(
                            onClick = { selectedStreamPids.clear() },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("Clear", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        FilledTonalButton(
                            onClick = {
                                if (!isRecording) viewModel.startRecording()
                                showMultiStreamGraphView = true
                            },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(if (isRecording) "View Graph 📈" else "REC & Graph 📈", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = isLiveStreamsExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column {
                Spacer(modifier = Modifier.height(4.dp))

                val displayedStreams = remember(supportedStreams, favoriteLiveStreamKeys) {
                    if (favoriteLiveStreamKeys.isNotEmpty()) {
                        val favs = supportedStreams.filter { favoriteLiveStreamKeys.contains(it.pid.pidHex) }
                        if (favs.isNotEmpty()) favs else supportedStreams
                    } else {
                        supportedStreams
                    }
                }

                // Chunk live streams into pairs for 2 rows in horizontal carousel
                val streamTileChunks = remember(displayedStreams, metrics, isConnected, unitSystem) {
                    if (isConnected && displayedStreams.isNotEmpty()) {
                        displayedStreams.chunked(2)
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
                        items(
                            items = streamTileChunks,
                            key = { chunk -> chunk.joinToString("-") { it.pid.pidHex } }
                        ) { columnItems ->
                            Column(
                                modifier = Modifier.width(102.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                columnItems.forEach { stream ->
                                    key(stream.pid.pidHex) {
                                        val (formattedVal, formattedUnit) = formatValueWithUnit(stream.rawValue, stream.pid, unitSystem)
                                        val pidHex = stream.pid.pidHex
                                        val isSelected = selectedStreamPids.contains(pidHex)
                                        SleekMetricTile(
                                            label = stream.pid.name,
                                            value = formattedVal,
                                            unit = formattedUnit,
                                            isSelected = isSelected,
                                            onClick = {
                                                if (!carouselListState.isScrollInProgress) {
                                                    selectedStreamForDetail = stream
                                                }
                                            },
                                            onLongClick = {
                                                if (selectedStreamPids.contains(pidHex)) {
                                                    selectedStreamPids.remove(pidHex)
                                                } else {
                                                    selectedStreamPids.add(pidHex)
                                                }
                                            },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(66.dp)
                                        )
                                    }
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

                    val rpmSelected = selectedStreamPids.contains("0C")
                    val speedSelected = selectedStreamPids.contains("0D")
                    val coolantSelected = selectedStreamPids.contains("05")
                    val loadSelected = selectedStreamPids.contains("04")
                    val fuelTrimSelected = selectedStreamPids.contains("06")
                    val mafSelected = selectedStreamPids.contains("10")
                    val intakeSelected = selectedStreamPids.contains("0F")
                    val throttleSelected = selectedStreamPids.contains("11")

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SleekMetricTile("RPM", if (isConnected) "${metrics.rpm}" else "--", "RPM", isSelected = rpmSelected, onLongClick = {
                            if (rpmSelected) selectedStreamPids.remove("0C") else selectedStreamPids.add("0C")
                        }, modifier = Modifier.weight(1f))
                        SleekMetricTile("Speed", displaySpeed, speedUnit, isSelected = speedSelected, onLongClick = {
                            if (speedSelected) selectedStreamPids.remove("0D") else selectedStreamPids.add("0D")
                        }, modifier = Modifier.weight(1f))
                        SleekMetricTile("Coolant", displayCoolant, tempUnit, isSelected = coolantSelected, onLongClick = {
                            if (coolantSelected) selectedStreamPids.remove("05") else selectedStreamPids.add("05")
                        }, modifier = Modifier.weight(1f))
                        SleekMetricTile("Load", if (isConnected) "${metrics.engineLoad}" else "--", "%", isSelected = loadSelected, onLongClick = {
                            if (loadSelected) selectedStreamPids.remove("04") else selectedStreamPids.add("04")
                        }, modifier = Modifier.weight(1f))
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SleekMetricTile("Fuel Trim", if (isConnected) "${metrics.fuelTrim}" else "--", "%", isSelected = fuelTrimSelected, onLongClick = {
                            if (fuelTrimSelected) selectedStreamPids.remove("06") else selectedStreamPids.add("06")
                        }, modifier = Modifier.weight(1f))
                        SleekMetricTile("MAF", if (isConnected) String.format("%.1f", metrics.maf) else "--", "g/s", isSelected = mafSelected, onLongClick = {
                            if (mafSelected) selectedStreamPids.remove("10") else selectedStreamPids.add("10")
                        }, modifier = Modifier.weight(1f))
                        SleekMetricTile("Intake", displayIntake, tempUnit, isSelected = intakeSelected, onLongClick = {
                            if (intakeSelected) selectedStreamPids.remove("0F") else selectedStreamPids.add("0F")
                        }, modifier = Modifier.weight(1f))
                        SleekMetricTile("Throttle", if (isConnected) "${metrics.throttlePosition}" else "--", "%", isSelected = throttleSelected, onLongClick = {
                            if (throttleSelected) selectedStreamPids.remove("11") else selectedStreamPids.add("11")
                        }, modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 3.4 Advanced Dynamics & High-Speed CAN Section (Always visible)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isOpendbcExpanded = !isOpendbcExpanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Advanced Dynamics",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (isOpendbcExpanded) " ▲" else " ▼",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (isConnected && activeOpendbcProfile != null) {
                                "Chassis Profile: ${activeOpendbcProfile?.platformName}"
                            } else {
                                "100Hz Raw CAN Chassis & Dynamics"
                            },
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (isConnected && opendbcSignals.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(6.dp))
                            val infiniteTransition = rememberInfiniteTransition(label = "dynamicsPulse")
                            val pulseAlpha by infiniteTransition.animateFloat(
                                initialValue = 0.25f,
                                targetValue = 1.0f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(800, easing = FastOutSlowInEasing),
                                    repeatMode = RepeatMode.Reverse
                                ),
                                label = "dynamicsPulseAlpha"
                            )
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF00E676).copy(alpha = pulseAlpha))
                            )
                        }
                    }
                }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    onClick = { showAdvancedDynamicsPicker = true },
                    shape = RoundedCornerShape(8.dp),
                    color = if (isConnected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(end = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        DashboardClusterTuneIcon(
                            tint = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        val totalCanCount = if (opendbcSignals.isNotEmpty()) opendbcSignals.size else 8
                        val favCanCount = if (favoriteCanSignalKeys.isNotEmpty()) {
                            if (opendbcSignals.isNotEmpty()) {
                                opendbcSignals.keys.count { favoriteCanSignalKeys.contains(it) }
                            } else {
                                val standardKeys = setOf("Steering Angle", "Brake Pressure", "Accelerator", "Yaw Rate", "Wheel FL", "Wheel FR", "Wheel RL", "Wheel RR")
                                favoriteCanSignalKeys.count { standardKeys.contains(it) }
                            }
                        } else totalCanCount
                        Text(
                            text = if (!isConnected && opendbcSignals.isEmpty()) "Select Signals" else "Select ($favCanCount/$totalCanCount)",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }

                FilledTonalButton(
                    onClick = {
                        isOpendbcExpanded = true
                        viewModel.scanCanBus()
                    },
                    enabled = isConnected && !isSniffingCan,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    if (isSniffingCan) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Scanning...", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Text("Scan CAN Bus", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        AnimatedVisibility(
                visible = isOpendbcExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(4.dp))
                    if (opendbcSignals.isNotEmpty()) {
                        val signalList = remember(opendbcSignals, favoriteCanSignalKeys) {
                            if (favoriteCanSignalKeys.isNotEmpty()) {
                                val favs = opendbcSignals.values.filter { favoriteCanSignalKeys.contains(it.name) }
                                if (favs.isNotEmpty()) favs else opendbcSignals.values.toList()
                            } else {
                                opendbcSignals.values.toList()
                            }
                        }
                        val chunks = signalList.chunked(4)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            chunks.forEach { rowSignals ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    rowSignals.forEach { sig ->
                                        val cleanVal = sig.formattedValue.replace(sig.unit, "").trim()
                                        SleekMetricTile(
                                            label = sig.name,
                                            value = cleanVal,
                                            unit = sig.unit,
                                            isSelected = false,
                                            onClick = {
                                                selectedTileDetail = buildAdvancedDynamicsDetailInfo(
                                                    label = sig.name,
                                                    valueStr = cleanVal,
                                                    unitStr = sig.unit,
                                                    isLive = true
                                                )
                                            },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    if (rowSignals.size < 4) {
                                        repeat(4 - rowSignals.size) {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // Clean 8-tile (2 rows of 4) placeholder layout when disconnected or waiting for CAN frames
                        val spdUnit = if (unitSystem == UnitSystem.SAE) "mph" else "km/h"
                        val pressUnit = if (unitSystem == UnitSystem.SAE) "psi" else "bar"
                        val standardTiles = remember(spdUnit, pressUnit) {
                            listOf(
                                Triple("Steering Angle", "--", "deg"),
                                Triple("Brake Pressure", "--", pressUnit),
                                Triple("Accelerator", "--", "%"),
                                Triple("Yaw Rate", "--", "deg/s"),
                                Triple("Wheel FL", "--", spdUnit),
                                Triple("Wheel FR", "--", spdUnit),
                                Triple("Wheel RL", "--", spdUnit),
                                Triple("Wheel RR", "--", spdUnit)
                            )
                        }
                        val displayedStandardTiles = remember(standardTiles, favoriteCanSignalKeys) {
                            if (favoriteCanSignalKeys.isNotEmpty()) {
                                val favs = standardTiles.filter { favoriteCanSignalKeys.contains(it.first) }
                                if (favs.isNotEmpty()) favs else standardTiles
                            } else {
                                standardTiles
                            }
                        }
                        val standardChunks = displayedStandardTiles.chunked(4)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            standardChunks.forEach { rowTiles ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    rowTiles.forEach { tile ->
                                        SleekMetricTile(
                                            label = tile.first,
                                            value = tile.second,
                                            unit = tile.third,
                                            isSelected = false,
                                            onClick = {
                                                selectedTileDetail = buildAdvancedDynamicsDetailInfo(tile.first, tile.second, tile.third, false)
                                            },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    if (rowTiles.size < 4) {
                                        repeat(4 - rowTiles.size) {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = if (isConnected && isHighSpeedCanCapable) {
                                    "Adapter supports high-speed CAN (${adapterHardwareName}), but no broadcast signals have been decoded yet. Tap \"Scan CAN Bus\" (engine on / READY) to see which messages this vehicle sends to the OBD-II port."
                                } else if (isConnected) {
                                    "Connected via standard OBD-II (${adapterHardwareName}). High-speed 100Hz broadcast signals (Steering, Wheel Speeds, Brake Pressure) require a vLinker FS / MC+ or OBDLink adapter (or an SGW bypass cable on 2018+ vehicles)."
                                } else {
                                    "Advanced Dynamics decode real-time steering angle, individual wheel speeds, and brake line pressure directly from high-speed CAN broadcasts. Connect a vLinker FS / MC+ or OBDLink adapter (or start Demo Mode) to stream live."
                                },
                                fontSize = 10.sp,
                                lineHeight = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(8.dp)
                            )
                        }

                        // Only show raw CAN diagnostic report when connected AND no live decoded signals were found
                        if (isConnected) {
                            canSniffReport?.let { rpt ->
                                Spacer(modifier = Modifier.height(4.dp))
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    SelectionContainer {
                                        Text(
                                            text = rpt.toDisplayText(),
                                            fontSize = 9.sp,
                                            lineHeight = 12.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(8.dp)
                                        )
                                    }
                                }
                            }
                        }
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
                    onClick = { showMode06Picker = true },
                    shape = RoundedCornerShape(8.dp),
                    color = if (isConnected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(end = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        DashboardClusterTuneIcon(
                            tint = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        val displayedCount = if (favoriteMode06Keys.isNotEmpty()) {
                            mode06Results.count { favoriteMode06Keys.contains(it.name) }
                        } else mode06Results.size
                        Text(
                            text = if (mode06Results.isEmpty()) "Select Monitors" else "Select ($displayedCount/${mode06Results.size})",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }

                FilledTonalButton(
                    onClick = {
                        isMode06Expanded = true
                        viewModel.scanMode06Monitors()
                    },
                    enabled = isConnected && !isScanningMode06,
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

                            val filteredTiles = remember(mode06Results, selectedDashboardCategory, favoriteMode06Keys) {
                                val base = if (favoriteMode06Keys.isNotEmpty()) {
                                    val favs = mode06Results.filter { favoriteMode06Keys.contains(it.name) }
                                    if (favs.isNotEmpty()) favs else mode06Results
                                } else mode06Results
                                if (selectedDashboardCategory == "ALL") base else base.filter { it.category == selectedDashboardCategory }
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
                                        onClick = { selectedTileDetail = buildMode06DetailInfo(m06) },
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
                                text = "Mode 06 evaluates on-board ECU component monitors (misfires, catalyst, O2 sensors, EVAP). Tap 'Scan Mode 06' or 'Inspector' to view test results.",
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
        val displayedCustomPids = remember(allCustomPidItems, favoriteCustomPidKeys, isHybridOrEv) {
            val favs = allCustomPidItems.filter { favoriteCustomPidKeys.contains(it.key) }
            if (favs.isNotEmpty()) {
                favs
            } else {
                val defaultKeys = selectDefaultFavoriteCustomPids(allCustomPidItems, isHybridOrEv, maxCount = 8)
                allCustomPidItems.filter { defaultKeys.contains(it.key) }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { isMode22Expanded = !isMode22Expanded }
                .padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
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
                    text = if (allCustomPidItems.isEmpty()) {
                        if (isHybridOrEv) "Hybrid Battery, MG1/MG2 Temps & Enhanced PIDs"
                        else "Enhanced PIDs (Trans Temp, Oil Pressure, Voltages)"
                    } else {
                        "Showing ${displayedCustomPids.size} Favorites • ${allCustomPidItems.size} Total Available"
                    },
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    onClick = { showCustomPidPicker = true },
                    shape = RoundedCornerShape(8.dp),
                    color = if (isConnected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(end = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        DashboardClusterTuneIcon(
                            tint = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (allCustomPidItems.isEmpty()) "Select PIDs" else "Select (${displayedCustomPids.size}/${allCustomPidItems.size})",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }

                FilledTonalButton(
                    onClick = {
                        isMode22Expanded = true
                        viewModel.scanMode22Parameters()
                    },
                    enabled = isConnected && !isScanningMode22,
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
                        Text("Scan PIDs", fontSize = 10.sp, fontWeight = FontWeight.Bold)
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
                        if (displayedCustomPids.isNotEmpty()) {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(displayedCustomPids, key = { it.key }) { item ->
                                    val baseAccentColor = when (item.category) {
                                        CustomPidCategory.HYBRID_EV -> Color(0xFF00E676)
                                        CustomPidCategory.POWERTRAIN -> Color(0xFF00E5FF)
                                        CustomPidCategory.TRANSMISSION -> Color(0xFFFFB74D)
                                        CustomPidCategory.ELECTRICAL -> Color(0xFF64B5F6)
                                        CustomPidCategory.CHASSIS_TPMS -> Color(0xFFFF8A65)
                                        CustomPidCategory.CLIMATE_BODY -> Color(0xFF81D4FA)
                                        CustomPidCategory.STATUS_SWITCHES -> Color(0xFFB0BEC5)
                                    }
                                    val accentColor = when (item.healthStatus) {
                                        PidHealthStatus.OUT_OF_RANGE -> Color(0xFFFF5252)
                                        PidHealthStatus.MARGINAL -> Color(0xFFFFCA28)
                                        PidHealthStatus.NORMAL -> baseAccentColor
                                    }
                                    val cardSurfaceColor = when (item.healthStatus) {
                                        PidHealthStatus.OUT_OF_RANGE -> Color(0xFFB71C1C).copy(alpha = 0.16f)
                                        PidHealthStatus.MARGINAL -> Color(0xFFE65100).copy(alpha = 0.14f)
                                        PidHealthStatus.NORMAL -> MaterialTheme.colorScheme.surface
                                    }
                                    val valueTextColor = when (item.healthStatus) {
                                        PidHealthStatus.OUT_OF_RANGE -> Color(0xFFFF5252)
                                        PidHealthStatus.MARGINAL -> Color(0xFFFFCA28)
                                        PidHealthStatus.NORMAL -> MaterialTheme.colorScheme.onSurface
                                    }

                                    Surface(
                                        onClick = { selectedTileDetail = buildCustomPidDetailInfo(item) },
                                        shape = RoundedCornerShape(12.dp),
                                        color = cardSurfaceColor,
                                        border = androidx.compose.foundation.BorderStroke(
                                            width = if (item.healthStatus != PidHealthStatus.NORMAL) 1.1.dp else 0.6.dp,
                                            color = if (item.healthStatus != PidHealthStatus.NORMAL) accentColor.copy(alpha = 0.85f) else accentColor.copy(alpha = 0.4f)
                                        ),
                                        modifier = Modifier
                                            .width(108.dp)
                                            .height(70.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(horizontal = 6.dp, vertical = 5.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.Center
                                            ) {
                                                if (item.healthStatus != PidHealthStatus.NORMAL) {
                                                    DashboardAlertTriangleIcon(
                                                        tint = accentColor,
                                                        modifier = Modifier.size(11.dp)
                                                    )
                                                } else {
                                                    DashboardPidIcon(
                                                        item = item,
                                                        tint = accentColor,
                                                        modifier = Modifier.size(11.dp)
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = item.name,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    color = if (item.healthStatus != PidHealthStatus.NORMAL) accentColor else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Text(
                                                text = item.formattedValue,
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.Black,
                                                maxLines = 1,
                                                color = valueTextColor
                                            )
                                            Text(
                                                text = when (item.healthStatus) {
                                                    PidHealthStatus.OUT_OF_RANGE -> "${item.unit} • ALERT".trim(' ', '•')
                                                    PidHealthStatus.MARGINAL -> "${item.unit} • WARN".trim(' ', '•')
                                                    PidHealthStatus.NORMAL -> item.unit.ifBlank { item.category.displayName.substringBefore(" ") }
                                                },
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                maxLines = 1,
                                                color = accentColor
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            Text(
                                text = "Enhanced Custom PIDs read manufacturer-specific parameters (${if (isHybridOrEv) "HV Battery SOC, MG1/MG2 Inverter Temps, Aux 12V, Tire Pressures" else "Transmission Temp, Oil Pressure, Cylinder Head Temp, Odometer"}). Tap 'Scan PIDs' to query ECU, or 'Select PIDs' to customize your favorites.",
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



        val chatBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
        // 4. Integrated AI Mechanic & Diagnostics Container Card (Aircraft Carrier Hook & Stretch)
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .height(availableHeight)
                .onGloballyPositioned { coordinates ->
                    chatCardTopY = coordinates.positionInParent().y
                    chatCardHeight = coordinates.size.height.toFloat()
                }
                .drawWithContent {
                    drawContent()
                    val barHeight = if (inputBarHeightPx > 0f) inputBarHeightPx else 80.dp.toPx()
                    val surfaceTop = screenHeightPx - barHeight + inputBarTranslationY
                    val localSurfaceTop = surfaceTop - chatHeaderTopOnScreen
                    val strokeWidthPx = 1.5.dp.toPx()
                    val cornerRadiusPx = 18.dp.toPx()
                    val left = strokeWidthPx / 2f
                    val top = strokeWidthPx / 2f
                    val right = size.width - strokeWidthPx / 2f
                    // Extend 16dp under the input bar's top edge so there is no gap, but stop ~60dp above the bottom edge & bottom corners
                    val bottom = (localSurfaceTop + 16.dp.toPx()).coerceIn(cornerRadiusPx, size.height)

                    val path = Path().apply {
                        moveTo(left, bottom)
                        lineTo(left, top + cornerRadiusPx)
                        arcTo(
                            rect = Rect(left, top, left + cornerRadiusPx * 2f, top + cornerRadiusPx * 2f),
                            startAngleDegrees = 180f,
                            sweepAngleDegrees = 90f,
                            forceMoveTo = false
                        )
                        lineTo(right - cornerRadiusPx, top)
                        arcTo(
                            rect = Rect(right - cornerRadiusPx * 2f, top, right, top + cornerRadiusPx * 2f),
                            startAngleDegrees = 270f,
                            sweepAngleDegrees = 90f,
                            forceMoveTo = false
                        )
                        lineTo(right, bottom)
                    }
                    drawPath(
                        path = path,
                        color = chatBorderColor,
                        style = Stroke(width = strokeWidthPx)
                    )
                },
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 0.dp, bottomEnd = 0.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
            ),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 6.dp)
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "VERA AI Chat",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        // Pulldown Model Selector
                        var modelDropdownExpanded by remember { mutableStateOf(false) }
                        val currentModel by viewModel.selectedChatModel.collectAsState()

                        Box {
                            Surface(
                                modifier = Modifier.clickable { modelDropdownExpanded = true },
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = currentModel.shortName,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Icon(
                                        imageVector = Icons.Default.ArrowDropDown,
                                        contentDescription = "Select AI Model",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            DropdownMenu(
                                expanded = modelDropdownExpanded,
                                onDismissRequest = { modelDropdownExpanded = false }
                            ) {
                                GeminiChatModel.entries.forEach { model ->
                                    DropdownMenuItem(
                                        text = {
                                            Column(modifier = Modifier.padding(vertical = 2.dp)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        text = model.displayName,
                                                        fontWeight = if (model == currentModel) FontWeight.Bold else FontWeight.Medium,
                                                        fontSize = 13.sp,
                                                        color = if (model == currentModel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                                    )
                                                    if (model == currentModel) {
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Icon(
                                                            imageVector = Icons.Default.Check,
                                                            contentDescription = "Active",
                                                            tint = MaterialTheme.colorScheme.primary,
                                                            modifier = Modifier.size(14.dp)
                                                        )
                                                    }
                                                }
                                                Text(
                                                    text = model.subtitle,
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                                )
                                            }
                                        },
                                        onClick = {
                                            viewModel.setChatModel(model)
                                            modelDropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }
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
                        LazyColumn(
                            state = chatListState,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            contentPadding = PaddingValues(top = 6.dp, bottom = 28.dp, start = 8.dp, end = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            reverseLayout = false
                        ) {
                        items(viewModel.messages, key = { it.id }) { msg ->
                            val isUser = msg.sender == "User"
                            val isError = msg.sender == "Error"
                            val isSystem = msg.sender == "System"
                            val isCriticalAlert = msg.text.contains("[CRITICAL ALERT]")
                            val isWarningAlert = msg.text.contains("[WARNING]")
                            val isAdvisoryAlert = msg.text.contains("[ADVISORY]")
                            val isInfoAlert = msg.text.contains("[INFO]")
                            val isAlert = isCriticalAlert || isWarningAlert || isAdvisoryAlert || isInfoAlert

                            val alertAccentColor = when {
                                isCriticalAlert || isError -> Color(0xFFFF5252)
                                isWarningAlert -> Color(0xFFFFD54F)
                                isAdvisoryAlert -> Color(0xFF00E5FF)
                                isInfoAlert -> Color(0xFF80D8FF)
                                else -> MaterialTheme.colorScheme.primary
                            }

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
                                        isUser -> MaterialTheme.colorScheme.primaryContainer
                                        isError || isCriticalAlert -> Color(0xFF330E0E)
                                        isWarningAlert -> Color(0xFF261D0A)
                                        isAdvisoryAlert -> Color(0xFF0A1F26)
                                        isInfoAlert -> Color(0xFF0D1E2C)
                                        isSystem -> MaterialTheme.colorScheme.surface
                                        else -> MaterialTheme.colorScheme.surface
                                    },
                                    contentColor = when {
                                        isUser -> MaterialTheme.colorScheme.onPrimaryContainer
                                        isError || isCriticalAlert -> Color(0xFFFFCDD2)
                                        isWarningAlert -> Color(0xFFFFF9C4)
                                        isAdvisoryAlert -> Color(0xFFE0F7FA)
                                        isInfoAlert -> Color(0xFFE1F5FE)
                                        else -> MaterialTheme.colorScheme.onSurface
                                    },
                                    modifier = Modifier
                                        .widthIn(max = 280.dp)
                                        .border(
                                            width = if (isAlert || isError) 1.5.dp else 1.dp,
                                            color = when {
                                                isAlert || isError -> alertAccentColor
                                                isUser -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                                else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                                            },
                                            shape = RoundedCornerShape(14.dp)
                                        )
                                ) {
                                    if (isAlert) {
                                        val alertBadgeTitle = when {
                                            isCriticalAlert -> "CRITICAL ALERT"
                                            isWarningAlert -> "SYSTEM WARNING"
                                            isAdvisoryAlert -> "ADVISORY"
                                            else -> "SYSTEM INFO"
                                        }
                                        val alertIcon = when {
                                            isCriticalAlert -> Icons.Outlined.ErrorOutline
                                            isWarningAlert -> Icons.Outlined.WarningAmber
                                            isAdvisoryAlert -> Icons.Outlined.Info
                                            else -> Icons.Outlined.NotificationsActive
                                        }
                                        val cleanAlertText = msg.text
                                            .replace("🚨", "")
                                            .replace("⚠️", "")
                                            .replace("ℹ️", "")
                                            .replace("📋", "")
                                            .replace("[CRITICAL ALERT]", "")
                                            .replace("[WARNING]", "")
                                            .replace("[ADVISORY]", "")
                                            .replace("[INFO]", "")
                                            .trimStart()

                                        Column(modifier = Modifier.padding(11.dp)) {
                                            // Modern flat header with vector outline icon
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Icon(
                                                    imageVector = alertIcon,
                                                    contentDescription = null,
                                                    tint = alertAccentColor,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = alertBadgeTitle,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    letterSpacing = 1.2.sp,
                                                    color = alertAccentColor
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(5.dp))
                                            HorizontalDivider(
                                                color = alertAccentColor.copy(alpha = 0.25f),
                                                thickness = 1.dp
                                            )
                                            Spacer(modifier = Modifier.height(6.dp))
                                            DtcAwareMessageText(
                                                text = cleanAlertText,
                                                textColor = when {
                                                    isCriticalAlert || isError -> Color(0xFFFFCDD2)
                                                    isWarningAlert -> Color(0xFFFFF9C4)
                                                    isAdvisoryAlert -> Color(0xFFE0F7FA)
                                                    isInfoAlert -> Color(0xFFE1F5FE)
                                                    else -> MaterialTheme.colorScheme.onSurface
                                                },
                                                onDtcClick = onDtcClick
                                            )
                                        }
                                    } else {
                                        val cleanMsgText = msg.text
                                            .replace("🚗", "")
                                            .replace("🌐", "")
                                            .replace("🔴", "")
                                            .replace("💾", "")
                                            .replace("🗑️", "")
                                            .replace("📸", "")
                                            .replace("🧠", "")
                                            .replace("ℹ️", "")
                                            .replace("⚠️", "")
                                            .replace("⛔", "")
                                            .replace("📷", "")
                                            .replace("📄", "")
                                            .trimStart()

                                        val lowerClean = cleanMsgText.lowercase()
                                        val eventIconType: String? = when {
                                            isError || lowerClean.startsWith("cannot ") || lowerClean.startsWith("failed ") || lowerClean.contains("unavailable") -> "WARN"
                                            lowerClean.startsWith("vehicle connected") || lowerClean.startsWith("vin decoded") ||
                                                lowerClean.startsWith("connecting to") || lowerClean.startsWith("successfully connected") ||
                                                lowerClean.startsWith("disconnected from") -> "CAR"
                                            lowerClean.startsWith("obdb") || lowerClean.startsWith("scanning ") ||
                                                lowerClean.startsWith("dtc scan") || lowerClean.startsWith("mode 06 scan") ||
                                                lowerClean.startsWith("custom pids") || lowerClean.startsWith("re-querying mode 09") -> "SIGNAL"
                                            lowerClean.startsWith("live data recording started") -> "REC"
                                            lowerClean.startsWith("recorded live data log") || lowerClean.startsWith("captured 5-minute") -> "SAVE"
                                            lowerClean.startsWith("live data recording discarded") -> "TRASH"
                                            lowerClean.startsWith("diagnostic model changed") -> "MODEL"
                                            isSystem -> "INFO"
                                            else -> null
                                        }

                                        val eventIconTint = when (eventIconType) {
                                            "WARN" -> Color(0xFFFF5252)
                                            "REC" -> Color(0xFFFF5252)
                                            "CAR", "SIGNAL", "SAVE", "MODEL" -> MaterialTheme.colorScheme.primary
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        }

                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                if (eventIconType != null) {
                                                    ChatEventFlatIcon(
                                                        eventType = eventIconType,
                                                        tint = eventIconTint,
                                                        modifier = Modifier.size(14.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(5.dp))
                                                }
                                                Text(
                                                    text = when (msg.sender) {
                                                        "User" -> "You"
                                                        "VERA", "OttoAI" -> "VERA AI"
                                                        "VERA (Live)", "OttoAI (Live)" -> "VERA (Live)"
                                                        else -> msg.sender
                                                    },
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 11.sp,
                                                    color = if (isError) Color(0xFFFF5252) else MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(2.dp))
                                            DtcAwareMessageText(
                                                text = cleanMsgText,
                                                textColor = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                                onDtcClick = onDtcClick
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        item(key = "chat_bottom_anchor") {
                            Spacer(modifier = Modifier.height(28.dp))
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
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                shape = RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp, topStart = 8.dp, topEnd = 8.dp)
            ),
        shape = RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp, topStart = 8.dp, topEnd = 8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
        tonalElevation = 8.dp
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
                placeholder = { Text("Text Vera", fontSize = 13.sp) },
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

            // 1. Send Button (flat, modern paper airplane icon before voice button)
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
                shape = RoundedCornerShape(18.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier.height(36.dp)
            ) {
                if (isSendingMessage) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    SendPaperPlaneIcon(
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(4.dp))

            // 2. Hands-Free 2-Way Voice Streaming Button (Gemini Live)
            val isLiveActive = isLiveSessionActive
            val isFollowUp = followUpSecondsRemaining > 0
            val isStandby = isHandsFreeVoiceActive && !isLiveActive
            val isListening = (isLiveActive || isFollowUp) && !isGeminiSpeaking

            // Full brightness at start (1.0f), then smooth slow fade to 0.15f, then back to full brightness
            val infiniteTransition = rememberInfiniteTransition(label = "voicePulseTransition")
            val pulseAlpha by infiniteTransition.animateFloat(
                initialValue = 1.0f,
                targetValue = 0.18f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "voiceListeningPulse"
            )

            val voiceBgColor = when {
                isGeminiSpeaking -> Color(0xFF0284C7)
                isListening -> Color(0xFF00E5FF).copy(alpha = pulseAlpha)
                isStandby -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }

            val voiceBorderColor = when {
                isGeminiSpeaking -> Color(0xFF38BDF8)
                isListening -> Color(0xFF00E5FF).copy(alpha = (pulseAlpha + 0.25f).coerceAtMost(1f))
                isStandby -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
            }

            val voiceContentColor = when {
                isGeminiSpeaking -> Color(0xFF070D18)
                isListening -> if (pulseAlpha > 0.55f) Color(0xFF070D18) else Color(0xFF00E5FF)
                isStandby -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.primary
            }

            Surface(
                onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        Toast.makeText(context, "Microphone permission required for voice mode.", Toast.LENGTH_SHORT).show()
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else if (isHandsFreeVoiceActive) {
                        // Clean toggle OFF: Single tap turns voice interaction completely OFF from any state
                        viewModel.stopGeminiLiveSession()
                    } else {
                        // Clean toggle ON: Single tap turns voice interaction ON
                        viewModel.startGeminiLiveSession()
                    }
                },
                shape = RoundedCornerShape(18.dp),
                color = voiceBgColor,
                border = androidx.compose.foundation.BorderStroke(1.dp, voiceBorderColor),
                modifier = Modifier.height(36.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Hands-Free Gemini Live Voice Mode",
                        tint = voiceContentColor,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = when {
                            isGeminiSpeaking -> "Speaking"
                            followUpSecondsRemaining > 0 -> "Listening (${followUpSecondsRemaining}s)"
                            isLiveActive -> "Listening"
                            isStandby -> "Hey Vera"
                            else -> "Voice"
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = voiceContentColor
                    )
                }
            }
        }
        }
    }
}

    if (selectedDtcForDetail != null) {
        val dtc = selectedDtcForDetail!!
        val makeModel = vehicleDetails?.split("•")?.take(3)?.joinToString(" ")?.trim() ?: ""
        val googleSearchTerms = if (makeModel.isNotBlank()) {
            "$makeModel ${dtc.code} trouble code causes symptoms repair"
        } else {
            "${dtc.code} OBD2 trouble code causes symptoms repair"
        }
        val googleSearchUrl = "https://www.google.com/search?q=" + java.net.URLEncoder.encode(googleSearchTerms, "UTF-8")
        val obdCodesUrl = "https://www.obd-codes.com/${dtc.code.lowercase()}"

        AlertDialog(
            onDismissRequest = { selectedDtcForDetail = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.border(0.75.dp, Color(0xFFFF5252), RoundedCornerShape(8.dp))
                    ) {
                        Text(
                            text = dtc.code,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 15.sp,
                            color = Color(0xFFFF5252),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "[${dtc.category}]",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            text = {
                Column {
                    Text(
                        text = dtc.description,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = getDtcDiagnosticGuidance(dtc.code, dtc.description),
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                        thickness = 1.dp
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "ONLINE TECHNICAL RESEARCH & WEBPAGES",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(googleSearchUrl))
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Could not open web browser", Toast.LENGTH_SHORT).show()
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurface
                            ),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Google ↗", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        FilledTonalButton(
                            onClick = {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(obdCodesUrl))
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Could not open web browser", Toast.LENGTH_SHORT).show()
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurface
                            ),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Public,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = Color(0xFF00E5FF)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("OBD-Codes ↗", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
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
