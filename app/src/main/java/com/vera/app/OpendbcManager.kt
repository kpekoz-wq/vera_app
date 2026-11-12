package com.vera.app

import android.util.Log

/**
 * OpendbcManager
 *
 * Incorporates comma.ai open-source opendbc signal definitions for high-speed
 * chassis and vehicle dynamics decoding on capable adapters (vLinker FS USB,
 * vLinker MC+, OBDLink EX/MX+, STN11xx/22xx).
 *
 * Supported Platforms & Multi-Generation Architectures:
 * - Toyota / Lexus / Scion (2010+ Prius / Hybrid & TSS2 Global CAN)
 * - Ford / Lincoln / Mercury (2004-2014 Legacy HS-CAN + 2013-2023 C1/CD4/T6/FD1 CAN)
 * - Honda / Acura (Bosch & Nidec CAN)
 * - General Motors / Chevrolet / GMC / Cadillac / Buick (Global A & Chassis CAN)
 * - Hyundai / Kia / Genesis (HKG CAN Powertrain & Chassis)
 * - Subaru (Global & Pre-Global CAN)
 * - Chrysler / Dodge / Jeep / Ram (FCA / Pacifica / Grand Cherokee / Ram CAN)
 * - Volkswagen / Audi / Porsche (MQB + PQ35/PQ46 CAN)
 * - Nissan / Infiniti (RE0F / X-Trail / Rogue / Leaf CAN)
 * - Mazda (SkyActiv Gen 6 / MZR CAN)
 * - BMW / Mini (E8x/E9x/E6x/E7x/F-series PT-CAN & K-CAN)
 * - Mercedes-Benz (W204/W212/W164/W221 Chassis CAN)
 * - Volvo (P3 / SPA Powertrain & Chassis CAN)
 * - Tesla (Model S/X/3/Y Party & Chassis CAN)
 */
object OpendbcManager {
    private const val TAG = "OpendbcManager"

    enum class Endianness {
        LITTLE_ENDIAN, // Intel format (@1)
        BIG_ENDIAN     // Motorola format (@0, MSB-0 bit index)
    }

    /**
     * Converts a standard Vector .dbc Motorola (@0) startBit into our MSB-0 startBit index.
     * In Vector .dbc files, byte b has bits (b*8) (LSB) .. (b*8 + 7) (MSB).
     * In MSB-0 indexing, byte b has bits (b*8) (MSB) .. (b*8 + 7) (LSB).
     */
    private fun dbcBe(dbcBit: Int): Int = (dbcBit / 8) * 8 + (7 - (dbcBit % 8))

    data class CanSignalDef(
        val name: String,
        val startBit: Int,
        val bitLength: Int,
        val endianness: Endianness,
        val scale: Double,
        val offset: Double,
        val min: Double,
        val max: Double,
        val unit: String,
        val signed: Boolean = false
    ) {
        fun decode(data: ByteArray): Double? {
            if (data.isEmpty() || bitLength <= 0 || bitLength > 64) return null
            var rawInt = extractRawBits(data, startBit, bitLength, endianness) ?: return null
            if (signed && bitLength in 2..63) {
                val signBit = 1L shl (bitLength - 1)
                if ((rawInt and signBit) != 0L) {
                    rawInt -= (1L shl bitLength)
                }
            }
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
        val filterCanIds: List<String>, // Top high-priority CAN IDs to filter
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
        val makeKey = resolveMakeKey(vin, make)
        if (makeKey.isBlank()) return null
        return profiles[makeKey] ?: profiles.values.find { it.make.equals(makeKey, ignoreCase = true) }
    }

    /**
     * Resolves the best VehicleDbcProfile using both VIN/Make metadata and the live
     * CAN bus arbitration ID fingerprint observed during a scan or inline sample.
     *
     * This handles:
     * 1. Multi-generation architectures within the same automaker (e.g., VW MQB vs. VW PQ35/PQ46,
     *    Ford 2004-2014 HS-CAN vs. Ford 2013+ C1/CD4/FD1).
     * 2. Cross-badge / shared platforms (e.g., Toyota GR86 / Subaru BRZ, Ford Escape / Mazda Tribute).
     * 3. Vehicles where Mode 09 VIN reading was unavailable on connect.
     */
    fun resolveBestProfile(
        vin: String,
        make: String = "",
        observedCanIds: Set<Int> = emptySet()
    ): VehicleDbcProfile? {
        val baseProfile = findProfileForVehicle(vin, make)
        if (observedCanIds.isEmpty()) return baseProfile

        val makeKey = resolveMakeKey(vin, make)
        var bestProfile: VehicleDbcProfile? = baseProfile
        var bestScore = if (baseProfile != null) {
            val hits = baseProfile.messages.keys.count { it in observedCanIds }
            if (hits > 0) hits * 10 + 5 else 0
        } else {
            0
        }

        for ((key, candidate) in profiles.entries.distinctBy { it.value.platformName }) {
            val hits = candidate.messages.keys.count { it in observedCanIds }
            if (hits <= 0) continue
            val sameMakeBonus = if (makeKey.isNotEmpty() && (key.startsWith(makeKey) || candidate.make.equals(baseProfile?.make, ignoreCase = true))) 5 else 0
            val score = hits * 10 + sameMakeBonus
            if (score > bestScore) {
                bestScore = score
                bestProfile = candidate
            }
        }

        if (bestProfile != null && bestProfile.platformName != baseProfile?.platformName) {
            Log.i(TAG, "CAN fingerprint matched profile '${bestProfile.platformName}' (score=$bestScore, observedIds=${observedCanIds.size})")
        }
        return bestProfile
    }

    private fun resolveMakeKey(vin: String, make: String): String {
        val fromVin = if (vin.length >= 3) getMakeFromVinWmi(vin.substring(0, 3).uppercase()) else ""
        if (fromVin.isNotEmpty()) return fromVin
        if (make.isNotBlank()) {
            val m = make.trim().lowercase()
            return when {
                m.contains("toyota") || m.contains("lexus") || m.contains("scion") -> "toyota"
                m.contains("ford") || m.contains("lincoln") || m.contains("mercury") -> "ford"
                m.contains("honda") || m.contains("acura") -> "honda"
                m.contains("gm") || m.contains("chevrolet") || m.contains("chevy") || m.contains("gmc") || m.contains("cadillac") || m.contains("buick") || m.contains("pontiac") || m.contains("saturn") -> "gm"
                m.contains("hyundai") || m.contains("kia") || m.contains("genesis") -> "hyundai"
                m.contains("subaru") -> "subaru"
                m.contains("chrysler") || m.contains("dodge") || m.contains("jeep") || m.contains("ram") || m.contains("fiat") -> "chrysler"
                m.contains("volkswagen") || m.contains("vw") || m.contains("audi") || m.contains("porsche") -> "volkswagen"
                m.contains("nissan") || m.contains("infiniti") || m.contains("datsun") -> "nissan"
                m.contains("mazda") -> "mazda"
                m.contains("bmw") || m.contains("mini") -> "bmw"
                m.contains("mercedes") || m.contains("benz") || m.contains("maybach") -> "mercedes"
                m.contains("volvo") || m.contains("polestar") -> "volvo"
                m.contains("tesla") -> "tesla"
                else -> m
            }
        }
        return ""
    }

    /**
     * Decode a single raw CAN frame line (e.g. "025 12 34 56 78 90 AB CD EF" or "0251234567890ABCDEF")
     */
    fun decodeCanFrame(
        profile: VehicleDbcProfile,
        rawFrame: String
    ): List<DecodedOpendbcSignal> {
        val trimmed = rawFrame.trim()
        if (trimmed.length < 5) return emptyList()

        val idHex: String
        val dataHex: String

        if (trimmed.contains(' ')) {
            val tokens = trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (tokens.size >= 2) {
                idHex = tokens[0]
                val payloadTokens = if (tokens.size >= 3 && tokens[1].length == 1 && tokens[1][0].isDigit()) {
                    tokens.drop(2) // Skip optional single-digit DLC token if present
                } else {
                    tokens.drop(1)
                }
                dataHex = payloadTokens.filter { it.length == 2 }.joinToString("")
            } else {
                return emptyList()
            }
        } else {
            // Unspaced ELM monitor output: 3-char 11-bit ID or 8-char 29-bit ID followed by hex bytes
            if (trimmed.length in 5..19 && (trimmed.length - 3) % 2 == 0) {
                idHex = trimmed.substring(0, 3)
                dataHex = trimmed.substring(3)
            } else if (trimmed.length >= 10 && (trimmed.length - 8) % 2 == 0) {
                idHex = trimmed.substring(0, 8)
                dataHex = trimmed.substring(8)
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
                val clamped = if (sig.max > sig.min && sig.min == 0.0 && decoded < 0.0 && decoded >= -1.0) 0.0 else decoded
                val formatted = formatSignalValue(clamped, sig.unit)
                results.add(
                    DecodedOpendbcSignal(
                        name = sig.name,
                        value = clamped,
                        formattedValue = formatted,
                        unit = sig.unit
                    )
                )
            }
        }
        return results
    }

    fun formatSignalValue(value: Double, unit: String): String {
        return when (unit) {
            "deg" -> String.format(java.util.Locale.US, "%.1f deg", value)
            "deg/s" -> String.format(java.util.Locale.US, "%.1f deg/s", value)
            "km/h" -> String.format(java.util.Locale.US, "%.1f km/h", value)
            "mph" -> String.format(java.util.Locale.US, "%.1f mph", value)
            "rpm" -> String.format(java.util.Locale.US, "%.0f rpm", value)
            "%" -> String.format(java.util.Locale.US, "%.1f %%", value)
            "bar" -> String.format(java.util.Locale.US, "%.1f bar", value)
            "psi" -> String.format(java.util.Locale.US, "%.1f psi", value)
            "g" -> String.format(java.util.Locale.US, "%+.2f g", value)
            "Nm" -> String.format(java.util.Locale.US, "%.1f Nm", value)
            "m" -> String.format(java.util.Locale.US, "%.1f m", value)
            "m/s" -> String.format(java.util.Locale.US, "%+.1f m/s", value)
            "Gear" -> when (value.toInt()) {
                0 -> "P"
                1 -> "R"
                2 -> "N"
                3 -> "D"
                4 -> "B"
                5 -> "S"
                else -> value.toInt().toString()
            }
            else -> String.format(java.util.Locale.US, "%.2f %s", value, unit).trim()
        }
    }

    private fun extractRawBits(data: ByteArray, startBit: Int, bitLength: Int, endianness: Endianness): Long? {
        try {
            var rawValue = 0L
            if (endianness == Endianness.LITTLE_ENDIAN) {
                // Intel format (LSB first)
                for (i in 0 until bitLength) {
                    val currentBit = startBit + i
                    val byteIdx = currentBit / 8
                    val bitInByte = currentBit % 8
                    if (byteIdx >= data.size) return null
                    val bitVal = ((data[byteIdx].toInt() and 0xFF) shr bitInByte) and 1
                    rawValue = rawValue or (bitVal.toLong() shl i)
                }
            } else {
                // Big Endian / Motorola format (bit 0 = MSB of byte 0, bit 7 = LSB of byte 0, bit 8 = MSB of byte 1, ...)
                for (i in 0 until bitLength) {
                    val currentBit = startBit + i
                    val byteIdx = currentBit / 8
                    val bitInByte = 7 - (currentBit % 8)
                    if (byteIdx >= data.size) return null
                    val bitVal = ((data[byteIdx].toInt() and 0xFF) shr bitInByte) and 1
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
            wmi.startsWith("1G") || wmi.startsWith("2G") || wmi.startsWith("3G") || wmi.startsWith("5G") -> "gm"
            wmi.startsWith("1F") || wmi.startsWith("2F") || wmi.startsWith("3F") || wmi.startsWith("1L") || wmi.startsWith("2L") || wmi.startsWith("3L") || wmi.startsWith("5L") -> "ford"
            wmi.startsWith("1C") || wmi.startsWith("2C") || wmi.startsWith("3C") || wmi.startsWith("1D") || wmi.startsWith("2D") || wmi.startsWith("3D") || wmi.startsWith("1J") -> "chrysler"
            wmi.startsWith("JT") || wmi.startsWith("2T") || wmi.startsWith("4T") || wmi.startsWith("5T") || wmi.startsWith("7SV") || wmi.startsWith("7MU") -> "toyota"
            wmi.startsWith("JH") || wmi.startsWith("1H") || wmi.startsWith("2H") || wmi.startsWith("5J") || wmi.startsWith("5F") || wmi.startsWith("19U") || wmi.startsWith("19X") -> "honda"
            wmi.startsWith("KM") || wmi.startsWith("5N") -> "hyundai"
            wmi.startsWith("KN") || wmi.startsWith("5X") || wmi.startsWith("3KP") -> "kia"
            wmi.startsWith("JF") || wmi.startsWith("4S") -> "subaru"
            wmi.startsWith("JN") || wmi.startsWith("1N") || wmi.startsWith("3N") || wmi.startsWith("5N1") -> "nissan"
            wmi.startsWith("JM") || wmi.startsWith("1YV") || wmi.startsWith("3MZ") || wmi.startsWith("4F") -> "mazda"
            wmi.startsWith("WBA") || wmi.startsWith("WBS") || wmi.startsWith("WBY") || wmi.startsWith("4US") || wmi.startsWith("5UX") || wmi.startsWith("WMW") -> "bmw"
            wmi.startsWith("WDB") || wmi.startsWith("WDD") || wmi.startsWith("WDC") || wmi.startsWith("W1K") || wmi.startsWith("W1N") || wmi.startsWith("4JG") || wmi.startsWith("55S") -> "mercedes"
            wmi.startsWith("YV1") || wmi.startsWith("YV4") || wmi.startsWith("7JR") -> "volvo"
            wmi.startsWith("WV") || wmi.startsWith("WA") || wmi.startsWith("3V") || wmi.startsWith("1V") || wmi.startsWith("TRU") || wmi.startsWith("WP0") || wmi.startsWith("WP1") -> "volkswagen"
            wmi.startsWith("5YJ") || wmi.startsWith("7SA") || wmi.startsWith("7G2") || wmi.startsWith("LRW") || wmi.startsWith("XP7") -> "tesla"
            else -> ""
        }
    }

    private fun buildProfiles(): Map<String, VehicleDbcProfile> {
        val map = mutableMapOf<String, VehicleDbcProfile>()

        // 1. TOYOTA / LEXUS / SCION (2010+ Prius / Hybrid & TSS2 Global CAN - toyota_new_mc_pt_generated.dbc)
        map["toyota"] = VehicleDbcProfile(
            make = "Toyota",
            platformName = "Toyota / Lexus Hybrid & Global CAN (opendbc)",
            filterCanIds = listOf("0AA", "024", "025", "245", "1C4", "0B4", "224", "228", "260", "127"),
            messages = mapOf(
                0x25 to CanMessageDef(
                    "025", 0x25, "STEER_ANGLE_SENSOR",
                    listOf(
                        CanSignalDef("Steering Angle", dbcBe(3), 12, Endianness.BIG_ENDIAN, 1.5, 0.0, -500.0, 500.0, "deg", signed = true),
                        CanSignalDef("Steering Rate", dbcBe(35), 12, Endianness.BIG_ENDIAN, 1.0, 0.0, -2000.0, 2000.0, "deg/s", signed = true)
                    )
                ),
                0x24 to CanMessageDef(
                    "024", 0x24, "KINEMATICS",
                    listOf(
                        CanSignalDef("Yaw Rate", 6, 10, Endianness.BIG_ENDIAN, 1.0, -512.0, -512.0, 512.0, "deg/s"),
                        CanSignalDef("Lateral G", 38, 10, Endianness.BIG_ENDIAN, 0.01, -5.12, -5.0, 5.0, "g")
                    )
                ),
                0x228 to CanMessageDef(
                    "228", 0x228, "ACCELEROMETER",
                    listOf(
                        CanSignalDef("Longitudinal G", 1, 15, Endianness.BIG_ENDIAN, 0.001, 0.0, -2.5, 2.5, "g", signed = true)
                    )
                ),
                0x260 to CanMessageDef(
                    "260", 0x260, "STEER_TORQUE_SENSOR",
                    listOf(
                        CanSignalDef("Driver Torque", dbcBe(15), 16, Endianness.BIG_ENDIAN, 0.01, 0.0, -100.0, 100.0, "Nm", signed = true),
                        CanSignalDef("EPS Torque", dbcBe(47), 16, Endianness.BIG_ENDIAN, 0.0066, 0.0, -200.0, 200.0, "Nm", signed = true)
                    )
                ),
                0xAA to CanMessageDef(
                    "0AA", 0xAA, "WHEEL_SPEEDS",
                    listOf(
                        // Raw 0x1A6F (6767) = 0.00 km/h (scale 0.01 km/h, offset -67.67 km/h -> in mph: scale 0.00621371, offset -42.0482)
                        CanSignalDef("Wheel FR", dbcBe(7), 16, Endianness.BIG_ENDIAN, 0.00621371, -42.0482, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel FL", dbcBe(23), 16, Endianness.BIG_ENDIAN, 0.00621371, -42.0482, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel RR", dbcBe(39), 16, Endianness.BIG_ENDIAN, 0.00621371, -42.0482, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel RL", dbcBe(55), 16, Endianness.BIG_ENDIAN, 0.00621371, -42.0482, 0.0, 180.0, "mph")
                    )
                ),
                0xB4 to CanMessageDef(
                    "0B4", 0xB4, "SPEED",
                    listOf(
                        CanSignalDef("Vehicle Speed (CAN)", dbcBe(47), 16, Endianness.BIG_ENDIAN, 0.00621371, 0.0, 0.0, 180.0, "mph")
                    )
                ),
                0x224 to CanMessageDef(
                    "224", 0x224, "BRAKE_MODULE",
                    listOf(
                        CanSignalDef("Brake Pressure", dbcBe(7), 16, Endianness.BIG_ENDIAN, 0.25, 0.0, 0.0, 2000.0, "psi")
                    )
                ),
                0x245 to CanMessageDef(
                    "245", 0x245, "GAS_PEDAL",
                    listOf(
                        CanSignalDef("Accelerator", dbcBe(23), 8, Endianness.BIG_ENDIAN, 0.5, 0.0, 0.0, 100.0, "%")
                    )
                ),
                0x1C4 to CanMessageDef(
                    "1C4", 0x1C4, "POWERTRAIN",
                    listOf(
                        CanSignalDef("Engine Speed (CAN)", dbcBe(7), 16, Endianness.BIG_ENDIAN, 1.0, 0.0, 0.0, 8000.0, "rpm")
                    )
                ),
                0x2E6 to CanMessageDef(
                    "2E6", 0x2E6, "LEAD_INFO",
                    listOf(
                        CanSignalDef("Lead Distance", dbcBe(7), 13, Endianness.BIG_ENDIAN, 0.05, 0.0, 0.0, 250.0, "m")
                    )
                ),
                0x127 to CanMessageDef(
                    "127", 0x127, "GEAR_PACKET",
                    listOf(
                        CanSignalDef("Gear", 40, 4, Endianness.BIG_ENDIAN, 1.0, 0.0, 0.0, 10.0, "Gear")
                    )
                )
            )
        )
        map["lexus"] = map["toyota"]!!
        map["scion"] = map["toyota"]!!

        // 2. FORD / LINCOLN / MERCURY (2004-2014 Legacy HS-CAN + 2013-2023 ford_lincoln_base_pt.dbc C1/CD4/T6/FD1)
        map["ford"] = VehicleDbcProfile(
            make = "Ford",
            platformName = "Ford / Lincoln Multi-Gen HS-CAN (opendbc)",
            filterCanIds = listOf("201", "4B0", "4B8", "077", "07E", "091", "092", "202", "204", "217"),
            messages = mapOf(
                // 2004-2014 Ford F-150 / Mustang / Explorer / Escape / Fusion HS-CAN (verified on 2007 F-150)
                0x201 to CanMessageDef(
                    "201", 0x201, "POWERTRAIN_DATA_LEGACY",
                    listOf(
                        CanSignalDef("Engine Speed (CAN)", dbcBe(7), 16, Endianness.BIG_ENDIAN, 0.25, 0.0, 0.0, 8000.0, "rpm"),
                        // Raw 0x2710 (10000) = 0.00 km/h (scale 0.01 km/h, offset -100 km/h -> in mph: scale 0.00621371, offset -62.1371)
                        CanSignalDef("Vehicle Speed (CAN)", dbcBe(39), 16, Endianness.BIG_ENDIAN, 0.00621371, -62.1371, 0.0, 180.0, "mph"),
                        CanSignalDef("Accelerator", dbcBe(55), 8, Endianness.BIG_ENDIAN, 0.5, 0.0, 0.0, 100.0, "%")
                    )
                ),
                0x4B0 to CanMessageDef(
                    "4B0", 0x4B0, "WHEEL_SPEEDS_HS_LEGACY",
                    listOf(
                        CanSignalDef("Wheel FL", dbcBe(7), 16, Endianness.BIG_ENDIAN, 0.00621371, -62.1371, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel FR", dbcBe(23), 16, Endianness.BIG_ENDIAN, 0.00621371, -62.1371, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel RL", dbcBe(39), 16, Endianness.BIG_ENDIAN, 0.00621371, -62.1371, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel RR", dbcBe(55), 16, Endianness.BIG_ENDIAN, 0.00621371, -62.1371, 0.0, 180.0, "mph")
                    )
                ),
                0x4B8 to CanMessageDef(
                    "4B8", 0x4B8, "BRAKE_ABS_MODULE_LEGACY",
                    listOf(
                        CanSignalDef("Brake Pressure", dbcBe(7), 16, Endianness.BIG_ENDIAN, 0.1, 0.0, 0.0, 3000.0, "psi")
                    )
                ),
                // 2013-2023 Ford/Lincoln C1/CD4/T6/FD1 (ford_lincoln_base_pt.dbc)
                0x77 to CanMessageDef(
                    "077", 0x77, "BrakeSnData_3",
                    listOf(
                        CanSignalDef("Vehicle Speed (CAN)", dbcBe(7), 16, Endianness.BIG_ENDIAN, 0.00621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Yaw Rate", dbcBe(19), 12, Endianness.BIG_ENDIAN, 0.03663, -75.0, -75.0, 75.0, "deg/s"),
                        CanSignalDef("Lateral G", dbcBe(43), 10, Endianness.BIG_ENDIAN, 0.035 / 9.80665, -17.9 / 9.80665, -2.0, 2.0, "g"),
                        CanSignalDef("Longitudinal G", dbcBe(49), 10, Endianness.BIG_ENDIAN, 0.035 / 9.80665, -17.9 / 9.80665, -2.0, 2.0, "g")
                    )
                ),
                0x76 to CanMessageDef(
                    "076", 0x76, "BrakeSnData_5",
                    listOf(
                        CanSignalDef("Driver Torque", dbcBe(31), 8, Endianness.BIG_ENDIAN, 0.0625, -8.0, -8.0, 8.0, "Nm")
                    )
                ),
                0x7D to CanMessageDef(
                    "07D", 0x7D, "BrakeSnData_4",
                    listOf(
                        CanSignalDef("Brake Pressure", dbcBe(27), 13, Endianness.BIG_ENDIAN, 0.5, 0.0, 0.0, 3000.0, "psi")
                    )
                ),
                0x7E to CanMessageDef(
                    "07E", 0x7E, "SteeringPinion_Data",
                    listOf(
                        CanSignalDef("Steering Angle", dbcBe(22), 15, Endianness.BIG_ENDIAN, 0.1, -1600.0, -900.0, 900.0, "deg")
                    )
                ),
                0x85 to CanMessageDef(
                    "085", 0x85, "SteeringPinion_Data_Alt",
                    listOf(
                        CanSignalDef("Steering Angle", dbcBe(22), 15, Endianness.BIG_ENDIAN, 0.1, -1600.0, -900.0, 900.0, "deg")
                    )
                ),
                0x82 to CanMessageDef(
                    "082", 0x82, "STEERING_PINION",
                    listOf(
                        CanSignalDef("Steering Angle", 2, 14, Endianness.BIG_ENDIAN, 0.1, -800.0, -800.0, 800.0, "deg"),
                        CanSignalDef("Steering Rate", 20, 12, Endianness.BIG_ENDIAN, 1.0, 0.0, -2048.0, 2047.0, "deg/s", signed = true)
                    )
                ),
                0x91 to CanMessageDef(
                    "091", 0x91, "Yaw_Data_FD1",
                    listOf(
                        CanSignalDef("Yaw Rate", dbcBe(23), 16, Endianness.BIG_ENDIAN, 0.0002 * 57.29578, -6.5 * 57.29578, -150.0, 150.0, "deg/s")
                    )
                ),
                0x92 to CanMessageDef(
                    "092", 0x92, "Accel_Data_FD1",
                    listOf(
                        CanSignalDef("Lateral G", dbcBe(4), 13, Endianness.BIG_ENDIAN, 0.01 / 9.80665, -40.0 / 9.80665, -3.0, 3.0, "g"),
                        CanSignalDef("Longitudinal G", dbcBe(20), 13, Endianness.BIG_ENDIAN, 0.01 / 9.80665, -40.0 / 9.80665, -3.0, 3.0, "g")
                    )
                ),
                0x171 to CanMessageDef(
                    "171", 0x171, "EngineData_1",
                    listOf(
                        CanSignalDef("Gear", dbcBe(7), 4, Endianness.BIG_ENDIAN, 1.0, 0.0, 0.0, 12.0, "Gear")
                    )
                ),
                0x202 to CanMessageDef(
                    "202", 0x202, "EngVehicleSpThrottle2",
                    listOf(
                        CanSignalDef("Vehicle Speed (CAN)", dbcBe(55), 16, Endianness.BIG_ENDIAN, 0.00621371, 0.0, 0.0, 180.0, "mph")
                    )
                ),
                0x204 to CanMessageDef(
                    "204", 0x204, "EngVehicleSpThrottle",
                    listOf(
                        CanSignalDef("Accelerator", dbcBe(1), 10, Endianness.BIG_ENDIAN, 0.1, 0.0, 0.0, 100.0, "%"),
                        CanSignalDef("Engine Speed (CAN)", dbcBe(28), 13, Endianness.BIG_ENDIAN, 2.0, 0.0, 0.0, 8500.0, "rpm")
                    )
                ),
                0x217 to CanMessageDef(
                    "217", 0x217, "WheelSpeed",
                    listOf(
                        CanSignalDef("Wheel FL", dbcBe(6), 15, Endianness.BIG_ENDIAN, 0.00738, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel FR", dbcBe(22), 15, Endianness.BIG_ENDIAN, 0.00738, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel RL", dbcBe(38), 15, Endianness.BIG_ENDIAN, 0.00738, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel RR", dbcBe(54), 15, Endianness.BIG_ENDIAN, 0.00738, 0.0, 0.0, 180.0, "mph")
                    )
                ),
                0x130 to CanMessageDef(
                    "130", 0x130, "WHEEL_SPEEDS",
                    listOf(
                        CanSignalDef("Wheel FL", 2, 14, Endianness.BIG_ENDIAN, 0.01 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel FR", 18, 14, Endianness.BIG_ENDIAN, 0.01 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel RL", 34, 14, Endianness.BIG_ENDIAN, 0.01 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel RR", 50, 14, Endianness.BIG_ENDIAN, 0.01 * 0.621371, 0.0, 0.0, 180.0, "mph")
                    )
                ),
                0x165 to CanMessageDef(
                    "165", 0x165, "PEDAL_SENSORS",
                    listOf(
                        CanSignalDef("Brake Pressure", 6, 10, Endianness.BIG_ENDIAN, 0.1, 0.0, 0.0, 100.0, "%"),
                        CanSignalDef("Accelerator", 22, 10, Endianness.BIG_ENDIAN, 0.1, 0.0, 0.0, 100.0, "%")
                    )
                )
            )
        )
        map["lincoln"] = map["ford"]!!
        map["mercury"] = map["ford"]!!

        // 3. HONDA / ACURA (honda_civic_touring_2016_can_generated.dbc & Bosch/Nidec CAN)
        map["honda"] = VehicleDbcProfile(
            make = "Honda",
            platformName = "Honda / Acura Bosch & Nidec CAN (opendbc)",
            filterCanIds = listOf("14A", "1D0", "17C", "1A6", "191", "18F"),
            messages = mapOf(
                0x14A to CanMessageDef(
                    "14A", 0x14A, "STEERING_SENSORS",
                    listOf(
                        CanSignalDef("Steering Angle", dbcBe(7), 16, Endianness.BIG_ENDIAN, -0.1, 0.0, -900.0, 900.0, "deg", signed = true),
                        CanSignalDef("Steering Rate", dbcBe(23), 16, Endianness.BIG_ENDIAN, -0.1, 0.0, -2000.0, 2000.0, "deg/s", signed = true),
                        CanSignalDef("Driver Torque", dbcBe(39), 8, Endianness.BIG_ENDIAN, -0.1, 0.0, -50.0, 50.0, "Nm", signed = true)
                    )
                ),
                0x1D0 to CanMessageDef(
                    "1D0", 0x1D0, "WHEEL_SPEEDS",
                    listOf(
                        CanSignalDef("Wheel FL", dbcBe(7), 15, Endianness.BIG_ENDIAN, 0.01 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel FR", dbcBe(22), 15, Endianness.BIG_ENDIAN, 0.01 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel RL", dbcBe(37), 15, Endianness.BIG_ENDIAN, 0.01 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel RR", dbcBe(52), 15, Endianness.BIG_ENDIAN, 0.01 * 0.621371, 0.0, 0.0, 180.0, "mph")
                    )
                ),
                0x17C to CanMessageDef(
                    "17C", 0x17C, "POWERTRAIN_DATA",
                    listOf(
                        CanSignalDef("Accelerator", dbcBe(7), 8, Endianness.BIG_ENDIAN, 0.392157, 0.0, 0.0, 100.0, "%"),
                        CanSignalDef("Engine Speed (CAN)", dbcBe(23), 16, Endianness.BIG_ENDIAN, 1.0, 0.0, 0.0, 9000.0, "rpm")
                    )
                ),
                0x1A6 to CanMessageDef(
                    "1A6", 0x1A6, "GEARBOX",
                    listOf(
                        CanSignalDef("Gear", dbcBe(3), 4, Endianness.BIG_ENDIAN, 1.0, 0.0, 0.0, 10.0, "Gear")
                    )
                ),
                0x191 to CanMessageDef(
                    "191", 0x191, "VEHICLE_DYNAMICS",
                    listOf(
                        CanSignalDef("Lateral G", dbcBe(7), 16, Endianness.BIG_ENDIAN, 0.001, 0.0, -3.0, 3.0, "g", signed = true),
                        CanSignalDef("Yaw Rate", dbcBe(23), 16, Endianness.BIG_ENDIAN, 0.01, 0.0, -200.0, 200.0, "deg/s", signed = true)
                    )
                )
            )
        )
        map["acura"] = map["honda"]!!

        // 4. GM / CHEVROLET / GMC / CADILLAC / BUICK (Global A / B Powertrain & Chassis)
        map["gm"] = VehicleDbcProfile(
            make = "General Motors",
            platformName = "GM Global A / Global B Architecture (opendbc)",
            filterCanIds = listOf("1E5", "34A", "1F5", "1A1", "0C9", "170", "180"),
            messages = mapOf(
                0x1E5 to CanMessageDef(
                    "1E5", 0x1E5, "EBCM_STEERING_WHEEL",
                    listOf(
                        CanSignalDef("Steering Angle", dbcBe(15), 16, Endianness.BIG_ENDIAN, 0.0625, 0.0, -900.0, 900.0, "deg", signed = true),
                        CanSignalDef("Steering Rate", dbcBe(31), 12, Endianness.BIG_ENDIAN, 1.0, 0.0, -2000.0, 2000.0, "deg/s", signed = true)
                    )
                ),
                0x348 to CanMessageDef(
                    "348", 0x348, "EBCM_WHEEL_SPEEDS_FRONT",
                    listOf(
                        CanSignalDef("Wheel FL", dbcBe(7), 16, Endianness.BIG_ENDIAN, 0.01 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel FR", dbcBe(23), 16, Endianness.BIG_ENDIAN, 0.01 * 0.621371, 0.0, 0.0, 180.0, "mph")
                    )
                ),
                0x34A to CanMessageDef(
                    "34A", 0x34A, "EBCM_WHEEL_SPEEDS_ALL",
                    listOf(
                        CanSignalDef("Wheel FL", dbcBe(7), 16, Endianness.BIG_ENDIAN, 0.01 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel FR", dbcBe(23), 16, Endianness.BIG_ENDIAN, 0.01 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel RL", dbcBe(39), 16, Endianness.BIG_ENDIAN, 0.01 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel RR", dbcBe(55), 16, Endianness.BIG_ENDIAN, 0.01 * 0.621371, 0.0, 0.0, 180.0, "mph")
                    )
                ),
                0x0C9 to CanMessageDef(
                    "0C9", 0x0C9, "ECM_ENGINE_STATUS",
                    listOf(
                        CanSignalDef("Engine Speed (CAN)", dbcBe(15), 16, Endianness.BIG_ENDIAN, 0.25, 0.0, 0.0, 8500.0, "rpm"),
                        CanSignalDef("Accelerator", dbcBe(39), 8, Endianness.BIG_ENDIAN, 0.392157, 0.0, 0.0, 100.0, "%")
                    )
                ),
                0x170 to CanMessageDef(
                    "170", 0x170, "EBCM_BRAKE_PEDAL",
                    listOf(
                        CanSignalDef("Brake Pressure", dbcBe(23), 16, Endianness.BIG_ENDIAN, 0.1, 0.0, 0.0, 2000.0, "psi")
                    )
                ),
                0x1F5 to CanMessageDef(
                    "1F5", 0x1F5, "TRANSMISSION_SHIFTER",
                    listOf(
                        CanSignalDef("Gear", dbcBe(3), 4, Endianness.BIG_ENDIAN, 1.0, 0.0, 0.0, 10.0, "Gear")
                    )
                ),
                0x1A1 to CanMessageDef(
                    "1A1", 0x1A1, "GAS_PEDAL_DATA",
                    listOf(
                        CanSignalDef("Accelerator", dbcBe(55), 8, Endianness.BIG_ENDIAN, 0.392157, 0.0, 0.0, 100.0, "%")
                    )
                )
            )
        )
        map["chevrolet"] = map["gm"]!!
        map["gmc"] = map["gm"]!!
        map["cadillac"] = map["gm"]!!
        map["buick"] = map["gm"]!!

        // 5. HYUNDAI / KIA / GENESIS (HKG CAN Powertrain)
        map["hyundai"] = VehicleDbcProfile(
            make = "Hyundai",
            platformName = "Hyundai / Kia / Genesis CAN (opendbc)",
            filterCanIds = listOf("2B0", "386", "220", "367", "251", "316"),
            messages = mapOf(
                0x2B0 to CanMessageDef(
                    "2B0", 0x2B0, "SAS11",
                    listOf(
                        CanSignalDef("Steering Angle", 0, 16, Endianness.LITTLE_ENDIAN, 0.1, 0.0, -900.0, 900.0, "deg", signed = true),
                        CanSignalDef("Steering Rate", 16, 8, Endianness.LITTLE_ENDIAN, 4.0, 0.0, -1000.0, 1000.0, "deg/s")
                    )
                ),
                0x386 to CanMessageDef(
                    "386", 0x386, "WHL_SPD11",
                    listOf(
                        CanSignalDef("Wheel FL", 0, 14, Endianness.LITTLE_ENDIAN, 0.03125 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel FR", 16, 14, Endianness.LITTLE_ENDIAN, 0.03125 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel RL", 32, 14, Endianness.LITTLE_ENDIAN, 0.03125 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel RR", 48, 14, Endianness.LITTLE_ENDIAN, 0.03125 * 0.621371, 0.0, 0.0, 180.0, "mph")
                    )
                ),
                0x251 to CanMessageDef(
                    "251", 0x251, "MDPS12",
                    listOf(
                        CanSignalDef("Driver Torque", 0, 11, Endianness.LITTLE_ENDIAN, 0.01, -10.24, -15.0, 15.0, "Nm"),
                        CanSignalDef("EPS Torque", 16, 11, Endianness.LITTLE_ENDIAN, 0.01, -10.24, -15.0, 15.0, "Nm")
                    )
                ),
                0x220 to CanMessageDef(
                    "220", 0x220, "ESP12",
                    listOf(
                        CanSignalDef("Lateral G", 0, 11, Endianness.LITTLE_ENDIAN, 0.01 / 9.80665, -10.23 / 9.80665, -2.0, 2.0, "g"),
                        CanSignalDef("Longitudinal G", 11, 11, Endianness.LITTLE_ENDIAN, 0.01 / 9.80665, -10.23 / 9.80665, -2.0, 2.0, "g"),
                        CanSignalDef("Brake Pressure", 22, 12, Endianness.LITTLE_ENDIAN, 0.1 * 14.5038, 0.0, 0.0, 3000.0, "psi"),
                        CanSignalDef("Yaw Rate", 40, 13, Endianness.LITTLE_ENDIAN, 0.01, -40.95, -50.0, 50.0, "deg/s")
                    )
                ),
                0x316 to CanMessageDef(
                    "316", 0x316, "EMS11",
                    listOf(
                        CanSignalDef("Engine Speed (CAN)", 8, 16, Endianness.LITTLE_ENDIAN, 0.25, 0.0, 0.0, 8500.0, "rpm"),
                        CanSignalDef("Accelerator", 56, 8, Endianness.LITTLE_ENDIAN, 0.392157, 0.0, 0.0, 100.0, "%")
                    )
                ),
                0x367 to CanMessageDef(
                    "367", 0x367, "TCU12",
                    listOf(
                        CanSignalDef("Gear", 0, 4, Endianness.LITTLE_ENDIAN, 1.0, 0.0, 0.0, 10.0, "Gear")
                    )
                )
            )
        )
        map["kia"] = map["hyundai"]!!
        map["genesis"] = map["hyundai"]!!

        // 6. SUBARU (subaru_global_2017_generated.dbc & Pre-Global CAN)
        map["subaru"] = VehicleDbcProfile(
            make = "Subaru",
            platformName = "Subaru Global & Pre-Global CAN (opendbc)",
            filterCanIds = listOf("119", "13A", "134", "040", "139", "140"),
            messages = mapOf(
                0x119 to CanMessageDef(
                    "119", 0x119, "Steering_Torque",
                    listOf(
                        CanSignalDef("Steering Angle", 16, 16, Endianness.LITTLE_ENDIAN, -0.04395, 0.0, -900.0, 900.0, "deg", signed = true),
                        CanSignalDef("Driver Torque", 32, 11, Endianness.LITTLE_ENDIAN, 0.01, 0.0, -25.0, 25.0, "Nm", signed = true)
                    )
                ),
                0x134 to CanMessageDef(
                    "134", 0x134, "Wheel_Speeds_Global",
                    listOf(
                        CanSignalDef("Wheel FL", 12, 13, Endianness.LITTLE_ENDIAN, 0.05625 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel FR", 25, 13, Endianness.LITTLE_ENDIAN, 0.05625 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel RL", 38, 13, Endianness.LITTLE_ENDIAN, 0.05625 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel RR", 51, 13, Endianness.LITTLE_ENDIAN, 0.05625 * 0.621371, 0.0, 0.0, 180.0, "mph")
                    )
                ),
                0x13A to CanMessageDef(
                    "13A", 0x13A, "Wheel_Speeds_PreGlobal",
                    listOf(
                        CanSignalDef("Wheel FL", dbcBe(7), 14, Endianness.BIG_ENDIAN, 0.05 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel FR", dbcBe(23), 14, Endianness.BIG_ENDIAN, 0.05 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel RL", dbcBe(39), 14, Endianness.BIG_ENDIAN, 0.05 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel RR", dbcBe(55), 14, Endianness.BIG_ENDIAN, 0.05 * 0.621371, 0.0, 0.0, 180.0, "mph")
                    )
                ),
                0x040 to CanMessageDef(
                    "040", 0x040, "Throttle",
                    listOf(
                        CanSignalDef("Accelerator", 32, 8, Endianness.LITTLE_ENDIAN, 0.392157, 0.0, 0.0, 100.0, "%"),
                        CanSignalDef("Engine Speed (CAN)", 16, 14, Endianness.LITTLE_ENDIAN, 1.0, 0.0, 0.0, 8500.0, "rpm")
                    )
                ),
                0x139 to CanMessageDef(
                    "139", 0x139, "Brake_Pedal",
                    listOf(
                        CanSignalDef("Brake Pressure", 16, 8, Endianness.LITTLE_ENDIAN, 1.0, 0.0, 0.0, 255.0, "psi")
                    )
                )
            )
        )

        // 7. CHRYSLER / DODGE / JEEP / RAM (chrysler_pacifica_2017_hybrid.dbc & FCA CAN)
        map["chrysler"] = VehicleDbcProfile(
            make = "Chrysler",
            platformName = "Chrysler / Dodge / Jeep / Ram FCA CAN (opendbc)",
            filterCanIds = listOf("122", "158", "140", "11C", "170", "1F4"),
            messages = mapOf(
                0x122 to CanMessageDef(
                    "122", 0x122, "STEERING",
                    listOf(
                        CanSignalDef("Steering Angle", dbcBe(4), 13, Endianness.BIG_ENDIAN, 0.3187251, -1307.888, -800.0, 800.0, "deg"),
                        CanSignalDef("Steering Rate", dbcBe(19), 12, Endianness.BIG_ENDIAN, 0.5, -1024.0, -1024.0, 1024.0, "deg/s")
                    )
                ),
                0x158 to CanMessageDef(
                    "158", 0x158, "WHEEL_SPEEDS",
                    listOf(
                        // 0.0189408 m/s -> * 2.236936 = 0.042369 mph
                        CanSignalDef("Wheel FL", dbcBe(3), 12, Endianness.BIG_ENDIAN, 0.042369, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel FR", dbcBe(19), 12, Endianness.BIG_ENDIAN, 0.042369, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel RL", dbcBe(35), 12, Endianness.BIG_ENDIAN, 0.042369, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel RR", dbcBe(51), 12, Endianness.BIG_ENDIAN, 0.042369, 0.0, 0.0, 180.0, "mph")
                    )
                ),
                0x140 to CanMessageDef(
                    "140", 0x140, "BRAKE_2",
                    listOf(
                        CanSignalDef("Brake Pressure", dbcBe(3), 12, Endianness.BIG_ENDIAN, 0.5, 0.0, 0.0, 2000.0, "psi")
                    )
                ),
                0x11C to CanMessageDef(
                    "11C", 0x11C, "ACCEL_GAS",
                    listOf(
                        CanSignalDef("Accelerator", dbcBe(15), 8, Endianness.BIG_ENDIAN, 0.392157, 0.0, 0.0, 100.0, "%")
                    )
                ),
                0x170 to CanMessageDef(
                    "170", 0x170, "TRANSMISSION_STATUS",
                    listOf(
                        CanSignalDef("Gear", dbcBe(19), 4, Endianness.BIG_ENDIAN, 1.0, 0.0, 0.0, 10.0, "Gear")
                    )
                )
            )
        )
        map["dodge"] = map["chrysler"]!!
        map["jeep"] = map["chrysler"]!!
        map["ram"] = map["chrysler"]!!

        // 8A. VOLKSWAGEN / AUDI MQB (vw_mqb.dbc - 2015+ Golf Mk7/Mk8, Jetta, Tiguan, Atlas, Audi A3/Q3/TT)
        map["volkswagen"] = VehicleDbcProfile(
            make = "Volkswagen",
            platformName = "VW / Audi MQB Architecture (opendbc)",
            filterCanIds = listOf("086", "09F", "0AD", "0B2", "0FD", "101", "106", "121"),
            messages = mapOf(
                0x86 to CanMessageDef(
                    "086", 0x86, "LWI_01",
                    listOf(
                        CanSignalDef("Steering Angle", 16, 13, Endianness.LITTLE_ENDIAN, 0.1, 0.0, 0.0, 800.0, "deg"),
                        CanSignalDef("Steering Rate", 31, 9, Endianness.LITTLE_ENDIAN, 5.0, 0.0, 0.0, 2500.0, "deg/s")
                    )
                ),
                0x9F to CanMessageDef(
                    "09F", 0x9F, "LH_EPS_03",
                    listOf(
                        CanSignalDef("Driver Torque", 40, 10, Endianness.LITTLE_ENDIAN, 0.01, 0.0, 0.0, 15.0, "Nm")
                    )
                ),
                0xAD to CanMessageDef(
                    "0AD", 0xAD, "Getriebe_11",
                    listOf(
                        CanSignalDef("Gear", 42, 4, Endianness.LITTLE_ENDIAN, 1.0, 0.0, 0.0, 15.0, "Gear")
                    )
                ),
                0xB2 to CanMessageDef(
                    "0B2", 0xB2, "ESP_19",
                    listOf(
                        CanSignalDef("Wheel RL", 0, 16, Endianness.LITTLE_ENDIAN, 0.0075 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel RR", 16, 16, Endianness.LITTLE_ENDIAN, 0.0075 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel FL", 32, 16, Endianness.LITTLE_ENDIAN, 0.0075 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel FR", 48, 16, Endianness.LITTLE_ENDIAN, 0.0075 * 0.621371, 0.0, 0.0, 180.0, "mph")
                    )
                ),
                0xFD to CanMessageDef(
                    "0FD", 0xFD, "ESP_21",
                    listOf(
                        CanSignalDef("Vehicle Speed (CAN)", 32, 16, Endianness.LITTLE_ENDIAN, 0.01 * 0.621371, 0.0, 0.0, 180.0, "mph")
                    )
                ),
                0x101 to CanMessageDef(
                    "101", 0x101, "ESP_02",
                    listOf(
                        CanSignalDef("Lateral G", 16, 8, Endianness.LITTLE_ENDIAN, 0.01, -1.27, -1.5, 1.5, "g"),
                        CanSignalDef("Longitudinal G", 24, 10, Endianness.LITTLE_ENDIAN, 0.03125 / 9.80665, -16.0 / 9.80665, -2.0, 2.0, "g"),
                        CanSignalDef("Yaw Rate", 40, 14, Endianness.LITTLE_ENDIAN, 0.01, 0.0, 0.0, 163.0, "deg/s")
                    )
                ),
                0x106 to CanMessageDef(
                    "106", 0x106, "ESP_05",
                    listOf(
                        CanSignalDef("Brake Pressure", 16, 10, Endianness.LITTLE_ENDIAN, 0.3 * 14.5038, -30.0 * 14.5038, 0.0, 3000.0, "psi")
                    )
                ),
                0x121 to CanMessageDef(
                    "121", 0x121, "Motor_20",
                    listOf(
                        CanSignalDef("Accelerator", 12, 8, Endianness.LITTLE_ENDIAN, 0.4, 0.0, 0.0, 100.0, "%")
                    )
                )
            )
        )
        map["audi"] = map["volkswagen"]!!
        map["porsche"] = map["volkswagen"]!!

        // 8B. VOLKSWAGEN / AUDI PQ35/PQ46 (vw_pq.dbc - 2004-2014 Golf Mk5/Mk6, Jetta, Passat, Tiguan, Audi A3/TT)
        map["volkswagen_pq"] = VehicleDbcProfile(
            make = "Volkswagen",
            platformName = "VW / Audi PQ35/PQ46 Architecture (opendbc)",
            filterCanIds = listOf("0D0", "1A8", "1AC", "280", "380", "440", "4A0"),
            messages = mapOf(
                0xD0 to CanMessageDef(
                    "0D0", 0xD0, "Lenkhilfe_3",
                    listOf(
                        CanSignalDef("Driver Torque", 16, 10, Endianness.LITTLE_ENDIAN, 0.01, 0.0, 0.0, 15.0, "Nm"),
                        CanSignalDef("Steering Angle", 32, 12, Endianness.LITTLE_ENDIAN, 0.15, 0.0, 0.0, 615.0, "deg")
                    )
                ),
                0x1A8 to CanMessageDef(
                    "1A8", 0x1A8, "Bremse_6",
                    listOf(
                        CanSignalDef("Brake Pressure", 0, 10, Endianness.LITTLE_ENDIAN, 0.3255 * 14.5038, -40.0 * 14.5038, 0.0, 3000.0, "psi")
                    )
                ),
                0x1AC to CanMessageDef(
                    "1AC", 0x1AC, "Bremse_8",
                    listOf(
                        CanSignalDef("Longitudinal G", 32, 9, Endianness.LITTLE_ENDIAN, 0.02 / 9.80665, -7.22 / 9.80665, -1.5, 1.5, "g")
                    )
                ),
                0x280 to CanMessageDef(
                    "280", 0x280, "Motor_1",
                    listOf(
                        CanSignalDef("Engine Speed (CAN)", 16, 16, Endianness.LITTLE_ENDIAN, 0.25, 0.0, 0.0, 8500.0, "rpm"),
                        CanSignalDef("Accelerator", 40, 8, Endianness.LITTLE_ENDIAN, 0.4, 0.0, 0.0, 100.0, "%")
                    )
                ),
                0x380 to CanMessageDef(
                    "380", 0x380, "Motor_3",
                    listOf(
                        CanSignalDef("Accelerator", 16, 8, Endianness.LITTLE_ENDIAN, 0.4, 0.0, 0.0, 100.0, "%")
                    )
                ),
                0x440 to CanMessageDef(
                    "440", 0x440, "Getriebe_1",
                    listOf(
                        CanSignalDef("Gear", 8, 4, Endianness.LITTLE_ENDIAN, 1.0, 0.0, 0.0, 12.0, "Gear")
                    )
                ),
                0x4A0 to CanMessageDef(
                    "4A0", 0x4A0, "Bremse_3",
                    listOf(
                        CanSignalDef("Wheel FL", 1, 15, Endianness.LITTLE_ENDIAN, 0.01 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel FR", 17, 15, Endianness.LITTLE_ENDIAN, 0.01 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel RL", 33, 15, Endianness.LITTLE_ENDIAN, 0.01 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel RR", 49, 15, Endianness.LITTLE_ENDIAN, 0.01 * 0.621371, 0.0, 0.0, 180.0, "mph")
                    )
                )
            )
        )

        // 9. NISSAN / INFINITI (X-Trail / Rogue / Altima / Leaf CAN)
        map["nissan"] = VehicleDbcProfile(
            make = "Nissan",
            platformName = "Nissan / Infiniti Powertrain CAN (opendbc)",
            filterCanIds = listOf("002", "284", "285", "180", "1F9"),
            messages = mapOf(
                0x002 to CanMessageDef(
                    "002", 0x002, "STEER_ANGLE_SENSOR",
                    listOf(
                        CanSignalDef("Steering Angle", 0, 16, Endianness.LITTLE_ENDIAN, 0.1, 0.0, -900.0, 900.0, "deg", signed = true),
                        CanSignalDef("Steering Rate", 16, 8, Endianness.LITTLE_ENDIAN, 4.0, 0.0, -1000.0, 1000.0, "deg/s")
                    )
                ),
                0x284 to CanMessageDef(
                    "284", 0x284, "WHEEL_SPEEDS_FRONT",
                    listOf(
                        CanSignalDef("Wheel FR", dbcBe(7), 16, Endianness.BIG_ENDIAN, 0.005 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel FL", dbcBe(23), 16, Endianness.BIG_ENDIAN, 0.005 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Vehicle Speed (CAN)", dbcBe(39), 16, Endianness.BIG_ENDIAN, 0.01 * 0.621371, 0.0, 0.0, 180.0, "mph")
                    )
                ),
                0x285 to CanMessageDef(
                    "285", 0x285, "WHEEL_SPEEDS_REAR",
                    listOf(
                        CanSignalDef("Wheel RR", dbcBe(7), 16, Endianness.BIG_ENDIAN, 0.005 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel RL", dbcBe(23), 16, Endianness.BIG_ENDIAN, 0.005 * 0.621371, 0.0, 0.0, 180.0, "mph")
                    )
                ),
                0x180 to CanMessageDef(
                    "180", 0x180, "THROTTLE",
                    listOf(
                        CanSignalDef("Engine Speed (CAN)", dbcBe(7), 16, Endianness.BIG_ENDIAN, 0.125, 0.0, 0.0, 8000.0, "rpm"),
                        CanSignalDef("Accelerator", dbcBe(47), 10, Endianness.BIG_ENDIAN, 0.1, 0.0, 0.0, 100.0, "%")
                    )
                )
            )
        )
        map["infiniti"] = map["nissan"]!!

        // 10. MAZDA (mazda_2017.dbc - SkyActiv Mazda3, Mazda6, CX-3, CX-5, CX-9, MX-5)
        map["mazda"] = VehicleDbcProfile(
            make = "Mazda",
            platformName = "Mazda SkyActiv CAN (opendbc)",
            filterCanIds = listOf("086", "165", "202", "215", "240", "241"),
            messages = mapOf(
                0x086 to CanMessageDef(
                    "086", 0x086, "STEER2",
                    listOf(
                        CanSignalDef("Steering Angle", dbcBe(7), 16, Endianness.BIG_ENDIAN, 0.1, -1600.0, -600.0, 600.0, "deg")
                    )
                ),
                0x165 to CanMessageDef(
                    "165", 0x165, "PEDALS",
                    listOf(
                        CanSignalDef("Gear", 48, 5, Endianness.LITTLE_ENDIAN, 1.0, 0.0, 0.0, 10.0, "Gear")
                    )
                ),
                0x202 to CanMessageDef(
                    "202", 0x202, "ENGINE_DATA",
                    listOf(
                        CanSignalDef("Engine Speed (CAN)", dbcBe(7), 16, Endianness.BIG_ENDIAN, 0.25, 0.0, 0.0, 8500.0, "rpm"),
                        CanSignalDef("Vehicle Speed (CAN)", dbcBe(23), 16, Endianness.BIG_ENDIAN, 0.01 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Accelerator", dbcBe(39), 12, Endianness.BIG_ENDIAN, 0.1, 0.0, 0.0, 100.0, "%")
                    )
                ),
                0x215 to CanMessageDef(
                    "215", 0x215, "WHEEL_SPEEDS",
                    listOf(
                        CanSignalDef("Wheel FL", dbcBe(7), 16, Endianness.BIG_ENDIAN, 0.00621371, -62.1371, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel FR", dbcBe(23), 16, Endianness.BIG_ENDIAN, 0.00621371, -62.1371, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel RL", dbcBe(39), 16, Endianness.BIG_ENDIAN, 0.00621371, -62.1371, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel RR", dbcBe(55), 16, Endianness.BIG_ENDIAN, 0.00621371, -62.1371, 0.0, 180.0, "mph")
                    )
                ),
                0x240 to CanMessageDef(
                    "240", 0x240, "STEER_TORQUE",
                    listOf(
                        CanSignalDef("Driver Torque", dbcBe(7), 8, Endianness.BIG_ENDIAN, 1.0, -127.0, -85.0, 85.0, "Nm"),
                        CanSignalDef("EPS Torque", dbcBe(46), 15, Endianness.BIG_ENDIAN, 0.1, 0.0, -300.0, 300.0, "Nm", signed = true)
                    )
                ),
                0x241 to CanMessageDef(
                    "241", 0x241, "STEER_RATE",
                    listOf(
                        CanSignalDef("Steering Rate", dbcBe(23), 16, Endianness.BIG_ENDIAN, 0.25, -8192.0, -2000.0, 2000.0, "deg/s")
                    )
                )
            )
        )

        // 11. BMW / MINI (bmw_e9x_e8x.dbc - E8x/E9x/E6x/E7x/F-series PT-CAN & K-CAN)
        map["bmw"] = VehicleDbcProfile(
            make = "BMW",
            platformName = "BMW / Mini PT-CAN & K-CAN (opendbc)",
            filterCanIds = listOf("0A8", "0AA", "0C4", "0C9", "0CE", "19E", "1A0", "1D2"),
            messages = mapOf(
                0xA8 to CanMessageDef(
                    "0A8", 0xA8, "EngineAndBrake",
                    listOf(
                        CanSignalDef("Engine Torque", 12, 12, Endianness.LITTLE_ENDIAN, 0.5, 0.0, -1000.0, 1000.0, "Nm", signed = true)
                    )
                ),
                0xAA to CanMessageDef(
                    "0AA", 0xAA, "AccPedal",
                    listOf(
                        CanSignalDef("Accelerator", 16, 16, Endianness.LITTLE_ENDIAN, 0.04, 0.0, 0.0, 100.0, "%"),
                        CanSignalDef("Engine Speed (CAN)", 32, 16, Endianness.LITTLE_ENDIAN, 0.25, 0.0, 0.0, 8500.0, "rpm")
                    )
                ),
                0xC4 to CanMessageDef(
                    "0C4", 0xC4, "SteeringWheelAngle",
                    listOf(
                        CanSignalDef("Steering Angle", 0, 16, Endianness.LITTLE_ENDIAN, 0.04283, 0.0, -600.0, 600.0, "deg", signed = true),
                        CanSignalDef("Steering Rate", 24, 16, Endianness.LITTLE_ENDIAN, 0.04283, 0.0, -1500.0, 1500.0, "deg/s", signed = true)
                    )
                ),
                0xC9 to CanMessageDef(
                    "0C9", 0xC9, "SteeringWheelAngle_DSC",
                    listOf(
                        CanSignalDef("Steering Angle", 0, 16, Endianness.LITTLE_ENDIAN, 0.04283, 0.0, -600.0, 600.0, "deg", signed = true)
                    )
                ),
                0xCE to CanMessageDef(
                    "0CE", 0xCE, "WheelSpeeds",
                    listOf(
                        CanSignalDef("Wheel FL", 0, 16, Endianness.LITTLE_ENDIAN, 0.0625 * 0.621371, 0.0, 0.0, 180.0, "mph", signed = true),
                        CanSignalDef("Wheel FR", 16, 16, Endianness.LITTLE_ENDIAN, 0.0625 * 0.621371, 0.0, 0.0, 180.0, "mph", signed = true),
                        CanSignalDef("Wheel RL", 32, 16, Endianness.LITTLE_ENDIAN, 0.0625 * 0.621371, 0.0, 0.0, 180.0, "mph", signed = true),
                        CanSignalDef("Wheel RR", 48, 16, Endianness.LITTLE_ENDIAN, 0.0625 * 0.621371, 0.0, 0.0, 180.0, "mph", signed = true)
                    )
                ),
                0x19E to CanMessageDef(
                    "19E", 0x19E, "StatusDSC_KCAN",
                    listOf(
                        CanSignalDef("Brake Pressure", 48, 8, Endianness.LITTLE_ENDIAN, 14.5038, 0.0, 0.0, 3000.0, "psi")
                    )
                ),
                0x1A0 to CanMessageDef(
                    "1A0", 0x1A0, "Speed",
                    listOf(
                        CanSignalDef("Vehicle Speed (CAN)", 0, 12, Endianness.LITTLE_ENDIAN, 0.103 * 0.621371, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Lateral G", 16, 12, Endianness.LITTLE_ENDIAN, 0.002, 0.0, -2.5, 2.5, "g", signed = true),
                        CanSignalDef("Longitudinal G", 28, 12, Endianness.LITTLE_ENDIAN, 0.002, 0.0, -2.5, 2.5, "g", signed = true),
                        CanSignalDef("Yaw Rate", 40, 12, Endianness.LITTLE_ENDIAN, 0.05, 0.0, -100.0, 100.0, "deg/s", signed = true)
                    )
                ),
                0x1D2 to CanMessageDef(
                    "1D2", 0x1D2, "TransmissionDataDisplay",
                    listOf(
                        CanSignalDef("Gear", 12, 4, Endianness.LITTLE_ENDIAN, 1.0, -4.0, 0.0, 10.0, "Gear")
                    )
                )
            )
        )
        map["mini"] = map["bmw"]!!

        // 12. MERCEDES-BENZ (mercedes_benz_e350_2010.dbc - W204/W212/W164/W221 Chassis CAN)
        map["mercedes"] = VehicleDbcProfile(
            make = "Mercedes-Benz",
            platformName = "Mercedes-Benz Chassis CAN (opendbc)",
            filterCanIds = listOf("003", "005", "00E", "073", "105", "203"),
            messages = mapOf(
                0x003 to CanMessageDef(
                    "003", 0x003, "STEER_SENSOR",
                    listOf(
                        CanSignalDef("Steering Angle", dbcBe(3), 12, Endianness.BIG_ENDIAN, -0.5, 0.0, -500.0, 500.0, "deg", signed = true),
                        CanSignalDef("Steering Rate", dbcBe(19), 12, Endianness.BIG_ENDIAN, 0.5, 0.0, -1000.0, 1000.0, "deg/s", signed = true)
                    )
                ),
                0x005 to CanMessageDef(
                    "005", 0x005, "BRAKE_MODULE",
                    listOf(
                        CanSignalDef("Brake Pressure", dbcBe(17), 10, Endianness.BIG_ENDIAN, 1.0, 0.0, 0.0, 1023.0, "psi")
                    )
                ),
                0x00E to CanMessageDef(
                    "00E", 0x00E, "STEER_TORQUE",
                    listOf(
                        CanSignalDef("Driver Torque", dbcBe(15), 8, Endianness.BIG_ENDIAN, 0.5, -64.0, -64.0, 64.0, "Nm")
                    )
                ),
                0x073 to CanMessageDef(
                    "073", 0x073, "GEAR_PACKET",
                    listOf(
                        CanSignalDef("Gear", 0, 4, Endianness.LITTLE_ENDIAN, 1.0, 0.0, 0.0, 10.0, "Gear")
                    )
                ),
                0x105 to CanMessageDef(
                    "105", 0x105, "GAS_PEDAL",
                    listOf(
                        CanSignalDef("Accelerator", dbcBe(39), 8, Endianness.BIG_ENDIAN, 0.392157, 0.0, 0.0, 100.0, "%")
                    )
                ),
                0x203 to CanMessageDef(
                    "203", 0x203, "WHEEL_SPEEDS",
                    listOf(
                        CanSignalDef("Wheel FL", dbcBe(2), 11, Endianness.BIG_ENDIAN, 0.0375, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel FR", dbcBe(18), 11, Endianness.BIG_ENDIAN, 0.0375, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel RL", dbcBe(34), 11, Endianness.BIG_ENDIAN, 0.0375, 0.0, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel RR", dbcBe(50), 11, Endianness.BIG_ENDIAN, 0.0375, 0.0, 0.0, 180.0, "mph")
                    )
                )
            )
        )

        // 13. VOLVO (volvo_v60_2015_pt.dbc - P3 / SPA S60, V60, XC60, XC70, S80, XC90)
        map["volvo"] = VehicleDbcProfile(
            make = "Volvo",
            platformName = "Volvo P3 / SPA Powertrain CAN (opendbc)",
            filterCanIds = listOf("010", "020", "12A", "148", "20A", "220", "235", "246"),
            messages = mapOf(
                0x010 to CanMessageDef(
                    "010", 0x010, "SAS0",
                    listOf(
                        CanSignalDef("Steering Angle", dbcBe(53), 14, Endianness.BIG_ENDIAN, 0.0445, 0.0, -720.0, 720.0, "deg")
                    )
                ),
                0x020 to CanMessageDef(
                    "020", 0x020, "AccPedal",
                    listOf(
                        CanSignalDef("Accelerator", dbcBe(17), 10, Endianness.BIG_ENDIAN, 0.1, 0.0, 0.0, 100.0, "%")
                    )
                ),
                0x12A to CanMessageDef(
                    "12A", 0x12A, "EngineData",
                    listOf(
                        CanSignalDef("Engine Speed (CAN)", dbcBe(52), 13, Endianness.BIG_ENDIAN, 1.0, 0.0, 0.0, 8000.0, "rpm")
                    )
                ),
                0x148 to CanMessageDef(
                    "148", 0x148, "VehicleSpeed1",
                    listOf(
                        CanSignalDef("Vehicle Speed (CAN)", dbcBe(55), 16, Endianness.BIG_ENDIAN, 0.01 * 0.621371, 0.0, 0.0, 180.0, "mph")
                    )
                ),
                0x20A to CanMessageDef(
                    "20A", 0x20A, "Brake_Info",
                    listOf(
                        CanSignalDef("Brake Pressure", dbcBe(17), 10, Endianness.BIG_ENDIAN, 1.0, 0.0, 0.0, 1023.0, "psi")
                    )
                ),
                0x220 to CanMessageDef(
                    "220", 0x220, "wheelspeed1",
                    listOf(
                        CanSignalDef("Wheel RR", dbcBe(39), 16, Endianness.BIG_ENDIAN, 0.01 * 0.621371, -327.68 * 0.621371, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel RL", dbcBe(55), 16, Endianness.BIG_ENDIAN, 0.01 * 0.621371, -327.68 * 0.621371, 0.0, 180.0, "mph")
                    )
                ),
                0x235 to CanMessageDef(
                    "235", 0x235, "wheelspeed0",
                    listOf(
                        CanSignalDef("Wheel FR", dbcBe(39), 16, Endianness.BIG_ENDIAN, 0.01 * 0.621371, -327.68 * 0.621371, 0.0, 180.0, "mph"),
                        CanSignalDef("Wheel FL", dbcBe(55), 16, Endianness.BIG_ENDIAN, 0.01 * 0.621371, -327.68 * 0.621371, 0.0, 180.0, "mph")
                    )
                ),
                0x246 to CanMessageDef(
                    "246", 0x246, "PSCM1",
                    listOf(
                        CanSignalDef("Steering Angle", dbcBe(23), 16, Endianness.BIG_ENDIAN, 0.0447, -1464.8, -720.0, 720.0, "deg")
                    )
                )
            )
        )

        // 14. TESLA (tesla_can.dbc & tesla_powertrain.dbc - Model S/X/3/Y)
        map["tesla"] = VehicleDbcProfile(
            make = "Tesla",
            platformName = "Tesla Party & Chassis CAN (opendbc)",
            filterCanIds = listOf("003", "00E", "106", "108", "116", "118", "131", "155", "370"),
            messages = mapOf(
                0x003 to CanMessageDef(
                    "003", 0x003, "STW_ANGL_STAT",
                    listOf(
                        CanSignalDef("Steering Angle", dbcBe(5), 14, Endianness.BIG_ENDIAN, 0.5, -2048.0, -900.0, 900.0, "deg"),
                        CanSignalDef("Steering Rate", dbcBe(21), 14, Endianness.BIG_ENDIAN, 0.5, -2048.0, -2000.0, 2000.0, "deg/s")
                    )
                ),
                0x00E to CanMessageDef(
                    "00E", 0x00E, "STW_ANGLHP_STAT",
                    listOf(
                        CanSignalDef("Steering Angle", dbcBe(5), 14, Endianness.BIG_ENDIAN, 0.1, -819.2, -819.0, 819.0, "deg"),
                        CanSignalDef("Steering Rate", dbcBe(21), 14, Endianness.BIG_ENDIAN, 0.5, -4096.0, -2000.0, 2000.0, "deg/s")
                    )
                ),
                0x106 to CanMessageDef(
                    "106", 0x106, "DI_torque1_PT",
                    listOf(
                        CanSignalDef("Driver Torque", 0, 13, Endianness.LITTLE_ENDIAN, 0.25, 0.0, -750.0, 750.0, "Nm", signed = true),
                        CanSignalDef("Engine Torque", 16, 13, Endianness.LITTLE_ENDIAN, 0.25, 0.0, -750.0, 750.0, "Nm", signed = true),
                        CanSignalDef("Engine Speed (CAN)", 32, 16, Endianness.LITTLE_ENDIAN, 1.0, 0.0, 0.0, 17000.0, "rpm", signed = true),
                        CanSignalDef("Accelerator", 48, 8, Endianness.LITTLE_ENDIAN, 0.4, 0.0, 0.0, 100.0, "%")
                    )
                ),
                0x108 to CanMessageDef(
                    "108", 0x108, "DI_torque1",
                    listOf(
                        CanSignalDef("Driver Torque", 0, 13, Endianness.LITTLE_ENDIAN, 0.25, 0.0, -750.0, 750.0, "Nm", signed = true),
                        CanSignalDef("Engine Torque", 16, 13, Endianness.LITTLE_ENDIAN, 0.25, 0.0, -750.0, 750.0, "Nm", signed = true),
                        CanSignalDef("Engine Speed (CAN)", 32, 16, Endianness.LITTLE_ENDIAN, 1.0, 0.0, 0.0, 17000.0, "rpm", signed = true),
                        CanSignalDef("Accelerator", 48, 8, Endianness.LITTLE_ENDIAN, 0.4, 0.0, 0.0, 100.0, "%")
                    )
                ),
                0x116 to CanMessageDef(
                    "116", 0x116, "DI_torque2_PT",
                    listOf(
                        CanSignalDef("Gear", 12, 3, Endianness.LITTLE_ENDIAN, 1.0, 0.0, 0.0, 7.0, "Gear"),
                        CanSignalDef("Vehicle Speed (CAN)", 16, 12, Endianness.LITTLE_ENDIAN, 0.05, -25.0, 0.0, 180.0, "mph")
                    )
                ),
                0x118 to CanMessageDef(
                    "118", 0x118, "DI_torque2",
                    listOf(
                        CanSignalDef("Gear", 12, 3, Endianness.LITTLE_ENDIAN, 1.0, 0.0, 0.0, 7.0, "Gear"),
                        CanSignalDef("Vehicle Speed (CAN)", 16, 12, Endianness.LITTLE_ENDIAN, 0.05, -25.0, 0.0, 180.0, "mph")
                    )
                ),
                0x131 to CanMessageDef(
                    "131", 0x131, "EPAS3P_sysStatus",
                    listOf(
                        CanSignalDef("Driver Torque", dbcBe(19), 12, Endianness.BIG_ENDIAN, 0.01, -20.5, -20.5, 20.5, "Nm"),
                        CanSignalDef("Steering Angle", dbcBe(37), 14, Endianness.BIG_ENDIAN, 0.1, -819.2, -819.0, 819.0, "deg")
                    )
                ),
                0x370 to CanMessageDef(
                    "370", 0x370, "EPAS_sysStatus",
                    listOf(
                        CanSignalDef("Driver Torque", dbcBe(19), 12, Endianness.BIG_ENDIAN, 0.01, -20.5, -20.5, 20.5, "Nm"),
                        CanSignalDef("Steering Angle", dbcBe(37), 14, Endianness.BIG_ENDIAN, 0.1, -819.2, -819.0, 819.0, "deg")
                    )
                ),
                0x155 to CanMessageDef(
                    "155", 0x155, "ESP_B",
                    listOf(
                        CanSignalDef("Vehicle Speed (CAN)", dbcBe(47), 16, Endianness.BIG_ENDIAN, 0.01 * 0.621371, 0.0, 0.0, 180.0, "mph")
                    )
                )
            )
        )

        return map
    }
}
