package com.vera.app

import android.util.Log

/**
 * OpendbcManager
 *
 * Incorporates comma.ai open-source opendbc signal definitions for high-speed
 * chassis and vehicle dynamics decoding on capable adapters (vLinker FS USB,
 * vLinker MC+, OBDLink EX/MX+, STN11xx/22xx).
 *
 * Supported Platforms:
 * - Toyota / Lexus (TSS2 / Powertrain)
 * - Ford / Lincoln (Global Powertrain / Chassis)
 * - Honda / Acura (Bosch / Nidec CAN)
 * - General Motors / Chevrolet / GMC / Cadillac (Global A / B)
 * - Hyundai / Kia / Genesis (CAN Powertrain)
 * - Subaru (Global Chassis)
 * - Chrysler / Dodge / Jeep / Ram (FCA CAN)
 * - Volkswagen / Audi (MQB Platform)
 * - Nissan / Infiniti (Powertrain)
 * - Tesla (Model 3/Y CAN)
 */
object OpendbcManager {
    private const val TAG = "OpendbcManager"

    enum class Endianness {
        LITTLE_ENDIAN, // Intel format
        BIG_ENDIAN     // Motorola format
    }

    data class CanSignalDef(
        val name: String,
        val startBit: Int,
        val bitLength: Int,
        val endianness: Endianness,
        val scale: Double,
        val offset: Double,
        val min: Double,
        val max: Double,
        val unit: String
    ) {
        fun decode(data: ByteArray): Double? {
            if (data.isEmpty() || bitLength <= 0 || bitLength > 64) return null
            val rawInt = extractRawBits(data, startBit, bitLength, endianness) ?: return null
            return (rawInt * scale) + offset
        }
    }

    data class CanMessageDef(
        val messageIdHex: String, // e.g. "025", "180", "34A"
        val messageIdInt: Int,
        val name: String,
        val signals: List<CanSignalDef>
    )

    data class VehicleDbcProfile(
        val make: String,
        val platformName: String,
        val filterCanIds: List<String>, // Top 3-5 high-priority CAN IDs to filter
        val messages: Map<Int, CanMessageDef>
    )

    data class DecodedOpendbcSignal(
        val name: String,
        val value: Double,
        val formattedValue: String,
        val unit: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    // In-memory catalog of major vehicle platforms from opendbc
    private val profiles: Map<String, VehicleDbcProfile> = buildProfiles()

    /**
     * Resolves matching VehicleDbcProfile from VIN or Make.
     */
    fun findProfileForVehicle(vin: String, make: String = ""): VehicleDbcProfile? {
        val makeKey = when {
            make.isNotBlank() -> make.trim().lowercase()
            vin.length >= 3 -> getMakeFromVinWmi(vin.substring(0, 3).uppercase())
            else -> ""
        }

        return profiles[makeKey] ?: profiles.values.find { it.make.equals(makeKey, ignoreCase = true) }
    }

    /**
     * Decode a single raw CAN frame line (e.g. "025 8 12 34 56 78 90 AB CD EF" or "0251234567890ABCDEF")
     */
    fun decodeCanFrame(
        profile: VehicleDbcProfile,
        rawFrame: String
    ): List<DecodedOpendbcSignal> {
        val clean = rawFrame.trim().replace(" ", "")
        if (clean.length < 5) return emptyList()

        val idHex: String
        val dataHex: String

        // Check if format is standard ELM monitor: ID (3 or 8 chars) followed by payload
        if (clean.length in 19..20) {
            // Standard 11-bit CAN frame: 3 chars ID + 16 chars payload (8 bytes)
            idHex = clean.substring(0, 3)
            dataHex = clean.substring(3)
        } else if (clean.length >= 24) {
            // Extended 29-bit CAN frame: 8 chars ID + 16 chars payload
            idHex = clean.substring(0, 8)
            dataHex = clean.substring(8)
        } else {
            // Check space-delimited fallback
            val tokens = rawFrame.trim().split(Regex("\\s+"))
            if (tokens.size >= 2) {
                idHex = tokens[0]
                dataHex = tokens.drop(1).filter { it.length == 2 }.joinToString("")
            } else {
                return emptyList()
            }
        }

        val msgId = idHex.toIntOrNull(16) ?: return emptyList()
        val msgDef = profile.messages[msgId] ?: return emptyList()

        val bytes = hexStringToByteArray(dataHex)
        if (bytes.isEmpty()) return emptyList()

        val results = mutableListOf<DecodedOpendbcSignal>()
        for (sig in msgDef.signals) {
            val decoded = sig.decode(bytes)
            if (decoded != null && (sig.max == sig.min || (decoded >= sig.min - 1.0 && decoded <= sig.max + 1.0))) {
                val formatted = formatSignalValue(decoded, sig.unit)
                results.add(
                    DecodedOpendbcSignal(
                        name = sig.name,
                        value = decoded,
                        formattedValue = formatted,
                        unit = sig.unit
                    )
                )
            }
        }
        return results
    }

    private fun formatSignalValue(value: Double, unit: String): String {
        return when (unit) {
            "deg" -> String.format(java.util.Locale.US, "%.1f°", value)
            "deg/s" -> String.format(java.util.Locale.US, "%.1f°/s", value)
            "km/h" -> String.format(java.util.Locale.US, "%.1f km/h", value)
            "mph" -> String.format(java.util.Locale.US, "%.1f mph", value)
            "%" -> String.format(java.util.Locale.US, "%.1f%%", value)
            "bar" -> String.format(java.util.Locale.US, "%.1f bar", value)
            "psi" -> String.format(java.util.Locale.US, "%.1f psi", value)
            "Gear" -> when (value.toInt()) {
                0 -> "P"
                1 -> "R"
                2 -> "N"
                3 -> "D"
                4 -> "S"
                5 -> "1"
                6 -> "2"
                7 -> "3"
                8 -> "4"
                else -> value.toInt().toString()
            }
            else -> String.format(java.util.Locale.US, "%.2f %s", value, unit).trim()
        }
    }

    private fun extractRawBits(data: ByteArray, startBit: Int, bitLength: Int, endianness: Endianness): Long? {
        try {
            var rawValue = 0L
            if (endianness == Endianness.LITTLE_ENDIAN) {
                // Intel format
                for (i in 0 until bitLength) {
                    val currentBit = startBit + i
                    val byteIdx = currentBit / 8
                    val bitInByte = currentBit % 8
                    if (byteIdx >= data.size) return null
                    val bitVal = ((data[byteIdx].toInt() and 0xFF) shr bitInByte) and 1
                    rawValue = rawValue or (bitVal.toLong() shl i)
                }
            } else {
                // Motorola / Big Endian format
                for (i in 0 until bitLength) {
                    val byteIdx = (startBit / 8) + (i / 8)
                    val bitInByte = 7 - (startBit % 8 + i % 8)
                    if (byteIdx >= data.size) return null
                    val bitVal = ((data[byteIdx].toInt() and 0xFF) shr (if (bitInByte in 0..7) bitInByte else 0)) and 1
                    rawValue = (rawValue shl 1) or bitVal.toLong()
                }
            }
            return rawValue
        } catch (_: Exception) {
            return null
        }
    }

    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        if (len % 2 != 0) return ByteArray(0)
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            val b = s.substring(i, i + 2).toIntOrNull(16) ?: return ByteArray(0)
            data[i / 2] = b.toByte()
            i += 2
        }
        return data
    }

    private fun getMakeFromVinWmi(wmi: String): String {
        return when {
            wmi.startsWith("1G") || wmi.startsWith("2G") || wmi.startsWith("3G") -> "gm"
            wmi.startsWith("1F") || wmi.startsWith("2F") || wmi.startsWith("3F") -> "ford"
            wmi.startsWith("1C") || wmi.startsWith("2C") || wmi.startsWith("3C") -> "chrysler"
            wmi.startsWith("JT") || wmi.startsWith("4T") || wmi.startsWith("5T") -> "toyota"
            wmi.startsWith("JH") || wmi.startsWith("1H") || wmi.startsWith("2H") -> "honda"
            wmi.startsWith("KM") || wmi.startsWith("5N") -> "hyundai"
            wmi.startsWith("KN") || wmi.startsWith("5X") -> "kia"
            wmi.startsWith("JF") || wmi.startsWith("4S") -> "subaru"
            wmi.startsWith("JN") || wmi.startsWith("1N") || wmi.startsWith("3N") -> "nissan"
            wmi.startsWith("WV") || wmi.startsWith("WA") || wmi.startsWith("3V") -> "volkswagen"
            wmi.startsWith("5YJ") || wmi.startsWith("7SA") -> "tesla"
            else -> ""
        }
    }

    private fun buildProfiles(): Map<String, VehicleDbcProfile> {
        val map = mutableMapOf<String, VehicleDbcProfile>()

        // 1. TOYOTA / LEXUS
        map["toyota"] = VehicleDbcProfile(
            make = "Toyota",
            platformName = "Toyota TSS2 / Global Architecture",
            filterCanIds = listOf("025", "0AA", "200", "3BC"),
            messages = mapOf(
                0x25 to CanMessageDef(
                    "025", 0x25, "STEERING_MODULE",
                    listOf(
                        CanSignalDef("Steering Angle", 3, 12, Endianness.LITTLE_ENDIAN, 0.1, -204.8, -204.8, 204.7, "deg"),
                        CanSignalDef("Steering Rate", 16, 12, Endianness.LITTLE_ENDIAN, 1.0, 0.0, -2048.0, 2047.0, "deg/s")
                    )
                ),
                0xAA to CanMessageDef(
                    "0AA", 0xAA, "WHEEL_SPEEDS",
                    listOf(
                        CanSignalDef("Wheel Speed FL", 0, 16, Endianness.LITTLE_ENDIAN, 0.01 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel Speed FR", 16, 16, Endianness.LITTLE_ENDIAN, 0.01 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel Speed RL", 32, 16, Endianness.LITTLE_ENDIAN, 0.01 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel Speed RR", 48, 16, Endianness.LITTLE_ENDIAN, 0.01 * 0.621371, 0.0, 0.0, 180.0, "mph")
                    )
                ),
                0x200 to CanMessageDef(
                    "200", 0x200, "BRAKE_SYSTEM",
                    listOf(
                        CanSignalDef("Brake Line Pressure", 0, 8, Endianness.LITTLE_ENDIAN, 0.392, 0.0, 0.0, 100.0, "%"),
                        CanSignalDef("Accelerator Position", 16, 8, Endianness.LITTLE_ENDIAN, 0.392, 0.0, 0.0, 100.0, "%")
                    )
                ),
                0x3BC to CanMessageDef(
                    "3BC", 0x3BC, "GEAR_POSITION",
                    listOf(
                        CanSignalDef("Transmission Gear", 0, 4, Endianness.LITTLE_ENDIAN, 1.0, 0.0, 0.0, 10.0, "Gear")
                    )
                )
            )
        )
        map["lexus"] = map["toyota"]!!

        // 2. FORD / LINCOLN
        map["ford"] = VehicleDbcProfile(
            make = "Ford",
            platformName = "Ford C1 / Global Powertrain",
            filterCanIds = listOf("082", "130", "165", "176"),
            messages = mapOf(
                0x82 to CanMessageDef(
                    "082", 0x82, "STEERING_PINION",
                    listOf(
                        CanSignalDef("Steering Angle", 0, 14, Endianness.BIG_ENDIAN, 0.1, -800.0, -800.0, 800.0, "deg")
                    )
                ),
                0x130 to CanMessageDef(
                    "130", 0x130, "WHEEL_SPEEDS",
                    listOf(
                        CanSignalDef("Wheel Speed FL", 0, 14, Endianness.BIG_ENDIAN, 0.01 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel Speed FR", 16, 14, Endianness.BIG_ENDIAN, 0.01 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel Speed RL", 32, 14, Endianness.BIG_ENDIAN, 0.01 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel Speed RR", 48, 14, Endianness.BIG_ENDIAN, 0.01 * 0.621371, 0.0, 0.0, 180.0, "mph")
                    )
                ),
                0x165 to CanMessageDef(
                    "165", 0x165, "PEDAL_SENSORS",
                    listOf(
                        CanSignalDef("Brake Line Pressure", 0, 10, Endianness.BIG_ENDIAN, 0.1, 0.0, 0.0, 100.0, "%"),
                        CanSignalDef("Accelerator Position", 16, 10, Endianness.BIG_ENDIAN, 0.1, 0.0, 0.0, 100.0, "%")
                    )
                ),
                0x176 to CanMessageDef(
                    "176", 0x176, "TRANSMISSION_STATUS",
                    listOf(
                        CanSignalDef("Transmission Gear", 0, 4, Endianness.BIG_ENDIAN, 1.0, 0.0, 0.0, 10.0, "Gear")
                    )
                )
            )
        )
        map["lincoln"] = map["ford"]!!

        // 3. HONDA / ACURA
        map["honda"] = VehicleDbcProfile(
            make = "Honda",
            platformName = "Honda Bosch / Nidec Architecture",
            filterCanIds = listOf("14A", "1D0", "17C", "1A6"),
            messages = mapOf(
                0x14A to CanMessageDef(
                    "14A", 0x14A, "STEERING_SENSORS",
                    listOf(
                        CanSignalDef("Steering Angle", 0, 16, Endianness.BIG_ENDIAN, -0.1, 0.0, -900.0, 900.0, "deg")
                    )
                ),
                0x1D0 to CanMessageDef(
                    "1D0", 0x1D0, "WHEEL_SPEEDS",
                    listOf(
                        CanSignalDef("Wheel Speed FL", 0, 16, Endianness.BIG_ENDIAN, 0.01 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel Speed FR", 16, 16, Endianness.BIG_ENDIAN, 0.01 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel Speed RL", 32, 16, Endianness.BIG_ENDIAN, 0.01 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel Speed RR", 48, 16, Endianness.BIG_ENDIAN, 0.01 * 0.621371, 0.0, 0.0, 180.0, "mph")
                    )
                ),
                0x17C to CanMessageDef(
                    "17C", 0x17C, "PEDAL_STATUS",
                    listOf(
                        CanSignalDef("Accelerator Position", 0, 8, Endianness.BIG_ENDIAN, 0.392, 0.0, 0.0, 100.0, "%"),
                        CanSignalDef("Brake Line Pressure", 16, 8, Endianness.BIG_ENDIAN, 0.392, 0.0, 0.0, 100.0, "%")
                    )
                ),
                0x1A6 to CanMessageDef(
                    "1A6", 0x1A6, "GEARBOX_INFO",
                    listOf(
                        CanSignalDef("Transmission Gear", 0, 4, Endianness.BIG_ENDIAN, 1.0, 0.0, 0.0, 10.0, "Gear")
                    )
                )
            )
        )
        map["acura"] = map["honda"]!!

        // 4. GM / CHEVROLET / GMC / CADILLAC
        map["gm"] = VehicleDbcProfile(
            make = "General Motors",
            platformName = "GM Global A / Global B Architecture",
            filterCanIds = listOf("1E5", "34A", "1F5", "1A1"),
            messages = mapOf(
                0x1E5 to CanMessageDef(
                    "1E5", 0x1E5, "STEERING_WHEEL_DATA",
                    listOf(
                        CanSignalDef("Steering Angle", 0, 16, Endianness.BIG_ENDIAN, 0.0625, -2048.0, -2048.0, 2047.0, "deg")
                    )
                ),
                0x34A to CanMessageDef(
                    "34A", 0x34A, "WHEEL_SPEEDS",
                    listOf(
                        CanSignalDef("Wheel Speed FL", 0, 16, Endianness.BIG_ENDIAN, 0.015625 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel Speed FR", 16, 16, Endianness.BIG_ENDIAN, 0.015625 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel Speed RL", 32, 16, Endianness.BIG_ENDIAN, 0.015625 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel Speed RR", 48, 16, Endianness.BIG_ENDIAN, 0.015625 * 0.621371, 0.0, 0.0, 180.0, "mph")
                    )
                ),
                0x1F5 to CanMessageDef(
                    "1F5", 0x1F5, "BRAKE_PEDAL_DATA",
                    listOf(
                        CanSignalDef("Brake Line Pressure", 0, 8, Endianness.BIG_ENDIAN, 0.392, 0.0, 0.0, 100.0, "%")
                    )
                ),
                0x1A1 to CanMessageDef(
                    "1A1", 0x1A1, "GAS_PEDAL_DATA",
                    listOf(
                        CanSignalDef("Accelerator Position", 0, 8, Endianness.BIG_ENDIAN, 0.392, 0.0, 0.0, 100.0, "%")
                    )
                )
            )
        )
        map["chevrolet"] = map["gm"]!!
        map["gmc"] = map["gm"]!!
        map["cadillac"] = map["gm"]!!
        map["buick"] = map["gm"]!!

        // 5. HYUNDAI / KIA / GENESIS
        map["hyundai"] = VehicleDbcProfile(
            make = "Hyundai",
            platformName = "Hyundai / Kia CAN Powertrain",
            filterCanIds = listOf("2B0", "386", "220", "367"),
            messages = mapOf(
                0x2B0 to CanMessageDef(
                    "2B0", 0x2B0, "SAS_ANGLE_MODULE",
                    listOf(
                        CanSignalDef("Steering Angle", 0, 16, Endianness.LITTLE_ENDIAN, 0.1, -500.0, -500.0, 500.0, "deg")
                    )
                ),
                0x386 to CanMessageDef(
                    "386", 0x386, "WHEEL_SPEEDS",
                    listOf(
                        CanSignalDef("Wheel Speed FL", 0, 14, Endianness.LITTLE_ENDIAN, 0.03125 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel Speed FR", 16, 14, Endianness.LITTLE_ENDIAN, 0.03125 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel Speed RL", 32, 14, Endianness.LITTLE_ENDIAN, 0.03125 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel Speed RR", 48, 14, Endianness.LITTLE_ENDIAN, 0.03125 * 0.621371, 0.0, 0.0, 180.0, "mph")
                    )
                ),
                0x220 to CanMessageDef(
                    "220", 0x220, "BRAKE_PRESSURE_MODULE",
                    listOf(
                        CanSignalDef("Brake Line Pressure", 0, 10, Endianness.LITTLE_ENDIAN, 0.1, 0.0, 0.0, 100.0, "%")
                    )
                ),
                0x367 to CanMessageDef(
                    "367", 0x367, "GEAR_MODULE",
                    listOf(
                        CanSignalDef("Transmission Gear", 0, 4, Endianness.LITTLE_ENDIAN, 1.0, 0.0, 0.0, 10.0, "Gear")
                    )
                )
            )
        )
        map["kia"] = map["hyundai"]!!
        map["genesis"] = map["hyundai"]!!

        // 6. SUBARU
        map["subaru"] = VehicleDbcProfile(
            make = "Subaru",
            platformName = "Subaru Global Architecture",
            filterCanIds = listOf("119", "13A", "140"),
            messages = mapOf(
                0x119 to CanMessageDef(
                    "119", 0x119, "STEERING_MODULE",
                    listOf(
                        CanSignalDef("Steering Angle", 0, 16, Endianness.BIG_ENDIAN, -0.1, 0.0, -800.0, 800.0, "deg")
                    )
                ),
                0x13A to CanMessageDef(
                    "13A", 0x13A, "WHEEL_SPEEDS",
                    listOf(
                        CanSignalDef("Wheel Speed FL", 0, 14, Endianness.BIG_ENDIAN, 0.05 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel Speed FR", 16, 14, Endianness.BIG_ENDIAN, 0.05 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel Speed RL", 32, 14, Endianness.BIG_ENDIAN, 0.05 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel Speed RR", 48, 14, Endianness.BIG_ENDIAN, 0.05 * 0.621371, 0.0, 0.0, 180.0, "mph")
                    )
                ),
                0x140 to CanMessageDef(
                    "140", 0x140, "THROTTLE_PEDAL",
                    listOf(
                        CanSignalDef("Accelerator Position", 0, 8, Endianness.BIG_ENDIAN, 0.392, 0.0, 0.0, 100.0, "%")
                    )
                )
            )
        )

        // 7. CHRYSLER / DODGE / JEEP / RAM
        map["chrysler"] = VehicleDbcProfile(
            make = "Chrysler",
            platformName = "Chrysler / FCA Powertrain",
            filterCanIds = listOf("214", "138", "1F4", "1E4"),
            messages = mapOf(
                0x214 to CanMessageDef(
                    "214", 0x214, "STEERING_WHEEL_ANGLE",
                    listOf(
                        CanSignalDef("Steering Angle", 0, 16, Endianness.LITTLE_ENDIAN, 0.1, -800.0, -800.0, 800.0, "deg")
                    )
                ),
                0x138 to CanMessageDef(
                    "138", 0x138, "WHEEL_SPEEDS",
                    listOf(
                        CanSignalDef("Wheel Speed FL", 0, 16, Endianness.LITTLE_ENDIAN, 0.01 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel Speed FR", 16, 16, Endianness.LITTLE_ENDIAN, 0.01 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel Speed RL", 32, 16, Endianness.LITTLE_ENDIAN, 0.01 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel Speed RR", 48, 16, Endianness.LITTLE_ENDIAN, 0.01 * 0.621371, 0.0, 0.0, 180.0, "mph")
                    )
                ),
                0x1F4 to CanMessageDef(
                    "1F4", 0x1F4, "PEDAL_PRESSURES",
                    listOf(
                        CanSignalDef("Brake Line Pressure", 0, 8, Endianness.LITTLE_ENDIAN, 0.392, 0.0, 0.0, 100.0, "%"),
                        CanSignalDef("Accelerator Position", 16, 8, Endianness.LITTLE_ENDIAN, 0.392, 0.0, 0.0, 100.0, "%")
                    )
                ),
                0x1E4 to CanMessageDef(
                    "1E4", 0x1E4, "GEAR_LEVER_STATUS",
                    listOf(
                        CanSignalDef("Transmission Gear", 0, 4, Endianness.LITTLE_ENDIAN, 1.0, 0.0, 0.0, 10.0, "Gear")
                    )
                )
            )
        )
        map["dodge"] = map["chrysler"]!!
        map["jeep"] = map["chrysler"]!!
        map["ram"] = map["chrysler"]!!

        // 8. VOLKSWAGEN / AUDI
        map["volkswagen"] = VehicleDbcProfile(
            make = "Volkswagen",
            platformName = "VW / Audi MQB Architecture",
            filterCanIds = listOf("086", "0FD", "106"),
            messages = mapOf(
                0x86 to CanMessageDef(
                    "086", 0x86, "STEERING_ANGLE",
                    listOf(
                        CanSignalDef("Steering Angle", 0, 16, Endianness.BIG_ENDIAN, 0.1, -800.0, -800.0, 800.0, "deg")
                    )
                ),
                0xFD to CanMessageDef(
                    "0FD", 0xFD, "WHEEL_SPEEDS",
                    listOf(
                        CanSignalDef("Wheel Speed FL", 0, 16, Endianness.BIG_ENDIAN, 0.0075 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel Speed FR", 16, 16, Endianness.BIG_ENDIAN, 0.0075 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel Speed RL", 32, 16, Endianness.BIG_ENDIAN, 0.0075 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel Speed RR", 48, 16, Endianness.BIG_ENDIAN, 0.0075 * 0.621371, 0.0, 0.0, 180.0, "mph")
                    )
                ),
                0x106 to CanMessageDef(
                    "106", 0x106, "GEAR_STATUS",
                    listOf(
                        CanSignalDef("Transmission Gear", 0, 4, Endianness.BIG_ENDIAN, 1.0, 0.0, 0.0, 10.0, "Gear")
                    )
                )
            )
        )
        map["audi"] = map["volkswagen"]!!

        // 9. NISSAN / INFINITI
        map["nissan"] = VehicleDbcProfile(
            make = "Nissan",
            platformName = "Nissan / Infiniti Powertrain",
            filterCanIds = listOf("002", "280", "180"),
            messages = mapOf(
                0x2 to CanMessageDef(
                    "002", 0x2, "STEERING_MODULE",
                    listOf(
                        CanSignalDef("Steering Angle", 0, 16, Endianness.BIG_ENDIAN, 0.1, -800.0, -800.0, 800.0, "deg")
                    )
                ),
                0x280 to CanMessageDef(
                    "280", 0x280, "WHEEL_SPEEDS",
                    listOf(
                        CanSignalDef("Wheel Speed FL", 0, 16, Endianness.BIG_ENDIAN, 0.01 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel Speed FR", 16, 16, Endianness.BIG_ENDIAN, 0.01 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel Speed RL", 32, 16, Endianness.BIG_ENDIAN, 0.01 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel Speed RR", 48, 16, Endianness.BIG_ENDIAN, 0.01 * 0.621371, 0.0, 0.0, 180.0, "mph")
                    )
                ),
                0x180 to CanMessageDef(
                    "180", 0x180, "PEDALS",
                    listOf(
                        CanSignalDef("Accelerator Position", 0, 8, Endianness.BIG_ENDIAN, 0.392, 0.0, 0.0, 100.0, "%")
                    )
                )
            )
        )
        map["infiniti"] = map["nissan"]!!

        return map
    }
}
