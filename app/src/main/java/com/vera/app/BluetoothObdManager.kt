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
    val parseValue: (IntArray) -> Double // Parser formula taking byte array A, B, C...
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
        "P0500" to "Vehicle Speed Sensor Malfunction"
    )

    fun getDescription(code: String): String {
        return KNOWN_DESCRIPTIONS[code] ?: when {
            code.startsWith("P04") -> "Emissions / EVAP / Catalyst System Fault"
            code.startsWith("P03") -> "Ignition System or Engine Misfire Fault"
            code.startsWith("P01") -> "Fuel or Air Metering System Fault"
            code.startsWith("P02") -> "Fuel Injector Circuit Fault"
            code.startsWith("P05") -> "Idle Speed or Speed Sensor Fault"
            code.startsWith("P07") -> "Transmission Control System Fault"
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
    val parseValue: (IntArray) -> Double
)

data class Mode22StreamData(
    val pid: Mode22PidDefinition,
    val formattedValue: String = "--",
    val rawValue: Double = 0.0,
    val lastUpdated: Long = System.currentTimeMillis()
)


object Mode22Registry {
    val KNOWN_PIDS = listOf(
        // Ford / Lincoln / Mercury
        Mode22PidDefinition("1E1C", "Transmission Temp", "Transmission", "°C", "Ford Transmission Fluid Temp (5R55/6R80/4R75)", "Ford") { a -> if (a.size >= 2) ((a[0] * 256) + a[1]) / 16.0 - 40.0 else (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 },
        Mode22PidDefinition("1E01", "Transmission Temp Alt", "Transmission", "°C", "Ford Automatic Transmission Temp (5R110)", "Ford") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 },
        Mode22PidDefinition("0931", "Transmission Temp Alt 2", "Transmission", "°C", "Ford Transmission Temp 6R80/10R80", "Ford") { a -> if (a.size >= 2) ((a[0] * 256) + a[1]) / 16.0 - 40.0 else (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 },
        Mode22PidDefinition("1674", "Cylinder Head Temp", "Engine", "°C", "Ford Cylinder Head Temperature (CHT)", "Ford") { a -> if (a.size >= 2) ((a[0] * 256) + a[1]) / 64.0 else (a.getOrNull(0)?.toDouble() ?: 0.0) },
        Mode22PidDefinition("1672", "Cylinder Head Temp Raw", "Engine", "°C", "Ford Cylinder Head Temperature Raw", "Ford") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 },
        Mode22PidDefinition("1304", "Engine Oil Pressure", "Engine Oil", "psi", "Ford Engine Oil Pressure Sensor", "Ford") { a -> if (a.size >= 2) ((a[0] * 256) + a[1]) * 0.145 else 0.0 },
        Mode22PidDefinition("1E1E", "Engine Oil Pressure Alt", "Engine Oil", "psi", "Ford Engine Oil Pressure Gauge", "Ford") { a -> if (a.size >= 2) ((a[0] * 256) + a[1]) * 0.145 else 0.0 },
        Mode22PidDefinition("056D", "Engine Oil Switch", "Engine Oil", "psi", "Ford Engine Oil Pressure Switch", "Ford") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) * 0.5 },
        Mode22PidDefinition("1310", "Engine Oil Temp", "Engine Oil", "°C", "Ford Engine Oil Temperature", "Ford") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 },
        Mode22PidDefinition("0483", "EcoBoost Turbo Pressure", "Engine Boost", "psi", "Ford EcoBoost Turbocharger Gauge Pressure", "Ford") { a -> if (a.size >= 2) (((a[0] * 256) + a[1]) * 0.03625) - 14.7 else 0.0 },
        Mode22PidDefinition("0484", "EcoBoost Turbo Compressor", "Engine Boost", "psi", "Ford EcoBoost Compressor Outlet Pressure", "Ford") { a -> if (a.size >= 2) (((a[0] * 256) + a[1]) * 0.03625) - 14.7 else 0.0 },
        Mode22PidDefinition("162B", "Torque Converter Slip", "Transmission", "RPM", "Ford Torque Converter Lockup Slip Speed", "Ford") { a -> if (a.size >= 2) ((a[0] * 256) + a[1]) / 4.0 else 0.0 },
        Mode22PidDefinition("092A", "Torque Converter Slip Alt", "Transmission", "RPM", "Ford Torque Converter Lockup Slip", "Ford") { a -> if (a.size >= 2) ((a[0] * 256) + a[1]) / 4.0 else 0.0 },
        Mode22PidDefinition("162C", "Transmission Gear Ratio", "Transmission", "Ratio", "Ford Transmission Gear Ratio", "Ford") { a -> if (a.size >= 2) ((a[0] * 256) + a[1]) / 1000.0 else 0.0 },
        Mode22PidDefinition("11B3", "Transmission Commanded Gear", "Transmission", "Gear", "Ford Transmission Commanded Gear", "Ford") { a -> a.getOrNull(0)?.toDouble() ?: 0.0 },
        Mode22PidDefinition("160E", "A/C Pressure Voltage", "Climate", "V", "Ford A/C Pressure Sensor Voltage", "Ford") { a -> if (a.size >= 2) ((a[0] * 256) + a[1]) / 1023.0 * 5.0 else 0.0 },
        Mode22PidDefinition("0322", "A/C Pressure Gauge", "Climate", "psi", "Ford A/C Refrigerant Pressure", "Ford") { a -> if (a.size >= 2) ((a[0] * 256) + a[1]) * 0.145 else 0.0 },
        Mode22PidDefinition("11B6", "Fuel Rail High Pressure", "Fuel System", "kPa", "Ford Direct Injection High Pressure System", "Ford") { a -> if (a.size >= 2) ((a[0] * 256) + a[1]) * 10.0 else 0.0 },
        Mode22PidDefinition("11B7", "Fuel Pump Commanded", "Fuel System", "kPa", "Ford High Pressure Fuel Pump Commanded", "Ford") { a -> if (a.size >= 2) ((a[0] * 256) + a[1]) * 10.0 else 0.0 },
        Mode22PidDefinition("054B", "Engine Oil Life", "Maintenance", "%", "Ford Engine Oil Life Remaining Index", "Ford") { a -> ((a.getOrNull(0) ?: 0) * 100) / 255.0 },
        Mode22PidDefinition("1801", "AWD Clutch Duty", "Drivetrain", "%", "Ford AWD Transfer Case Torque Split Duty Cycle", "Ford") { a -> ((a.getOrNull(0) ?: 0) * 100) / 255.0 },
        Mode22PidDefinition("404C", "Cluster Odometer", "Maintenance", "km", "Ford Instrument Cluster Total Odometer (2004-2008 F-150 / HEC)", "Ford") { a -> if (a.size >= 4) ((a[0].toLong() shl 24) or (a[1].toLong() shl 16) or (a[2].toLong() shl 8) or a[3].toLong()).toDouble() * 0.1 else if (a.size >= 3) ((a[0].toLong() shl 16) or (a[1].toLong() shl 8) or a[2].toLong()).toDouble() * 0.1 else 0.0 },
        Mode22PidDefinition("DD01", "Vehicle Odometer", "Maintenance", "km", "Ford Total Vehicle Odometer (IPC / BCM)", "Ford") { a -> if (a.size >= 4) ((a[0].toLong() shl 24) or (a[1].toLong() shl 16) or (a[2].toLong() shl 8) or a[3].toLong()).toDouble() / 10.0 else if (a.size >= 3) ((a[0].toLong() shl 16) or (a[1].toLong() shl 8) or a[2].toLong()).toDouble() else 0.0 },
        Mode22PidDefinition("042F", "Battery State of Charge", "Electrical", "%", "Ford 12V Battery State of Charge (BMS)", "Ford") { a -> ((a.getOrNull(0) ?: 0) * 100) / 255.0 },
        Mode22PidDefinition("042C", "Battery Voltage (BCM)", "Electrical", "V", "Ford Battery Terminal Voltage (BCM)", "Ford") { a -> if (a.size >= 2) ((a[0] * 256) + a[1]) / 100.0 else 0.0 },
        Mode22PidDefinition("11A2", "DPF Soot Load", "Emissions", "%", "Ford Diesel Particulate Filter Soot Load", "Ford") { a -> ((a.getOrNull(0) ?: 0) * 100) / 255.0 },
        Mode22PidDefinition("11A3", "Exhaust Gas Temp 1", "Emissions", "°C", "Ford Exhaust Gas Temperature EGT1", "Ford") { a -> if (a.size >= 2) (((a[0] * 256) + a[1]) / 10.0) - 40.0 else 0.0 },
        Mode22PidDefinition("11A4", "Exhaust Gas Temp 2", "Emissions", "°C", "Ford Exhaust Gas Temperature EGT2", "Ford") { a -> if (a.size >= 2) (((a[0] * 256) + a[1]) / 10.0) - 40.0 else 0.0 },
        Mode22PidDefinition("03B6", "Charge Air Cooler Temp", "Engine", "°C", "Ford Charge Air Cooler Temp (CAC)", "Ford") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 },
        Mode22PidDefinition("11A6", "Knock Retard Cyl 1", "Engine", "°", "Ford Knock Retard Cylinder 1", "Ford") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) * 0.25 },

        // GM / Chevrolet / GMC / Buick / Cadillac / Pontiac / Saturn
        Mode22PidDefinition("1940", "Transmission Temp", "Transmission", "°C", "GM Transmission Fluid Temp (4L60E/4L80E)", "GM") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 },
        Mode22PidDefinition("1942", "Transmission Temp Alt", "Transmission", "°C", "GM Transmission Fluid Temp (6L80/8L90/10L90)", "GM") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 },
        Mode22PidDefinition("1943", "Torque Converter Slip", "Transmission", "RPM", "GM Torque Converter Clutch Slip", "GM") { a -> if (a.size >= 2) ((a[0] * 256) + a[1]).toShort().toDouble() else 0.0 },
        Mode22PidDefinition("1999", "Transmission Current Gear", "Transmission", "Gear", "GM Transmission Commanded Gear", "GM") { a -> a.getOrNull(0)?.toDouble() ?: 0.0 },
        Mode22PidDefinition("1154", "Engine Oil Temp", "Engine Oil", "°C", "GM Engine Oil Temperature", "GM") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 },
        Mode22PidDefinition("1150", "Engine Oil Pressure", "Engine Oil", "kPa", "GM Engine Oil Pressure", "GM") { a -> (a.getOrNull(0) ?: 0) * 4.0 },
        Mode22PidDefinition("1162", "Knock Retard", "Engine", "°", "GM Knock Retard Total", "GM") { a -> (a.getOrNull(0) ?: 0) * 0.3515 },
        Mode22PidDefinition("1163", "Knock Retard Cyl 1", "Engine", "°", "GM Knock Retard Cylinder 1", "GM") { a -> (a.getOrNull(0) ?: 0) * 0.3515 },
        Mode22PidDefinition("11A1", "Fuel Rail High Pressure", "Fuel System", "kPa", "GM Direct Injection Fuel Pressure", "GM") { a -> if (a.size >= 2) ((a[0] * 256) + a[1]) * 0.1 else 0.0 },
        Mode22PidDefinition("115A", "Engine Oil Life", "Maintenance", "%", "GM Oil Life Remaining Percentage", "GM") { a -> ((a.getOrNull(0) ?: 0) * 100) / 255.0 },
        Mode22PidDefinition("115C", "Vehicle Odometer", "Maintenance", "km", "GM Total Distance Odometer", "GM") { a -> if (a.size >= 4) ((a[0].toLong() shl 24) or (a[1].toLong() shl 16) or (a[2].toLong() shl 8) or a[3].toLong()).toDouble() / 64.0 else if (a.size >= 3) ((a[0].toLong() shl 16) or (a[1].toLong() shl 8) or a[2].toLong()).toDouble() else 0.0 },
        Mode22PidDefinition("1141", "Ethanol Alcohol Percent", "Fuel System", "%", "GM FlexFuel Alcohol Content Sensor", "GM") { a -> ((a.getOrNull(0) ?: 0) * 100) / 255.0 },
        Mode22PidDefinition("1153", "Boost Pressure Sensor", "Engine Boost", "kPa", "GM Turbocharger / Supercharger Boost", "GM") { a -> if (a.size >= 2) ((a[0] * 256) + a[1]) * 0.05 else 0.0 },
        Mode22PidDefinition("11B4", "Exhaust Gas Temp 1", "Emissions", "°C", "GM Duramax Exhaust Gas Temp 1", "GM") { a -> if (a.size >= 2) (((a[0] * 256) + a[1]) / 10.0) - 40.0 else 0.0 },
        Mode22PidDefinition("11BD", "DPF Soot Mass", "Emissions", "g", "GM Duramax DPF Soot Accumulation Mass", "GM") { a -> if (a.size >= 2) ((a[0] * 256) + a[1]) / 100.0 else 0.0 },
        Mode22PidDefinition("1165", "Camshaft Retard Offset", "Engine", "°", "GM CMP Camshaft Retard Offset", "GM") { a -> ((a.getOrNull(0) ?: 128) - 128) * 0.5 },

        // Toyota / Lexus / Scion
        Mode22PidDefinition("1620", "Transmission Pan Temp", "Transmission", "°C", "Toyota Transmission Fluid Temp Pan", "Toyota", servicePrefix = "21") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 },
        Mode22PidDefinition("1621", "Transmission Outlet Temp", "Transmission", "°C", "Toyota Torque Converter Outlet Temp", "Toyota", servicePrefix = "21") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 },
        Mode22PidDefinition("1624", "ATF Degradation Index", "Transmission", "counts", "Toyota Automatic Transmission Fluid Degradation", "Toyota", servicePrefix = "21") { a -> if (a.size >= 2) ((a[0] * 256) + a[1]).toDouble() else 0.0 },
        Mode22PidDefinition("1101", "Engine Oil Temp", "Engine Oil", "°C", "Toyota Engine Oil Temperature", "Toyota", servicePrefix = "21") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 },
        Mode22PidDefinition("1625", "Hybrid HV Battery SoC", "Battery", "%", "Toyota High Voltage Battery State of Charge", "Toyota", servicePrefix = "21") { a -> (a.getOrNull(0) ?: 0) * 0.5 },
        Mode22PidDefinition("1630", "Hybrid HV Battery Voltage", "Battery", "V", "Toyota High Voltage Battery Voltage", "Toyota", servicePrefix = "21") { a -> if (a.size >= 2) ((a[0] * 256) + a[1]) * 0.1 else 0.0 },
        Mode22PidDefinition("1635", "HV Battery Cell Delta", "Hybrid Battery", "V", "Toyota High Voltage Battery Cell Max-Min Delta", "Toyota", servicePrefix = "21") { a -> if (a.size >= 2) ((a[0] * 256) + a[1]) / 1000.0 else 0.0 },
        Mode22PidDefinition("1628", "Hybrid MG1 Inverter Temp", "Hybrid Battery", "°C", "Toyota Hybrid Motor Generator 1 Inverter Temp", "Toyota", servicePrefix = "21") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 },
        Mode22PidDefinition("1629", "Hybrid MG2 Inverter Temp", "Hybrid Battery", "°C", "Toyota Hybrid Motor Generator 2 Inverter Temp", "Toyota", servicePrefix = "21") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 },
        Mode22PidDefinition("1638", "Hybrid MG1 RPM", "Hybrid Battery", "RPM", "Toyota Hybrid Motor Generator 1 Speed", "Toyota", servicePrefix = "21") { a -> if (a.size >= 2) ((a[0] * 256) + a[1]).toShort().toDouble() else 0.0 },
        Mode22PidDefinition("1639", "Hybrid MG2 RPM", "Hybrid Battery", "RPM", "Toyota Hybrid Motor Generator 2 Speed", "Toyota", servicePrefix = "21") { a -> if (a.size >= 2) ((a[0] * 256) + a[1]).toShort().toDouble() else 0.0 },
        Mode22PidDefinition("1631", "Auxiliary 12V Voltage", "Electrical", "V", "Toyota 12V Auxiliary Battery Voltage", "Toyota", servicePrefix = "21") { a -> if (a.size >= 2) ((a[0] * 256) + a[1]) / 1000.0 else 0.0 },
        Mode22PidDefinition("1626", "Vehicle Odometer", "Maintenance", "km", "Toyota Vehicle Total Odometer", "Toyota", servicePrefix = "21") { a -> if (a.size >= 3) ((a[0].toLong() shl 16) or (a[1].toLong() shl 8) or a[2].toLong()).toDouble() else 0.0 },
        Mode22PidDefinition("1681", "Tire Pressure FL", "Chassis", "psi", "Toyota TPMS Front Left Pressure", "Toyota", servicePrefix = "21") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) * 0.2 },
        Mode22PidDefinition("1682", "Tire Pressure FR", "Chassis", "psi", "Toyota TPMS Front Right Pressure", "Toyota", servicePrefix = "21") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) * 0.2 },
        Mode22PidDefinition("1683", "Tire Pressure RL", "Chassis", "psi", "Toyota TPMS Rear Left Pressure", "Toyota", servicePrefix = "21") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) * 0.2 },
        Mode22PidDefinition("1684", "Tire Pressure RR", "Chassis", "psi", "Toyota TPMS Rear Right Pressure", "Toyota", servicePrefix = "21") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) * 0.2 },
        Mode22PidDefinition("1620", "Transmission Pan Temp Alt", "Transmission", "°C", "Toyota Transmission Fluid Temp Pan", "Toyota", servicePrefix = "22") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 },
        Mode22PidDefinition("1621", "Transmission Outlet Temp Alt", "Transmission", "°C", "Toyota Torque Converter Outlet Temp", "Toyota", servicePrefix = "22") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 },
        Mode22PidDefinition("1101", "Engine Oil Temp Alt", "Engine Oil", "°C", "Toyota Engine Oil Temperature", "Toyota", servicePrefix = "22") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 },

        // Honda / Acura
        Mode22PidDefinition("2201", "Transmission Temp", "Transmission", "°C", "Honda Automatic Transmission Temp", "Honda") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 },
        Mode22PidDefinition("2205", "Engine Oil Temp", "Engine Oil", "°C", "Honda Engine Oil Temperature", "Honda") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 },
        Mode22PidDefinition("2210", "VTEC Solenoid Pressure", "Engine", "kPa", "Honda VTEC Pressure Solenoid", "Honda") { a -> (a.getOrNull(0) ?: 0) * 2.5 },
        Mode22PidDefinition("2208", "Knock Retard", "Engine", "°", "Honda Knock Retard", "Honda") { a -> ((a.getOrNull(0) ?: 128) - 128) * 0.5 },
        Mode22PidDefinition("2209", "CVT Pulley Ratio", "Transmission", "Ratio", "Honda CVT Pulley Speed Ratio", "Honda") { a -> if (a.size >= 2) ((a[0] * 256) + a[1]) / 1000.0 else 0.0 },
        Mode22PidDefinition("2220", "Hybrid IMA Battery SoC", "Battery", "%", "Honda IMA Hybrid Battery State of Charge", "Honda") { a -> ((a.getOrNull(0) ?: 0) * 100) / 255.0 },
        Mode22PidDefinition("2221", "Hybrid IMA Motor Temp", "Battery", "°C", "Honda IMA Hybrid Electric Motor Temp", "Honda") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 },
        Mode22PidDefinition("2202", "Vehicle Odometer", "Maintenance", "km", "Honda Vehicle Total Odometer", "Honda") { a -> if (a.size >= 3) ((a[0].toLong() shl 16) or (a[1].toLong() shl 8) or a[2].toLong()).toDouble() else 0.0 },
        Mode22PidDefinition("2203", "Fuel Tank Level (Liters)", "Fuel System", "L", "Honda Fuel Remaining in Tank", "Honda") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) * 0.5 },

        // Chrysler / Dodge / Jeep / Ram / Fiat
        Mode22PidDefinition("111E", "Transmission Temp", "Transmission", "°C", "Chrysler Transmission Fluid Temp", "Chrysler") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 },
        Mode22PidDefinition("2101", "Transmission Temp Alt", "Transmission", "°C", "Chrysler Transmission Temp (Nag1/8HP70)", "Chrysler", servicePrefix = "21") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 },
        Mode22PidDefinition("111D", "Engine Oil Temp", "Engine Oil", "°C", "Chrysler Engine Oil Temperature", "Chrysler") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 },
        Mode22PidDefinition("1122", "Engine Oil Pressure", "Engine Oil", "psi", "Chrysler Engine Oil Pressure", "Chrysler") { a -> (a.getOrNull(0) ?: 0) * 1.0 },
        Mode22PidDefinition("1120", "Torque Converter Slip", "Transmission", "RPM", "Chrysler Torque Converter Lockup Slip", "Chrysler") { a -> if (a.size >= 2) ((a[0] * 256) + a[1]).toShort().toDouble() else 0.0 },
        Mode22PidDefinition("1130", "Cummins Fuel Rail Pressure", "Fuel System", "psi", "Chrysler Cummins Diesel Fuel Rail Pressure", "Chrysler") { a -> if (a.size >= 2) ((a[0] * 256) + a[1]) * 1.0 else 0.0 },
        Mode22PidDefinition("1131", "Cummins Turbo Boost", "Engine Boost", "psi", "Chrysler Cummins Turbocharger Boost Pressure", "Chrysler") { a -> if (a.size >= 2) ((a[0] * 256) + a[1]) * 0.05 else 0.0 },
        Mode22PidDefinition("1132", "Cummins Pre-Turbo EGT", "Emissions", "°C", "Chrysler Cummins Exhaust Gas Temp Pre-Turbo", "Chrysler") { a -> if (a.size >= 2) (((a[0] * 256) + a[1]) / 10.0) - 40.0 else 0.0 },
        Mode22PidDefinition("1133", "Cummins DPF Soot Load", "Emissions", "%", "Chrysler Cummins DPF Soot Percentage", "Chrysler") { a -> ((a.getOrNull(0) ?: 0) * 100) / 255.0 },
        Mode22PidDefinition("01B4", "Vehicle Odometer", "Maintenance", "km", "Chrysler Total Vehicle Mileage", "Chrysler") { a -> if (a.size >= 4) ((a[0].toLong() shl 24) or (a[1].toLong() shl 16) or (a[2].toLong() shl 8) or a[3].toLong()).toDouble() / 10.0 else if (a.size >= 3) ((a[0].toLong() shl 16) or (a[1].toLong() shl 8) or a[2].toLong()).toDouble() else 0.0 },
        Mode22PidDefinition("1140", "Tire Pressure FL", "Chassis", "psi", "Chrysler TPMS Front Left Pressure", "Chrysler") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) * 0.25 },
        Mode22PidDefinition("1141", "Tire Pressure FR", "Chassis", "psi", "Chrysler TPMS Front Right Pressure", "Chrysler") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) * 0.25 },
        Mode22PidDefinition("1142", "Tire Pressure RL", "Chassis", "psi", "Chrysler TPMS Rear Left Pressure", "Chrysler") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) * 0.25 },
        Mode22PidDefinition("1143", "Tire Pressure RR", "Chassis", "psi", "Chrysler TPMS Rear Right Pressure", "Chrysler") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) * 0.25 },

        // Nissan / Infiniti
        Mode22PidDefinition("0005", "CVT Fluid Temp", "Transmission", "°C", "Nissan CVT Fluid Temperature", "Nissan", servicePrefix = "21") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 },
        Mode22PidDefinition("0008", "CVT Degradation Count", "Transmission", "counts", "Nissan CVT Fluid Degradation Index", "Nissan", servicePrefix = "21") { a -> if (a.size >= 2) ((a[0] * 256) + a[1]).toDouble() else 0.0 },
        Mode22PidDefinition("0006", "Engine Oil Temp", "Engine Oil", "°C", "Nissan Engine Oil Temperature", "Nissan", servicePrefix = "21") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 },
        Mode22PidDefinition("0007", "Engine Oil Pressure", "Engine Oil", "kPa", "Nissan Engine Oil Pressure", "Nissan", servicePrefix = "21") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) * 2.0 },
        Mode22PidDefinition("0009", "Vehicle Odometer", "Maintenance", "km", "Nissan Vehicle Total Distance", "Nissan", servicePrefix = "21") { a -> if (a.size >= 3) ((a[0].toLong() shl 16) or (a[1].toLong() shl 8) or a[2].toLong()).toDouble() else 0.0 },
        Mode22PidDefinition("000B", "Fuel Tank Level", "Fuel System", "%", "Nissan Fuel Level Sensor", "Nissan", servicePrefix = "21") { a -> ((a.getOrNull(0) ?: 0) * 100) / 255.0 },
        Mode22PidDefinition("0015", "Steering Angle Sensor", "Chassis", "°", "Nissan Steering Wheel Angle Position", "Nissan", servicePrefix = "21") { a -> if (a.size >= 2) ((a[0] shl 8) or a[1]).toShort() * 0.1 else 0.0 },

        // Volkswagen / Audi / SEAT / Skoda / Porsche (VAG)
        Mode22PidDefinition("0101", "DSG Transmission Temp", "Transmission", "°C", "VW/Audi DSG Transmission Temp", "Volkswagen") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 },
        Mode22PidDefinition("1001", "Transmission Temp Alt", "Transmission", "°C", "VW/Audi S-Tronic / Tiptronic Transmission Temp", "Volkswagen") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 },
        Mode22PidDefinition("0105", "Engine Oil Temp", "Engine Oil", "°C", "VW/Audi Engine Oil Temperature", "Volkswagen") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 },
        Mode22PidDefinition("2005", "Engine Oil Temp (UDS)", "Engine Oil", "°C", "VW/Audi UDS Engine Oil Temperature", "Volkswagen") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 },
        Mode22PidDefinition("0108", "Turbo Boost Actual", "Engine Boost", "kPa", "VW/Audi Turbocharger Actual Boost Pressure", "Volkswagen") { a -> if (a.size >= 2) ((a[0] * 256) + a[1]) / 100.0 else 0.0 },
        Mode22PidDefinition("0109", "Turbo Boost Specified", "Engine Boost", "kPa", "VW/Audi Commanded Boost Pressure", "Volkswagen") { a -> if (a.size >= 2) ((a[0] * 256) + a[1]) / 100.0 else 0.0 },
        Mode22PidDefinition("010A", "Total Misfires", "Engine", "counts", "VW/Audi Total Cylinder Misfire Counter", "Volkswagen") { a -> if (a.size >= 2) ((a[0] * 256) + a[1]).toDouble() else 0.0 },
        Mode22PidDefinition("0115", "DPF Soot Mass", "Emissions", "g", "VW/Audi TDI Diesel Particulate Filter Soot Mass", "Volkswagen") { a -> if (a.size >= 2) ((a[0] * 256) + a[1]) / 100.0 else 0.0 },
        Mode22PidDefinition("0120", "Haldex AWD Coupling Temp", "Drivetrain", "°C", "VW/Audi 4Motion / Quattro Haldex Oil Temp", "Volkswagen") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 },
        Mode22PidDefinition("0102", "Vehicle Odometer", "Maintenance", "km", "VW/Audi Vehicle Total Odometer", "Volkswagen") { a -> if (a.size >= 4) ((a[0].toLong() shl 24) or (a[1].toLong() shl 16) or (a[2].toLong() shl 8) or a[3].toLong()).toDouble() else if (a.size >= 3) ((a[0].toLong() shl 16) or (a[1].toLong() shl 8) or a[2].toLong()).toDouble() else 0.0 },

        // Subaru
        Mode22PidDefinition("1121", "CVT Fluid Temp", "Transmission", "°C", "Subaru Lineartronic CVT Fluid Temperature", "Subaru") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 },
        Mode22PidDefinition("1125", "Engine Oil Temp", "Engine Oil", "°C", "Subaru Engine Oil Temperature", "Subaru") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 },
        Mode22PidDefinition("1126", "Knock Learning FLKC", "Engine", "°", "Subaru Fine Learning Knock Correction", "Subaru") { a -> ((a.getOrNull(0) ?: 128) - 128) * 0.3515 },
        Mode22PidDefinition("1127", "Turbo Boost Gauge", "Engine Boost", "psi", "Subaru Turbocharger Boost Pressure", "Subaru") { a -> if (a.size >= 2) (((a[0] * 256) + a[1]) * 0.0193) - 14.7 else 0.0 },
        Mode22PidDefinition("1128", "VVT Intake Angle Left", "Engine", "°", "Subaru Variable Valve Timing Left", "Subaru") { a -> ((a.getOrNull(0) ?: 128) - 128) * 0.5 },
        Mode22PidDefinition("1129", "VVT Intake Angle Right", "Engine", "°", "Subaru Variable Valve Timing Right", "Subaru") { a -> ((a.getOrNull(0) ?: 128) - 128) * 0.5 },
        Mode22PidDefinition("112A", "CVT Oil Degradation", "Transmission", "counts", "Subaru Lineartronic Fluid Degradation Index", "Subaru") { a -> if (a.size >= 2) ((a[0] * 256) + a[1]).toDouble() else 0.0 },

        // BMW / Mini
        Mode22PidDefinition("1001", "Transmission Temp", "Transmission", "°C", "BMW ZF 6HP/8HP Automatic Transmission Temp", "BMW") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 },
        Mode22PidDefinition("1002", "Engine Oil Temp", "Engine Oil", "°C", "BMW Engine Oil Temperature", "BMW") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 },
        Mode22PidDefinition("1003", "Engine Oil Pressure", "Engine Oil", "kPa", "BMW Engine Oil Pressure", "BMW") { a -> if (a.size >= 2) ((a[0] * 256) + a[1]) * 0.1 else 0.0 },
        Mode22PidDefinition("1004", "Radiator Outlet Temp", "Cooling", "°C", "BMW Radiator Coolant Outlet Temperature", "BMW") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 },
        Mode22PidDefinition("1005", "VANOS Intake Position", "Engine", "°", "BMW VANOS Camshaft Intake Position", "BMW") { a -> ((a.getOrNull(0) ?: 128) - 128) * 0.5 },
        Mode22PidDefinition("1008", "Electric Coolant Pump", "Cooling", "%", "BMW Electric Water Pump Speed", "BMW") { a -> ((a.getOrNull(0) ?: 0) * 100) / 255.0 },

        // Hyundai / Kia / Genesis
        Mode22PidDefinition("0101", "Transmission Temp", "Transmission", "°C", "Hyundai/Kia Automatic Transmission Temp", "Hyundai") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 },
        Mode22PidDefinition("0102", "Engine Oil Temp", "Engine Oil", "°C", "Hyundai/Kia Engine Oil Temperature", "Hyundai") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 },
        Mode22PidDefinition("0103", "DCT Clutch Temp", "Transmission", "°C", "Hyundai/Kia Dual-Clutch Gearbox Temp", "Hyundai") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 },
        Mode22PidDefinition("0106", "EV/HV Battery SoC", "Battery", "%", "Hyundai/Kia EV High Voltage Battery State of Charge", "Hyundai") { a -> ((a.getOrNull(0) ?: 0) * 100) / 255.0 },
        Mode22PidDefinition("0107", "EV Battery Max Cell Volt", "Battery", "V", "Hyundai/Kia EV Maximum Battery Cell Voltage", "Hyundai") { a -> if (a.size >= 2) ((a[0] * 256) + a[1]) / 1000.0 else 0.0 },
        Mode22PidDefinition("0108", "EV Battery Min Cell Volt", "Battery", "V", "Hyundai/Kia EV Minimum Battery Cell Voltage", "Hyundai") { a -> if (a.size >= 2) ((a[0] * 256) + a[1]) / 1000.0 else 0.0 },
        Mode22PidDefinition("0109", "EV Battery Pack Temp", "Battery", "°C", "Hyundai/Kia EV High Voltage Battery Pack Temp", "Hyundai") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 },

        // Mazda
        Mode22PidDefinition("0101", "Transmission Temp", "Transmission", "°C", "Mazda SkyActiv-Drive Automatic Transmission Temp", "Mazda") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 },
        Mode22PidDefinition("0105", "Engine Oil Temp", "Engine Oil", "°C", "Mazda SkyActiv Engine Oil Temperature", "Mazda") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 },
        Mode22PidDefinition("0104", "Transmission Current Gear", "Transmission", "Gear", "Mazda Commanded Gear Position", "Mazda") { a -> a.getOrNull(0)?.toDouble() ?: 0.0 },
        Mode22PidDefinition("0109", "DPF Soot Accumulation", "Emissions", "%", "Mazda SkyActiv-D Diesel DPF Soot Load", "Mazda") { a -> ((a.getOrNull(0) ?: 0) * 100) / 255.0 },

        // Mercedes-Benz / Smart
        Mode22PidDefinition("0101", "Transmission Temp", "Transmission", "°C", "Mercedes-Benz 7G/9G-Tronic Transmission Temp", "Mercedes") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 },
        Mode22PidDefinition("0102", "Engine Oil Temp", "Engine Oil", "°C", "Mercedes-Benz Engine Oil Temperature", "Mercedes") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 },
        Mode22PidDefinition("0103", "Engine Oil Level", "Engine Oil", "mm", "Mercedes-Benz Electronic Oil Level Indicator", "Mercedes") { a -> a.getOrNull(0)?.toDouble() ?: 0.0 },
        Mode22PidDefinition("0104", "EGT Pre-Catalyst", "Emissions", "°C", "Mercedes-Benz Exhaust Gas Temp Before Catalytic Converter", "Mercedes") { a -> if (a.size >= 2) (((a[0] * 256) + a[1]) / 10.0) - 40.0 else 0.0 },

        // Universal Fallback & Multi-Vehicle Sub-System PIDs
        Mode22PidDefinition("0052", "Ethanol Content", "Fuel System", "%", "E85 Flex-Fuel Ethanol Concentration", "Universal") { a -> ((a.getOrNull(0) ?: 0) * 100) / 255.0 },
        Mode22PidDefinition("11AF", "DPF Soot Mass", "Emissions", "%", "Diesel Particulate Filter Soot Load Accumulation", "Universal") { a -> ((a.getOrNull(0) ?: 0) * 100) / 255.0 },
        Mode22PidDefinition("11B2", "Steering Angle", "Chassis", "°", "Steering Wheel Angle Position", "Universal") { a -> if (a.size >= 2) ((a[0] shl 8) or a[1]).toShort() * 0.1 else 0.0 },
        Mode22PidDefinition("1610", "A/C Evaporator Temp", "Climate", "°C", "A/C Evaporator Core Temp (Anti-Freeze Guard)", "Universal") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 },
        Mode22PidDefinition("1001", "Transmission Temp (Univ)", "Transmission", "°C", "Universal Transmission Fluid Temp", "Universal") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 },
        Mode22PidDefinition("1002", "Engine Oil Temp (Univ)", "Engine Oil", "°C", "Universal Engine Oil Temp", "Universal") { a -> (a.getOrNull(0)?.toDouble() ?: 0.0) - 40.0 }
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

        val isHonda = m.contains("HONDA") || m.contains("ACURA") || wmi.startsWith("1H") || wmi.startsWith("2H") || wmi.startsWith("JH") || wmi.startsWith("5J")
        val isNissan = m.contains("NISSAN") || m.contains("INFINITI") || wmi.startsWith("JN") || wmi.startsWith("1N") || wmi.startsWith("5N")

        val isVag = m.contains("VOLKSWAGEN") || m.contains("VW") || m.contains("AUDI") || m.contains("PORSCHE") || m.contains("SEAT") || m.contains("SKODA") ||
                    wmi.startsWith("WV") || wmi.startsWith("WA") || wmi.startsWith("WP") || wmi.startsWith("3V") || wmi.startsWith("1V")

        val isSubaru = m.contains("SUBARU") || wmi.startsWith("JF") || wmi.startsWith("4S")
        val isBmw = m.contains("BMW") || m.contains("MINI") || wmi.startsWith("WB") || wmi.startsWith("4U") || wmi.startsWith("5U") || wmi.startsWith("WM")
        val isHyundai = m.contains("HYUNDAI") || m.contains("KIA") || m.contains("GENESIS") ||
                        wmi.startsWith("KM") || wmi.startsWith("KN") || wmi.startsWith("5N") || wmi.startsWith("5X")
        val isMazda = m.contains("MAZDA") || wmi.startsWith("JM") || wmi.startsWith("1Y") || wmi.startsWith("4F")
        val isMercedes = m.contains("MERCEDES") || m.contains("BENZ") || m.contains("SMART") ||
                         wmi.startsWith("WD") || wmi.startsWith("4J") || wmi.startsWith("55")

        val match = KNOWN_PIDS.filter { pid ->
            val pM = pid.manufacturer.uppercase()
            (isFord && pM == "FORD") ||
            (isGm && pM == "GM") ||
            (isToyota && pM == "TOYOTA") ||
            (isChrysler && pM == "CHRYSLER") ||
            (isHonda && pM == "HONDA") ||
            (isNissan && pM == "NISSAN") ||
            (isVag && (pM == "VOLKSWAGEN" || pM == "VAG")) ||
            (isSubaru && pM == "SUBARU") ||
            (isBmw && pM == "BMW") ||
            (isHyundai && pM == "HYUNDAI") ||
            (isMazda && pM == "MAZDA") ||
            (isMercedes && pM == "MERCEDES") ||
            pid.manufacturer == "Universal"
        }

        return if (match.isNotEmpty()) match else KNOWN_PIDS.filter { it.manufacturer == "Universal" }
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

    // Synchronization for synchronous AT command-response flow
    private val responseMutex = Mutex()
    private var latestResponse = StringBuilder()
    private var promptReceived = false

    var isDemoMode = false
        private set
    var demoPreset: DemoVehiclePreset = DemoVehiclePreset.CLEAN_ECU

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
        } else {
            _metrics.value = _metrics.value.copy(
                dtcs = emptyList(),
                rpm = 780,
                coolantTemp = 90,
                fuelTrim = 1,
                engineLoad = 22
            )
            _mode06Results.value = emptyList()
        }

        val demoOdoKm = if (demoPreset == DemoVehiclePreset.DIRTY_ECU) 184290.0 else 67450.0
        _vehicleOdometerKm.value = demoOdoKm

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

                delay(250)
            }
        }
    }

    /**
     * Connects to a Bluetooth OBD2 device with automatic secure/insecure fallback and reflection.
     * Returns true if connection and ELM327 handshake succeed, false otherwise.
     */
    suspend fun connect(device: BluetoothDevice, secure: Boolean = true): Boolean = withContext(Dispatchers.IO) {
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

                // Query vehicle odometer reading via standard Mode 01 PID A6 or Mode 22 enhanced manufacturer PID
                fetchVehicleOdometer()

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

        // Initialize StateFlow list of Supported Streams
        _supportedStreams.value = discoveredPidDefinitions.map { LiveStreamData(pid = it) }
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
            val demoResults = mapOf(
                "engine_rpm" to (if (demoPreset == DemoVehiclePreset.DIRTY_ECU) 740.0 else 780.0),
                "coolant_temp" to (if (demoPreset == DemoVehiclePreset.DIRTY_ECU) 98.0 else 90.0),
                "fuel_level" to 65.5,
                "intake_air_temp" to 28.0,
                "ambient_air_temp" to 22.0
            )
            _obdbSignalResults.value = demoResults
            return demoResults
        }
        val loaded = (_obdbLoadedResult.value as? ObdbRepository.LoadResult.Loaded) ?: return emptyMap()
        val wasPolling = pollingJob?.isActive == true
        isPollingPaused = true
        pollingJob?.cancel()

        val resultsMap = mutableMapOf<String, Any?>()

        return try {
            if (wasPolling) delay(200)
            sendAtCommand("AT", 80)

            val runner = ObdbRunner { cmd -> sendAtCommand(cmd, 100) }
            val commandsByHeader = loaded.commands.groupBy { it.hdr }

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
                            }
                        }
                    }
                }
            }

            runner.restoreHeader()
            _obdbSignalResults.value = resultsMap
            resultsMap
        } catch (e: Exception) {
            Log.e(TAG, "Error running OBDb signal scan", e)
            emptyMap()
        } finally {
            sendAtCommand("AT SH 7DF", 80)
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

    /**
     * Sends an AT command and waits for the prompt '>' response.
     */
    suspend fun sendAtCommand(command: String, delayMs: Long = 100): String {
        if (!ReadOnlyGuard.isAllowed(command)) {
            Log.w(TAG, "BLOCKED non-read command: [$command]")
            return "BLOCKED"
        }
        return responseMutex.withLock {
            latestResponse.clear()
            promptReceived = false

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

            var maxTimeout = COMMAND_TIMEOUT_MS
            val startTime = System.currentTimeMillis()
            while (!promptReceived) {
                // SAE J1979 / ISO 15765-4: Extend timeout up to 5000ms if ECU sends 7F xx 78 (Response Pending)
                val currentText = latestResponse.toString()
                if (currentText.contains("7F") && currentText.contains("78")) {
                    maxTimeout = 5000L
                }
                if (System.currentTimeMillis() - startTime > maxTimeout) {
                    Log.w(TAG, "Command timeout for: $command")
                    break
                }
                delay(15)
            }
            val result = latestResponse.toString().trim()
            Log.d(TAG, "TX: [$command] -> RX: [$result]")
            logElmExchange(command, result)
            delay(delayMs)
            result
        }
    }

    /**
     * Continuous background input stream reader state machine.
     */
    private fun startStreamReader() {
        workerJob?.cancel()
        workerJob = ioScope.launch {
            val buffer = ByteArray(1024)
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

                    if (bytesRead == -1) {
                        if (!isUsbConnection && inputStream == null) break
                        delay(20)
                        continue
                    }

                    for (i in 0 until bytesRead) {
                        val c = buffer[i].toInt().toChar()
                        when (c) {
                            '>' -> {
                                promptReceived = true
                                if (lineBuilder.isNotEmpty()) {
                                    latestResponse.append(lineBuilder.toString())
                                    lineBuilder.clear()
                                }
                                val fullText = latestResponse.toString()
                                processResponse(fullText)
                            }
                            '\r', '\n' -> {
                                if (lineBuilder.isNotEmpty()) {
                                    val line = lineBuilder.toString().trim()
                                    latestResponse.append(line).append("\n")
                                    lineBuilder.clear()
                                }
                            }
                            else -> {
                                lineBuilder.append(c)
                            }
                        }
                    }
                }
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
    private fun processResponse(fullResponse: String) {
        if (fullResponse.isBlank()) return
        val lines = fullResponse.split("\r", "\n")
        for (rawLine in lines) {
            val cleaned = rawLine.replace(" ", "").uppercase()
            if (cleaned.contains("NODATA") || cleaned.contains("ERROR") || cleaned.contains("UNABLE")) {
                continue
            }

            val payloads = ObdParser.extractPayloads(rawLine)
            for (payload in payloads) {
                if (payload.size >= 2 && payload[0] == 0x41) {
                    val pidHex = String.format("%02X", payload[1])
                    val dataBytes = payload.copyOfRange(2, payload.size)

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

                    val currentStreams = _supportedStreams.value.toMutableList()
                    val streamIdx = currentStreams.indexOfFirst { it.pid.pidHex == pidHex }
                    if (streamIdx != -1) {
                        val pidDef = currentStreams[streamIdx].pid
                        if (dataBytes.size >= pidDef.bytesRequired) {
                            val calculatedValue = pidDef.parseValue(dataBytes)
                            val formatted = String.format(java.util.Locale.US, "%.1f %s", calculatedValue, pidDef.unit)
                            val currentHistory = currentStreams[streamIdx].history
                            val updatedHistory = (currentHistory + StreamSample(calculatedValue)).takeLast(500)
                            currentStreams[streamIdx] = LiveStreamData(
                                pid = pidDef,
                                formattedValue = formatted,
                                rawValue = calculatedValue,
                                history = updatedHistory,
                                lastUpdated = System.currentTimeMillis()
                            )
                            _supportedStreams.value = currentStreams
                        }
                    }
                }
            }
        }
    }

    /**
     * Periodic PID polling loop dynamically polling all discovered supported PIDs.
     */
    private fun startPollingLoop() {
        pollingJob?.cancel()
        pollingJob = ioScope.launch {
            val pidsToPoll = if (discoveredPidDefinitions.isNotEmpty()) discoveredPidDefinitions else ObdPidRegistry.DEFAULT_PIDS
            while (isActive) {
                try {
                    if (!isPollingPaused) {
                        for (pidDef in pidsToPoll) {
                            if (isPollingPaused || !isActive) break
                            val fastCmd = pidDef.fullCommand
                            sendAtCommand(fastCmd, 10)
                        }
                    }
                    delay(50)
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
                    DtcDetails("P0171", "Stored / Active", "System Too Lean (Bank 1)")
                )
            } else {
                emptyList()
            }
            _metrics.value = _metrics.value.copy(dtcs = dtcs.map { it.code })
            return dtcs
        }

        val wasPolling = pollingJob?.isActive == true
        isPollingPaused = true
        pollingJob?.cancel()

        return try {
            if (wasPolling) {
                delay(200)
            }
            sendAtCommand("AT", 80)
            sendAtCommand("AT SH 7DF", 100) // Ensure Functional Broadcast Header 7DF for Engine PCM!

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

            val uniqueDtcs = allDtcList.distinctBy { "${it.code}_${it.category}_${it.description}" }
            _metrics.value = _metrics.value.copy(dtcs = uniqueDtcs.map { it.code })
            uniqueDtcs
        } finally {
            sendAtCommand("AT SH 7DF", 80)
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
     * Configures physical CAN headers (e.g. AT SH 7E0 for Ford PCM) to route queries directly to the Engine Control Module.
     */
    suspend fun scanMode22Pids(): List<Mode22StreamData> {
        if (isDemoMode) {
            val demoPids = listOfNotNull(
                Mode22Registry.KNOWN_PIDS.firstOrNull { it.pidHex == "1E1C" }?.let { Mode22StreamData(it, "85.0 °C", 85.0) },
                Mode22Registry.KNOWN_PIDS.firstOrNull { it.pidHex == "1674" }?.let { Mode22StreamData(it, "92.0 °C", 92.0) },
                Mode22Registry.KNOWN_PIDS.firstOrNull { it.pidHex == "1304" }?.let { Mode22StreamData(it, "42.5 psi", 42.5) },
                Mode22Registry.KNOWN_PIDS.firstOrNull { it.pidHex == "162B" }?.let { Mode22StreamData(it, "0.0 RPM", 0.0) }
            )
            _mode22Streams.value = demoPids
            return demoPids
        }

        val make = _vehicleDetails.value ?: ""
        val targetPids = Mode22Registry.getPidsForManufacturer(make)
        Log.i(TAG, "Scanning ${targetPids.size} Mode 22 Proprietary PIDs for vehicle make: $make")

        val results = mutableListOf<Mode22StreamData>()

        val wasPolling = pollingJob?.isActive == true
        isPollingPaused = true
        pollingJob?.cancel()

        try {
            if (wasPolling) {
                delay(200) // Flush in-flight serial polling frame
            }
            sendAtCommand("AT", 80) // Sync handshake

            var currentHeader = ""
            suspend fun ensureHeader(hdr: String) {
                if (currentHeader != hdr) {
                    sendAtCommand("AT SH $hdr", 60)
                    currentHeader = hdr
                }
            }

            // Prioritize already verified cached PIDs for this vehicle
            val sortedPids = if (cachedSupportedMode22Pids.isNotEmpty()) {
                targetPids.sortedByDescending { cachedSupportedMode22Pids.contains(it.pidHex) }
            } else {
                targetPids
            }

            for (pidDef in sortedPids) {
                // Route queries specifically to the relevant electronic control module
                val targetHeaders: List<String> = when (pidDef.category) {
                    "Transmission" -> listOf("7E0", "7E1")
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
                            var headerLen = 1 + pidByteCount

                            // Determine if payload includes full PID bytes after response service byte
                            if (payload.size > pidByteCount + 1) {
                                val pidHi = pidDef.pidHex.substring(0, 2).toIntOrNull(16) ?: 0
                                if (payload[1] != pidHi) {
                                    headerLen = 1
                                }
                            } else {
                                headerLen = 1
                            }

                            val dataBytes = if (payload.size > headerLen) payload.copyOfRange(headerLen, payload.size) else intArrayOf()
                            if (dataBytes.isNotEmpty()) {
                                val rawVal = try {
                                    pidDef.parseValue(dataBytes)
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to parse ${pidDef.name} (${pidDef.pidHex}): ${e.message}")
                                    null
                                }

                                if (rawVal != null && !rawVal.isNaN() && !rawVal.isInfinite()) {
                                    // Validate rawVal physical bounds to filter out unsupported/uninitialized ECU responses
                                    val isBogus = when (pidDef.unit) {
                                        "kPa" -> rawVal > 50000.0 || rawVal < 0.0
                                        "bar" -> rawVal > 500.0 || rawVal < 0.0
                                        "°C" -> rawVal > 300.0 || rawVal < -50.0
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
                                        val formatted = String.format(java.util.Locale.US, "%.1f %s", rawVal, pidDef.unit)
                                        results.add(
                                            Mode22StreamData(
                                                pid = pidDef,
                                                formattedValue = formatted,
                                                rawValue = rawVal
                                            )
                                        )
                                        cachedSupportedMode22Pids.add(pidDef.pidHex)
                                        if ((pidDef.pidHex.equals("404C", ignoreCase = true) || pidDef.pidHex.equals("DD01", ignoreCase = true) || pidDef.pidHex.equals("115C", ignoreCase = true)) && rawVal in 10.0..1_500_000.0) {
                                            _vehicleOdometerKm.value = rawVal
                                            Log.i(TAG, "Mode 22 scan updated vehicle odometer: $rawVal km (${(rawVal * 0.621371).toInt()} miles)")
                                        }
                                        Log.i(TAG, "Mode 22 RX [Header $header]: ${pidDef.name} = $formatted")
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
            sendAtCommand("AT SH 7DF", 100)
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
            _metrics.value = VehicleMetrics()
            _supportedStreams.value = emptyList()
            _mode06Results.value = emptyList()
            _mode22Streams.value = emptyList()
            cachedSupportedMode22Pids.clear()
            _vehicleVin.value = null
            _vehicleDetails.value = null
            _vehicleOdometerKm.value = null
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
                    list.add(ObdDeviceItem(name = "🔌 $devName", address = "USB:${dev.deviceId}", isUsb = true))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error listing USB devices", e)
        }

        // 2. Add Standard Wi-Fi OBD2 Adapter option (ELM327 Wi-Fi / Vgate / Veepeak)
        list.add(
            ObdDeviceItem(
                name = "📶 Wi-Fi OBD2 (192.168.0.10:35000)",
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
                list.add(ObdDeviceItem(name = "📡 $name (${dev.address})", address = dev.address, isUsb = false, isWifi = false))
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
            fetchVehicleOdometer()

            _connectionState.value = ConnectionState.Connected("📶 Wi-Fi OBD ($host:$port)")
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

            // 2. CRITICAL for FTDI / vLinker FS USB: Enable DTR & RTS signals to unblock FTDI FIFO buffer transmission!
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
            fetchVehicleOdometer()

            val devName = device.manufacturerName ?: "Vgate vLinker FS USB"
            _connectionState.value = ConnectionState.Connected("🔌 $devName")

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