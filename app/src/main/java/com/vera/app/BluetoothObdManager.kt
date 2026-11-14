package com.vera.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.*
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Method
import java.util.UUID

// OBD-II PID Definition Data Class
data class ObdPidDefinition(
    val pidHex: String,           // e.g. "04", "0C", "0D"
    val fullCommand: String,      // e.g. "0104", "010C"
    val name: String,             // e.g. "Engine RPM"
    val unit: String,             // e.g. "RPM"
    val description: String,       // e.g. "Engine Revolutions Per Minute"
    val bytesRequired: Int,       // 1 or 2
    @Transient val parseValue: ((IntArray) -> Double)? = null // Parser formula taking byte array A, B, C...
)

data class StreamSample(
    val value: Double,
    val timestamp: Long = System.currentTimeMillis()
)

enum class UnitSystem {
    SAE,    // Imperial: mph, °F, psi, miles, GPH
    METRIC  // ISO: km/h, °C, kPa, km, L/h
}

fun formatValueWithUnit(rawValue: Double, pid: ObdPidDefinition, unitSystem: UnitSystem): Pair<String, String> {
    return when (unitSystem) {
        UnitSystem.SAE -> {
            when (pid.unit) {
                "km/h" -> Pair(String.format(java.util.Locale.US, "%.0f", rawValue * 0.621371), "mph")
                "°C" -> Pair(String.format(java.util.Locale.US, "%.0f", (rawValue * 1.8) + 32), "°F")
                "kPa" -> Pair(String.format(java.util.Locale.US, "%.1f", rawValue * 0.145038), "psi")
                "bar" -> Pair(String.format(java.util.Locale.US, "%.1f", rawValue * 14.5038), "psi")
                "Pa" -> Pair(String.format(java.util.Locale.US, "%.2f", rawValue / 6894.76), "psi")
                "km" -> Pair(String.format(java.util.Locale.US, "%.0f", rawValue * 0.621371), "miles")
                "L/h" -> Pair(String.format(java.util.Locale.US, "%.1f", rawValue * 0.264172), "GPH")
                "g/s" -> Pair(String.format(java.util.Locale.US, "%.1f", rawValue), "g/s")
                "RPM" -> Pair(String.format(java.util.Locale.US, "%.0f", rawValue), "RPM")
                "%" -> Pair(String.format(java.util.Locale.US, "%.1f", rawValue), "%")
                "A/F" -> Pair(String.format(java.util.Locale.US, "%.1f", rawValue), "A/F")
                "V" -> Pair(String.format(java.util.Locale.US, "%.2f", rawValue), "V")
                "ratio", "Ratio" -> Pair(String.format(java.util.Locale.US, "%.3f", rawValue), "ratio")
                "Nm" -> Pair(String.format(java.util.Locale.US, "%.0f", rawValue * 0.737562), "lb-ft")
                "mm" -> Pair(String.format(java.util.Locale.US, "%.2f", rawValue * 0.0393701), "in")
                "Gear" -> Pair(String.format(java.util.Locale.US, "%.0f", rawValue), "Gear")
                "counts" -> Pair(String.format(java.util.Locale.US, "%.0f", rawValue), "counts")
                "ppm" -> Pair(String.format(java.util.Locale.US, "%.0f", rawValue), "ppm")
                "mg/stroke" -> Pair(String.format(java.util.Locale.US, "%.1f", rawValue), "mg/stroke")
                else -> Pair(String.format(java.util.Locale.US, "%.1f", rawValue), pid.unit)
            }
        }
        UnitSystem.METRIC -> {
            when (pid.unit) {
                "km/h" -> Pair(String.format(java.util.Locale.US, "%.0f", rawValue), "km/h")
                "°C" -> Pair(String.format(java.util.Locale.US, "%.0f", rawValue), "°C")
                "kPa" -> Pair(String.format(java.util.Locale.US, "%.1f", rawValue), "kPa")
                "bar" -> Pair(String.format(java.util.Locale.US, "%.2f", rawValue), "bar")
                "Pa" -> Pair(String.format(java.util.Locale.US, "%.0f", rawValue), "Pa")
                "km" -> Pair(String.format(java.util.Locale.US, "%.0f", rawValue), "km")
                "L/h" -> Pair(String.format(java.util.Locale.US, "%.1f", rawValue), "L/h")
                "RPM" -> Pair(String.format(java.util.Locale.US, "%.0f", rawValue), "RPM")
                "%" -> Pair(String.format(java.util.Locale.US, "%.1f", rawValue), "%")
                "A/F" -> Pair(String.format(java.util.Locale.US, "%.1f", rawValue), "A/F")
                "V" -> Pair(String.format(java.util.Locale.US, "%.2f", rawValue), "V")
                "Nm" -> Pair(String.format(java.util.Locale.US, "%.0f", rawValue), "Nm")
                "mm" -> Pair(String.format(java.util.Locale.US, "%.1f", rawValue), "mm")
                "Gear" -> Pair(String.format(java.util.Locale.US, "%.0f", rawValue), "Gear")
                "counts" -> Pair(String.format(java.util.Locale.US, "%.0f", rawValue), "counts")
                "ppm" -> Pair(String.format(java.util.Locale.US, "%.0f", rawValue), "ppm")
                "mg/stroke" -> Pair(String.format(java.util.Locale.US, "%.1f", rawValue), "mg/stroke")
                else -> Pair(String.format(java.util.Locale.US, "%.1f", rawValue), pid.unit)
            }
        }
    }
}

// Dynamic Live Stream State Data Class
data class LiveStreamData(
    val pid: ObdPidDefinition,
    val formattedValue: String = "--",
    val rawValue: Double = 0.0,
    val history: List<StreamSample> = emptyList(),
    val lastUpdated: Long = System.currentTimeMillis()
)

// Master Registry of Known OBD-II Mode 01 PIDs (SAE J1979 Comprehensive)
object ObdPidRegistry {
    val KNOWN_PIDS = mapOf(
        "01" to ObdPidDefinition("01", "0101", "DTC Status", "codes", "Monitors & DTC Count", 4) { a -> (a[0] and 0x7F).toDouble() },
        "03" to ObdPidDefinition("03", "0103", "Fuel System Status", "", "Fuel System Loop Status", 2) { a -> a[0].toDouble() },
        "04" to ObdPidDefinition("04", "0104", "Engine Load", "%", "Calculated Engine Load", 1) { a -> (a[0] * 100) / 255.0 },
        "05" to ObdPidDefinition("05", "0105", "Coolant Temp", "°C", "Engine Coolant Temperature", 1) { a -> (a[0] - 40).toDouble() },
        "06" to ObdPidDefinition("06", "0106", "Short Term Fuel Trim B1", "%", "Short Term Fuel Trim Bank 1", 1) { a -> ((a[0] - 128) * 100) / 128.0 },
        "07" to ObdPidDefinition("07", "0107", "Long Term Fuel Trim B1", "%", "Long Term Fuel Trim Bank 1", 1) { a -> ((a[0] - 128) * 100) / 128.0 },
        "08" to ObdPidDefinition("08", "0108", "Short Term Fuel Trim B2", "%", "Short Term Fuel Trim Bank 2", 1) { a -> ((a[0] - 128) * 100) / 128.0 },
        "09" to ObdPidDefinition("09", "0109", "Long Term Fuel Trim B2", "%", "Long Term Fuel Trim Bank 2", 1) { a -> ((a[0] - 128) * 100) / 128.0 },
        "0A" to ObdPidDefinition("0A", "010A", "Fuel Pressure", "kPa", "Fuel Pressure Gauge", 1) { a -> (a[0] * 3).toDouble() },
        "0B" to ObdPidDefinition("0B", "010B", "Intake MAP", "kPa", "Intake Manifold Absolute Pressure", 1) { a -> a[0].toDouble() },
        "0C" to ObdPidDefinition("0C", "010C", "Engine RPM", "RPM", "Engine Revolutions Per Minute", 2) { a -> ((a[0] * 256) + a[1]) / 4.0 },
        "0D" to ObdPidDefinition("0D", "010D", "Vehicle Speed", "km/h", "Vehicle Speed", 1) { a -> a[0].toDouble() },
        "0E" to ObdPidDefinition("0E", "010E", "Timing Advance", "°", "Ignition Timing Advance", 1) { a -> (a[0] - 128) / 2.0 },
        "0F" to ObdPidDefinition("0F", "010F", "Intake Air Temp", "°C", "Intake Air Temperature", 1) { a -> (a[0] - 40).toDouble() },
        "10" to ObdPidDefinition("10", "0110", "MAF Air Flow", "g/s", "Mass Air Flow Rate", 2) { a -> ((a[0] * 256) + a[1]) / 100.0 },
        "11" to ObdPidDefinition("11", "0111", "Throttle Position", "%", "Absolute Throttle Position", 1) { a -> (a[0] * 100) / 255.0 },
        "13" to ObdPidDefinition("13", "0113", "O2 Sensors Present", "", "Oxygen Sensors Present Banks 1 & 2", 1) { a -> a[0].toDouble() },
        "14" to ObdPidDefinition("14", "0114", "O2 Sensor 1 B1", "V", "Oxygen Sensor 1 Bank 1 Voltage", 2) { a -> a[0] / 200.0 },
        "15" to ObdPidDefinition("15", "0115", "O2 Sensor 2 B1", "V", "Oxygen Sensor 2 Bank 1 Voltage", 2) { a -> a[0] / 200.0 },
        "16" to ObdPidDefinition("16", "0116", "O2 Sensor 3 B1", "V", "Oxygen Sensor 3 Bank 1 Voltage", 2) { a -> a[0] / 200.0 },
        "17" to ObdPidDefinition("17", "0117", "O2 Sensor 4 B1", "V", "Oxygen Sensor 4 Bank 1 Voltage", 2) { a -> a[0] / 200.0 },
        "18" to ObdPidDefinition("18", "0118", "O2 Sensor 1 B2", "V", "Oxygen Sensor 1 Bank 2 Voltage", 2) { a -> a[0] / 200.0 },
        "19" to ObdPidDefinition("19", "0119", "O2 Sensor 2 B2", "V", "Oxygen Sensor 2 Bank 2 Voltage", 2) { a -> a[0] / 200.0 },
        "1C" to ObdPidDefinition("1C", "011C", "OBD Standard", "", "OBD Standards Vehicle Conforms To", 1) { a -> a[0].toDouble() },
        "1F" to ObdPidDefinition("1F", "011F", "Engine Run Time", "sec", "Run Time Since Engine Start", 2) { a -> ((a[0] * 256) + a[1]).toDouble() },
        "21" to ObdPidDefinition("21", "0121", "Distance MIL On", "km", "Distance Traveled with MIL On", 2) { a -> ((a[0] * 256) + a[1]).toDouble() },
        "22" to ObdPidDefinition("22", "0122", "Fuel Rail MAP", "kPa", "Fuel Rail Pressure Relative to Manifold", 2) { a -> ((a[0] * 256) + a[1]) * 0.079 },
        "23" to ObdPidDefinition("23", "0123", "Fuel Rail Pressure", "kPa", "Fuel Rail Direct Injection Pressure", 2) { a -> ((a[0] * 256) + a[1]) * 10.0 },
        "24" to ObdPidDefinition("24", "0124", "O2 Sensor 1 Air-Fuel", "A/F", "O2 Sensor 1 Equivalence Ratio", 4) { a -> (((a[0] * 256) + a[1]) / 32768.0) * 14.7 },
        "25" to ObdPidDefinition("25", "0125", "O2 Sensor 2 Air-Fuel", "A/F", "O2 Sensor 2 Equivalence Ratio", 4) { a -> (((a[0] * 256) + a[1]) / 32768.0) * 14.7 },
        "2C" to ObdPidDefinition("2C", "012C", "Commanded EGR", "%", "Commanded EGR Purge", 1) { a -> (a[0] * 100) / 255.0 },
        "2D" to ObdPidDefinition("2D", "012D", "EGR Error", "%", "EGR Position Error", 1) { a -> ((a[0] - 128) * 100) / 128.0 },
        "2E" to ObdPidDefinition("2E", "012E", "Evap Purge", "%", "Commanded Evaporative Purge", 1) { a -> (a[0] * 100) / 255.0 },
        "2F" to ObdPidDefinition("2F", "012F", "Fuel Tank Level", "%", "Fuel Tank Level Input", 1) { a -> (a[0] * 100) / 255.0 },
        "30" to ObdPidDefinition("30", "0130", "Warm-ups Since Cleared", "count", "Warm-up Cycles Since DTCs Cleared", 1) { a -> a[0].toDouble() },
        "31" to ObdPidDefinition("31", "0131", "Distance Since Cleared", "km", "Distance Traveled Since DTCs Cleared", 2) { a -> ((a[0] * 256) + a[1]).toDouble() },
        "32" to ObdPidDefinition("32", "0132", "Evap Vapor Pressure", "Pa", "Evap System Vapor Pressure", 2) { a -> (((a[0] shl 8) or a[1]).toShort().toInt()) / 4.0 },
        "33" to ObdPidDefinition("33", "0133", "Barometric Pressure", "kPa", "Absolute Barometric Pressure", 1) { a -> a[0].toDouble() },
        "34" to ObdPidDefinition("34", "0134", "O2 Equivalence B1", "A/F", "O2 Sensor 1 Equivalence Ratio", 4) { a -> (((a[0] * 256) + a[1]) / 32768.0) * 14.7 },
        "3C" to ObdPidDefinition("3C", "013C", "Catalyst Temp B1S1", "°C", "Catalyst Temperature Bank 1 Sensor 1", 2) { a -> (((a[0] * 256) + a[1]) / 10.0) - 40.0 },
        "3D" to ObdPidDefinition("3D", "013D", "Catalyst Temp B2S1", "°C", "Catalyst Temperature Bank 2 Sensor 1", 2) { a -> (((a[0] * 256) + a[1]) / 10.0) - 40.0 },
        "3E" to ObdPidDefinition("3E", "013E", "Catalyst Temp B1S2", "°C", "Catalyst Temperature Bank 1 Sensor 2", 2) { a -> (((a[0] * 256) + a[1]) / 10.0) - 40.0 },
        "3F" to ObdPidDefinition("3F", "013F", "Catalyst Temp B2S2", "°C", "Catalyst Temperature Bank 2 Sensor 2", 2) { a -> (((a[0] * 256) + a[1]) / 10.0) - 40.0 },
        "41" to ObdPidDefinition("41", "0141", "Drive Cycle Status", "", "Monitor Status This Drive Cycle", 4) { a -> a[0].toDouble() },
        "42" to ObdPidDefinition("42", "0142", "Control Module Voltage", "V", "Control Module Power Supply Voltage", 2) { a -> ((a[0] * 256) + a[1]) / 1000.0 },
        "43" to ObdPidDefinition("43", "0143", "Absolute Engine Load", "%", "Absolute Load Value", 2) { a -> (((a[0] * 256) + a[1]) * 100) / 255.0 },
        "44" to ObdPidDefinition("44", "0144", "Target Air-Fuel Ratio", "A/F", "Commanded Fuel Air Equivalence Ratio", 2) { a -> (((a[0] * 256) + a[1]) / 32768.0) * 14.7 },
        "45" to ObdPidDefinition("45", "0145", "Relative Throttle Pos", "%", "Relative Throttle Position", 1) { a -> (a[0] * 100) / 255.0 },
        "46" to ObdPidDefinition("46", "0146", "Ambient Air Temp", "°C", "Ambient Air Temperature", 1) { a -> (a[0] - 40).toDouble() },
        "47" to ObdPidDefinition("47", "0147", "Absolute Throttle Pos B", "%", "Absolute Throttle Position B", 1) { a -> (a[0] * 100) / 255.0 },
        "48" to ObdPidDefinition("48", "0148", "Absolute Throttle Pos C", "%", "Absolute Throttle Position C", 1) { a -> (a[0] * 100) / 255.0 },
        "49" to ObdPidDefinition("49", "0149", "Accelerator Pedal Pos D", "%", "Accelerator Pedal Position D", 1) { a -> (a[0] * 100) / 255.0 },
        "4A" to ObdPidDefinition("4A", "014A", "Accelerator Pedal Pos E", "%", "Accelerator Pedal Position E", 1) { a -> (a[0] * 100) / 255.0 },
        "4B" to ObdPidDefinition("4B", "014B", "Accelerator Pedal Pos F", "%", "Accelerator Pedal Position F", 1) { a -> (a[0] * 100) / 255.0 },
        "4C" to ObdPidDefinition("4C", "014C", "Commanded Throttle Actuator", "%", "Commanded Throttle Actuator Position", 1) { a -> (a[0] * 100) / 255.0 },
        "4D" to ObdPidDefinition("4D", "014D", "Time Run MIL On", "min", "Engine Run Time with MIL On", 2) { a -> ((a[0] * 256) + a[1]).toDouble() },
        "4E" to ObdPidDefinition("4E", "014E", "Time Since Cleared", "min", "Engine Run Time Since DTCs Cleared", 2) { a -> ((a[0] * 256) + a[1]).toDouble() },
        "51" to ObdPidDefinition("51", "0151", "Fuel Type", "", "Fuel Type Vehicle Runs On", 1) { a -> a[0].toDouble() },
        "52" to ObdPidDefinition("52", "0152", "Ethanol Fuel Level", "%", "Ethanol Fuel Percentage", 1) { a -> (a[0] * 100) / 255.0 },
        "53" to ObdPidDefinition("53", "0153", "Abs EVAP Vapor Pressure", "kPa", "Absolute EVAP System Vapor Pressure", 2) { a -> ((a[0] * 256) + a[1]) / 200.0 },
        "54" to ObdPidDefinition("54", "0154", "EVAP Vapor Pressure (Alt)", "Pa", "Evap System Vapor Pressure (Alt)", 2) { a -> (((a[0] shl 8) or a[1]).toShort().toDouble()) - 32767.0 },
        "59" to ObdPidDefinition("59", "0159", "Absolute Fuel Rail Pressure", "kPa", "Absolute Fuel Rail Pressure", 2) { a -> ((a[0] * 256) + a[1]) * 10.0 },
        "5A" to ObdPidDefinition("5A", "015A", "Relative Pedal Position", "%", "Relative Accelerator Pedal Position", 1) { a -> (a[0] * 100) / 255.0 },
        "5B" to ObdPidDefinition("5B", "015B", "Hybrid Battery Remaining", "%", "Hybrid Battery Pack Remaining Life", 1) { a -> (a[0] * 100) / 255.0 },
        "5C" to ObdPidDefinition("5C", "015C", "Engine Oil Temp", "°C", "Engine Oil Temperature", 1) { a -> (a[0] - 40).toDouble() },
        "5D" to ObdPidDefinition("5D", "015D", "Fuel Injection Timing", "°", "Fuel Injection Timing", 2) { a -> (((a[0] * 256) + a[1]) - 26880) / 128.0 },
        "5E" to ObdPidDefinition("5E", "015E", "Engine Fuel Rate", "L/h", "Engine Fuel Rate", 2) { a -> ((a[0] * 256) + a[1]) / 20.0 },
        "61" to ObdPidDefinition("61", "0161", "Driver Demand Torque", "%", "Driver's Demand Engine Percent Torque", 1) { a -> (a[0] - 125).toDouble() },
        "62" to ObdPidDefinition("62", "0162", "Actual Engine Torque", "%", "Actual Engine Percent Torque", 1) { a -> (a[0] - 125).toDouble() },
        "63" to ObdPidDefinition("63", "0163", "Reference Torque", "Nm", "Engine Reference Torque", 2) { a -> ((a[0] * 256) + a[1]).toDouble() },
        "67" to ObdPidDefinition("67", "0167", "Coolant Temp Sensor 1", "°C", "Engine Coolant Temperature Sensor 1", 1) { a -> (a[0] - 40).toDouble() },
        "68" to ObdPidDefinition("68", "0168", "Intake Air Temp B1S1", "°C", "Intake Air Temperature Bank 1 Sensor 1", 1) { a -> (a[0] - 40).toDouble() },
        "6F" to ObdPidDefinition("6F", "016F", "Turbo Inlet Pressure", "kPa", "Turbocharger Compressor Inlet Pressure", 1) { a -> a[0].toDouble() },
        "70" to ObdPidDefinition("70", "0170", "Boost Pressure Control", "kPa", "Boost Pressure Control Target", 2) { a -> ((a[0] * 256) + a[1]) / 32.0 },
        "71" to ObdPidDefinition("71", "0171", "VGT Turbo Control", "%", "Variable Geometry Turbocharger (VGT) Control", 1) { a -> (a[0] * 100) / 255.0 },
        "72" to ObdPidDefinition("72", "0172", "Wastegate Control", "%", "Wastegate Position Control", 1) { a -> (a[0] * 100) / 255.0 },
        "73" to ObdPidDefinition("73", "0173", "Exhaust Pressure", "kPa", "Exhaust Pressure Gauge", 2) { a -> ((a[0] * 256) + a[1]) / 10.0 },
        "74" to ObdPidDefinition("74", "0174", "Turbocharger RPM", "RPM", "Turbocharger Shaft Speed", 2) { a -> ((a[0] * 256) + a[1]).toDouble() },
        "75" to ObdPidDefinition("75", "0175", "Turbo Temp 1", "°C", "Turbocharger Compressor Temperature", 1) { a -> (a[0] - 40).toDouble() },
        "77" to ObdPidDefinition("77", "0177", "Charge Air Cooler Temp", "°C", "Charge Air Cooler Temperature (CACT)", 1) { a -> (a[0] - 40).toDouble() },
        "78" to ObdPidDefinition("78", "0178", "Exhaust Gas Temp B1S1", "°C", "Exhaust Gas Temperature (EGT) Bank 1 Sensor 1", 2) { a -> (((a[0] * 256) + a[1]) / 10.0) - 40.0 },
        "79" to ObdPidDefinition("79", "0179", "Exhaust Gas Temp B2S1", "°C", "Exhaust Gas Temperature (EGT) Bank 2 Sensor 1", 2) { a -> (((a[0] * 256) + a[1]) / 10.0) - 40.0 },
        "7A" to ObdPidDefinition("7A", "017A", "DPF Differential Press", "kPa", "Diesel Particulate Filter Differential Pressure", 2) { a -> ((a[0] * 256) + a[1]) / 100.0 },
        "7C" to ObdPidDefinition("7C", "017C", "DPF Temperature", "°C", "Diesel Particulate Filter Temperature", 2) { a -> (((a[0] * 256) + a[1]) / 10.0) - 40.0 },
        "85" to ObdPidDefinition("85", "0185", "Fuel Rail Pressure Direct", "kPa", "High Pressure Common Rail Fuel Direct Injection", 2) { a -> ((a[0] * 256) + a[1]) * 10.0 },
        "86" to ObdPidDefinition("86", "0186", "NOx Concentration", "ppm", "NOx Sensor Concentration", 2) { a -> ((a[0] * 256) + a[1]).toDouble() },
        "87" to ObdPidDefinition("87", "0187", "DEF Tank Temp", "°C", "SCR Diesel Exhaust Fluid (DEF) Tank Temperature", 1) { a -> (a[0] - 40).toDouble() },
        "A1" to ObdPidDefinition("A1", "01A1", "DEF Tank Level", "%", "Diesel Exhaust Fluid (DEF) / AdBlue Tank Level", 1) { a -> (a[0] * 100) / 255.0 },
        "A2" to ObdPidDefinition("A2", "01A2", "Cylinder Fuel Rate", "mg/stroke", "Cylinder Fuel Rate", 2) { a -> ((a[0] * 256) + a[1]) / 32.0 },
        "A4" to ObdPidDefinition("A4", "01A4", "Transmission Actual Gear", "Gear", "Transmission Actual Commanded Gear", 1) { a -> (a[0] and 0x0F).toDouble() },
        "A6" to ObdPidDefinition("A6", "01A6", "Universal Odometer", "km", "Vehicle Total Odometer (Universal 2019+)", 4) { a -> if (a.size >= 4) ((a[0].toLong() shl 24) or (a[1].toLong() shl 16) or (a[2].toLong() shl 8) or a[3].toLong()).toDouble() / 10.0 else 0.0 }
    )

    val DEFAULT_PIDS = listOf(
        KNOWN_PIDS["04"]!!, KNOWN_PIDS["05"]!!, KNOWN_PIDS["06"]!!, KNOWN_PIDS["0C"]!!,
        KNOWN_PIDS["0D"]!!, KNOWN_PIDS["0F"]!!, KNOWN_PIDS["10"]!!, KNOWN_PIDS["11"]!!
    )
}

// OBD-II Diagnostic Trouble Code Details Data Class
data class DtcDetails(
    val code: String,          // e.g. "P0420"
    val category: String,      // "Stored / Active", "Pending", or "Permanent / Historic"
    val description: String    // "Catalyst System Efficiency Below Threshold (Bank 1)"
)

// Master Registry of Common OBD-II DTC Descriptions
object ObdDtcRegistry {
    val KNOWN_DESCRIPTIONS = mapOf(
        "P0100" to "Mass or Volume Air Flow Circuit Malfunction",
        "P0101" to "Mass Air Flow (MAF) Circuit Range/Performance Problem",
        "P0102" to "Mass Air Flow (MAF) Circuit Low Input",
        "P0103" to "Mass Air Flow (MAF) Circuit High Input",
        "P0106" to "Manifold Absolute Pressure (MAP) Circuit Range/Performance Problem",
        "P0113" to "Intake Air Temperature (IAT) Sensor 1 Circuit High Input",
        "P0128" to "Coolant Temperature Below Thermostat Regulating Temperature",
        "P0171" to "System Too Lean (Bank 1)",
        "P0172" to "System Too Rich (Bank 1)",
        "P0174" to "System Too Lean (Bank 2)",
        "P0175" to "System Too Rich (Bank 2)",
        "P0300" to "Random or Multiple Cylinder Misfire Detected",
        "P0301" to "Cylinder 1 Misfire Detected",
        "P0302" to "Cylinder 2 Misfire Detected",
        "P0303" to "Cylinder 3 Misfire Detected",
        "P0304" to "Cylinder 4 Misfire Detected",
        "P0305" to "Cylinder 5 Misfire Detected",
        "P0306" to "Cylinder 6 Misfire Detected",
        "P0335" to "Crankshaft Position Sensor A Circuit Malfunction",
        "P0401" to "Exhaust Gas Recirculation (EGR) Flow Insufficient Detected",
        "P0420" to "Catalyst System Efficiency Below Threshold (Bank 1)",
        "P0430" to "Catalyst System Efficiency Below Threshold (Bank 2)",
        "P0440" to "Evaporative Emission (EVAP) Control System Malfunction",
        "P0441" to "EVAP Control System Incorrect Purge Flow",
        "P0442" to "EVAP Control System Leak Detected (Small Leak)",
        "P0455" to "EVAP Control System Leak Detected (Gross Leak)",
        "P0500" to "Vehicle Speed Sensor Malfunction",
        "C1501" to "Tire Pressure Sensor 1 (FL) Internal / Pressure Low",
        "C1502" to "Tire Pressure Sensor 2 (FR) Internal / Pressure Low",
        "C1503" to "Tire Pressure Sensor 3 (RR) Internal / Pressure Low",
        "C1504" to "Tire Pressure Sensor 4 (RL) Internal / Pressure Low",
        "C151D" to "Tire Pressure Sensor Location / Threshold Exceeded"
    )

    fun getDescription(code: String): String {
        return KNOWN_DESCRIPTIONS[code] ?: when {
            code.startsWith("P04") -> "Emissions / EVAP / Catalyst System Fault"
            code.startsWith("P03") -> "Ignition System or Engine Misfire Fault"
            code.startsWith("P01") -> "Fuel or Air Metering System Fault"
            code.startsWith("P02") -> "Fuel Injector Circuit Fault"
            code.startsWith("P05") -> "Idle Speed or Speed Sensor Fault"
            code.startsWith("P07") -> "Transmission Control System Fault"
            code.startsWith("C15") || code.contains("150") || code.contains("151") -> "Tire Pressure Monitoring System (TPMS) Warning"
            code.startsWith("C") -> "Chassis / ABS / Brake System Fault"
            code.startsWith("B") -> "Body Control Module Fault"
            code.startsWith("U") -> "CAN Bus / Network Communication Fault"
            else -> "Powertrain System Fault Code"
        }
    }
}

// Mode 06 On-Board Diagnostic Component Test Result Data Class
enum class Mode06Status {
    PASS,
    WARNING,
    FAIL,
    UNKNOWN
}

enum class DemoVehiclePreset {
    CLEAN_ECU,
    DIRTY_ECU
}

data class Mode06TestResult(
    val midHex: String,           // e.g. "21", "A2"
    val category: String = "Misfire", // "Misfire", "Catalyst", "O2 Sensor", "EVAP System", "EGR / VVT", "Secondary Air"
    val name: String,             // e.g. "Cylinder 1 Misfires"
    val testValue: Double,        // e.g. 14.0
    val minLimit: Double? = null,
    val maxLimit: Double? = null,
    val unit: String = "misfires",
    val status: Mode06Status = Mode06Status.PASS,
    val description: String = ""
)

// Mode 22 Proprietary / Enhanced Parameter PID Data Class
data class Mode22PidDefinition(
    val pidHex: String,           // e.g. "1E01", "11B6", "0B"
    val name: String,             // e.g. "Transmission Temp"
    val category: String,         // e.g. "Transmission", "Engine Oil", "Battery", "Emissions"
    val unit: String,             // e.g. "°C", "psi", "%", "kPa"
    val description: String,       // e.g. "Automatic Transmission Fluid Temp"
    val manufacturer: String,     // e.g. "Ford", "GM", "Toyota", "Honda", "Chrysler", "Nissan", "Volkswagen", "Subaru", "Universal"
    val servicePrefix: String = "22", // Default "22", or "21" for Chrysler Mode 21
    @Transient val parseValue: ((IntArray) -> Double)? = null
)

/**
 * Verification ladder for proprietary Mode 21/22 and OBDb community PIDs.
 * Prevents bogus or mismatched DIDs (e.g. Chrysler 240°F transmission temp) from triggering
 * false alarms or reaching the LLM as verified facts.
 */
enum class PidVerificationStatus(val label: String, val isTrusted: Boolean) {
    VERIFIED("Verified", true),           // Confirmed OEM match or verified thermodynamic cross-check
    CORROBORATED("Corroborated", true),   // Multi-frame consistent and within expected physics bounds
    CANDIDATE("Unverified", false),       // Unconfirmed candidate DID; requires caution / (?) badge in UI
    IMPLAUSIBLE("Implausible", false)     // Physically or thermodynamically impossible; rejected
}

data class Mode22StreamData(
    val pid: Mode22PidDefinition,
    val formattedValue: String = "--",
    val rawValue: Double = 0.0,
    val lastUpdated: Long = System.currentTimeMillis(),
    val verificationStatus: PidVerificationStatus = PidVerificationStatus.VERIFIED
)

/**
 * Cross-checks an enhanced PID against physical laws and thermodynamic reference sensors.
 * Prevents mismatched calibrations (such as engine throttle parameters falsely interpreted as 240°F ATF)
 * from triggering alarms or being narrated by Vera.
 */
fun verifyMode22Physics(
    stream: Mode22StreamData,
    coolantTempC: Double?,
    ambientTempC: Double?,
    rpm: Int = 0
): PidVerificationStatus {
    val name = stream.pid.name.lowercase()
    val unit = stream.pid.unit
    val raw = stream.rawValue

    // 1. Transmission Temperature: Physical laws & thermodynamic cross-checks
    if (name.contains("transmission") && (name.contains("temp") || unit == "°C" || unit == "°F")) {
        val tempC = if (unit == "°C") raw else (raw - 32.0) / 1.8
        // Hard physical sensor bounds: ATF is never below -40°C or above 180°C (356°F)
        if (tempC < -40.0 || tempC > 180.0) return PidVerificationStatus.IMPLAUSIBLE

        // Thermodynamic cross-check against Engine Coolant
        if (coolantTempC != null && coolantTempC > -35.0) {
            // Cold engine start: If engine coolant is cold (< 50°C / 122°F), transmission fluid cannot physically exceed coolant + 35°C or be > 75°C (167°F)
            // (Catches the Chrysler 240°F bug where raw engine data was falsely parsed as ATF!)
            if (coolantTempC < 50.0 && tempC > 75.0) {
                return PidVerificationStatus.IMPLAUSIBLE
            }
            // Hot engine: If engine coolant is > 75°C, cold-soaked ATF (< -5°C) is physically impossible
            if (coolantTempC >= 75.0 && tempC < -5.0) {
                return PidVerificationStatus.IMPLAUSIBLE
            }
            if (Math.abs(tempC - coolantTempC) < 45.0) {
                return PidVerificationStatus.VERIFIED
            }
            return PidVerificationStatus.CORROBORATED
        }

        // Cross-check against Ambient / Intake Temp if coolant is not yet available
        if (ambientTempC != null && ambientTempC > -35.0) {
            if (ambientTempC < 15.0 && tempC > 90.0) {
                return PidVerificationStatus.IMPLAUSIBLE
            }
        }
        return PidVerificationStatus.CANDIDATE
    }

    // 2. Engine Oil Temperature: Tracks coolant closely
    if (name.contains("oil temp") && (unit == "°C" || unit == "°F")) {
        val tempC = if (unit == "°C") raw else (raw - 32.0) / 1.8
        if (tempC < -40.0 || tempC > 180.0) return PidVerificationStatus.IMPLAUSIBLE
        if (coolantTempC != null && coolantTempC > -35.0) {
            if (coolantTempC < 50.0 && tempC > 80.0) return PidVerificationStatus.IMPLAUSIBLE
            if (Math.abs(tempC - coolantTempC) < 40.0) return PidVerificationStatus.VERIFIED
            return PidVerificationStatus.CORROBORATED
        }
        return PidVerificationStatus.CANDIDATE
    }

    // 3. Engine Oil Pressure: 0 psi when engine is stopped; 10-100 psi running
    if (name.contains("oil pressure")) {
        val psi = if (unit == "kPa") raw * 0.145038 else raw
        if (psi < 0.0 || psi > 160.0) return PidVerificationStatus.IMPLAUSIBLE
        if (rpm < 100 && psi > 20.0) return PidVerificationStatus.IMPLAUSIBLE
        if (rpm > 500 && psi in 10.0..100.0) return PidVerificationStatus.VERIFIED
        return PidVerificationStatus.CORROBORATED
    }

    // 4. 12V Battery / Alternator Voltage: 9-16V
    if (name.contains("battery") && name.contains("volt")) {
        if (raw < 6.0 || raw > 18.0) return PidVerificationStatus.IMPLAUSIBLE
        if (raw in 11.5..15.5) return PidVerificationStatus.VERIFIED
        return PidVerificationStatus.CORROBORATED
    }

    // 5. Tire Pressures: 15-60 psi typical
    if (name.contains("tire") || name.contains("pressure")) {
        val psi = if (unit == "bar") raw * 14.5038 else if (unit == "kPa") raw * 0.145038 else raw
        if (psi < 0.0 || psi > 120.0) return PidVerificationStatus.IMPLAUSIBLE
        if (psi in 20.0..55.0) return PidVerificationStatus.VERIFIED
        if (psi == 0.0) return PidVerificationStatus.CANDIDATE // Wheel sensor sleeping while parked
        return PidVerificationStatus.CORROBORATED
    }

    return PidVerificationStatus.VERIFIED
}



object Mode22Registry {
    val KNOWN_PIDS = listOf(
        // Ford / Lincoln / Mercury (Verified Mode 22 PIDs; full model/year coverage provided via OBDb)
        Mode22PidDefinition("1E1C", "Transmission Temp", "Transmission", "°C", "Ford Transmission Fluid Temp (5R55/6R80/4R75)", "Ford") { a -> if (a.size >= 2) ((a[0] * 256) + a[1]) / 16.0 - 40.0 else (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 },
        Mode22PidDefinition("1674", "Cylinder Head Temp", "Engine", "°C", "Ford Cylinder Head Temperature (CHT)", "Ford") { a -> if (a.size >= 2) ((a[0] * 256) + a[1]) / 64.0 else (a.getOrNull(0)?.toDouble() ?: 0.0) },
        Mode22PidDefinition("1310", "Engine Oil Temp", "Engine Oil", "°C", "Ford Engine Oil Temperature", "Ford") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 },
        Mode22PidDefinition("162B", "Torque Converter Slip", "Transmission", "RPM", "Ford Torque Converter Lockup Slip Speed", "Ford") { a -> if (a.size >= 2) ((a[0] * 256) + a[1]).toShort() / 4.0 else 0.0 },
        Mode22PidDefinition("162C", "Transmission Gear Ratio", "Transmission", "Ratio", "Ford Transmission Gear Ratio", "Ford") { a -> if (a.size >= 2) ((a[0] * 256) + a[1]) / 1000.0 else 0.0 },
        Mode22PidDefinition("11B3", "Transmission Commanded Gear", "Transmission", "Gear", "Ford Transmission Commanded Gear", "Ford") { a -> a.getOrNull(0)?.toDouble() ?: 0.0 },
        Mode22PidDefinition("054B", "Engine Oil Life", "Maintenance", "%", "Ford Engine Oil Life Remaining Index", "Ford") { a -> ((a.getOrNull(0) ?: 0) * 100) / 255.0 },
        Mode22PidDefinition("404C", "Cluster Odometer", "Maintenance", "km", "Ford Instrument Cluster Total Odometer (2004-2008 F-150 / HEC)", "Ford") { a -> if (a.size >= 4) ((a[0].toLong() shl 24) or (a[1].toLong() shl 16) or (a[2].toLong() shl 8) or a[3].toLong()).toDouble() * 0.1 else if (a.size >= 3) ((a[0].toLong() shl 16) or (a[1].toLong() shl 8) or a[2].toLong()).toDouble() * 0.1 else 0.0 },
        Mode22PidDefinition("DD01", "Vehicle Odometer", "Maintenance", "km", "Ford Total Vehicle Odometer (IPC / BCM)", "Ford") { a -> if (a.size >= 4) ((a[0].toLong() shl 24) or (a[1].toLong() shl 16) or (a[2].toLong() shl 8) or a[3].toLong()).toDouble() / 10.0 else if (a.size >= 3) ((a[0].toLong() shl 16) or (a[1].toLong() shl 8) or a[2].toLong()).toDouble() else 0.0 },
        Mode22PidDefinition("042F", "Battery State of Charge", "Electrical", "%", "Ford 12V Battery State of Charge (BMS)", "Ford") { a -> ((a.getOrNull(0) ?: 0) * 100) / 255.0 },
        Mode22PidDefinition("042C", "Battery Voltage (BCM)", "Electrical", "V", "Ford Battery Terminal Voltage (BCM)", "Ford") { a -> if (a.size >= 2) ((a[0] * 256) + a[1]) / 100.0 else 0.0 },
        Mode22PidDefinition("2813", "Tire Pressure FL", "Chassis", "psi", "Ford Front Left Tire Pressure (TPMS)", "Ford") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) * 0.145038 },
        Mode22PidDefinition("2814", "Tire Pressure FR", "Chassis", "psi", "Ford Front Right Tire Pressure (TPMS)", "Ford") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) * 0.145038 },
        Mode22PidDefinition("2815", "Tire Pressure RR", "Chassis", "psi", "Ford Rear Right Tire Pressure (TPMS)", "Ford") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) * 0.145038 },
        Mode22PidDefinition("2816", "Tire Pressure RL", "Chassis", "psi", "Ford Rear Left Tire Pressure (TPMS)", "Ford") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) * 0.145038 },

        // GM / Chevrolet / GMC / Buick / Cadillac (Verified Mode 22 fallbacks)
        Mode22PidDefinition("1940", "Transmission Temp", "Transmission", "°C", "GM Transmission Fluid Temp (4L60E/4L80E)", "GM") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 },
        Mode22PidDefinition("1942", "Transmission Temp Alt", "Transmission", "°C", "GM Transmission Fluid Temp (6L80/8L90/10L90)", "GM") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 },
        Mode22PidDefinition("1154", "Engine Oil Temp", "Engine Oil", "°C", "GM Engine Oil Temperature", "GM") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 },
        Mode22PidDefinition("1150", "Engine Oil Pressure", "Engine Oil", "kPa", "GM Engine Oil Pressure", "GM") { a -> (a.getOrNull(0) ?: 0) * 4.0 },
        Mode22PidDefinition("115A", "Engine Oil Life", "Maintenance", "%", "GM Oil Life Remaining Percentage", "GM") { a -> ((a.getOrNull(0) ?: 0) * 100) / 255.0 },
        Mode22PidDefinition("115C", "Vehicle Odometer", "Maintenance", "km", "GM Total Distance Odometer", "GM") { a -> if (a.size >= 4) ((a[0].toLong() shl 24) or (a[1].toLong() shl 16) or (a[2].toLong() shl 8) or a[3].toLong()).toDouble() / 64.0 else if (a.size >= 3) ((a[0].toLong() shl 16) or (a[1].toLong() shl 8) or a[2].toLong()).toDouble() else 0.0 },
        Mode22PidDefinition("1951", "Tire Pressure FL", "Chassis", "psi", "GM Front Left Tire Pressure (TPMS)", "GM") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) * 0.3625 },
        Mode22PidDefinition("1952", "Tire Pressure FR", "Chassis", "psi", "GM Front Right Tire Pressure (TPMS)", "GM") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) * 0.3625 },
        Mode22PidDefinition("1953", "Tire Pressure RR", "Chassis", "psi", "GM Rear Right Tire Pressure (TPMS)", "GM") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) * 0.3625 },
        Mode22PidDefinition("1954", "Tire Pressure RL", "Chassis", "psi", "GM Rear Left Tire Pressure (TPMS)", "GM") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) * 0.3625 },

        // Toyota / Lexus / Scion (Verified Mode 21 1-byte PIDs; full hybrid/chassis set loaded via OBDb)
        Mode22PidDefinition("82", "Transmission Pan Temp", "Transmission", "°C", "Toyota ATF Pan Temperature (Mode 21 PID 82)", "Toyota", servicePrefix = "21") { a -> if (a.size >= 2) (((a[0] * 256) + a[1]) / 256.0) - 40.0 else -40.0 },
        Mode22PidDefinition("219C", "Tire Pressure FL", "Chassis", "psi", "Toyota Front Left Tire Pressure (TPMS)", "Toyota", servicePrefix = "21") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) * 0.145038 },
        Mode22PidDefinition("219D", "Tire Pressure FR", "Chassis", "psi", "Toyota Front Right Tire Pressure (TPMS)", "Toyota", servicePrefix = "21") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) * 0.145038 },
        Mode22PidDefinition("219E", "Tire Pressure RL", "Chassis", "psi", "Toyota Rear Left Tire Pressure (TPMS)", "Toyota", servicePrefix = "21") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) * 0.145038 },
        Mode22PidDefinition("219F", "Tire Pressure RR", "Chassis", "psi", "Toyota Rear Right Tire Pressure (TPMS)", "Toyota", servicePrefix = "21") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) * 0.145038 },

        // Chrysler / Dodge / Jeep / Ram (Town & Country, Grand Caravan, Ram 1500, Grand Cherokee, 3.6L Pentastar / Hemi / 62TE)
        // NOTE: On Chrysler, TCM is header 7E1. Service 21 PID 01 is genuine TCM transmission temp.
        // Mode 22 PID 0120 is an engine throttle parameter on PCM 7E0 that falsely registered as 240°F and has been removed.
        Mode22PidDefinition("01", "Transmission Fluid Temp", "Transmission", "°C", "Chrysler 62TE / NAG1 / 845RE Transmission Fluid Temp (TCM Mode 21 PID 01)", "Chrysler", servicePrefix = "21") { a -> if (a.size >= 2) (((a[0] * 256) + a[1]) / 64.0) - 40.0 else (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 },
        Mode22PidDefinition("1A87", "Transmission Temp (UDS)", "Transmission", "°C", "Chrysler UDS Transmission Fluid Temp (DID 1A87)", "Chrysler") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 },
        Mode22PidDefinition("2172", "Transmission Temp Alt", "Transmission", "°C", "Chrysler Transmission Fluid Temp (DID 2172)", "Chrysler") { a -> if (a.size >= 2) ((a[0] * 256) + a[1]) / 10.0 - 40.0 else (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 },
        Mode22PidDefinition("1940", "Transmission Temp Pan", "Transmission", "°C", "Chrysler Transmission Fluid Temp (DID 1940)", "Chrysler") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 },
        Mode22PidDefinition("0124", "Engine Oil Temp", "Engine Oil", "°C", "Chrysler 3.6L Pentastar / Hemi Engine Oil Temperature", "Chrysler") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 },
        Mode22PidDefinition("0125", "Engine Oil Pressure", "Engine Oil", "psi", "Chrysler 3.6L Pentastar Engine Oil Pressure", "Chrysler") { a -> if (a.size >= 2) ((a[0] * 256) + a[1]) / 10.0 else (a.getOrNull(0)?.toDouble() ?: 0.0) * 3.5 },
        Mode22PidDefinition("0126", "Ambient Air Temp", "Climate", "°C", "Chrysler Ambient Exterior Air Temperature", "Chrysler") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 },
        Mode22PidDefinition("01B4", "Vehicle Odometer", "Maintenance", "km", "Chrysler Total Vehicle Mileage", "Chrysler") { a -> if (a.size >= 4) ((a[0].toLong() shl 24) or (a[1].toLong() shl 16) or (a[2].toLong() shl 8) or a[3].toLong()).toDouble() / 10.0 else if (a.size >= 3) ((a[0].toLong() shl 16) or (a[1].toLong() shl 8) or a[2].toLong()).toDouble() else 0.0 },
        Mode22PidDefinition("1001", "Engine Oil Life", "Maintenance", "%", "Chrysler Engine Oil Life Remaining Index", "Chrysler") { a -> ((a.getOrNull(0) ?: 0) * 100) / 255.0 },
        Mode22PidDefinition("0108", "Fuel Tank Level", "Fuel", "%", "Chrysler Fuel Level Sensor", "Chrysler") { a -> ((a.getOrNull(0) ?: 0) * 100) / 255.0 },
        Mode22PidDefinition("0121", "Commanded Gear", "Transmission", "Gear", "Chrysler Commanded Transmission Gear", "Chrysler") { a -> a.getOrNull(0)?.toDouble() ?: 0.0 },
        Mode22PidDefinition("0122", "Torque Converter Slip", "Transmission", "RPM", "Chrysler Torque Converter Lockup Slip Speed", "Chrysler") { a -> if (a.size >= 2) ((a[0] * 256) + a[1]).toShort().toDouble() else 0.0 },
        Mode22PidDefinition("0204", "Battery Voltage (TIPM)", "Electrical", "V", "Chrysler TIPM / BCM Terminal Voltage", "Chrysler") { a -> if (a.size >= 2) ((a[0] * 256) + a[1]) / 100.0 else (a.getOrNull(0)?.toDouble() ?: 0.0) / 10.0 },
        Mode22PidDefinition("0205", "Alternator Target Voltage", "Electrical", "V", "Chrysler Alternator Target Charging Voltage", "Chrysler") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) / 10.0 },
        Mode22PidDefinition("0207", "Alternator Field Duty", "Electrical", "%", "Chrysler Generator Field Control Duty Cycle", "Chrysler") { a -> ((a.getOrNull(0) ?: 0) * 100) / 255.0 },
        // Chrysler TPMS UDS DIDs (Wireless Control Module / RF Hub header 7DA)
        Mode22PidDefinition("A020", "Tire Pressure FL", "Chassis", "psi", "Chrysler Front Left Tire Pressure (TPMS UDS A020)", "Chrysler") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) * 0.3625 },
        Mode22PidDefinition("A021", "Tire Pressure FR", "Chassis", "psi", "Chrysler Front Right Tire Pressure (TPMS UDS A021)", "Chrysler") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) * 0.3625 },
        Mode22PidDefinition("A022", "Tire Pressure RR", "Chassis", "psi", "Chrysler Rear Right Tire Pressure (TPMS UDS A022)", "Chrysler") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) * 0.3625 },
        Mode22PidDefinition("A023", "Tire Pressure RL", "Chassis", "psi", "Chrysler Rear Left Tire Pressure (TPMS UDS A023)", "Chrysler") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) * 0.3625 },
        // Alternate legacy TPMS PIDs
        Mode22PidDefinition("0301", "Tire Pressure FL (Alt)", "Chassis", "psi", "Chrysler Front Left Tire Pressure (TPMS)", "Chrysler") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) * 0.3625 },
        Mode22PidDefinition("0302", "Tire Pressure FR (Alt)", "Chassis", "psi", "Chrysler Front Right Tire Pressure (TPMS)", "Chrysler") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) * 0.3625 },
        Mode22PidDefinition("0303", "Tire Pressure RL (Alt)", "Chassis", "psi", "Chrysler Rear Left Tire Pressure (TPMS)", "Chrysler") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) * 0.3625 },
        Mode22PidDefinition("0304", "Tire Pressure RR (Alt)", "Chassis", "psi", "Chrysler Rear Right Tire Pressure (TPMS)", "Chrysler") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) * 0.3625 },
        Mode22PidDefinition("116B", "Ignition Knock Retard", "Engine", "°", "Chrysler Spark Knock Retard Timing Offset", "Chrysler") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) / 2.0 },
        Mode22PidDefinition("16CF", "Cam Phaser Error B1", "Engine", "°", "Chrysler VVT Variable Cam Timing Error Bank 1", "Chrysler") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) / 2.0 },
        Mode22PidDefinition("16D0", "Cam Phaser Error B2", "Engine", "°", "Chrysler VVT Variable Cam Timing Error Bank 2", "Chrysler") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) / 2.0 }
    )

    val DEMO_CUSTOM_PID_DEFS = listOf(
        Mode22PidDefinition("1E1C", "Transmission Fluid Temp", "Transmission", "°C", "Transmission Fluid Temperature (TFT)", "Ford"),
        Mode22PidDefinition("1310", "Engine Oil Temp", "Engine Oil", "°C", "Engine Oil Temperature (EOT)", "Ford"),
        Mode22PidDefinition("1304", "Engine Oil Pressure", "Engine Oil", "psi", "Engine Oil Gallery Pressure", "Ford"),
        Mode22PidDefinition("1674", "Cylinder Head Temp", "Engine", "°C", "Cylinder Head Temperature (CHT)", "Ford"),
        Mode22PidDefinition("162B", "Torque Converter Slip", "Transmission", "RPM", "Torque Converter Lockup Slip Speed", "Ford"),
        Mode22PidDefinition("162C", "Transmission Gear Ratio", "Transmission", "Ratio", "Actual Transmission Gear Ratio", "Ford"),
        Mode22PidDefinition("11B3", "Transmission Commanded Gear", "Transmission", "Gear", "Commanded Gear Position", "Ford"),
        Mode22PidDefinition("1E24", "Trans Line Pressure Duty", "Transmission", "%", "Transmission EPC Solenoid Duty Cycle", "Ford"),
        Mode22PidDefinition("042C", "Battery Voltage (BCM)", "Electrical", "V", "12V Battery Terminal Voltage", "Ford"),
        Mode22PidDefinition("042F", "12V Battery SOC", "Electrical", "%", "12V Battery State of Charge (BMS)", "Ford"),
        Mode22PidDefinition("0430", "Alternator Field Duty", "Electrical", "%", "Generator Field Control Duty Cycle", "Ford"),
        Mode22PidDefinition("0432", "Alternator Charging Current", "Electrical", "A", "12V Charging System Current", "Ford"),
        Mode22PidDefinition("116B", "Ignition Knock Retard", "Engine", "°", "Spark Knock Retard Timing Offset", "Ford"),
        Mode22PidDefinition("16CF", "VCT Cam Phaser Error B1", "Engine", "°", "Variable Cam Timing Error Bank 1", "Ford"),
        Mode22PidDefinition("16D0", "VCT Cam Phaser Error B2", "Engine", "°", "Variable Cam Timing Error Bank 2", "Ford"),
        Mode22PidDefinition("163E", "Fuel Rail Pressure", "Fuel", "psi", "Electronic Fuel Rail Pressure", "Ford"),
        Mode22PidDefinition("163F", "Fuel Pump Duty Cycle", "Fuel", "%", "Fuel Pump Driver Module Duty", "Ford"),
        Mode22PidDefinition("1166", "EVAP Purge Valve Duty", "Emissions", "%", "Canister Purge Valve Duty Cycle", "Ford"),
        Mode22PidDefinition("2813", "Tire Pressure FL", "Chassis", "psi", "Front Left Tire Pressure (TPMS)", "Ford"),
        Mode22PidDefinition("2814", "Tire Pressure FR", "Chassis", "psi", "Front Right Tire Pressure (TPMS)", "Ford"),
        Mode22PidDefinition("2815", "Tire Pressure RL", "Chassis", "psi", "Rear Left Tire Pressure (TPMS)", "Ford"),
        Mode22PidDefinition("2816", "Tire Pressure RR", "Chassis", "psi", "Rear Right Tire Pressure (TPMS)", "Ford"),
        Mode22PidDefinition("0994", "A/C Evaporator Temp", "Climate", "°C", "A/C Evaporator Core Temperature", "Ford"),
        Mode22PidDefinition("0995", "Cabin Interior Temp", "Climate", "°C", "In-Car Cabin Temperature Sensor", "Ford"),
        Mode22PidDefinition("0998", "A/C High Side Pressure", "Climate", "psi", "A/C Refrigerant Discharge Pressure", "Ford"),
        Mode22PidDefinition("031A", "Cooling Fan Speed Duty", "Engine", "%", "Radiator Cooling Fan Commanded Duty", "Ford"),
        Mode22PidDefinition("054B", "Engine Oil Life Remaining", "Maintenance", "%", "Intelligent Oil Life Monitor Index", "Ford")
    )

    fun getPidsForManufacturer(manufacturer: String): List<Mode22PidDefinition> {
        val m = manufacturer.uppercase()
        val vinMatch = Regex("[A-HJ-NPR-Z0-9]{17}").find(m)?.value ?: ""
        val wmi = if (vinMatch.length >= 3) vinMatch.substring(0, 3) else ""

        val isFord = m.contains("FORD") || m.contains("LINCOLN") || m.contains("MERCURY") ||
                     wmi.startsWith("1F") || wmi.startsWith("2F") || wmi.startsWith("3F") || wmi.startsWith("1L") || wmi.startsWith("2L")

        val isGm = m.contains("GM") || m.contains("CHEVROLET") || m.contains("CHEVY") || m.contains("GMC") || m.contains("BUICK") || m.contains("CADILLAC") || m.contains("PONTIAC") || m.contains("SATURN") ||
                   wmi.startsWith("1G") || wmi.startsWith("2G") || wmi.startsWith("3G")

        val isToyota = m.contains("TOYOTA") || m.contains("LEXUS") || m.contains("SCION") ||
                       wmi.startsWith("JT") || wmi.startsWith("2T") || wmi.startsWith("4T") || wmi.startsWith("5T") || wmi.startsWith("3TM")

        val isChrysler = m.contains("CHRYSLER") || m.contains("DODGE") || m.contains("JEEP") || m.contains("RAM") || m.contains("FIAT") ||
                         wmi.startsWith("1C") || wmi.startsWith("2C") || wmi.startsWith("3C") || wmi.startsWith("1J") || wmi.startsWith("1D") || wmi.startsWith("2D") || wmi.startsWith("3D") || wmi.startsWith("1B") || wmi.startsWith("3B")

        return KNOWN_PIDS.filter { pid ->
            val pM = pid.manufacturer.uppercase()
            (isFord && pM == "FORD") ||
            (isGm && pM == "GM") ||
            (isToyota && pM == "TOYOTA") ||
            (isChrysler && pM == "CHRYSLER")
        }
    }
}

@SuppressLint("MissingPermission")
class BluetoothObdManager {

    companion object {
        private const val TAG = "BluetoothObdManager"
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val COMMAND_TIMEOUT_MS = 2500L
    }

    // Connection & Engine States
    sealed interface ConnectionState {
        object Disconnected : ConnectionState
        data class Connecting(val deviceName: String) : ConnectionState
        data class Connected(val deviceName: String) : ConnectionState
        data class Error(val message: String) : ConnectionState
    }

    // Legacy Vehicle Metrics Data Class
    data class VehicleMetrics(
        val engineLoad: Int = 0,
        val coolantTemp: Int = -40,
        val fuelTrim: Int = 0,
        val rpm: Int = 0,
        val speed: Int = 0,
        val intakeAirTemp: Int = -40,
        val maf: Double = 0.0,
        val throttlePosition: Int = 0,
        val dtcs: List<String> = emptyList(),
        val rawResponse: String = ""
    )

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _metrics = MutableStateFlow(VehicleMetrics())
    val metrics: StateFlow<VehicleMetrics> = _metrics.asStateFlow()

    // Dynamic StateFlow exposing all streams supported by the connected car
    private val _supportedStreams = MutableStateFlow<List<LiveStreamData>>(emptyList())
    val supportedStreams: StateFlow<List<LiveStreamData>> = _supportedStreams.asStateFlow()

    // Reactive StateFlow exposing decoded 17-character VIN via Mode 09 PID 02
    private val _vehicleVin = MutableStateFlow<String?>(null)
    val vehicleVin: StateFlow<String?> = _vehicleVin.asStateFlow()

    // Reactive StateFlow exposing decoded Make, Model Year, and VIN summary
    private val _vehicleDetails = MutableStateFlow<String?>(null)
    val vehicleDetails: StateFlow<String?> = _vehicleDetails.asStateFlow()

    // Reactive StateFlow exposing total vehicle odometer in kilometers
    private val _vehicleOdometerKm = MutableStateFlow<Double?>(null)
    val vehicleOdometerKm: StateFlow<Double?> = _vehicleOdometerKm.asStateFlow()

    // Reactive StateFlow indicating whether live telemetry frames (Mode 01) are actively streaming
    private val _isStreamingLiveData = MutableStateFlow(false)
    val isStreamingLiveData: StateFlow<Boolean> = _isStreamingLiveData.asStateFlow()

    // High-Speed CAN & opendbc StateFlows
    private val _isHighSpeedCanCapable = MutableStateFlow(false)
    val isHighSpeedCanCapable: StateFlow<Boolean> = _isHighSpeedCanCapable.asStateFlow()

    private val _adapterHardwareName = MutableStateFlow("Generic ELM327")
    val adapterHardwareName: StateFlow<String> = _adapterHardwareName.asStateFlow()

    private val _activeOpendbcProfile = MutableStateFlow<OpendbcManager.VehicleDbcProfile?>(null)
    val activeOpendbcProfile: StateFlow<OpendbcManager.VehicleDbcProfile?> = _activeOpendbcProfile.asStateFlow()

    private val _opendbcSignals = MutableStateFlow<Map<String, OpendbcManager.DecodedOpendbcSignal>>(emptyMap())
    val opendbcSignals: StateFlow<Map<String, OpendbcManager.DecodedOpendbcSignal>> = _opendbcSignals.asStateFlow()

    fun updateVehicleDetails(details: String) {
        _vehicleDetails.value = details
    }

    // Persistent Debug Log File Reference for ADB Pull & Analysis
    var debugLogFile: java.io.File? = null

    fun setLogFileDirectory(dir: java.io.File?) {
        if (dir != null) {
            try {
                if (!dir.exists()) dir.mkdirs()
                debugLogFile = java.io.File(dir, "vera_elm327_debug.log")
            } catch (e: Exception) {
                Log.e(TAG, "Error setting log file directory", e)
            }
        }
    }

    // Reactive StateFlow exposing rolling raw ELM327 TX/RX command trace log
    private val _elmCommandLog = MutableStateFlow<List<String>>(emptyList())
    val elmCommandLog: StateFlow<List<String>> = _elmCommandLog.asStateFlow()

    private fun logElmExchange(tx: String, rx: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
        val cleanRx = rx.replace("\r", " ").replace("\n", " ").trim()
        val entry = "[$time] TX: $tx  ➜  RX: $cleanRx"

        val current = _elmCommandLog.value.toMutableList()
        if (current.size >= 200) {
            current.removeAt(0)
        }
        current.add(entry)
        _elmCommandLog.value = current

        // Append to persistent device debug log file
        val targetFile = debugLogFile
        if (targetFile != null) {
            try {
                targetFile.appendText("$entry\n", Charsets.UTF_8)
            } catch (e: Exception) {
                Log.e(TAG, "Error appending entry to debug log file", e)
            }
        }
    }

    // Reactive StateFlow exposing Mode 06 On-Board Diagnostic Component Test Results
    private val _mode06Results = MutableStateFlow<List<Mode06TestResult>>(emptyList())
    val mode06Results: StateFlow<List<Mode06TestResult>> = _mode06Results.asStateFlow()

    // Reactive StateFlow exposing Mode 22 Enhanced Proprietary Parameters
    private val _mode22Streams = MutableStateFlow<List<Mode22StreamData>>(emptyList())
    val mode22Streams: StateFlow<List<Mode22StreamData>> = _mode22Streams.asStateFlow()
    private val cachedSupportedMode22Pids = mutableSetOf<String>()

    private var discoveredPidDefinitions: List<ObdPidDefinition> = ObdPidRegistry.DEFAULT_PIDS

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    private var wifiSocket: java.net.Socket? = null
    var isWifiConnection = false
        private set

    private var usbSerialPort: com.hoho.android.usbserial.driver.UsbSerialPort? = null
    private var usbDeviceConnection: android.hardware.usb.UsbDeviceConnection? = null
    var isUsbConnection = false
        private set

    var obdbRepository: ObdbRepository? = null

    private val _obdbLoadedResult = MutableStateFlow<ObdbRepository.LoadResult?>(null)
    val obdbLoadedResult: StateFlow<ObdbRepository.LoadResult?> = _obdbLoadedResult.asStateFlow()

    private val _obdbSignalResults = MutableStateFlow<Map<String, Any?>>(emptyMap())
    val obdbSignalResults: StateFlow<Map<String, Any?>> = _obdbSignalResults.asStateFlow()

    private val managerJob = SupervisorJob()
    private val ioScope = CoroutineScope(Dispatchers.IO + managerJob)
    private var workerJob: Job? = null
    private var pollingJob: Job? = null
    @Volatile var isPollingPaused = false
    @Volatile private var isMultiPidBatchSupported: Boolean? = null
    @Volatile private var isPassiveCanBlockedOnGateway = false
    @Volatile private var profConfirmedOnBus = false
    private var consecutiveEmptyCanSamples = 0
    private val activeStreamCache = java.util.concurrent.ConcurrentHashMap<String, LiveStreamData>()
    private val streamRingBuffers = java.util.concurrent.ConcurrentHashMap<String, java.util.ArrayDeque<StreamSample>>()
    private var lastStreamUiEmitMs = 0L

    // Synchronization for synchronous AT command-response flow
    private val responseMutex = Mutex()
    private val responseBufferLock = Any()
    private var latestResponse = StringBuilder()
    @Volatile private var promptReceived = false
    @Volatile private var promptDeferred: CompletableDeferred<Unit>? = null

    private fun clearResponseBuffer() {
        synchronized(responseBufferLock) {
            latestResponse.setLength(0)
            promptReceived = false
            promptDeferred = null
        }
    }

    private fun drainResponseBuffer(): String {
        return synchronized(responseBufferLock) {
            val s = latestResponse.toString()
            latestResponse.setLength(0)
            s
        }
    }

    private fun peekResponseBuffer(): String {
        return synchronized(responseBufferLock) {
            latestResponse.toString()
        }
    }

    private fun hasPendingResponseInBuffer(): Boolean {
        return synchronized(responseBufferLock) {
            val idx = latestResponse.indexOf("7F")
            idx != -1 && latestResponse.indexOf("78", idx) != -1
        }
    }

    private fun appendResponseText(text: String) {
        synchronized(responseBufferLock) {
            if (latestResponse.length > 65536) {
                latestResponse.delete(0, 32768)
            }
            latestResponse.append(text)
        }
    }

    var isDemoMode = false
        private set
    var demoPreset: DemoVehiclePreset = DemoVehiclePreset.CLEAN_ECU

    private fun buildDemoMode22Streams(preset: DemoVehiclePreset): List<Mode22StreamData> {
        val isDirty = preset == DemoVehiclePreset.DIRTY_ECU
        val rawMap = mapOf(
            "1E1C" to if (isDirty) 116.5 else 82.0,  // Trans Fluid Temp: 116.5°C (241.7°F) OUT OF RANGE vs 82.0°C (179.6°F) Normal
            "1310" to if (isDirty) 119.5 else 91.0,  // Engine Oil Temp: 119.5°C (247.1°F) OUT OF RANGE vs 91.0°C (195.8°F) Normal
            "1304" to if (isDirty) 16.5 else 44.0,   // Oil Pressure: 16.5 psi MARGINAL vs 44.0 psi Normal
            "1674" to if (isDirty) 109.0 else 94.0,  // Cylinder Head Temp: 109.0°C (228.2°F) MARGINAL vs 94.0°C (201.2°F) Normal
            "162B" to if (isDirty) 58.0 else 2.0,    // Torque Converter Slip: 58 RPM MARGINAL vs 2 RPM Normal
            "162C" to 1.00,                          // Gear Ratio
            "11B3" to 3.0,                           // Commanded Gear
            "1E24" to if (isDirty) 78.0 else 36.0,   // Trans Line Pressure Duty
            "042C" to if (isDirty) 11.9 else 14.1,   // 12V Battery Voltage: 11.9V MARGINAL vs 14.1V Normal
            "042F" to if (isDirty) 54.0 else 94.0,   // 12V Battery SOC: 54% MARGINAL vs 94% Normal
            "0430" to if (isDirty) 88.0 else 42.0,   // Alternator Field Duty
            "0432" to if (isDirty) 68.5 else 24.0,   // Alternator Current
            "116B" to if (isDirty) 4.8 else 0.0,     // Ignition Knock Retard: 4.8° OUT OF RANGE vs 0.0° Normal
            "16CF" to if (isDirty) 5.2 else 0.2,     // VCT Cam Phaser Error B1: 5.2° OUT OF RANGE vs 0.2° Normal
            "16D0" to if (isDirty) 1.8 else 0.1,     // VCT Cam Phaser Error B2: 1.8° MARGINAL vs 0.1° Normal
            "163E" to if (isDirty) 26.5 else 39.5,   // Fuel Rail Pressure: 26.5 psi MARGINAL (causing P0171 lean) vs 39.5 psi Normal
            "163F" to if (isDirty) 84.0 else 44.0,   // Fuel Pump Duty: 84% MARGINAL vs 44% Normal
            "1166" to if (isDirty) 62.0 else 34.0,   // EVAP Purge Duty
            "2813" to 34.5,                          // Tire Pressure FL
            "2814" to 34.0,                          // Tire Pressure FR
            "2815" to if (isDirty) 26.5 else 33.5,   // Tire Pressure RL: 26.5 psi MARGINAL vs 33.5 psi Normal
            "2816" to if (isDirty) 21.0 else 33.5,   // Tire Pressure RR: 21.0 psi OUT OF RANGE vs 33.5 psi Normal
            "0994" to 7.5,                           // A/C Evaporator Temp
            "0995" to 21.5,                          // Cabin Temp
            "0998" to 165.0,                         // A/C High Side Pressure
            "031A" to if (isDirty) 95.0 else 25.0,   // Cooling Fan Duty
            "054B" to if (isDirty) 11.0 else 86.0    // Engine Oil Life: 11% MARGINAL vs 86% Normal
        )
        return Mode22Registry.DEMO_CUSTOM_PID_DEFS.mapNotNull { def ->
            val raw = rawMap[def.pidHex] ?: return@mapNotNull null
            val formatted = String.format(java.util.Locale.US, "%.1f %s", raw, def.unit)
            Mode22StreamData(pid = def, formattedValue = formatted, rawValue = raw)
        }
    }

    private fun startDemoSimulatorLoop() {
        isDemoMode = true
        pollingJob?.cancel()

        val (vinStr, vehicleDetailsStr) = when (demoPreset) {
            DemoVehiclePreset.CLEAN_ECU -> Pair("1FTPW14VX7FA97675", "Ford • F-150 • 2007 • VIN: 1FTPW14VX7FA97675")
            DemoVehiclePreset.DIRTY_ECU -> Pair("1FTPW14VX7FA97675", "Ford • F-150 5.4L V8 • 2007 • VIN: 1FTPW14VX7FA97675")
        }

        _connectionState.value = ConnectionState.Connected("Demo ECU Simulator")
        _vehicleVin.value = vinStr
        _vehicleDetails.value = vehicleDetailsStr
        _isStreamingLiveData.value = true

        if (demoPreset == DemoVehiclePreset.DIRTY_ECU) {
            val dirtyDtcs = listOf("P0305", "P0171")
            _metrics.value = _metrics.value.copy(
                dtcs = dirtyDtcs,
                rpm = 740,
                coolantTemp = 98,
                fuelTrim = 14,
                engineLoad = 32
            )
            _mode06Results.value = listOf(
                Mode06TestResult("A1", "Misfire", "General / Total Misfires", 18.0, 0.0, 10.0, "misfires", Mode06Status.FAIL, "General / Total Misfires: 18 detected misfires (FAIL)"),
                Mode06TestResult("A2", "Misfire", "Cylinder 1 Misfires", 0.0, 0.0, 10.0, "misfires", Mode06Status.PASS, "Cylinder 1 Misfires: 0 misfires (Clean)"),
                Mode06TestResult("A3", "Misfire", "Cylinder 2 Misfires", 0.0, 0.0, 10.0, "misfires", Mode06Status.PASS, "Cylinder 2 Misfires: 0 misfires (Clean)"),
                Mode06TestResult("A4", "Misfire", "Cylinder 3 Misfires", 0.0, 0.0, 10.0, "misfires", Mode06Status.PASS, "Cylinder 3 Misfires: 0 misfires (Clean)"),
                Mode06TestResult("A5", "Misfire", "Cylinder 4 Misfires", 0.0, 0.0, 10.0, "misfires", Mode06Status.PASS, "Cylinder 4 Misfires: 0 misfires (Clean)"),
                Mode06TestResult("A6", "Misfire", "Cylinder 5 Misfires", 14.0, 0.0, 10.0, "misfires", Mode06Status.FAIL, "Cylinder 5 Misfires: 14 detected misfires (FAIL)"),
                Mode06TestResult("A7", "Misfire", "Cylinder 6 Misfires", 0.0, 0.0, 10.0, "misfires", Mode06Status.PASS, "Cylinder 6 Misfires: 0 misfires (Clean)"),
                Mode06TestResult("A8", "Misfire", "Cylinder 7 Misfires", 0.0, 0.0, 10.0, "misfires", Mode06Status.PASS, "Cylinder 7 Misfires: 0 misfires (Clean)"),
                Mode06TestResult("A9", "Misfire", "Cylinder 8 Misfires", 0.0, 0.0, 10.0, "misfires", Mode06Status.PASS, "Cylinder 8 Misfires: 0 misfires (Clean)"),
                Mode06TestResult("21", "Catalyst", "Catalyst Efficiency Bank 1", 1.85, 0.8, 2.0, "counts", Mode06Status.WARNING, "Catalyst Efficiency Bank 1: Measured 2 (Min: 0 | Max: 2) - Near Limit"),
                Mode06TestResult("39", "EVAP System", "EVAP Leak Monitor (0.150\")", 12.0, 0.0, 500.0, "Pa", Mode06Status.PASS, "EVAP Leak Monitor: Measured 12 Pa (Min: 0 | Max: 500)")
            )
            _mode22Streams.value = buildDemoMode22Streams(DemoVehiclePreset.DIRTY_ECU)
            _obdbSignalResults.value = emptyMap()
            cachedSupportedMode22Pids.clear()
        } else {
            _metrics.value = _metrics.value.copy(
                dtcs = emptyList(),
                rpm = 780,
                coolantTemp = 90,
                fuelTrim = 1,
                engineLoad = 22
            )
            _mode06Results.value = listOf(
                Mode06TestResult("A1", "Misfire", "General / Total Misfires", 0.0, 0.0, 50.0, "misfires", Mode06Status.PASS, "General / Total Misfires: 0 misfires (Clean)"),
                Mode06TestResult("A2", "Misfire", "Cylinder 1 Misfires", 0.0, 0.0, 50.0, "misfires", Mode06Status.PASS, "Cylinder 1 Misfires: 0 misfires (Clean)"),
                Mode06TestResult("A3", "Misfire", "Cylinder 2 Misfires", 0.0, 0.0, 50.0, "misfires", Mode06Status.PASS, "Cylinder 2 Misfires: 0 misfires (Clean)"),
                Mode06TestResult("A4", "Misfire", "Cylinder 3 Misfires", 0.0, 0.0, 50.0, "misfires", Mode06Status.PASS, "Cylinder 3 Misfires: 0 misfires (Clean)"),
                Mode06TestResult("A5", "Misfire", "Cylinder 4 Misfires", 0.0, 0.0, 50.0, "misfires", Mode06Status.PASS, "Cylinder 4 Misfires: 0 misfires (Clean)"),
                Mode06TestResult("A6", "Misfire", "Cylinder 5 Misfires", 0.0, 0.0, 50.0, "misfires", Mode06Status.PASS, "Cylinder 5 Misfires: 0 misfires (Clean)"),
                Mode06TestResult("A7", "Misfire", "Cylinder 6 Misfires", 0.0, 0.0, 50.0, "misfires", Mode06Status.PASS, "Cylinder 6 Misfires: 0 misfires (Clean)"),
                Mode06TestResult("A8", "Misfire", "Cylinder 7 Misfires", 0.0, 0.0, 50.0, "misfires", Mode06Status.PASS, "Cylinder 7 Misfires: 0 misfires (Clean)"),
                Mode06TestResult("A9", "Misfire", "Cylinder 8 Misfires", 0.0, 0.0, 50.0, "misfires", Mode06Status.PASS, "Cylinder 8 Misfires: 0 misfires (Clean)"),
                Mode06TestResult("21", "Catalyst", "Catalyst Efficiency Bank 1", 1.02, 0.8, 2.0, "counts", Mode06Status.PASS, "Catalyst Efficiency Bank 1: Measured 1 (Min: 0 | Max: 2)"),
                Mode06TestResult("22", "Catalyst", "Catalyst Efficiency Bank 2", 1.05, 0.8, 2.0, "counts", Mode06Status.PASS, "Catalyst Efficiency Bank 2: Measured 1 (Min: 0 | Max: 2)"),
                Mode06TestResult("01", "O2 Sensor", "O2 Sensor Bank 1 Sensor 1", 0.85, 0.2, 1.0, "V", Mode06Status.PASS, "O2 Sensor Bank 1 Sensor 1: Measured 0 (Min: 0 | Max: 1)"),
                Mode06TestResult("39", "EVAP System", "EVAP Leak Monitor (0.150\")", 12.0, 0.0, 500.0, "Pa", Mode06Status.PASS, "EVAP Leak Monitor: Measured 12 Pa (Min: 0 | Max: 500)")
            )
            _mode22Streams.value = buildDemoMode22Streams(DemoVehiclePreset.CLEAN_ECU)
            _obdbSignalResults.value = emptyMap()
            cachedSupportedMode22Pids.clear()
        }

        val demoOdoKm = if (demoPreset == DemoVehiclePreset.DIRTY_ECU) 184290.0 else 67450.0
        _vehicleOdometerKm.value = demoOdoKm

        _isHighSpeedCanCapable.value = true
        _adapterHardwareName.value = "vLinker FS USB (High-Speed CAN Ready)"
        val profile = OpendbcManager.findProfileForVehicle(vinStr)
        _activeOpendbcProfile.value = profile

        // Trigger OBDb Signalset Loader for Demo Vehicle (Ford F-150 2007)
        ioScope.launch {
            loadObdbForVehicle("Ford", "F-150", 2007)
        }

        val defaultHexPids = listOf("04", "05", "06", "07", "08", "09", "0C", "0D", "0E", "0F", "10", "11", "22", "42", "46")
        val initialStreams = defaultHexPids.mapNotNull { hex ->
            ObdPidRegistry.KNOWN_PIDS[hex]
        }.map { pidDef ->
            val initVal = when (pidDef.pidHex) {
                "0C" -> if (demoPreset == DemoVehiclePreset.DIRTY_ECU) 740.0 else 780.0
                "0D" -> 0.0
                "05" -> if (demoPreset == DemoVehiclePreset.DIRTY_ECU) 98.0 else 90.0
                "04" -> 24.0
                "06" -> if (demoPreset == DemoVehiclePreset.DIRTY_ECU) 14.2 else 1.2
                "07" -> if (demoPreset == DemoVehiclePreset.DIRTY_ECU) 11.8 else 0.8
                "08" -> -0.5
                "09" -> -1.2
                "0E" -> 10.0
                "0F" -> 28.0
                "10" -> 4.1
                "11" -> 14.5
                "22" -> 270.0
                "42" -> 13.9
                "46" -> 22.0
                else -> 10.0
            }
            val formatted = String.format(java.util.Locale.US, "%.1f %s", initVal, pidDef.unit)
            LiveStreamData(
                pid = pidDef,
                formattedValue = formatted,
                rawValue = initVal,
                history = listOf(StreamSample(initVal))
            )
        }

        _supportedStreams.value = initialStreams

        pollingJob = ioScope.launch {
            var step = 0
            while (isActive && isDemoMode) {
                step++
                val currentList = _supportedStreams.value.toMutableList()
                for (i in currentList.indices) {
                    val stream = currentList[i]
                    val base = stream.rawValue
                    val delta = when (stream.pid.pidHex) {
                        "0C" -> if (demoPreset == DemoVehiclePreset.DIRTY_ECU) {
                            (Math.sin(step * 0.3) * 35.0) + (Math.random() * 12.0 - 6.0)
                        } else {
                            (Math.sin(step * 0.2) * 15.0) + (Math.random() * 4.0 - 2.0)
                        }
                        "06" -> if (demoPreset == DemoVehiclePreset.DIRTY_ECU) (Math.random() * 1.5 - 0.75) else (Math.random() * 0.8 - 0.4)
                        else -> 0.0
                    }
                    val newVal = maxOf(0.0, base + delta)
                    val formatted = String.format(java.util.Locale.US, "%.1f %s", newVal, stream.pid.unit)
                    val updatedHist = (stream.history + StreamSample(newVal)).takeLast(300)
                    currentList[i] = stream.copy(
                        formattedValue = formatted,
                        rawValue = newVal,
                        history = updatedHist,
                        lastUpdated = System.currentTimeMillis()
                    )
                }
                _supportedStreams.value = currentList

                val rpmVal = currentList.find { it.pid.pidHex == "0C" }?.rawValue?.toInt() ?: 780
                val coolantVal = currentList.find { it.pid.pidHex == "05" }?.rawValue?.toInt() ?: 90
                val fuelTrimVal = if (demoPreset == DemoVehiclePreset.DIRTY_ECU) 14 else 1
                val engineLoadVal = if (demoPreset == DemoVehiclePreset.DIRTY_ECU) 32 else 22
                val currentDtcs = if (demoPreset == DemoVehiclePreset.DIRTY_ECU) listOf("P0305", "P0171") else emptyList()
                _metrics.value = _metrics.value.copy(
                    rpm = rpmVal,
                    coolantTemp = coolantVal,
                    speed = 0,
                    engineLoad = engineLoadVal,
                    fuelTrim = fuelTrimVal,
                    maf = 4.2,
                    throttlePosition = 14,
                    dtcs = currentDtcs
                )

                // Simulate dynamic opendbc broadcast signals
                val steer = Math.sin(step * 0.1) * 18.5
                val steerRate = Math.cos(step * 0.1) * 12.0
                val driverTorque = Math.sin(step * 0.1) * 2.8
                val epsTorque = -Math.sin(step * 0.1) * 6.4
                val yawRate = Math.sin(step * 0.1) * 4.2
                val latG = Math.sin(step * 0.1) * 0.18
                val longG = 0.05 + Math.cos(step * 0.05) * 0.08
                val engTorque = 195.0 + Math.sin(step * 0.2) * 45.0
                val brake = if (step % 20 in 0..4) (Math.sin(step * 0.5).coerceAtLeast(0.0) * 25.0) else 0.0
                val accelPedal = 18.0 + (Math.sin(step * 0.2).coerceAtLeast(0.0) * 16.0)
                val leadDist = 32.5 + Math.sin(step * 0.08) * 6.0
                val speedBase = 65.0 + Math.sin(step * 0.05) * 5.0
                _opendbcSignals.value = linkedMapOf(
                    "Steering Angle" to OpendbcManager.DecodedOpendbcSignal("Steering Angle", steer, String.format(java.util.Locale.US, "%.1f°", steer), "deg"),
                    "Steering Rate" to OpendbcManager.DecodedOpendbcSignal("Steering Rate", steerRate, String.format(java.util.Locale.US, "%.1f°/s", steerRate), "deg/s"),
                    "Driver Torque" to OpendbcManager.DecodedOpendbcSignal("Driver Torque", driverTorque, String.format(java.util.Locale.US, "%.1f Nm", driverTorque), "Nm"),
                    "EPS Torque" to OpendbcManager.DecodedOpendbcSignal("EPS Torque", epsTorque, String.format(java.util.Locale.US, "%.1f Nm", epsTorque), "Nm"),
                    "Yaw Rate" to OpendbcManager.DecodedOpendbcSignal("Yaw Rate", yawRate, String.format(java.util.Locale.US, "%.1f°/s", yawRate), "deg/s"),
                    "Lateral G" to OpendbcManager.DecodedOpendbcSignal("Lateral G", latG, String.format(java.util.Locale.US, "%+.2f g", latG), "g"),
                    "Longitudinal G" to OpendbcManager.DecodedOpendbcSignal("Longitudinal G", longG, String.format(java.util.Locale.US, "%+.2f g", longG), "g"),
                    "Engine Torque" to OpendbcManager.DecodedOpendbcSignal("Engine Torque", engTorque, String.format(java.util.Locale.US, "%.0f Nm", engTorque), "Nm"),
                    "Wheel Speed FL" to OpendbcManager.DecodedOpendbcSignal("Wheel Speed FL", speedBase, String.format(java.util.Locale.US, "%.1f mph", speedBase), "mph"),
                    "Wheel Speed FR" to OpendbcManager.DecodedOpendbcSignal("Wheel Speed FR", speedBase + 0.2, String.format(java.util.Locale.US, "%.1f mph", speedBase + 0.2), "mph"),
                    "Wheel Speed RL" to OpendbcManager.DecodedOpendbcSignal("Wheel Speed RL", speedBase - 0.1, String.format(java.util.Locale.US, "%.1f mph", speedBase - 0.1), "mph"),
                    "Wheel Speed RR" to OpendbcManager.DecodedOpendbcSignal("Wheel Speed RR", speedBase + 0.1, String.format(java.util.Locale.US, "%.1f mph", speedBase + 0.1), "mph"),
                    "Brake Pressure" to OpendbcManager.DecodedOpendbcSignal("Brake Pressure", brake, String.format(java.util.Locale.US, "%.1f psi", brake * 14.5038), "psi"),
                    "Accelerator" to OpendbcManager.DecodedOpendbcSignal("Accelerator", accelPedal, String.format(java.util.Locale.US, "%.0f%%", accelPedal), "%"),
                    "Lead Distance" to OpendbcManager.DecodedOpendbcSignal("Lead Distance", leadDist, String.format(java.util.Locale.US, "%.1f m", leadDist), "m"),
                    "Transmission Gear" to OpendbcManager.DecodedOpendbcSignal("Transmission Gear", 3.0, "D", "Gear")
                )

                delay(250)
            }
        }
    }

    /**
     * Connects to a Bluetooth OBD2 device with automatic secure/insecure fallback and reflection.
     * Returns true if connection and ELM327 handshake succeed, false otherwise.
     */
    suspend fun connect(device: BluetoothDevice, secure: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        disconnect()
        if (bluetoothAdapter == null) {
            _connectionState.value = ConnectionState.Error("Bluetooth not supported")
            return@withContext false
        }

        bluetoothAdapter?.cancelDiscovery()
        _connectionState.value = ConnectionState.Connecting(device.name ?: "Unknown OBD Device")

        var connected = false
        var lastException: Exception? = null

        // Try secure first, then insecure standard sockets
        for (useSecure in listOf(secure, false)) {
            if (connected) break
            try {
                socket = createBluetoothSocket(device, useSecure)
                socket?.connect()
                connected = true
            } catch (e: Exception) {
                Log.w(TAG, "Connection attempt failed (secure=$useSecure)", e)
                lastException = e
                try {
                    socket?.close()
                } catch (_: Exception) {}
                socket = null
            }
        }

        // If standard sockets fail, try reflection fallback (secure then insecure port 1)
        if (!connected) {
            try {
                Log.w(TAG, "Trying reflection socket fallback...")
                val clazz = device.javaClass
                val m: Method = clazz.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                socket = m.invoke(device, 1) as BluetoothSocket
                socket?.connect()
                connected = true
            } catch (e: Exception) {
                Log.w(TAG, "Reflection fallback failed, trying insecure reflection...", e)
                try {
                    val clazz = device.javaClass
                    val m: Method = clazz.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
                    socket = m.invoke(device, 1) as BluetoothSocket
                    socket?.connect()
                    connected = true
                } catch (e2: Exception) {
                    Log.e(TAG, "All connection attempts failed", e2)
                    lastException = e2
                }
            }
        }

        if (connected) {
            try {
                delay(500)

                inputStream = socket?.inputStream
                outputStream = socket?.outputStream

                // Start background input stream reader
                startStreamReader()

                // Execute ELM327 Initialization Sequence
                initializeElm327()

                // Dynamically discover all live data streams supported by this vehicle!
                discoverSupportedPids()

                // Query and decode 17-character VIN via Mode 09 PID 02 to verify active ECU communication
                fetchVehicleVin()

                _connectionState.value = ConnectionState.Connected(device.name ?: "OBD Device")

                // Start dynamic polling loop for all discovered PIDs
                startPollingLoop()
                return@withContext true
            } catch (e: Exception) {
                Log.e(TAG, "Initialization or ECU handshake failed", e)
                disconnect()
                _connectionState.value = ConnectionState.Error("Connected to Bluetooth, but OBD2 adapter/ECU did not respond. Is the car ignition ON?")
                return@withContext false
            }
        } else {
            disconnect()
            _connectionState.value = ConnectionState.Error(lastException?.localizedMessage ?: "Connection failed (Socket closed or timeout)")
            return@withContext false
        }
    }

    private fun createBluetoothSocket(device: BluetoothDevice, secure: Boolean): BluetoothSocket {
        return if (secure) {
            device.createRfcommSocketToServiceRecord(SPP_UUID)
        } else {
            device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
        }
    }

    /**
     * ELM327 Initialization & Handshake Sequence
     */
    private suspend fun initializeElm327() {
        Log.i(TAG, "Starting ELM327 Initialization Sequence...")
        sendAtCommand("AT Z", 1200)
        sendAtCommand("AT E0", 200)
        sendAtCommand("AT L0", 200)
        sendAtCommand("AT H0", 200)

        // Query adapter identification for high-speed CAN capabilities
        val atiResp = sendAtCommand("ATI", 300)
        val at1Resp = sendAtCommand("AT @1", 300)
        val stdiResp = sendAtCommand("STDI", 300)

        val combinedAdapterInfo = "$atiResp $at1Resp $stdiResp".lowercase()
        val hasStnFirmware = !stdiResp.contains("?") && (
                combinedAdapterInfo.contains("vlinker") ||
                combinedAdapterInfo.contains("stn") ||
                combinedAdapterInfo.contains("obdlink") ||
                combinedAdapterInfo.contains("stntool")
        )
        isStnAdapter = hasStnFirmware
        val isCapable = isUsbConnection ||
                hasStnFirmware ||
                combinedAdapterInfo.contains("microsys")

        _isHighSpeedCanCapable.value = isCapable
        _adapterHardwareName.value = when {
            combinedAdapterInfo.contains("vlinker fs") -> "Vgate vLinker FS (High-Speed USB)"
            combinedAdapterInfo.contains("vlinker mc") -> "Vgate vLinker MC (High-Speed BT)"
            combinedAdapterInfo.contains("vlinker") -> "Vgate vLinker (High-Speed)"
            combinedAdapterInfo.contains("obdlink") -> "OBDLink (High-Speed)"
            combinedAdapterInfo.contains("stn") -> "STN ScanTool (High-Speed)"
            isUsbConnection -> "USB OBD Adapter (High-Speed)"
            else -> "Standard ELM327"
        }
        Log.i(TAG, "Adapter identified: ${_adapterHardwareName.value} (High-Speed CAN capable=$isCapable, STN=$isStnAdapter)")

        sendAtCommand("AT SP 0", 500)
        // Kickstart protocol auto-search with 0100
        sendAtCommand("0100", 1500)
        Log.i(TAG, "ELM327 Initialized Successfully.")
    }

    /**
     * Executes standard OBD-II PID discovery loop (`0100`, `0120`, `0140`, etc.)
     * Parses 4-byte bitmasks and builds the list of supported streams for this vehicle.
     */
    suspend fun discoverSupportedPids(): List<ObdPidDefinition> {
        val discoveredHexPids = mutableSetOf<String>()
        val discoveryBlocks = listOf("0100", "0120", "0140", "0160", "0180", "01A0", "01C0")

        for (blockCmd in discoveryBlocks) {
            Log.i(TAG, "Querying supported PID block: $blockCmd")
            var response = sendAtCommand(blockCmd, 300)
            var dataBytes = parse4ByteBitmask(response, blockCmd)

            if (dataBytes == null && blockCmd == "0100") {
                Log.w(TAG, "Block 0100 failed under AT SP 0, forcing CAN Protocol 6 (AT SP 6)...")
                sendAtCommand("AT SP 6", 300)
                response = sendAtCommand(blockCmd, 300)
                dataBytes = parse4ByteBitmask(response, blockCmd)
            }

            if (dataBytes == null) {
                Log.w(TAG, "No valid bitmask response for block $blockCmd")
                break
            }

            val startPidInt = blockCmd.substring(2).toInt(16) // e.g. 0x00, 0x20, 0x40

            // Parse 32 bits (4 bytes x 8 bits)
            for (bitIndex in 1..32) {
                val byteIdx = (bitIndex - 1) / 8
                val bitPos = 7 - ((bitIndex - 1) % 8)
                val isSupported = (dataBytes[byteIdx] and (1 shl bitPos)) != 0

                if (isSupported) {
                    val pidHex = String.format("%02X", startPidInt + bitIndex)
                    // PIDs ending in 00, 20, 40, 60, 80, A0, C0 are block query PIDs themselves
                    if (bitIndex != 32) {
                        discoveredHexPids.add(pidHex)
                    }
                }
            }

            // Bit 32 (index 32) indicates whether the NEXT block is supported
            val nextBlockSupported = (dataBytes[3] and 0x01) != 0
            if (!nextBlockSupported) {
                Log.i(TAG, "32nd bit not set for $blockCmd, ending PID discovery.")
                break
            }
        }

        // Map discovered hex PIDs to ObdPidDefinitions from Registry (filter ONLY known labeled PIDs)
        val supportedList = discoveredHexPids.mapNotNull { hex ->
            ObdPidRegistry.KNOWN_PIDS[hex]
        }

        discoveredPidDefinitions = if (supportedList.isNotEmpty()) supportedList else ObdPidRegistry.DEFAULT_PIDS
        Log.i(TAG, "Discovered ${discoveredPidDefinitions.size} supported PIDs on vehicle: ${discoveredPidDefinitions.map { it.pidHex }}")

        if (supportedList.isNotEmpty()) {
            _isStreamingLiveData.value = true
            // Lock discovered CAN protocol so the adapter never enters a multi-second "SEARCHING..."
            // state across legacy J1850/ISO9141 protocols if a single frame times out.
            val dpn = sendAtCommand("AT DPN", 60).uppercase().replace(" ", "").trim()
            val protoChar = dpn.lastOrNull { it in '1'..'9' || it in 'A'..'C' }
            if (protoChar != null && dpn.startsWith("A")) {
                Log.i(TAG, "Locking active OBD protocol $protoChar (from AT DPN=$dpn) to prevent mid-session SEARCHING...")
                sendAtCommand("AT SP $protoChar", 60)
            }
        }

        // Initialize StateFlow list of Supported Streams & Cache
        activeStreamCache.clear()
        streamRingBuffers.clear()
        for (pidDef in discoveredPidDefinitions) {
            activeStreamCache[pidDef.pidHex.uppercase()] = LiveStreamData(pid = pidDef)
        }
        _supportedStreams.value = activeStreamCache.values.sortedBy { it.pid.pidHex }
        return discoveredPidDefinitions
    }

    /**
     * Parses the 4-byte (8 hex character) bitmask response from commands like `0100`, `0120`, etc.
     */
    private fun parse4ByteBitmask(response: String, blockCmd: String): IntArray? {
        val lines = response.split("\r", "\n")
        val expectedHeader = "41" + blockCmd.substring(2) // e.g. "4100", "4120"
        for (line in lines) {
            val cleaned = line.replace(" ", "").uppercase()
            val idx = cleaned.indexOf(expectedHeader)
            if (idx != -1 && cleaned.length >= idx + 12) { // 4100 + 8 hex chars = 12 chars
                val hexData = cleaned.substring(idx + 4, idx + 12)
                return try {
                    intArrayOf(
                        hexData.substring(0, 2).toInt(16),
                        hexData.substring(2, 4).toInt(16),
                        hexData.substring(4, 6).toInt(16),
                        hexData.substring(6, 8).toInt(16)
                    )
                } catch (e: Exception) {
                    null
                }
            }
        }
        return null
    }

    /**
     * Executes a dedicated Mode 09 request (`09 02`) to retrieve and decode 
     * the 17-character VIN string with multi-header and protocol failovers.
     */
    suspend fun fetchVehicleVin(): String? {
        Log.i(TAG, "Executing dedicated Mode 09 VIN request (0902)...")
        sendAtCommand("AT SH 7DF", 100)
        var response = sendAtCommand("0902", 1200)
        var vin = parseVinFromResponse(response)

        if (vin == null) {
            response = sendAtCommand("09 02", 1200)
            vin = parseVinFromResponse(response)
        }

        if (vin == null) {
            Log.i(TAG, "Retrying Mode 09 VIN query on Engine PCM Header 7E0...")
            sendAtCommand("AT SH 7E0", 100)
            response = sendAtCommand("09 02", 1200)
            vin = parseVinFromResponse(response)
            sendAtCommand("AT SH 7DF", 100)
        }

        if (vin == null) {
            Log.i(TAG, "Retrying Mode 09 VIN query with CAN Protocol 6 (AT SP 6)...")
            sendAtCommand("AT SP 6", 300)
            sendAtCommand("AT SH 7E0", 100)
            response = sendAtCommand("09 02", 1200)
            vin = parseVinFromResponse(response)
            sendAtCommand("AT SH 7DF", 100)
        }

        if (vin != null && vin.length == 17) {
            _vehicleVin.value = vin
            val detailsStr = decodeVinDetails(vin)
            _vehicleDetails.value = detailsStr
            Log.i(TAG, "Successfully decoded Vehicle VIN & Details: $detailsStr")

            // Automatically resolve and arm opendbc vehicle profile
            val profile = OpendbcManager.findProfileForVehicle(vin)
            _activeOpendbcProfile.value = profile
            if (profile != null) {
                Log.i(TAG, "Matched opendbc profile: ${profile.make} (${profile.platformName}) with ${profile.messages.size} CAN message groups")
            }

            // Extract make, model, year for OBDb loader
            val parts = detailsStr.split("•").map { it.trim() }
            if (parts.size >= 3) {
                val makeName = parts[0]
                val modelName = parts[1]
                val yearInt = parts[2].toIntOrNull() ?: 2012
                Log.i(TAG, "Triggering OBDb Signalset Loader for Make='$makeName', Model='$modelName', Year=$yearInt")
                ioScope.launch {
                    loadObdbForVehicle(makeName, modelName, yearInt)
                }
            }
        } else {
            Log.w(TAG, "Could not decode 17-character VIN from Mode 09 response: $response")
            if (_vehicleVin.value.isNullOrBlank()) {
                _vehicleDetails.value = "Vehicle Connected (VIN Mode 09 Unsupported)"
            }
        }
        return _vehicleVin.value
    }

    /**
     * Queries the vehicle's total odometer reading in kilometers using a smart dual-stage strategy:
     * 1. SAE J1979 Mode 01 PID A6 (Standard for modern vehicles 2019+)
     * 2. Enhanced Manufacturer Mode 22 / Mode 21 fallback based on decoded vehicle make / VIN (Ford, GM, Toyota, Honda, Chrysler, VW, Nissan)
     */
    suspend fun fetchVehicleOdometer(): Double? {
        if (isDemoMode) {
            val demoKm = if (demoPreset == DemoVehiclePreset.DIRTY_ECU) 184290.0 else 67450.0
            _vehicleOdometerKm.value = demoKm
            return demoKm
        }

        val wasPolling = pollingJob?.isActive == true
        isPollingPaused = true
        pollingJob?.cancel()

        return try {
            if (wasPolling) delay(200)

            // Step 1: Standard SAE J1979 Mode 01 PID A6 (Modern vehicles 2019+)
            sendAtCommand("AT SH 7DF", 80)
            val resp01A6 = sendAtCommand("01A6", 400)
            val payloads01A6 = ObdParser.extractPayloads(resp01A6)
            val data01A6 = ObdParser.findResponse(payloads01A6, 0x41, 0xA6)
            if (data01A6 != null && data01A6.size >= 4) {
                val rawKm = ((data01A6[0].toLong() shl 24) or (data01A6[1].toLong() shl 16) or (data01A6[2].toLong() shl 8) or data01A6[3].toLong()).toDouble() / 10.0
                if (rawKm in 1.0..1_500_000.0) {
                    Log.i(TAG, "Decoded Odometer via SAE J1979 Mode 01 PID A6: $rawKm km")
                    _vehicleOdometerKm.value = rawKm
                    return rawKm
                }
            }

            // Step 2: Enhanced Manufacturer Mode 22 / Mode 21 fallback
            val make = _vehicleDetails.value?.uppercase() ?: ""
            val vin = _vehicleVin.value?.uppercase() ?: ""
            val wmi = if (vin.length >= 3) vin.substring(0, 3) else ""

            val isFord = make.contains("FORD") || make.contains("LINCOLN") || make.contains("MERCURY") ||
                    wmi.startsWith("1F") || wmi.startsWith("2F") || wmi.startsWith("3F")
            val isGm = make.contains("GM") || make.contains("CHEVROLET") || make.contains("CHEVY") || make.contains("GMC") || make.contains("BUICK") || make.contains("CADILLAC") ||
                    wmi.startsWith("1G") || wmi.startsWith("2G") || wmi.startsWith("3G")
            val isToyota = make.contains("TOYOTA") || make.contains("LEXUS") || make.contains("SCION") ||
                    wmi.startsWith("JT") || wmi.startsWith("2T") || wmi.startsWith("4T")
            val isHonda = make.contains("HONDA") || make.contains("ACURA") || wmi.startsWith("1H") || wmi.startsWith("2H") || wmi.startsWith("JH")
            val isChrysler = make.contains("CHRYSLER") || make.contains("DODGE") || make.contains("JEEP") || make.contains("RAM") ||
                    wmi.startsWith("1C") || wmi.startsWith("2C") || wmi.startsWith("3C")
            val isNissan = make.contains("NISSAN") || make.contains("INFINITI") || wmi.startsWith("JN") || wmi.startsWith("1N")
            val isVag = make.contains("VOLKSWAGEN") || make.contains("AUDI") || make.contains("VW") || wmi.startsWith("WV") || wmi.startsWith("WA")

            var decodedKm: Double? = null

            when {
                isFord -> {
                    // On Ford (including 2004-2008 F-150 and newer models), the master odometer is stored in the
                    // Instrument Cluster (IC / HEC) accessed via CAN header 720 (Mode 22 PIDs 404C, DD01).
                    // We query the cluster (720) first, then fallback to PCM (7E0).
                    val fordOdoQueries = listOf(
                        Pair("720", "22404C"), // 2004-2008 F-150 HEC cluster odometer (0.1 km)
                        Pair("720", "22DD01"), // 2009+ Ford IPC cluster odometer
                        Pair("7E0", "22DD01"), // Ford PCM odometer
                        Pair("7E0", "22404C")
                    )

                    for ((header, cmd) in fordOdoQueries) {
                        sendAtCommand("AT SH $header", 100)
                        val resp = sendAtCommand(cmd, 350)
                        val pList = ObdParser.extractPayloads(resp)
                        val pBytes = cmd.substring(2).chunked(2).map { it.toInt(16) }
                        val data = ObdParser.findResponse(pList, 0x62, pBytes[0], pBytes[1])
                        if (data != null && data.isNotEmpty()) {
                            val km = when {
                                data.size >= 4 -> {
                                    val raw = ((data[0].toLong() shl 24) or (data[1].toLong() shl 16) or (data[2].toLong() shl 8) or data[3].toLong()).toDouble()
                                    if (cmd.endsWith("404C")) raw * 0.1 else if (raw > 1_500_000.0) raw * 0.1 else raw
                                }
                                data.size >= 3 -> {
                                    val raw = ((data[0].toLong() shl 16) or (data[1].toLong() shl 8) or data[2].toLong()).toDouble()
                                    if (cmd.endsWith("404C")) raw * 0.1 else raw
                                }
                                else -> 0.0
                            }
                            if (km in 10.0..1_500_000.0) {
                                decodedKm = km
                                Log.i(TAG, "Decoded Ford Odometer via Header $header $cmd: $km km (${(km * 0.621371).toInt()} miles)")
                                break
                            }
                        }
                    }
                }
                isGm -> {
                    for (header in listOf("7E0", "7E2")) {
                        sendAtCommand("AT SH $header", 100)
                        val resp = sendAtCommand("22115C", 350)
                        val pList = ObdParser.extractPayloads(resp)
                        val data = ObdParser.findResponse(pList, 0x62, 0x11, 0x5C)
                        if (data != null && data.size >= 4) {
                            val km = ((data[0].toLong() shl 24) or (data[1].toLong() shl 16) or (data[2].toLong() shl 8) or data[3].toLong()).toDouble() / 64.0
                            if (km in 1.0..1_500_000.0) { decodedKm = km; break }
                        }
                    }
                }
                isToyota -> {
                    sendAtCommand("AT SH 7E0", 100)
                    for (cmd in listOf("211626", "220200")) {
                        val resp = sendAtCommand(cmd, 350)
                        val pList = ObdParser.extractPayloads(resp)
                        val data = if (cmd.startsWith("21")) ObdParser.findResponse(pList, 0x61, 0x16, 0x26) else ObdParser.findResponse(pList, 0x62, 0x02, 0x00)
                        if (data != null && data.size >= 3) {
                            val km = ((data[0].toLong() shl 16) or (data[1].toLong() shl 8) or data[2].toLong()).toDouble()
                            if (km in 1.0..1_500_000.0) { decodedKm = km; break }
                        }
                    }
                }
                isHonda -> {
                    sendAtCommand("AT SH 7E0", 100)
                    val resp = sendAtCommand("222202", 350)
                    val pList = ObdParser.extractPayloads(resp)
                    val data = ObdParser.findResponse(pList, 0x62, 0x22, 0x02)
                    if (data != null && data.size >= 3) {
                        val km = ((data[0].toLong() shl 16) or (data[1].toLong() shl 8) or data[2].toLong()).toDouble()
                        if (km in 1.0..1_500_000.0) decodedKm = km
                    }
                }
                isChrysler -> {
                    for (header in listOf("7E0", "7B0")) {
                        sendAtCommand("AT SH $header", 100)
                        val resp = sendAtCommand("2201B4", 350)
                        val pList = ObdParser.extractPayloads(resp)
                        val data = ObdParser.findResponse(pList, 0x62, 0x01, 0xB4)
                        if (data != null) {
                            if (data.size >= 4) {
                                val km = ((data[0].toLong() shl 24) or (data[1].toLong() shl 16) or (data[2].toLong() shl 8) or data[3].toLong()).toDouble() / 10.0
                                if (km in 1.0..1_500_000.0) { decodedKm = km; break }
                            } else if (data.size >= 3) {
                                val km = ((data[0].toLong() shl 16) or (data[1].toLong() shl 8) or data[2].toLong()).toDouble()
                                if (km in 1.0..1_500_000.0) { decodedKm = km; break }
                            }
                        }
                    }
                }
                isVag -> {
                    for (header in listOf("7E0", "714")) {
                        sendAtCommand("AT SH $header", 100)
                        val resp = sendAtCommand("220102", 350)
                        val pList = ObdParser.extractPayloads(resp)
                        val data = ObdParser.findResponse(pList, 0x62, 0x01, 0x02)
                        if (data != null) {
                            val km = if (data.size >= 4) {
                                ((data[0].toLong() shl 24) or (data[1].toLong() shl 16) or (data[2].toLong() shl 8) or data[3].toLong()).toDouble()
                            } else if (data.size >= 3) {
                                ((data[0].toLong() shl 16) or (data[1].toLong() shl 8) or data[2].toLong()).toDouble()
                            } else 0.0
                            if (km in 1.0..1_500_000.0) { decodedKm = km; break }
                        }
                    }
                }
                isNissan -> {
                    sendAtCommand("AT SH 7E0", 100)
                    val resp = sendAtCommand("210009", 350)
                    val pList = ObdParser.extractPayloads(resp)
                    val data = ObdParser.findResponse(pList, 0x61, 0x00, 0x09)
                    if (data != null && data.size >= 3) {
                        val km = ((data[0].toLong() shl 16) or (data[1].toLong() shl 8) or data[2].toLong()).toDouble()
                        if (km in 1.0..1_500_000.0) decodedKm = km
                    }
                }
            }

            // Restore Functional Broadcast Header for regular PID polling
            sendAtCommand("AT SH 7DF", 80)

            if (decodedKm != null) {
                Log.i(TAG, "Decoded Odometer via Enhanced Mode 22: $decodedKm km")
                _vehicleOdometerKm.value = decodedKm
            } else {
                Log.i(TAG, "Vehicle Odometer reading not available across standard or Mode 22 PIDs.")
            }
            decodedKm
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching vehicle odometer", e)
            try { sendAtCommand("AT SH 7DF", 80) } catch (_: Exception) {}
            null
        } finally {
            if (wasPolling) {
                isPollingPaused = false
                startPollingLoop()
            }
        }
    }

    fun initObdbRepository(context: android.content.Context) {
        if (obdbRepository == null) {
            val repo = ObdbRepository(context)
            obdbRepository = repo
            ioScope.launch {
                repo.refreshCatalogIfDue()
            }
        }
    }

    suspend fun loadObdbForVehicle(make: String, model: String, year: Int): ObdbRepository.LoadResult? {
        val repo = obdbRepository ?: return null
        val res = repo.loadSignalset(make, model, year)
        _obdbLoadedResult.value = res
        return res
    }

    suspend fun readObdbSignals(): Map<String, Any?> {
        if (isDemoMode) {
            _obdbSignalResults.value = emptyMap()
            return emptyMap()
        }
        val loaded = (_obdbLoadedResult.value as? ObdbRepository.LoadResult.Loaded) ?: return emptyMap()
        // Wait if an active CAN broadcast sniff is running so we never collide on the serial stream
        var sniffWait = 0
        while ((_isSniffingCan.value || canSniffActive) && sniffWait < 50) {
            delay(200)
            sniffWait++
        }
        val wasPolling = pollingJob?.isActive == true
        isPollingPaused = true
        pollingJob?.cancel()

        val resultsMap = mutableMapOf<String, Any?>()

        return try {
            if (wasPolling) delay(200)
            restoreStandardObdMode()

            val runner = ObdbRunner { cmd -> sendAtCommand(cmd, 100) }
            val commandsByHeader = loaded.commands.groupBy { it.hdr }
            val allSignalsById = loaded.commands.flatMap { it.signals }.associateBy { it.id }
            val updatedOpendbc = LinkedHashMap(_opendbcSignals.value)

            for ((hdr, cmds) in commandsByHeader) {
                var consecutiveNulls = 0
                for (cmd in cmds) {
                    val sigVals = runner.read(cmd)
                    if (sigVals == null || sigVals.values.all { it == null }) {
                        consecutiveNulls++
                        if (consecutiveNulls >= 3) {
                            Log.i(TAG, "Header $hdr returned 3 consecutive nulls, skipping remaining commands for this header.")
                            break
                        }
                    } else {
                        consecutiveNulls = 0
                        for ((sigId, valObj) in sigVals) {
                            if (valObj != null) {
                                resultsMap[sigId] = valObj
                                val numVal = (valObj as? Number)?.toDouble()
                                val sigMeta = allSignalsById[sigId]
                                if (numVal != null) {
                                    // Update vehicle odometer if an OBDb odometer signal was read
                                    if ((sigId.endsWith("_ODO") || sigMeta?.name?.equals("Odometer", ignoreCase = true) == true) && numVal in 10.0..1_500_000.0) {
                                        _vehicleOdometerKm.value = numVal
                                    }
                                    // Bridge OBDb chassis/dynamics signals into _opendbcSignals if present
                                    val dynMap: Pair<String, Pair<Double, String>>? = when {
                                        sigId.contains("STEER_ANGLE") || sigId.contains("STEERING_ANGLE") -> "Steering Angle" to (numVal to "deg")
                                        sigId.contains("LAT_G") -> "Lateral G" to (numVal to "g")
                                        sigId.contains("LNG_G") || sigId.contains("LONG_G") -> "Longitudinal G" to (numVal to "g")
                                        sigId.contains("YAW_RATE") -> "Yaw Rate" to (numVal to "deg/s")
                                        sigId.contains("TIRE_FL_SPD") || sigId.contains("FL_WHEEL_VSS") -> "Wheel FL" to ((numVal * 0.621371) to "mph")
                                        sigId.contains("TIRE_FR_SPD") || sigId.contains("FR_WHEEL_VSS") -> "Wheel FR" to ((numVal * 0.621371) to "mph")
                                        sigId.contains("TIRE_RL_SPD") || sigId.contains("RL_WHEEL_VSS") -> "Wheel RL" to ((numVal * 0.621371) to "mph")
                                        sigId.contains("TIRE_RR_SPD") || sigId.contains("RR_WHEEL_VSS") -> "Wheel RR" to ((numVal * 0.621371) to "mph")
                                        else -> null
                                    }
                                    if (dynMap != null) {
                                        val (dynName, valAndUnit) = dynMap
                                        val (v, u) = valAndUnit
                                        updatedOpendbc[dynName] = OpendbcManager.DecodedOpendbcSignal(
                                            name = dynName,
                                            value = v,
                                            formattedValue = OpendbcManager.formatSignalValue(v, u),
                                            unit = u
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (updatedOpendbc.size > _opendbcSignals.value.size) {
                _opendbcSignals.value = updatedOpendbc
            }

            runner.restoreHeader()
            _obdbSignalResults.value = resultsMap
            resultsMap
        } catch (e: Exception) {
            Log.e(TAG, "Error running OBDb signal scan", e)
            emptyMap()
        } finally {
            restoreStandardObdMode()
            isPollingPaused = false
            if (wasPolling && pollingJob?.isActive != true) startPollingLoop()
        }
    }

    /**
     * Parses multiline/multi-frame ISO 15765-4 CAN Mode 09 hex responses into a 17-character ASCII VIN string.
     */
    private fun parseVinFromResponse(response: String): String? {
        if (response.isBlank() || response.contains("NODATA") || response.contains("ERROR") || response.contains("UNABLE")) {
            return null
        }

        val lines = response.split("\r", "\n")
        val hexBytesList = mutableListOf<String>()

        for (rawLine in lines) {
            val cleaned = rawLine.replace(" ", "").uppercase()
            if (cleaned.contains(">") || cleaned.contains("0902") || cleaned.contains("SEARCHING")) {
                continue
            }

            var lineHex = cleaned
            if (lineHex.contains(":")) {
                lineHex = lineHex.substringAfter(":")
            }

            val idx4902 = lineHex.indexOf("4902")
            if (idx4902 != -1) {
                var payload = lineHex.substring(idx4902 + 4)
                if (payload.length >= 2 && payload.substring(0, 2).toIntOrNull(16) != null) {
                    payload = payload.substring(2)
                }
                extractHexBytePairs(payload, hexBytesList)
            } else {
                extractHexBytePairs(lineHex, hexBytesList)
            }
        }

        val asciiBuilder = StringBuilder()
        for (hexPair in hexBytesList) {
            try {
                val code = hexPair.toInt(16)
                if (code in 32..126) {
                    val c = code.toChar()
                    if (c.isLetterOrDigit() || c == '-') {
                        asciiBuilder.append(c)
                    }
                }
            } catch (_: Exception) {}
        }

        val decodedStr = asciiBuilder.toString()
        Log.d(TAG, "Extracted ASCII candidate string from Mode 09: $decodedStr")

        val vinRegex = Regex("[A-HJ-NPR-Z0-9]{17}")
        val match = vinRegex.find(decodedStr)
        if (match != null) {
            return match.value
        }

        val alphaNumOnly = decodedStr.filter { it.isLetterOrDigit() }
        return if (alphaNumOnly.length >= 17) {
            alphaNumOnly.substring(alphaNumOnly.length - 17)
        } else null
    }

    private fun extractHexBytePairs(hexStr: String, outList: MutableList<String>) {
        var i = 0
        while (i + 2 <= hexStr.length) {
            val pair = hexStr.substring(i, i + 2)
            if (pair.matches(Regex("^[0-9A-F]{2}$"))) {
                outList.add(pair)
            }
            i += 2
        }
    }

    /**
     * Decodes Make, Model, and Model Year from standard 17-character VIN.
     */
    fun decodeVinDetails(vin: String): String {
        if (vin.length != 17) return "VIN: $vin"

        val wmi = vin.substring(0, 3).uppercase()
        val make = when {
            wmi.startsWith("1FA") || wmi.startsWith("1FT") || wmi.startsWith("3FA") || wmi.startsWith("2FT") || wmi.startsWith("1F") || wmi.startsWith("2F") || wmi.startsWith("3F") -> "Ford"
            wmi.startsWith("1G") || wmi.startsWith("2G") || wmi.startsWith("3G") -> "GM"
            wmi.startsWith("1J") || wmi.startsWith("1C") || wmi.startsWith("2C") || wmi.startsWith("3C") || wmi.startsWith("1D") || wmi.startsWith("2D") || wmi.startsWith("3D") || wmi.startsWith("1B") || wmi.startsWith("3B") -> "Chrysler"
            wmi.startsWith("1H") || wmi.startsWith("2H") || wmi.startsWith("JH") || wmi.startsWith("5J") -> "Honda"
            wmi.startsWith("2T") || wmi.startsWith("4T") || wmi.startsWith("5T") || wmi.startsWith("5Y") || wmi.startsWith("JT") || wmi.startsWith("3TM") -> "Toyota"
            wmi.startsWith("3V") || wmi.startsWith("1V") || wmi.startsWith("WV") || wmi.startsWith("WA") || wmi.startsWith("WP") -> "Volkswagen"
            wmi.startsWith("WB") || wmi.startsWith("4US") || wmi.startsWith("5UX") -> "BMW"
            wmi.startsWith("WD") || wmi.startsWith("4JG") || wmi.startsWith("55") -> "Mercedes-Benz"
            wmi.startsWith("JN") || wmi.startsWith("1N") || wmi.startsWith("5N") -> "Nissan"
            wmi.startsWith("KM") || wmi.startsWith("KN") -> "Hyundai"
            wmi.startsWith("4S") || wmi.startsWith("JF") -> "Subaru"
            wmi.startsWith("JM") -> "Mazda"
            else -> "Vehicle"
        }

        val model = when {
            make == "Ford" -> "F-150"
            make == "Toyota" -> "Prius"
            else -> "Vehicle"
        }

        val yearChar = vin[9].uppercaseChar()
        val year = when (yearChar) {
            'A' -> "2010"; 'B' -> "2011"; 'C' -> "2012"; 'D' -> "2013"; 'E' -> "2014"; 'F' -> "2015"
            'G' -> "2016"; 'H' -> "2017"; 'J' -> "2018"; 'K' -> "2019"; 'L' -> "2020"; 'M' -> "2021"
            'N' -> "2022"; 'P' -> "2023"; 'R' -> "2024"; 'S' -> "2025"; 'T' -> "1996"; 'V' -> "1997"
            'W' -> "1998"; 'X' -> "1999"; 'Y' -> "2000"; '1' -> "2001"; '2' -> "2002"; '3' -> "2003"
            '4' -> "2004"; '5' -> "2005"; '6' -> "2006"; '7' -> "2007"; '8' -> "2008"; '9' -> "2009"
            else -> "$yearChar"
        }

        return "$make • $model • $year • VIN: $vin"
    }

    // ---------------------------------------------------------------------------------------------
    // Passive CAN bus sniffer (diagnostic). Listens with ATMA (monitor all) and reports which
    // broadcast CAN IDs actually reach the OBD-II port on this vehicle. Receive-only: nothing is
    // ever transmitted to the car. Used to find out what Advanced Dynamics can really decode.
    // ---------------------------------------------------------------------------------------------
    data class CanIdStat(
        val id: String,
        val count: Int,
        val distinctPayloads: Int,
        val samplePayloads: List<String>
    )

    data class CanSniffReport(
        val protocol: String,
        val durationMs: Long,
        val totalFrames: Int,
        val bufferFullEvents: Int,
        val ids: List<CanIdStat>
    ) {
        fun toDisplayText(maxIds: Int = 14): String {
            val sb = StringBuilder()
            sb.append("CAN scan · protocol: ").append(protocol.ifBlank { "unknown" }).append('\n')
            sb.append("$totalFrames frames in ${durationMs / 1000.0}s · ${ids.size} distinct IDs")
            if (bufferFullEvents > 0) sb.append(" · high-speed bus (${bufferFullEvents}x raw burst saturation; hardware filter active for live dynamics)")
            sb.append('\n')
            if (totalFrames == 0) {
                sb.append("No broadcast frames reached the OBD-II port. Possible causes: ignition/READY is off, ")
                sb.append("the adapter does not support monitor mode (ATMA), or this vehicle's gateway only ")
                sb.append("passes diagnostic request/response traffic to the port.")
                return sb.toString()
            }
            val hints = setOf("024", "025", "0AA", "0B4", "1D2", "1C4", "224", "245", "2E4", "260")
            val seenHints = ids.map { it.id }.filter { it in hints }
            if (seenHints.isNotEmpty()) sb.append("Chassis/powertrain IDs commonly seen on Toyotas: ${seenHints.joinToString()}\n")
            ids.take(maxIds).forEach { s ->
                sb.append(s.id.padEnd(4)).append(' ')
                    .append("x${s.count}".padEnd(6)).append(' ')
                    .append(s.samplePayloads.lastOrNull() ?: "")
                    .append(if (s.distinctPayloads > 1) "  (varies)" else "  (static)")
                    .append('\n')
            }
            if (ids.size > maxIds) sb.append("… and ${ids.size - maxIds} more IDs (see debug log)")
            return sb.toString().trimEnd()
        }
    }

    private val _canSniffReport = MutableStateFlow<CanSniffReport?>(null)
    val canSniffReport: StateFlow<CanSniffReport?> = _canSniffReport.asStateFlow()

    private val _isSniffingCan = MutableStateFlow(false)
    val isSniffingCan: StateFlow<Boolean> = _isSniffingCan.asStateFlow()

    // While true, the stream reader must not interpret raw monitor-mode frames as OBD responses.
    @Volatile private var canSniffActive = false
    @Volatile private var isStnAdapter = false

    private class CanAcc {
        var count = 0
        val distinct = HashSet<String>()
        val samples = ArrayDeque<String>()
    }

    /**
     * Programs STN hardware CAN pass filters (`STFAC` + `STFPA <id>, 7FF`) on vLinker / OBDLink
     * adapters so `STM` streams only the target `opendbc` CAN IDs without overflowing the UART buffer.
     */
    private suspend fun configureStnFiltersForProfile(profile: OpendbcManager.VehicleDbcProfile): Boolean {
        if (!isStnAdapter || profile.filterCanIds.isEmpty()) return false
        val clearResp = sendAtCommand("STFAC", 40)
        if (clearResp.contains("?")) return false
        var addedCount = 0
        for (hexId in profile.filterCanIds) {
            val cleanId = hexId.trim().uppercase().padStart(3, '0')
            if (cleanId.length == 3) {
                val r = sendAtCommand("STFPA $cleanId, 7FF", 30)
                if (!r.contains("?")) addedCount++
            }
        }
        return addedCount > 0
    }

    /**
     * Restores standard OBD-II request/response filtering after STN `STM` or `ATMA` monitoring,
     * or after non-standard ECU header queries.
     *
     * CRITICAL HARDWARE FIX: Adding pass filters with `STFPA` disables STN Automatic Filtering (`STFA`).
     * Calling `STFAC` alone clears all pass filters while leaving `STFA` disabled, which causes the
     * STN11xx / vLinker CAN controller to drop 100% of incoming ECU replies (`7E8`, `7B8`, `7C8`) and
     * return `NO DATA` for all subsequent Mode 01, Mode 06, and Custom PID commands!
     * Sending `STFA` + `AT CRA` + `AT AR` re-enables automatic OBD receive filtering immediately.
     */
    private suspend fun restoreStandardObdMode() {
        if (isStnAdapter) {
            sendAtCommand("STFAC", 40)
            sendAtCommand("STFA", 40)
        }
        sendAtCommand("AT CRA", 40)
        sendAtCommand("AT AR", 40)
        sendAtCommand("AT H0", 40)
        sendAtCommand("AT S1", 40)
        sendAtCommand("AT SH 7DF", 40)
    }

    private fun writeRaw(text: String) {
        val bytes = text.toByteArray(Charsets.US_ASCII)
        if (isUsbConnection && usbSerialPort != null) {
            try {
                usbSerialPort?.write(bytes, 1000)
            } catch (e: Exception) {
                Log.e(TAG, "USB raw write error", e)
            }
        } else {
            try {
                outputStream?.write(bytes)
                outputStream?.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Bluetooth raw write error", e)
            }
        }
    }

    /** Parses "0B4 00 12 AB ..." (headers on, spaces on). Returns id to payload or null. */
    private fun parseCanFrameLine(line: String): Pair<String, String>? {
        val tokens = line.trim().uppercase().split(' ').filter { it.isNotEmpty() }
        if (tokens.size < 2) return null
        val id = tokens[0]
        fun isHex(s: String) = s.all { it in '0'..'9' || it in 'A'..'F' }
        if ((id.length != 3 && id.length != 8) || !isHex(id)) return null
        val data = tokens.drop(1)
        if (data.size > 8 || !data.all { it.length == 2 && isHex(it) }) return null
        return id to data.joinToString(" ")
    }

    /**
     * Cleanly exits ELM327/STN `ATMA`/`STM` monitor mode while keeping [canSniffActive] true until
     * all in-flight buffered USB/Bluetooth CAN frames have drained and the `>` prompt arrives.
     * NEVER sends `\r` because `\r` at an already-stopped `>` prompt repeats the last command (`ATMA`)!
     */
    private suspend fun stopAtmaAndFlushBuffer() {
        try {
            canSniffActive = true
            if (!promptReceived) {
                // Send a space character (not '\r') to abort active ATMA/STM without triggering repeat-last-command
                writeRaw(" ")
                val deadline = System.currentTimeMillis() + 450L
                while (System.currentTimeMillis() < deadline && !promptReceived) {
                    delay(25)
                }
            }
            // Settle delay for any trailing USB FIFO bytes
            delay(40)
            clearResponseBuffer()
        } catch (e: Exception) {
            Log.w(TAG, "stopAtmaAndFlushBuffer issue: ${e.message}")
        } finally {
            canSniffActive = false
            clearResponseBuffer()
        }
    }

    suspend fun sniffCanBus(totalDurationMs: Long = 8000L): CanSniffReport? {
        if (isDemoMode || _isSniffingCan.value) return null
        if (_connectionState.value !is ConnectionState.Connected) return null

        _isSniffingCan.value = true
        val wasPolling = pollingJob?.isActive == true
        isPollingPaused = true
        pollingJob?.cancel()

        val started = System.currentTimeMillis()
        val stats = LinkedHashMap<String, CanAcc>()
        val decodedMap = LinkedHashMap(_opendbcSignals.value)
        var activeProfile = _activeOpendbcProfile.value
            ?: OpendbcManager.findProfileForVehicle(_vehicleVin.value ?: "", _vehicleDetails.value ?: "")
                ?.also { _activeOpendbcProfile.value = it }
        var total = 0
        var bufferFull = 0
        var protocol = ""
        var report: CanSniffReport? = null
        var lastUiEmitMs = 0L

        fun drain(forceEmit: Boolean = false) {
            val snapshot = drainResponseBuffer()
            if (snapshot.isEmpty()) {
                if (forceEmit && decodedMap.isNotEmpty()) {
                    _opendbcSignals.value = LinkedHashMap(decodedMap)
                }
                return
            }
            if (snapshot.contains("BUFFER FULL", ignoreCase = true)) bufferFull++
            val batchFrames = mutableListOf<Pair<String, String>>()
            for (line in snapshot.split('\n', '\r')) {
                val frame = parseCanFrameLine(line) ?: continue
                total++
                val acc = stats.getOrPut(frame.first) { CanAcc() }
                acc.count++
                if (acc.distinct.size < 64) acc.distinct.add(frame.second)
                acc.samples.addLast(frame.second)
                if (acc.samples.size > 3) acc.samples.removeFirst()
                batchFrames.add(frame)
            }
            if (batchFrames.isNotEmpty()) {
                val observedIds = stats.keys.mapNotNull { it.toIntOrNull(16) }.toSet()
                val resolved = OpendbcManager.resolveBestProfile(
                    vin = _vehicleVin.value ?: "",
                    make = _vehicleDetails.value ?: "",
                    observedCanIds = observedIds
                )
                if (resolved != null && resolved.platformName != activeProfile?.platformName) {
                    activeProfile = resolved
                    _activeOpendbcProfile.value = resolved
                }
                val prof = activeProfile
                if (prof != null) {
                    var decodedAny = false
                    for (frame in batchFrames) {
                        val sigs = OpendbcManager.decodeCanFrame(prof, "${frame.first} ${frame.second}")
                        if (sigs.isNotEmpty()) {
                            for (sig in sigs) {
                                decodedMap[sig.name] = sig
                            }
                            decodedAny = true
                        }
                    }
                    val now = System.currentTimeMillis()
                    if (decodedAny && (forceEmit || now - lastUiEmitMs >= 120L)) {
                        lastUiEmitMs = now
                        _opendbcSignals.value = LinkedHashMap(decodedMap)
                    }
                }
            } else if (forceEmit && decodedMap.isNotEmpty()) {
                _opendbcSignals.value = LinkedHashMap(decodedMap)
            }
        }

        try {
            if (wasPolling) delay(250)
            sendAtCommand("AT", 60)
            protocol = sendAtCommand("AT DP", 120).replace("\r", " ").replace("\n", " ").trim()
            sendAtCommand("AT S1", 60)
            sendAtCommand("AT H1", 60)

            // For vehicles whose gateway limits broadcast traffic on pin 6/14 or when running a manual scan,
            // we send "ATMA\r" so we monitor all broadcast traffic. If no frames are seen or buffer full occurs,
            // STN hardware pass filters can still be used.
            val monitorCmd = "ATMA\r"
            val effectiveDurationMs = totalDurationMs
            canSniffActive = true
            responseMutex.withLock {
                val deadline = System.currentTimeMillis() + effectiveDurationMs
                clearResponseBuffer()
                writeRaw(monitorCmd)
                var lastProgressFrames = 0
                var lastProgressTime = System.currentTimeMillis()

                while (System.currentTimeMillis() < deadline) {
                    delay(70)
                    drain(forceEmit = false)

                    val now = System.currentTimeMillis()
                    if (promptReceived) {
                        drain(forceEmit = false)
                        if (now + 200L < deadline) {
                            delay(35)
                            clearResponseBuffer()
                            writeRaw(monitorCmd)
                            lastProgressTime = System.currentTimeMillis()
                        } else {
                            break
                        }
                    } else if (now - lastProgressTime >= 500L) {
                        if (total == lastProgressFrames && now + 250L < deadline) {
                            writeRaw(" ")
                            delay(40)
                            clearResponseBuffer()
                            writeRaw(monitorCmd)
                        }
                        lastProgressFrames = total
                        lastProgressTime = System.currentTimeMillis()
                    }
                }
                drain(forceEmit = true)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "CAN sniff failed", e)
        } finally {
            withContext(kotlinx.coroutines.NonCancellable) {
                try {
                    stopAtmaAndFlushBuffer()
                    restoreStandardObdMode()
                } catch (e: Exception) {
                    Log.w(TAG, "CAN sniff cleanup issue", e)
                } finally {
                    canSniffActive = false
                }
                if (decodedMap.isNotEmpty()) {
                    _opendbcSignals.value = LinkedHashMap(decodedMap)
                }
                val ids = stats.entries
                    .sortedByDescending { it.value.count }
                    .map { CanIdStat(it.key, it.value.count, it.value.distinct.size, it.value.samples.toList()) }
                val finished = CanSniffReport(protocol, System.currentTimeMillis() - started, total, bufferFull, ids)
                report = finished
                _canSniffReport.value = finished
                if (total == 0) {
                    if (!isVehiclePre2018(_vehicleVin.value ?: "", _vehicleDetails.value ?: "")) {
                        isPassiveCanBlockedOnGateway = true
                        Log.i(TAG, "CAN sniff completed with 0 frames. Marking gateway as blocking passive broadcasts to OBD port.")
                    } else {
                        Log.i(TAG, "CAN sniff completed with 0 frames on pre-2018 vehicle (${_vehicleDetails.value}) - not gateway blocked.")
                    }
                } else {
                    isPassiveCanBlockedOnGateway = false
                    consecutiveEmptyCanSamples = 0
                }
                logElmExchange("CAN SNIFF", finished.toDisplayText(40).replace("\n", " | "))
                isPollingPaused = false
                _isSniffingCan.value = false
                if (wasPolling && pollingJob?.isActive != true && _connectionState.value is ConnectionState.Connected) {
                    startPollingLoop()
                }
            }
        }
        return report
    }

    /**
     * Determines whether a vehicle is pre-2018. Pre-2018 vehicles do not have FCA Secure Gateway (SGW)
     * modules blocking passive CAN access on the OBD-II port.
     */
    private fun isVehiclePre2018(vin: String, details: String): Boolean {
        val yearMatch = Regex("""\b(19\d\d|20\d\d)\b""").find(details)
        if (yearMatch != null) {
            val yr = yearMatch.value.toIntOrNull()
            if (yr != null && yr < 2018) return true
        }
        if (vin.length == 17) {
            val myChar = vin[9].uppercaseChar()
            if (myChar in 'A'..'H' || myChar in '1'..'9' || myChar == 'Y') {
                return true
            }
        }
        return false
    }

    /**
     * Performs a fast passive CAN broadcast sample (default 350ms) inside the polling loop
     * when connected via a high-speed CAN adapter, using STN hardware pass filters (`STM`) when
     * available so target `opendbc` signals (steering angle, wheel speeds, brake, pedal) stream
     * without overflowing the adapter buffer.
     */
    private suspend fun sampleCanBroadcastInline(durationMs: Long = 350L) {
        if (isDemoMode || _isSniffingCan.value || !_isHighSpeedCanCapable.value || isPassiveCanBlockedOnGateway) return
        if (_connectionState.value !is ConnectionState.Connected) return

        val vin = _vehicleVin.value ?: ""
        val details = _vehicleDetails.value ?: ""
        val isPre2018 = isVehiclePre2018(vin, details)

        val capturedFrames = mutableListOf<Pair<String, String>>()
        val observedIds = mutableSetOf<Int>()
        val decodedMap = LinkedHashMap(_opendbcSignals.value)
        var activeProfile = _activeOpendbcProfile.value
            ?: OpendbcManager.findProfileForVehicle(vin, details)
                ?.also { _activeOpendbcProfile.value = it }
        var lastUiEmitMs = 0L
        var usedStnFilter = false

        fun drainBurst(forceEmit: Boolean = false) {
            val snapshot = drainResponseBuffer()
            if (snapshot.isEmpty()) return
            val batchFrames = mutableListOf<Pair<String, String>>()
            for (line in snapshot.split('\n', '\r')) {
                val frame = parseCanFrameLine(line) ?: continue
                capturedFrames.add(frame)
                batchFrames.add(frame)
                frame.first.toIntOrNull(16)?.let { observedIds.add(it) }
            }
            if (batchFrames.isNotEmpty()) {
                val resolved = OpendbcManager.resolveBestProfile(
                    vin = vin,
                    make = details,
                    observedCanIds = observedIds
                )
                if (resolved != null && resolved.platformName != activeProfile?.platformName) {
                    activeProfile = resolved
                    _activeOpendbcProfile.value = resolved
                }
                val prof = activeProfile
                if (prof != null) {
                    var decodedAny = false
                    for (frame in batchFrames) {
                        val sigs = OpendbcManager.decodeCanFrame(prof, "${frame.first} ${frame.second}")
                        if (sigs.isNotEmpty()) {
                            for (sig in sigs) {
                                decodedMap[sig.name] = sig
                            }
                            decodedAny = true
                        }
                    }
                    if (decodedAny) {
                        profConfirmedOnBus = true
                    }
                    val now = System.currentTimeMillis()
                    if (decodedAny && (forceEmit || now - lastUiEmitMs >= 100L)) {
                        lastUiEmitMs = now
                        _opendbcSignals.value = LinkedHashMap(decodedMap)
                    }
                }
            }
        }

        try {
            sendAtCommand("AT S1", 40)
            sendAtCommand("AT H1", 40)
            val prof = activeProfile
            // Only apply STN hardware pass filters if the profile has been confirmed with matching CAN frames on this bus!
            // If unconfirmed, do an unfiltered ATMA census to discover actual CAN IDs on the bus without filtering them out.
            if (isStnAdapter && prof != null && profConfirmedOnBus) {
                usedStnFilter = configureStnFiltersForProfile(prof)
            }
            val monitorCmd = if (usedStnFilter) "STM\r" else "ATMA\r"
            canSniffActive = true
            responseMutex.withLock {
                clearResponseBuffer()
                writeRaw(monitorCmd)
                val burstEnd = System.currentTimeMillis() + durationMs
                while (System.currentTimeMillis() < burstEnd) {
                    delay(60)
                    drainBurst(forceEmit = false)
                    if (promptReceived) {
                        drainBurst(forceEmit = false)
                        if (System.currentTimeMillis() + 90L < burstEnd) {
                            delay(25)
                            clearResponseBuffer()
                            writeRaw(monitorCmd)
                        } else {
                            break
                        }
                    }
                }
                drainBurst(forceEmit = true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Inline CAN broadcast sample interrupted: ${e.message}")
        } finally {
            try {
                stopAtmaAndFlushBuffer()
                restoreStandardObdMode()
            } catch (_: Exception) {
                canSniffActive = false
            }
        }

        if (capturedFrames.isEmpty()) {
            if (usedStnFilter) {
                // The profile-specific STN filter yielded 0 frames — likely wrong profile / wrong platform generation!
                Log.w(TAG, "STN pass filter for ${activeProfile?.platformName} returned 0 frames. Reverting profile to perform unfiltered ATMA census on next pass.")
                profConfirmedOnBus = false
                _activeOpendbcProfile.value = null
            } else if (!isPre2018) {
                consecutiveEmptyCanSamples++
                if (consecutiveEmptyCanSamples >= 2) {
                    isPassiveCanBlockedOnGateway = true
                    Log.i(TAG, "Gateway detected blocking passive broadcast CAN frames on OBD port (2 consecutive 0-frame samples). Backing off inline sniffing to preserve 100% OBD bandwidth.")
                }
            } else {
                Log.d(TAG, "Pre-2018 vehicle ($details, VIN: $vin) returned 0 broadcast frames on OBD pins - no gateway blocking applied.")
            }
        } else {
            consecutiveEmptyCanSamples = 0
            isPassiveCanBlockedOnGateway = false
        }

        if (decodedMap.isNotEmpty() && decodedMap != _opendbcSignals.value) {
            _opendbcSignals.value = LinkedHashMap(decodedMap)
        }
    }

    /**
     * Sends an AT command and waits for the prompt '>' response asynchronously with zero polling dead time.
     */
    suspend fun sendAtCommand(command: String, delayMs: Long = 0L): String {
        if (!ReadOnlyGuard.isAllowed(command)) {
            Log.w(TAG, "BLOCKED non-read command: [$command]")
            return "BLOCKED"
        }
        return responseMutex.withLock {
            val deferred = CompletableDeferred<Unit>()
            synchronized(responseBufferLock) {
                latestResponse.setLength(0)
                promptReceived = false
                promptDeferred = deferred
            }

            val fullCommand = "$command\r"
            val bytes = fullCommand.toByteArray(Charsets.US_ASCII)
            if (isUsbConnection && usbSerialPort != null) {
                try {
                    usbSerialPort?.write(bytes, 1000)
                } catch (e: Exception) {
                    Log.e(TAG, "USB Write Error for command $command", e)
                }
            } else {
                outputStream?.write(bytes)
                outputStream?.flush()
            }

            val startTime = System.currentTimeMillis()
            var maxTimeout = COMMAND_TIMEOUT_MS
            try {
                while (!promptReceived && (System.currentTimeMillis() - startTime) < maxTimeout) {
                    val remaining = maxTimeout - (System.currentTimeMillis() - startTime)
                    if (remaining <= 0) break
                    withTimeoutOrNull(minOf(remaining, 200L)) {
                        deferred.await()
                    }
                    if (promptReceived) break
                    // SAE J1979 / ISO 15765-4: Extend timeout up to 5000ms if ECU sends 7F xx 78 (Response Pending)
                    if (hasPendingResponseInBuffer()) {
                        maxTimeout = 5000L
                    }
                }
            } catch (_: Exception) {}

            if (!promptReceived) {
                Log.w(TAG, "Command timeout for: $command")
            }

            val result = peekResponseBuffer().trim()
            Log.d(TAG, "TX: [$command] -> RX: [$result]")
            logElmExchange(command, result)
            if (delayMs > 0) {
                delay(delayMs)
            }
            result
        }
    }

    /**
     * Continuous background input stream reader state machine.
     */
    private fun startStreamReader() {
        workerJob?.cancel()
        workerJob = ioScope.launch {
            val buffer = ByteArray(2048)
            val lineBuilder = StringBuilder()
            try {
                while (isActive) {
                    val bytesRead = if (isUsbConnection && usbSerialPort != null) {
                        try {
                            usbSerialPort?.read(buffer, 200) ?: -1
                        } catch (e: Exception) { -1 }
                    } else if (inputStream != null) {
                        inputStream?.read(buffer) ?: -1
                    } else { -1 }

                    if (bytesRead <= 0) {
                        if (!isUsbConnection && inputStream == null) break
                        delay(15)
                        continue
                    }

                    for (i in 0 until bytesRead) {
                        val c = buffer[i].toInt().toChar()
                        when (c) {
                            '>' -> {
                                if (lineBuilder.isNotEmpty()) {
                                    appendResponseText(lineBuilder.toString())
                                    lineBuilder.clear()
                                }
                                promptReceived = true
                                promptDeferred?.complete(Unit)
                                if (!canSniffActive) {
                                    val fullText = peekResponseBuffer()
                                    try {
                                        processResponse(fullText)
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Error parsing response frame", e)
                                    }
                                }
                            }
                            '\r', '\n' -> {
                                if (lineBuilder.isNotEmpty()) {
                                    val line = lineBuilder.toString().trim()
                                    appendResponseText("$line\n")
                                    lineBuilder.clear()
                                }
                            }
                            else -> {
                                if (lineBuilder.length < 4096) {
                                    lineBuilder.append(c)
                                } else {
                                    lineBuilder.clear()
                                }
                            }
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Stream reader error or disconnected", e)
                disconnect()
            }
        }
    }

    /**
     * Routes incoming raw hex responses based on their service response header:
     * - Frames starting with `49` belong to Mode 09 (VIN / Vehicle Info).
     * - Frames starting with `41` belong to Mode 01 (Live Sensor Telemetry Stream).
     * - Frames starting with `43`, `47`, `4A` belong to Mode 03/07/0A (DTC Fault Codes).
     */
    private fun applyMode01Pid(pidHex: String, dataBytes: IntArray, pidDef: ObdPidDefinition?) {
        val currentMetrics = _metrics.value
        val updatedMetrics = when (pidHex) {
            "04" -> if (dataBytes.isNotEmpty()) currentMetrics.copy(engineLoad = (dataBytes[0] * 100) / 255) else currentMetrics
            "05" -> if (dataBytes.isNotEmpty()) currentMetrics.copy(coolantTemp = dataBytes[0] - 40) else currentMetrics
            "06" -> if (dataBytes.isNotEmpty()) currentMetrics.copy(fuelTrim = ((dataBytes[0] - 128) * 100) / 128) else currentMetrics
            "0C" -> if (dataBytes.size >= 2) currentMetrics.copy(rpm = ((dataBytes[0] * 256) + dataBytes[1]) / 4) else currentMetrics
            "0D" -> if (dataBytes.isNotEmpty()) currentMetrics.copy(speed = dataBytes[0]) else currentMetrics
            "0F" -> if (dataBytes.isNotEmpty()) currentMetrics.copy(intakeAirTemp = dataBytes[0] - 40) else currentMetrics
            "10" -> if (dataBytes.size >= 2) currentMetrics.copy(maf = ((dataBytes[0] * 256) + dataBytes[1]) / 100.0) else currentMetrics
            "11" -> if (dataBytes.isNotEmpty()) currentMetrics.copy(throttlePosition = (dataBytes[0] * 100) / 255) else currentMetrics
            else -> currentMetrics
        }
        _metrics.value = updatedMetrics

        val normalizedHex = pidHex.uppercase()
        val existing = activeStreamCache[normalizedHex]
        val def = existing?.pid ?: pidDef
        if (def != null && dataBytes.size >= def.bytesRequired) {
            val calculatedValue = def.parseValue?.invoke(dataBytes) ?: 0.0
            val formatted = String.format(java.util.Locale.US, "%.1f %s", calculatedValue, def.unit)
            val now = System.currentTimeMillis()

            // O(1) allocation-free circular ring buffer push
            val ring = streamRingBuffers.getOrPut(normalizedHex) { java.util.ArrayDeque(100) }
            synchronized(ring) {
                if (ring.size >= 100) {
                    ring.removeFirst()
                }
                ring.addLast(StreamSample(calculatedValue))
            }

            if (now - lastStreamUiEmitMs >= 100L) { // Smooth 10 Hz UI StateFlow throttle
                lastStreamUiEmitMs = now
                // Snapshot ring buffers into history lists ONLY when emitting to Compose UI StateFlow
                for ((hex, stream) in activeStreamCache) {
                    val buf = streamRingBuffers[hex]
                    if (buf != null) {
                        val snapshot = synchronized(buf) { buf.toList() }
                        activeStreamCache[hex] = stream.copy(history = snapshot)
                    }
                }
                val currentRingSnapshot = synchronized(ring) { ring.toList() }
                activeStreamCache[normalizedHex] = LiveStreamData(
                    pid = def,
                    formattedValue = formatted,
                    rawValue = calculatedValue,
                    history = currentRingSnapshot,
                    lastUpdated = now
                )
                _supportedStreams.value = activeStreamCache.values.sortedBy { it.pid.pidHex }
            } else {
                // High-speed serial path: update raw & formatted values with ZERO list copy / GC allocations
                activeStreamCache[normalizedHex] = (existing ?: LiveStreamData(pid = def)).copy(
                    formattedValue = formatted,
                    rawValue = calculatedValue,
                    lastUpdated = now
                )
            }
        }
    }

    private fun processResponse(fullResponse: String) {
        if (fullResponse.isBlank()) return
        if (canSniffActive) return // raw monitor-mode frames, not OBD responses
        val lines = fullResponse.split("\r", "\n")
        for (rawLine in lines) {
            val cleaned = rawLine.replace(" ", "").uppercase()
            if (cleaned.contains("NODATA") || cleaned.contains("ERROR") || cleaned.contains("UNABLE")) {
                continue
            }

            val payloads = ObdParser.extractPayloads(rawLine)
            for (payload in payloads) {
                if (payload.size >= 2 && payload[0] == 0x41) {
                    if (!_isStreamingLiveData.value) {
                        _isStreamingLiveData.value = true
                        Log.i(TAG, "First live Mode 01 telemetry frame received. isStreamingLiveData = true")
                    }

                    var offset = 1
                    var pidsInPayload = 0
                    while (offset < payload.size) {
                        val pidByte = payload[offset]
                        val pidHex = String.format("%02X", pidByte)
                        val pidDef = ObdPidRegistry.KNOWN_PIDS[pidHex]
                            ?: discoveredPidDefinitions.firstOrNull { it.pidHex.equals(pidHex, ignoreCase = true) }

                        val bytesReq = pidDef?.bytesRequired ?: (payload.size - (offset + 1))
                        if (offset + 1 + bytesReq <= payload.size && bytesReq > 0) {
                            val dataBytes = payload.copyOfRange(offset + 1, offset + 1 + bytesReq)
                            applyMode01Pid(pidHex, dataBytes, pidDef)
                            pidsInPayload++
                            offset += 1 + bytesReq
                        } else {
                            val remainingBytes = payload.copyOfRange(offset + 1, payload.size)
                            if (remainingBytes.isNotEmpty()) {
                                applyMode01Pid(pidHex, remainingBytes, pidDef)
                                pidsInPayload++
                            }
                            break
                        }
                    }
                    if (pidsInPayload > 1 && isMultiPidBatchSupported != true) {
                        isMultiPidBatchSupported = true
                        Log.i(TAG, "Multi-PID response decoded ($pidsInPayload PIDs in single frame). isMultiPidBatchSupported = true")
                    }
                }
            }
        }
    }

    /**
     * High-performance Tiered Multi-Rate PID polling loop with Multi-PID batch bundling.
     * Tier 1 (Fast, every cycle ~10-25ms): Speed (0D), RPM (0C), Throttle (11) - bundled in single request if supported.
     * Tier 2 (Medium, every 4 cycles ~100-200ms): Load (04), MAP (0B), Fuel Trims (06, 07), MAF (10), Timing (0E).
     * Tier 3 (Slow, every 24 cycles ~1.5-2.5s): Coolant (05), IAT (0F), Ambient (46), Fuel Level (2F), Baro (33), Run Time (1F), Oil Temp (5C).
     * Tier 4 (Extended, every 48 cycles ~3-5s): All other discovered PIDs (round-robin).
     * High-Speed CAN Broadcast: Interleaved every 12 cycles when driving, 6 when parked.
     */
    private fun startPollingLoop() {
        pollingJob?.cancel()
        pollingJob = ioScope.launch {
            val allPids = if (discoveredPidDefinitions.isNotEmpty()) discoveredPidDefinitions else ObdPidRegistry.DEFAULT_PIDS

            // Priority Tier 1: Speed (0D), RPM (0C), Throttle (11) - high dynamic range
            val tier1Hexes = setOf("0D", "0C", "11")
            val tier1Pids = allPids.filter { it.pidHex.uppercase() in tier1Hexes }.ifEmpty {
                listOfNotNull(ObdPidRegistry.KNOWN_PIDS["0D"], ObdPidRegistry.KNOWN_PIDS["0C"], ObdPidRegistry.KNOWN_PIDS["11"])
            }

            // Medium Tier 2: Load (04), MAP (0B), Fuel Trims (06, 07), MAF (10), Timing (0E)
            val tier2Hexes = setOf("04", "0B", "06", "07", "10", "0E")
            val tier2Pids = allPids.filter { it.pidHex.uppercase() in tier2Hexes }

            // Thermal Dynamics Tier 3 (polled every 12 cycles ~0.8-1.5s): Coolant (05), IAT (0F), Oil Temp (5C)
            // Focused on thermal dynamics for Sentinel hill-climbing, thermal runaway, and downhill thermostat checks
            val tier3Hexes = setOf("05", "0F", "5C")
            val tier3Pids = allPids.filter { it.pidHex.uppercase() in tier3Hexes }

            // Ultra-Slow Static Environmental Tier 5 (polled every 96 cycles ~10-15s): Fuel Level (2F), Baro (33), Ambient (46), Run Time (1F)
            // These change very slowly over a drive, so polling them infrequently saves serial bandwidth and OBD query effort!
            val tier5Hexes = setOf("2F", "33", "46", "1F")
            val tier5Pids = allPids.filter { it.pidHex.uppercase() in tier5Hexes }

            // Extended Tier 4: All other discovered dynamic PIDs (round-robin)
            val tier4Pids = allPids.filter {
                val hex = it.pidHex.uppercase()
                hex !in tier1Hexes && hex !in tier2Hexes && hex !in tier3Hexes && hex !in tier5Hexes
            }

            var tier2Idx = 0
            var tier3Idx = 0
            var tier4Idx = 0
            var tier5Idx = 0
            var cycleCount = 0
            var initialCanSampleDone = false
            var consecutiveEmptyResponses = 0

            // Multi-PID probe: Check if vehicle CAN ECU supports ISO 15765-4 multi-PID requests (e.g. 010D0C11)
            if (isMultiPidBatchSupported == null && !isDemoMode) {
                try {
                    val probeRx = sendAtCommand("010D0C11", 0)
                    val cleaned = probeRx.replace(" ", "").uppercase()
                    if (cleaned.contains("410D") && (cleaned.contains("0C") || cleaned.contains("11"))) {
                        isMultiPidBatchSupported = true
                        Log.i(TAG, "ECU verified supports Multi-PID batch requests! Enabling bundled 010D0C11.")
                    } else {
                        isMultiPidBatchSupported = false
                        Log.i(TAG, "ECU does not support Multi-PID batch requests (response: $probeRx). Using single-PID polling.")
                    }
                } catch (e: Exception) {
                    isMultiPidBatchSupported = false
                    Log.w(TAG, "Multi-PID probe failed", e)
                }
            }

            while (isActive) {
                try {
                    if (!isPollingPaused) {
                        cycleCount++

                        // 1. Tier 1 (Fast-Path, polled EVERY cycle): Speed, RPM, Throttle
                        if (isMultiPidBatchSupported == true) {
                            val rx = sendAtCommand("010D0C11", 0)
                            if (rx.isBlank()) consecutiveEmptyResponses++ else consecutiveEmptyResponses = 0
                        } else {
                            for (pPid in tier1Pids) {
                                if (isPollingPaused || !isActive) break
                                val rx = sendAtCommand(pPid.fullCommand, 0)
                                if (rx.isBlank()) consecutiveEmptyResponses++ else consecutiveEmptyResponses = 0
                            }
                        }

                        // 2. Tier 2 (Medium, polled every 4 cycles ~100-200ms): Load, MAP, Trims, MAF, Timing
                        if (cycleCount % 4 == 0 && tier2Pids.isNotEmpty() && consecutiveEmptyResponses < 6) {
                            val countToPoll = minOf(2, tier2Pids.size)
                            for (i in 0 until countToPoll) {
                                if (isPollingPaused || !isActive) break
                                val p = tier2Pids[tier2Idx % tier2Pids.size]
                                tier2Idx = (tier2Idx + 1) % tier2Pids.size
                                val rx = sendAtCommand(p.fullCommand, 0)
                                if (rx.isBlank()) consecutiveEmptyResponses++ else consecutiveEmptyResponses = 0
                            }
                        }

                        // 3. Tier 3 (Thermal Dynamics, polled every 12 cycles ~0.8-1.5s): Coolant, IAT, Oil Temp
                        if (cycleCount % 12 == 0 && tier3Pids.isNotEmpty() && consecutiveEmptyResponses < 6) {
                            if (!isPollingPaused && isActive) {
                                val p = tier3Pids[tier3Idx % tier3Pids.size]
                                tier3Idx = (tier3Idx + 1) % tier3Pids.size
                                val rx = sendAtCommand(p.fullCommand, 0)
                                if (rx.isBlank()) consecutiveEmptyResponses++ else consecutiveEmptyResponses = 0
                            }
                        }

                        // 4. Tier 4 (Extended, polled every 48 cycles ~3-5s): All other discovered dynamic PIDs
                        if (cycleCount % 48 == 0 && tier4Pids.isNotEmpty() && consecutiveEmptyResponses < 6) {
                            if (!isPollingPaused && isActive) {
                                val p = tier4Pids[tier4Idx % tier4Pids.size]
                                tier4Idx = (tier4Idx + 1) % tier4Pids.size
                                val rx = sendAtCommand(p.fullCommand, 0)
                                if (rx.isBlank()) consecutiveEmptyResponses++ else consecutiveEmptyResponses = 0
                            }
                        }

                        // 5. Tier 5 (Static / Environmental, polled every 96 cycles ~10-15s): Fuel Level, Baro, Ambient, Run Time
                        if (cycleCount % 96 == 0 && tier5Pids.isNotEmpty() && consecutiveEmptyResponses < 6) {
                            if (!isPollingPaused && isActive) {
                                val p = tier5Pids[tier5Idx % tier5Pids.size]
                                tier5Idx = (tier5Idx + 1) % tier5Pids.size
                                val rx = sendAtCommand(p.fullCommand, 0)
                                if (rx.isBlank()) consecutiveEmptyResponses++ else consecutiveEmptyResponses = 0
                            }
                        }

                        if (consecutiveEmptyResponses >= 8) {
                            Log.w(TAG, "Adapter stopped responding (8 consecutive empty timeouts) — disconnecting cleanly.")
                            disconnect()
                            break
                        }

                        // 5. High-Speed CAN Broadcast Sample: populate Advanced Vehicle Dynamics
                        val canSampleInterval = if (_metrics.value.speed > 0) 12 else 6
                        if (_isHighSpeedCanCapable.value && !_isSniffingCan.value && (!initialCanSampleDone || cycleCount % canSampleInterval == 0)) {
                            initialCanSampleDone = true
                            sampleCanBroadcastInline(180L)
                        }
                    }

                    // For USB wired connections (vLinker FS USB), delay 10ms for fast 20-30Hz loop.
                    // For wireless connections, delay 25ms to allow RF buffer drainage.
                    val loopDelay = if (isUsbConnection) 10L else 25L
                    delay(loopDelay)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in polling loop", e)
                    break
                }
            }
        }
    }

    /**
     * Returns a snapshot of all current sensor readings for Gemini agentic mode.
     */
    fun getAllLiveData(): Map<String, Any> {
        val streams = _supportedStreams.value
        val map = mutableMapOf<String, Any>()
        for (stream in streams) {
            map[stream.pid.name] = stream.formattedValue
        }
        map["dtcs"] = _metrics.value.dtcs
        return map
    }

    /**
     * Decodes a 2-byte OBD2 DTC hex pair into a 5-character string (e.g. P0420, P0442, P0171).
     */
    fun decodeDtcHexPair(byteA: Int, byteB: Int): String? {
        if (byteA == 0 && byteB == 0) return null

        val typeChar = when ((byteA and 0xC0) shr 6) {
            0 -> 'P'
            1 -> 'C'
            2 -> 'B'
            3 -> 'U'
            else -> 'P'
        }

        val digit1 = (byteA and 0x30) shr 4
        val digit2 = byteA and 0x0F
        val remainingHex = String.format("%X%02X", digit2, byteB)

        return "$typeChar$digit1$remainingHex"
    }

    private fun isMode06SentinelValue(hexStr: String, intVal: Int): Boolean {
        val h = hexStr.uppercase()
        return h == "FF00" || h == "FFFF" || h == "FE00" || h == "7FFF" || h == "8000" ||
               h == "2400" || h == "3000" || h == "4000" || h == "5000" || h == "6000" ||
               intVal >= 2048
    }

    /**
     * Queries OBD Mode 06 On-Board Diagnostic Component Test Results for Cylinder Misfires and returns DTC details if actual failure present.
     */
    suspend fun scanMode06Misfires(): List<DtcDetails> {
        val allMonitors = scanAllMode06Monitors()
        return allMonitors.filter { it.status == Mode06Status.FAIL }.map {
            val tag = if (it.category == "Misfire") {
                if (it.midHex == "A1") "GENERAL" else "CYL${(it.midHex.toIntOrNull(16) ?: 0) - 0xA1}"
            } else {
                "MONITOR-${it.midHex}"
            }
            DtcDetails(
                code = if (it.category == "Misfire") "MISFIRE-$tag" else "MONITOR-FAIL-${it.midHex}",
                category = "Mode 06 Monitor",
                description = it.description
            )
        }
    }

    /**
     * Executes a full OBD Mode 06 On-Board Diagnostic Component Test Result Scan covering:
     * Misfires (MID A1-A9), Catalyst (MID 21, 22), O2 Sensors (MID 01, 02, 05, 06), EVAP (MID 39, 3A), EGR/VVT (MID 31, 32, 35, 36), and Boost (MID 11, 41).
     * Correctly handles Toyota/SAE Mode 06 sentinel values (0xFF00 / 65280 = Test Incomplete / Clean).
     */
    suspend fun scanAllMode06Monitors(): List<Mode06TestResult> {
        if (isDemoMode) {
            val demoList = if (demoPreset == DemoVehiclePreset.DIRTY_ECU) {
                listOf(
                    Mode06TestResult("A1", "Misfire", "General / Total Misfires", 18.0, 0.0, 10.0, "misfires", Mode06Status.FAIL, "General / Total Misfires: 18 detected misfires (FAIL)"),
                    Mode06TestResult("A2", "Misfire", "Cylinder 1 Misfires", 0.0, 0.0, 10.0, "misfires", Mode06Status.PASS, "Cylinder 1 Misfires: 0 misfires (Clean)"),
                    Mode06TestResult("A3", "Misfire", "Cylinder 2 Misfires", 0.0, 0.0, 10.0, "misfires", Mode06Status.PASS, "Cylinder 2 Misfires: 0 misfires (Clean)"),
                    Mode06TestResult("A4", "Misfire", "Cylinder 3 Misfires", 0.0, 0.0, 10.0, "misfires", Mode06Status.PASS, "Cylinder 3 Misfires: 0 misfires (Clean)"),
                    Mode06TestResult("A5", "Misfire", "Cylinder 4 Misfires", 0.0, 0.0, 10.0, "misfires", Mode06Status.PASS, "Cylinder 4 Misfires: 0 misfires (Clean)"),
                    Mode06TestResult("A6", "Misfire", "Cylinder 5 Misfires", 14.0, 0.0, 10.0, "misfires", Mode06Status.FAIL, "Cylinder 5 Misfires: 14 detected misfires (FAIL)"),
                    Mode06TestResult("A7", "Misfire", "Cylinder 6 Misfires", 0.0, 0.0, 10.0, "misfires", Mode06Status.PASS, "Cylinder 6 Misfires: 0 misfires (Clean)"),
                    Mode06TestResult("A8", "Misfire", "Cylinder 7 Misfires", 0.0, 0.0, 10.0, "misfires", Mode06Status.PASS, "Cylinder 7 Misfires: 0 misfires (Clean)"),
                    Mode06TestResult("A9", "Misfire", "Cylinder 8 Misfires", 0.0, 0.0, 10.0, "misfires", Mode06Status.PASS, "Cylinder 8 Misfires: 0 misfires (Clean)"),
                    Mode06TestResult("21", "Catalyst", "Catalyst Efficiency Bank 1", 1.85, 0.8, 2.0, "counts", Mode06Status.WARNING, "Catalyst Efficiency Bank 1: Measured 2 (Min: 0 | Max: 2) - Near Limit"),
                    Mode06TestResult("39", "EVAP System", "EVAP Leak Monitor (0.150\")", 12.0, 0.0, 500.0, "Pa", Mode06Status.PASS, "EVAP Leak Monitor: Measured 12 Pa (Min: 0 | Max: 500)")
                )
            } else {
                listOf(
                    Mode06TestResult("A1", "Misfire", "General / Total Misfires", 0.0, 0.0, 50.0, "misfires", Mode06Status.PASS, "General / Total Misfires: 0 misfires (Clean)"),
                    Mode06TestResult("A2", "Misfire", "Cylinder 1 Misfires", 0.0, 0.0, 50.0, "misfires", Mode06Status.PASS, "Cylinder 1 Misfires: 0 misfires (Clean)"),
                    Mode06TestResult("A3", "Misfire", "Cylinder 2 Misfires", 0.0, 0.0, 50.0, "misfires", Mode06Status.PASS, "Cylinder 2 Misfires: 0 misfires (Clean)"),
                    Mode06TestResult("A4", "Misfire", "Cylinder 3 Misfires", 0.0, 0.0, 50.0, "misfires", Mode06Status.PASS, "Cylinder 3 Misfires: 0 misfires (Clean)"),
                    Mode06TestResult("A5", "Misfire", "Cylinder 4 Misfires", 0.0, 0.0, 50.0, "misfires", Mode06Status.PASS, "Cylinder 4 Misfires: 0 misfires (Clean)"),
                    Mode06TestResult("A6", "Misfire", "Cylinder 5 Misfires", 0.0, 0.0, 50.0, "misfires", Mode06Status.PASS, "Cylinder 5 Misfires: 0 misfires (Clean)"),
                    Mode06TestResult("A7", "Misfire", "Cylinder 6 Misfires", 0.0, 0.0, 50.0, "misfires", Mode06Status.PASS, "Cylinder 6 Misfires: 0 misfires (Clean)"),
                    Mode06TestResult("A8", "Misfire", "Cylinder 7 Misfires", 0.0, 0.0, 50.0, "misfires", Mode06Status.PASS, "Cylinder 7 Misfires: 0 misfires (Clean)"),
                    Mode06TestResult("A9", "Misfire", "Cylinder 8 Misfires", 0.0, 0.0, 50.0, "misfires", Mode06Status.PASS, "Cylinder 8 Misfires: 0 misfires (Clean)"),
                    Mode06TestResult("21", "Catalyst", "Catalyst Efficiency Bank 1", 1.02, 0.8, 2.0, "counts", Mode06Status.PASS, "Catalyst Efficiency Bank 1: Measured 1 (Min: 0 | Max: 2)"),
                    Mode06TestResult("22", "Catalyst", "Catalyst Efficiency Bank 2", 1.05, 0.8, 2.0, "counts", Mode06Status.PASS, "Catalyst Efficiency Bank 2: Measured 1 (Min: 0 | Max: 2)"),
                    Mode06TestResult("01", "O2 Sensor", "O2 Sensor Bank 1 Sensor 1", 0.85, 0.2, 1.0, "V", Mode06Status.PASS, "O2 Sensor Bank 1 Sensor 1: Measured 0 (Min: 0 | Max: 1)"),
                    Mode06TestResult("39", "EVAP System", "EVAP Leak Monitor (0.150\")", 12.0, 0.0, 500.0, "Pa", Mode06Status.PASS, "EVAP Leak Monitor: Measured 12 Pa (Min: 0 | Max: 500)")
                )
            }
            _mode06Results.value = demoList
            return demoList
        }

        Log.i(TAG, "Executing Full Mode 06 On-Board Diagnostic Monitor Scan...")
        val mode06TestList = mutableListOf<Mode06TestResult>()

        val monitorPids = listOf(
            // Misfires (MID A1 to A9)
            Triple("A1", "Misfire", "General / Total Misfires"),
            Triple("A2", "Misfire", "Cylinder 1 Misfires"),
            Triple("A3", "Misfire", "Cylinder 2 Misfires"),
            Triple("A4", "Misfire", "Cylinder 3 Misfires"),
            Triple("A5", "Misfire", "Cylinder 4 Misfires"),
            Triple("A6", "Misfire", "Cylinder 5 Misfires"),
            Triple("A7", "Misfire", "Cylinder 6 Misfires"),
            Triple("A8", "Misfire", "Cylinder 7 Misfires"),
            Triple("A9", "Misfire", "Cylinder 8 Misfires"),

            // Catalyst Monitors (MID 21, 22)
            Triple("21", "Catalyst", "Catalyst Efficiency Bank 1"),
            Triple("22", "Catalyst", "Catalyst Efficiency Bank 2"),

            // O2 Sensor Monitors (MID 01, 02, 05, 06)
            Triple("01", "O2 Sensor", "O2 Sensor Bank 1 Sensor 1"),
            Triple("02", "O2 Sensor", "O2 Sensor Bank 1 Sensor 2"),
            Triple("05", "O2 Sensor", "O2 Sensor Bank 2 Sensor 1"),
            Triple("06", "O2 Sensor", "O2 Sensor Bank 2 Sensor 2"),

            // EVAP System Monitors (MID 39, 3A)
            Triple("39", "EVAP System", "EVAP Leak Monitor (0.020\")"),
            Triple("3A", "EVAP System", "EVAP Purge Flow Monitor"),

            // EGR & VVT Monitors (MID 31, 32, 35, 36)
            Triple("31", "EGR / VVT", "EGR Flow Monitor Bank 1"),
            Triple("32", "EGR / VVT", "EGR Flow Monitor Bank 2"),
            Triple("35", "EGR / VVT", "VVT Timing Monitor Bank 1"),
            Triple("36", "EGR / VVT", "VVT Timing Monitor Bank 2"),

            // Boost & Secondary Air (MID 11, 41)
            Triple("11", "Secondary Air", "Secondary Air System Monitor"),
            Triple("41", "Boost Control", "Turbo / Supercharger Boost Monitor")
        )

        for ((pid, category, label) in monitorPids) {
            val response = sendAtCommand("06$pid", 200)
            var testValue = 0.0
            var minLimit: Double? = null
            var maxLimit: Double? = null
            var parsed = false
            var isSentinel = false

            if (!response.contains("NODATA") && !response.contains("ERROR") && !response.contains("UNABLE")) {
                val cleaned = response.replace(" ", "").uppercase()
                val idx46 = cleaned.indexOf("46$pid")
                if (idx46 != -1 && cleaned.length >= idx46 + 6) {
                    val payload = cleaned.substring(idx46 + 4)
                    try {
                        val valHex = if (payload.length >= 6) payload.substring(2, 6) else if (payload.length >= 4) payload.takeLast(4) else payload
                        val valInt = valHex.toIntOrNull(16) ?: 0

                        if (isMode06SentinelValue(valHex, valInt)) {
                            isSentinel = true
                            testValue = 0.0
                            minLimit = 0.0
                            maxLimit = 50.0
                            parsed = true
                        } else {
                            testValue = valInt.toDouble()
                            if (payload.length >= 14) {
                                val minHex = payload.substring(6, 10)
                                val maxHex = payload.substring(10, 14)
                                val parsedMin = minHex.toIntOrNull(16)?.toDouble()
                                val parsedMax = maxHex.toIntOrNull(16)?.toDouble()
                                if (parsedMin != null && !isMode06SentinelValue(minHex, parsedMin.toInt())) minLimit = parsedMin
                                if (parsedMax != null && !isMode06SentinelValue(maxHex, parsedMax.toInt())) maxLimit = parsedMax
                            }
                            if (minLimit == null) minLimit = 0.0
                            if (maxLimit == null) maxLimit = 50.0
                            parsed = true
                        }
                    } catch (_: Exception) {}
                }
            }

            // Skip non-existent cylinders 5-8 on 4-cylinder or V6 engines if ECU returned no parseable data for that MID
            if (pid in listOf("A6", "A7", "A8", "A9") && !parsed) {
                continue
            }

            val status = when {
                !parsed || isSentinel -> Mode06Status.PASS
                category == "Misfire" -> when {
                    testValue == 0.0 -> Mode06Status.PASS
                    maxLimit != null && testValue > maxLimit -> Mode06Status.FAIL
                    testValue in 1.0..9.0 -> Mode06Status.WARNING
                    else -> Mode06Status.FAIL
                }
                maxLimit != null && maxLimit > 0.0 -> when {
                    testValue > maxLimit -> Mode06Status.FAIL
                    minLimit != null && testValue < minLimit -> Mode06Status.FAIL
                    testValue >= maxLimit * 0.85 -> Mode06Status.WARNING
                    else -> Mode06Status.PASS
                }
                else -> Mode06Status.PASS
            }

            val desc = when {
                isSentinel -> "$label: 0 misfires (Clean / Incomplete Test)"
                category == "Misfire" && testValue == 0.0 -> "$label: 0 misfires (Clean)"
                category == "Misfire" -> "$label: ${testValue.toInt()} detected misfires"
                maxLimit != null -> "$label: Measured ${testValue.toInt()} (Min: ${minLimit?.toInt() ?: 0}, Max: ${maxLimit.toInt()})"
                else -> "$label: Test result ${testValue.toInt()} (PASS)"
            }

            val unitStr = when (category) {
                "Misfire" -> "misfires"
                "EVAP System" -> "Pa"
                "Boost Control" -> "kPa"
                "O2 Sensor" -> "V"
                else -> "counts"
            }

            mode06TestList.add(
                Mode06TestResult(
                    midHex = pid,
                    category = category,
                    name = label,
                    testValue = testValue,
                    minLimit = minLimit,
                    maxLimit = maxLimit,
                    unit = unitStr,
                    status = status,
                    description = desc
                )
            )
        }

        _mode06Results.value = mode06TestList
        return mode06TestList
    }

    /**
     * Scans Stored (Mode 03), Pending (Mode 07), Permanent (Mode 0A), and Mode 06 Component Monitors (Misfires).
     */
    suspend fun scanAllDtcModes(): List<DtcDetails> {
        if (isDemoMode) {
            val dtcs = if (demoPreset == DemoVehiclePreset.DIRTY_ECU) {
                listOf(
                    DtcDetails("P0305", "Stored / Active", "Cylinder 5 Misfire Detected"),
                    DtcDetails("P0171", "Stored / Active", "System Too Lean (Bank 1)"),
                    DtcDetails("P0442", "Pending", "EVAP System Small Leak (0.040 in) Detected")
                )
            } else {
                emptyList()
            }
            _metrics.value = _metrics.value.copy(dtcs = dtcs.map { it.code })
            return dtcs
        }

        if (_isSniffingCan.value || canSniffActive) {
            Log.d(TAG, "Skipping scanAllDtcModes while CAN broadcast sniff is active")
            return emptyList()
        }

        val wasPolling = pollingJob?.isActive == true
        isPollingPaused = true
        pollingJob?.cancel()

        return try {
            if (wasPolling) {
                delay(200)
            }
            restoreStandardObdMode()

            val allDtcList = mutableListOf<DtcDetails>()

            // 1. Scan Mode 03 (Stored / Active)
            val resp03 = sendAtCommand("03", 500)
            allDtcList.addAll(parseDtcModeResponse(resp03, "43", "Stored / Active"))

            // 2. Scan Mode 07 (Pending)
            val resp07 = sendAtCommand("07", 500)
            allDtcList.addAll(parseDtcModeResponse(resp07, "47", "Pending"))

            // 3. Scan Mode 0A (Permanent / Historic)
            val resp0A = sendAtCommand("0A", 500)
            allDtcList.addAll(parseDtcModeResponse(resp0A, "4A", "Permanent / Historic"))

            // 4. Scan Mode 06 On-Board Misfire Component Monitors
            val misfireList = scanMode06Misfires()
            allDtcList.addAll(misfireList)

            // 5. Scan Chassis & TPMS Modules across makes (Headers 7B0, 720, 726, 7A0, 750, 7E4)
            val make = _vehicleDetails.value ?: ""
            val chassisHeaders = when {
                make.contains("Chrysler", ignoreCase = true) || make.contains("Dodge", ignoreCase = true) ||
                make.contains("Jeep", ignoreCase = true) || make.contains("Ram", ignoreCase = true) -> listOf("7B0", "720")
                make.contains("Ford", ignoreCase = true) || make.contains("Lincoln", ignoreCase = true) -> listOf("726", "720")
                make.contains("GM", ignoreCase = true) || make.contains("Chevrolet", ignoreCase = true) ||
                make.contains("GMC", ignoreCase = true) || make.contains("Cadillac", ignoreCase = true) -> listOf("7A0", "720")
                make.contains("Toyota", ignoreCase = true) || make.contains("Lexus", ignoreCase = true) -> listOf("750", "720")
                else -> listOf("7E4", "720") // Universal standard Chassis/ABS/Gateway physical addresses
            }
            for (chassisHdr in chassisHeaders) {
                sendAtCommand("AT SH $chassisHdr", 60)
                val respChassis03 = sendAtCommand("03", 350)
                allDtcList.addAll(parseDtcModeResponse(respChassis03, "43", "Stored / Active"))
                val respChassis07 = sendAtCommand("07", 350)
                allDtcList.addAll(parseDtcModeResponse(respChassis07, "47", "Pending"))
            }
            restoreStandardObdMode()

            val uniqueDtcs = allDtcList.distinctBy { "${it.code}_${it.category}_${it.description}" }
            _metrics.value = _metrics.value.copy(dtcs = uniqueDtcs.map { it.code })
            uniqueDtcs
        } finally {
            restoreStandardObdMode()
            isPollingPaused = false
            if (wasPolling && pollingJob?.isActive != true) startPollingLoop()
        }
    }

    private fun parseDtcModeResponse(response: String, expectedHeader: String, category: String): List<DtcDetails> {
        if (response.isBlank() || response.contains("NODATA") || response.contains("ERROR") || response.contains("UNABLE")) {
            return emptyList()
        }

        val results = mutableListOf<DtcDetails>()
        val cleanedFull = response.replace(" ", "").replace("\r", "").replace("\n", "").uppercase()

        // Check if response matches "4300" (or 4700, 4A00)
        if (cleanedFull.contains("${expectedHeader}00")) {
            return emptyList()
        }

        val lines = response.split("\r", "\n")
        for (rawLine in lines) {
            val cleaned = rawLine.replace(" ", "").uppercase()
            val idx = cleaned.indexOf(expectedHeader)
            if (idx != -1) {
                val payload = cleaned.substring(idx + 2)
                if (payload.length >= 2) {
                    val countHex = payload.substring(0, 2)
                    val count = countHex.toIntOrNull(16) ?: 0
                    if (count == 0) {
                        return emptyList()
                    }

                    val dtcPayload = payload.substring(2)
                    var i = 0
                    while (i + 4 <= dtcPayload.length) {
                        val hexPairStr = dtcPayload.substring(i, i + 4)
                        try {
                            val byteA = hexPairStr.substring(0, 2).toInt(16)
                            val byteB = hexPairStr.substring(2, 4).toInt(16)
                            val dtcCode = decodeDtcHexPair(byteA, byteB)
                            if (dtcCode != null && dtcCode != "P0000") {
                                val desc = ObdDtcRegistry.getDescription(dtcCode)
                                results.add(DtcDetails(code = dtcCode, category = category, description = desc))
                            }
                        } catch (_: Exception) {}
                        i += 4
                    }
                }
            }
        }
        return results
    }

    /**
     * Retrieves active Diagnostic Trouble Codes (DTCs) using Mode 03, 07, and 0A.
     */
    suspend fun getTroubleCodes(): List<String> {
        val details = scanAllDtcModes()
        return details.map { "${it.code} [${it.category}]: ${it.description}" }
    }

    /**
     * Sanitizes raw ELM327 response strings by stripping prompt symbols, CAN headers,
     * multiline frame prefixes (0:, 1:, 2:), whitespace, ISO-TP framing bytes, and echo commands.
     */
    fun cleanElmResponse(rawResponse: String, expectedHeader: String): String {
        if (rawResponse.isBlank()) return ""

        val cleaned = rawResponse
            .replace(">", "")
            .replace("SEARCHING...", "")
            .replace("BUS INIT...", "")
            .replace("STOPPED", "")
            .replace("NO DATA", "NODATA")

        val lines = cleaned.split("\r", "\n")
        val validBytes = StringBuilder()

        for (line in lines) {
            var l = line.trim().replace(" ", "").uppercase()

            if (l.isBlank() || l.contains("NODATA") || l.contains("ERROR") || l.contains("UNABLE")) {
                continue
            }

            // 0. Strip standalone ISO-TP frame length lines (e.g. "018", "01A", "020") or 3-digit CAN ID headers
            if (l.length in 1..4 && l.all { it.isDigit() || it in 'A'..'F' }) {
                continue
            }

            // 1. Strip 3-digit hex CAN ID headers if present at start (e.g. 72E, 7E8, 728, 7A8, 7E0, 7E2, 730, 700)
            if (l.length >= 5 && l.substring(0, 3).matches(Regex("^[0-7][0-9A-F]{2}$"))) {
                l = l.substring(3)
            }

            // 2. Strip line index prefix like "0:", "1:", "2:" if present
            val colonIdx = l.indexOf(':')
            if (colonIdx in 1..3 && l.substring(0, colonIdx).all { it.isLetterOrDigit() }) {
                l = l.substring(colonIdx + 1)
            }

            // 3. Strip ISO-TP First Frame header "10XX" or "11XX" or "12XX" if present at start of line
            if (l.length >= 6 && (l.startsWith("10") || l.startsWith("11") || l.startsWith("12")) && l.substring(2, 4).matches(Regex("^[0-9A-F]{2}$"))) {
                if (l.substring(4).startsWith("62") || l.substring(4).startsWith("61") || l.substring(4).startsWith("41")) {
                    l = l.substring(4)
                }
            }

            // 4. Strip ISO-TP Consecutive Frame sequence byte "21", "22", "23"..."2F" if present at start of line
            if (l.length >= 4 && l.substring(0, 2).matches(Regex("^2[0-9A-F]$"))) {
                l = l.substring(2)
            }

            if (l.isNotBlank()) {
                validBytes.append(l)
            }
        }

        val fullStr = validBytes.toString()
        val headerIdx = fullStr.indexOf(expectedHeader)
        return if (headerIdx != -1) {
            fullStr.substring(headerIdx)
        } else {
            fullStr
        }
    }

    /**
     * Queries enhanced/proprietary Mode 22 PIDs based on the vehicle manufacturer decoded from VIN/details.
     * Configures physical CAN headers (e.g. AT SH 7E0 for Ford PCM, 7E1 for Chrysler TCM, 7DA for Chrysler TPMS).
     */
    suspend fun scanMode22Pids(): List<Mode22StreamData> {
        if (isDemoMode) {
            val demoPids = buildDemoMode22Streams(demoPreset)
            _mode22Streams.value = demoPids
            return demoPids
        }

        val make = _vehicleDetails.value ?: ""
        val targetPids = Mode22Registry.getPidsForManufacturer(make)
        Log.i(TAG, "Scanning ${targetPids.size} Mode 22 Proprietary PIDs for vehicle make: $make")

        val results = mutableListOf<Mode22StreamData>()

        var sniffWait = 0
        while ((_isSniffingCan.value || canSniffActive) && sniffWait < 50) {
            delay(200)
            sniffWait++
        }

        val wasPolling = pollingJob?.isActive == true
        isPollingPaused = true
        pollingJob?.cancel()

        try {
            if (wasPolling) {
                delay(200) // Flush in-flight serial polling frame
            }
            restoreStandardObdMode()

            var currentHeader = ""
            suspend fun ensureHeader(hdr: String) {
                if (currentHeader != hdr) {
                    sendAtCommand("AT SH $hdr", 60)
                    val hdrInt = hdr.toIntOrNull(16)
                    if (hdrInt != null && hdr.length == 3) {
                        val rxAddr = String.format("%03X", hdrInt + 8)
                        sendAtCommand("AT CRA $rxAddr", 50)
                    }
                    currentHeader = hdr
                }
            }

            // Prioritize already verified cached PIDs for this vehicle
            val sortedPids = if (cachedSupportedMode22Pids.isNotEmpty()) {
                targetPids.sortedByDescending { cachedSupportedMode22Pids.contains(it.pidHex) }
            } else {
                targetPids
            }

            val isChryslerVehicle = _vehicleVin.value?.let { v -> v.startsWith("1C") || v.startsWith("2C") || v.startsWith("3C") } ?: false
                || make.contains("Chrysler", true) || make.contains("Dodge", true) || make.contains("Jeep", true) || make.contains("Ram", true)

            for (pidDef in sortedPids) {
                // Route queries specifically to the relevant electronic control module
                val targetHeaders: List<String> = when (pidDef.category) {
                    "Transmission" -> if (isChryslerVehicle) listOf("7E1", "7E2", "7E0") else listOf("7E0", "7E1")
                    "Maintenance" -> {
                        if (pidDef.pidHex.equals("404C", ignoreCase = true) || pidDef.pidHex.equals("DD01", ignoreCase = true)) {
                            listOf("720", "7E0")
                        } else {
                            listOf("7E0")
                        }
                    }
                    "Battery", "Electrical" -> {
                        if (pidDef.pidHex.equals("042F", ignoreCase = true) || pidDef.pidHex.equals("042C", ignoreCase = true)) {
                            listOf("7E0", "726")
                        } else {
                            listOf("7E0")
                        }
                    }
                    "Chassis" -> if (isChryslerVehicle) listOf("7DA", "7B0", "720", "7E4", "7E0") else listOf("7B0", "720", "7E4", "7DA", "7E0") // 7DA is Chrysler Wireless Control Module (WCM/TPM/WIN)
                    else -> listOf("7E0") // Engine, Engine Oil, Boost, Fuel, Emissions, Climate are on PCM (7E0)
                }

                var foundValid = false

                for (header in targetHeaders) {
                    ensureHeader(header)
                    val cmd1 = "${pidDef.servicePrefix}${pidDef.pidHex}"
                    var response = sendAtCommand(cmd1, 100)
                    var payloads = ObdParser.extractPayloads(response)

                    // Fast-fail check: If ECU returned NRC 7F (Service Not Supported / Request Out of Range), do not retry
                    val isExplicitRejection = payloads.any {
                        it.isNotEmpty() && it[0] == 0x7F && (it.size < 3 || it[2] == 0x31 || it[2] == 0x12)
                    }

                    if (!isExplicitRejection && (payloads.isEmpty() || payloads.all { it.isEmpty() || it[0] == 0x7F })) {
                        val altPrefix = if (pidDef.servicePrefix == "22") "21" else "22"
                        response = sendAtCommand("$altPrefix${pidDef.pidHex}", 100)
                        payloads = ObdParser.extractPayloads(response)
                    }

                    for (payload in payloads) {
                        if (payload.size >= 2 && (payload[0] == 0x61 || payload[0] == 0x62)) {
                            val pidByteCount = pidDef.pidHex.length / 2
                            val headerLen = 1 + pidByteCount

                            // A positive response must echo the identifier we asked for (SAE J1979 / UDS / KWP).
                            // If it doesn't, the ECU answered a DIFFERENT parameter (e.g. Toyota replies to
                            // "21 16 20" with "61 20 ..."). Treating those bytes as our value produced the
                            // bogus negative oil/trans temperatures, so ignore the reply entirely.
                            val echoMatches = payload.size >= headerLen &&
                                (0 until pidByteCount).all { i ->
                                    payload[1 + i] == (pidDef.pidHex.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: -1)
                                }
                            if (!echoMatches) {
                                Log.d(TAG, "Mode 22 scan: ignoring reply that does not echo PID ${pidDef.pidHex}: ${payload.joinToString(" ") { String.format("%02X", it) }}")
                                continue
                            }

                            val dataBytes = if (payload.size > headerLen) payload.copyOfRange(headerLen, payload.size) else intArrayOf()
                            if (dataBytes.isNotEmpty()) {
                                val rawVal = try {
                                    pidDef.parseValue?.invoke(dataBytes)
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to parse ${pidDef.name} (${pidDef.pidHex}): ${e.message}")
                                    null
                                }

                                if (rawVal != null && !rawVal.isNaN() && !rawVal.isInfinite()) {
                                    // Validate rawVal physical bounds to filter out unsupported/uninitialized ECU responses
                                    val isBogus = when (pidDef.unit) {
                                        "kPa" -> rawVal > 50000.0 || rawVal < 0.0
                                        "bar" -> rawVal > 500.0 || rawVal < 0.0
                                        "°C" -> rawVal > 300.0 || rawVal <= -40.0 // -40 °C is the 'raw byte = 0' sentinel (no sensor / not populated)
                                        "psi" -> rawVal > 5000.0 || rawVal < -15.0
                                        "%" -> rawVal > 100.0 || rawVal < 0.0
                                        "RPM" -> rawVal > 10000.0 || rawVal < -1000.0
                                        "Ratio" -> rawVal > 25.0 || rawVal < 0.0
                                        "V" -> rawVal > 500.0 || rawVal < 0.0
                                        "km" -> rawVal > 2000000.0 || rawVal < 0.0
                                        "Nm" -> rawVal > 2500.0 || rawVal < -500.0
                                        "counts" -> rawVal > 10_000_000.0 || rawVal < 0.0
                                        "mm" -> rawVal > 500.0 || rawVal < 0.0
                                        "Gear" -> rawVal > 15.0 || rawVal < 0.0
                                        "ppm" -> rawVal > 50000.0 || rawVal < 0.0
                                        "mg/stroke" -> rawVal > 500.0 || rawVal < 0.0
                                        "°" -> rawVal > 360.0 || rawVal < -360.0
                                        else -> false
                                    }

                                    if (!isBogus) {
                                        val candidateStream = Mode22StreamData(pid = pidDef, rawValue = rawVal)
                                        val verification = verifyMode22Physics(
                                            stream = candidateStream,
                                            coolantTempC = _metrics.value.coolantTemp.toDouble().takeIf { it > -30.0 },
                                            ambientTempC = _metrics.value.intakeAirTemp.toDouble().takeIf { it > -30.0 },
                                            rpm = _metrics.value.rpm
                                        )

                                        if (verification == PidVerificationStatus.IMPLAUSIBLE) {
                                            Log.w(TAG, "Mode 22 scan: REJECTED implausible reading for ${pidDef.name} (${pidDef.pidHex}): $rawVal ${pidDef.unit} (coolant=${_metrics.value.coolantTemp}°C)")
                                            continue
                                        }

                                        val formatted = String.format(java.util.Locale.US, "%.1f %s", rawVal, pidDef.unit)
                                        results.add(
                                            Mode22StreamData(
                                                pid = pidDef,
                                                formattedValue = formatted,
                                                rawValue = rawVal,
                                                verificationStatus = verification
                                            )
                                        )
                                        cachedSupportedMode22Pids.add(pidDef.pidHex)
                                        if ((pidDef.pidHex.equals("404C", ignoreCase = true) || pidDef.pidHex.equals("DD01", ignoreCase = true) || pidDef.pidHex.equals("115C", ignoreCase = true)) && rawVal in 10.0..1_500_000.0) {
                                            _vehicleOdometerKm.value = rawVal
                                            Log.i(TAG, "Mode 22 scan updated vehicle odometer: $rawVal km (${(rawVal * 0.621371).toInt()} miles)")
                                        }
                                        Log.i(TAG, "Mode 22 RX [Header $header, Status ${verification.label}]: ${pidDef.name} = $formatted")
                                        foundValid = true
                                        break
                                    }
                                }
                            }
                        }
                    }

                    if (foundValid) break
                }
            }
        } finally {
            restoreStandardObdMode()
            isPollingPaused = false
            if (wasPolling && pollingJob?.isActive != true) startPollingLoop()
        }

        _mode22Streams.value = results
        return results
    }


    private var tcpBridgeServer: java.net.ServerSocket? = null
    private var tcpBridgeJob: Job? = null

    /**
     * Starts a local TCP server on port 8888 for ADB port forwarding (adb forward tcp:8888 tcp:8888).
     * Enables direct PC-to-Dongle AT/CAN command execution.
     */
    fun startTcpObdBridge(port: Int = 8888) {
        tcpBridgeJob?.cancel()
        tcpBridgeJob = ioScope.launch {
            try {
                tcpBridgeServer?.close()
                tcpBridgeServer = java.net.ServerSocket(port)
                Log.i(TAG, "TCP OBD2 Tunnel Bridge Server started on localhost:$port")

                while (isActive) {
                    val clientSocket = tcpBridgeServer?.accept() ?: break
                    Log.i(TAG, "ADB/PC Client connected to OBD2 TCP Tunnel Bridge!")

                    ioScope.launch {
                        try {
                            val reader = clientSocket.getInputStream().bufferedReader()
                            val writer = clientSocket.getOutputStream().bufferedWriter()

                            while (isActive && !clientSocket.isClosed) {
                                val line = reader.readLine() ?: break
                                val rx = sendAtCommand(line.trim(), 50)
                                writer.write("$rx\r\n>")
                                writer.flush()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "TCP Tunnel Client error", e)
                        } finally {
                            clientSocket.close()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "TCP Tunnel Server error", e)
            }
        }
    }

    fun stopTcpObdBridge() {
        tcpBridgeJob?.cancel()
        try {
            tcpBridgeServer?.close()
        } catch (_: Exception) {}
        tcpBridgeServer = null
    }

    /**
     * Gracefully disconnects and cleans up sockets and coroutine jobs.
     */
    fun disconnect() {
        isDemoMode = false
        stopTcpObdBridge()
        pollingJob?.cancel()
        workerJob?.cancel()
        try {
            inputStream?.close()
            outputStream?.close()
            socket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing socket", e)
        }
        try {
            wifiSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing Wi-Fi socket", e)
        }
        try {
            usbSerialPort?.close()
            usbDeviceConnection?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing USB port", e)
        } finally {
            inputStream = null
            outputStream = null
            socket = null
            wifiSocket = null
            isWifiConnection = false
            usbSerialPort = null
            usbDeviceConnection = null
            isUsbConnection = false
            isMultiPidBatchSupported = null
            isPassiveCanBlockedOnGateway = false
            profConfirmedOnBus = false
            promptDeferred?.complete(Unit)
            consecutiveEmptyCanSamples = 0
            activeStreamCache.clear()
            streamRingBuffers.clear()
            _metrics.value = VehicleMetrics()
            _supportedStreams.value = emptyList()
            _mode06Results.value = emptyList()
            _mode22Streams.value = emptyList()
            cachedSupportedMode22Pids.clear()
            _obdbLoadedResult.value = null
            _obdbSignalResults.value = emptyMap()
            _vehicleVin.value = null
            _vehicleDetails.value = null
            _vehicleOdometerKm.value = null
            _opendbcSignals.value = emptyMap()
            _activeOpendbcProfile.value = null
            _canSniffReport.value = null
            _isSniffingCan.value = false
            canSniffActive = false
            _isHighSpeedCanCapable.value = false
            isStnAdapter = false
            _adapterHardwareName.value = "Generic ELM327"
            _isStreamingLiveData.value = false
            _connectionState.value = ConnectionState.Disconnected
            Log.i(TAG, "Disconnected successfully.")
        }
    }

    data class ObdDeviceItem(
        val name: String,
        val address: String, // MAC Address for Bluetooth, "USB:<deviceId>" for USB OTG, or "WIFI:<host>:<port>"
        val isUsb: Boolean = false,
        val isWifi: Boolean = false
    )

    @SuppressLint("MissingPermission")
    fun getBondedDevices(): List<BluetoothDevice> {
        return try {
            val devices = bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
            devices.sortedBy { device ->
                val name = try { device.name ?: "" } catch (_: SecurityException) { "" }.uppercase()
                when {
                    name.contains("OBD") || name.contains("ELM") || name.contains("LINKER") -> 0
                    else -> 1
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "BLUETOOTH_CONNECT permission not granted yet for getBondedDevices()", e)
            emptyList()
        }
    }

    fun getAvailableDevices(context: android.content.Context): List<ObdDeviceItem> {
        val list = mutableListOf<ObdDeviceItem>()

        // 1. Scan for connected Wired USB OTG OBD2 Adapters (Vgate vLinker FS USB / FTDI / CH340 / CP210x)
        try {
            val usbManager = context.getSystemService(android.content.Context.USB_SERVICE) as? android.hardware.usb.UsbManager
            if (usbManager != null) {
                val availableDrivers = com.hoho.android.usbserial.driver.UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
                for (driver in availableDrivers) {
                    val dev = driver.device
                    val devName = when {
                        dev.vendorId == 0x0403 -> "Vgate vLinker FS (FTDI USB)"
                        dev.vendorId == 0x1A86 -> "Wired CH340 USB OBD2"
                        dev.vendorId == 0x10C4 -> "Wired CP210x USB OBD2"
                        else -> "Wired USB OBD2 (${dev.manufacturerName ?: "vLinker FS"})"
                    }
                    list.add(ObdDeviceItem(name = devName, address = "USB:${dev.deviceId}", isUsb = true))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error listing USB devices", e)
        }

        // 2. Add Standard Wi-Fi OBD2 Adapter option (ELM327 Wi-Fi / Vgate / Veepeak)
        list.add(
            ObdDeviceItem(
                name = "Wi-Fi OBD2 (192.168.0.10:35000)",
                address = "WIFI:192.168.0.10:35000",
                isUsb = false,
                isWifi = true
            )
        )

        // 3. Add Paired Bluetooth Devices
        try {
            val btDevices = bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
            val sortedBt = btDevices.sortedBy { device ->
                val name = try { device.name ?: "" } catch (_: SecurityException) { "" }.uppercase()
                when {
                    name.contains("OBD") || name.contains("ELM") || name.contains("LINKER") -> 0
                    else -> 1
                }
            }
            for (dev in sortedBt) {
                val name = try { dev.name ?: "Unknown OBD" } catch (_: SecurityException) { "OBD Device" }
                list.add(ObdDeviceItem(name = "$name (${dev.address})", address = dev.address, isUsb = false, isWifi = false))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error listing Bluetooth devices", e)
        }

        return list
    }

    /**
     * Connects to a Wi-Fi OBD2 Adapter (ELM327 Wi-Fi / Vgate Wi-Fi / Veepeak Wi-Fi).
     * Connects via standard TCP socket (default: 192.168.0.10:35000).
     */
    suspend fun connectToWifiDevice(host: String = "192.168.0.10", port: Int = 35000): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            disconnect()
            _connectionState.value = ConnectionState.Connecting("Wi-Fi OBD ($host:$port)")

            Log.i(TAG, "Opening TCP socket to Wi-Fi OBD2 adapter at $host:$port...")
            val s = java.net.Socket()
            s.tcpNoDelay = true
            s.connect(java.net.InetSocketAddress(host, port), 4000)

            wifiSocket = s
            inputStream = s.getInputStream()
            outputStream = s.getOutputStream()
            isWifiConnection = true
            isUsbConnection = false
            isDemoMode = false

            delay(300)
            startStreamReader()

            initializeElm327()
            discoverSupportedPids()
            fetchVehicleVin()

            _connectionState.value = ConnectionState.Connected("Wi-Fi OBD ($host:$port)")
            startPollingLoop()

            Log.i(TAG, "Successfully connected to Wi-Fi OBD2 adapter at $host:$port.")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to Wi-Fi OBD2 device at $host:$port", e)
            disconnect()
            val msg = e.localizedMessage ?: "Could not reach $host:$port. Make sure phone is connected to the OBD2 Wi-Fi network."
            _connectionState.value = ConnectionState.Error("Wi-Fi OBD connection failed: $msg")
            Result.failure(e)
        }
    }

    /**
     * Connects to a Wired USB OTG OBD2 Device (Vgate vLinker FS USB).
     */
    suspend fun connectToUsbDevice(context: android.content.Context, deviceIdInt: Int): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            disconnect()
            val usbManager = context.getSystemService(android.content.Context.USB_SERVICE) as? android.hardware.usb.UsbManager
                ?: return@withContext Result.failure(IllegalStateException("USB Manager not available"))

            val availableDrivers = com.hoho.android.usbserial.driver.UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            val driver = availableDrivers.find { it.device.deviceId == deviceIdInt }
                ?: availableDrivers.firstOrNull()
                ?: return@withContext Result.failure(IllegalStateException("No USB OBD2 device found. Ensure Vgate vLinker FS USB is connected via OTG."))

            val device = driver.device
            if (!usbManager.hasPermission(device)) {
                _connectionState.value = ConnectionState.Error("USB permission required for Vgate vLinker FS USB. Requesting permission...")
                val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) android.app.PendingIntent.FLAG_IMMUTABLE else 0
                val pi = android.app.PendingIntent.getBroadcast(context, 0, android.content.Intent("com.vera.app.USB_PERMISSION"), flags)
                usbManager.requestPermission(device, pi)
                return@withContext Result.failure(IllegalStateException("USB permission requested. Please tap ACCEPT on screen and reconnect."))
            }

            val connection = usbManager.openDevice(device)
                ?: return@withContext Result.failure(IllegalStateException("Could not open USB connection to Vgate vLinker FS USB"))

            val port = driver.ports[0]
            port.open(connection)

            // FTDI & Vgate vLinker FS USB Driver Configuration:
            // 1. Set baud rate to standard 115,200 (default vLinker FS speed)
            port.setParameters(115200, 8, com.hoho.android.usbserial.driver.UsbSerialPort.STOPBITS_1, com.hoho.android.usbserial.driver.UsbSerialPort.PARITY_NONE)

            // 2. CRITICAL for FTDI / vLinker FS USB: Lower FTDI latency timer from default 16ms to 2ms
            // SIO_SET_LATENCY_TIMER_REQUEST (0x09): eliminates 14ms of USB packet buffering dead time on every frame
            try {
                connection.controlTransfer(0x40, 0x09, 2, 1, null, 0, 500)
                Log.i(TAG, "FTDI latency timer configured to 2ms")
            } catch (e: Exception) {
                Log.w(TAG, "Could not set FTDI latency timer", e)
            }

            // 3. CRITICAL for FTDI / vLinker FS USB: Enable DTR & RTS signals to unblock FTDI FIFO buffer transmission!
            try {
                port.dtr = true
                port.rts = true
            } catch (e: Exception) {
                Log.w(TAG, "Could not set DTR/RTS on USB port", e)
            }

            usbSerialPort = port
            usbDeviceConnection = connection
            isUsbConnection = true
            isWifiConnection = false
            isDemoMode = false

            delay(300)
            startStreamReader()

            initializeElm327()
            discoverSupportedPids()
            fetchVehicleVin()

            val devName = device.manufacturerName ?: "Vgate vLinker FS USB"
            _connectionState.value = ConnectionState.Connected("USB: $devName")

            startPollingLoop()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to USB OBD2 device", e)
            disconnect()
            Result.failure(e)
        }
    }

    /**
     * Connects to a Bluetooth, USB, or Wi-Fi OBD2 device.
     */
    suspend fun connectToDevice(macOrUsbAddress: String, context: android.content.Context? = null): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                disconnect()
                if (macOrUsbAddress.startsWith("USB:") && context != null) {
                    val deviceIdInt = macOrUsbAddress.substringAfter("USB:").toIntOrNull() ?: 0
                    return@withContext connectToUsbDevice(context, deviceIdInt)
                }
                if (macOrUsbAddress.startsWith("WIFI:", ignoreCase = true) ||
                    macOrUsbAddress.startsWith("TCP:", ignoreCase = true) ||
                    macOrUsbAddress.contains(":35000") ||
                    (macOrUsbAddress.count { it == '.' } == 3 && macOrUsbAddress.any { it.isDigit() })) {
                    val cleaned = macOrUsbAddress
                        .removePrefix("WIFI:")
                        .removePrefix("wifi:")
                        .removePrefix("TCP:")
                        .removePrefix("tcp:")
                        .trim()
                    val parts = cleaned.split(":")
                    val host = parts.getOrNull(0)?.trim()?.ifBlank { "192.168.0.10" } ?: "192.168.0.10"
                    val port = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 35000
                    return@withContext connectToWifiDevice(host, port)
                }
                if (macOrUsbAddress == "DEMO_SIMULATOR_DIRTY" || macOrUsbAddress.contains("DIRTY", ignoreCase = true)) {
                    Log.i(TAG, "Connecting to Virtual Demo ECU Simulator (Dirty ECU - Cyl 5 Misfire)...")
                    demoPreset = DemoVehiclePreset.DIRTY_ECU
                    delay(300)
                    startDemoSimulatorLoop()
                    return@withContext Result.success(Unit)
                } else if (macOrUsbAddress == "DEMO_SIMULATOR_CLEAN" || macOrUsbAddress == "DEMO_SIMULATOR" || macOrUsbAddress.contains("DEMO", ignoreCase = true) || macOrUsbAddress.contains("CLEAN", ignoreCase = true)) {
                    Log.i(TAG, "Connecting to Virtual Demo ECU Simulator (Clean ECU)...")
                    demoPreset = DemoVehiclePreset.CLEAN_ECU
                    delay(300)
                    startDemoSimulatorLoop()
                    return@withContext Result.success(Unit)
                }

                val adapter = bluetoothAdapter ?: return@withContext Result.failure(IllegalStateException("Bluetooth not available"))
                val device = adapter.getRemoteDevice(macOrUsbAddress)
                val success = connect(device)
                if (success) {
                    Result.success(Unit)
                } else {
                    val errMsg = (_connectionState.value as? ConnectionState.Error)?.message ?: "Connection failed"
                    Result.failure(IllegalStateException(errMsg))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun readPid(pidHex: String): String {
        return sendAtCommand(pidHex, 100)
    }

    fun release() {
        disconnect()
        managerJob.cancel()
    }
}