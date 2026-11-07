package com.ottoai.app

/**
 * Pure-Kotlin (no Android imports) parsing for ELM327 / OBD-II / UDS responses.
 *
 * Golden rules this file enforces:
 *  1. Work on UNSIGNED ints (IntArray), never Kotlin Bytes.
 *  2. A response only counts if it starts with the EXACT expected prefix
 *     (41 <pid>, 46 <mid>, 62 <did-hi> <did-lo>, 49 02 ...). No substring searching.
 *  3. Multi-frame (ISO-TP) responses are reassembled before anything reads them.
 *  4. Anything that isn't hex (SEARCHING..., NO DATA, ?, STOPPED...) never reaches the hex parser.
 */
object ObdParser {

    // ---------------------------------------------------------------- framing

    private val HEX_LINE = Regex("^[0-9A-F]+$")
    private val FRAME_LINE = Regex("^([0-9A-F]):([0-9A-F]*)$")

    enum class Status { OK, NO_DATA, UNABLE_TO_CONNECT, ELM_ERROR, UNKNOWN_COMMAND, EMPTY }

    private val ERROR_MARKERS = listOf(
        "CANERROR", "BUSERROR", "BUSBUSY", "BUFFERFULL", "DATAERROR", "FBERROR",
        "LPALERT", "LVRESET", "ACTALERT", "RXERROR", "STOPPED"
    )

    /** Classify the raw text of one ELM327 reply (diagnostics / retry decisions only). */
    fun classify(raw: String): Status {
        val compact = raw.uppercase().replace(" ", "").replace(">", "")
        if (compact.isBlank()) return Status.EMPTY
        if (compact.contains("UNABLETOCONNECT")) return Status.UNABLE_TO_CONNECT
        if (compact.contains("NODATA")) return Status.NO_DATA
        if (ERROR_MARKERS.any { compact.contains(it) }) return Status.ELM_ERROR
        if (compact.split('\r', '\n').any { it.trim() == "?" }) return Status.UNKNOWN_COMMAND
        return Status.OK
    }

    /**
     * Turn raw ELM text into a list of complete payloads (one per responding ECU / reassembled message).
     * Handles ATCAF1 multi-line output:   "014" / "0: 49 02 01 31 46 54" / "1: 50 57 ..."
     * Drops: echo, prompts, SEARCHING..., NO DATA, etc. (anything that isn't pure hex).
     */
    fun extractPayloads(raw: String, echo: String? = null): List<IntArray> {
        val echoClean = echo?.replace(" ", "")?.uppercase()
        val lines = raw.uppercase()
            .replace('>', '\n')
            .split('\r', '\n')
            .map { it.replace(" ", "").trim() }
            .filter { it.isNotEmpty() && it != echoClean }
            .filter { HEX_LINE.matches(it) || FRAME_LINE.matches(it) }

        val out = ArrayList<IntArray>()
        var cur: StringBuilder? = null
        var curLen = -1
        var pendingLen = -1

        fun flush() {
            val sb = cur
            if (sb != null) out.add(hexToInts(sb.toString(), curLen))
            cur = null
            curLen = -1
        }

        for ((i, line) in lines.withIndex()) {
            val m = FRAME_LINE.matchEntire(line)
            if (m != null) {
                val idx = m.groupValues[1].toInt(16)
                if (idx == 0 || cur == null) {
                    flush()
                    cur = StringBuilder()
                    curLen = pendingLen
                    pendingLen = -1
                }
                cur!!.append(m.groupValues[2])
            } else {
                flush()
                val nextIsFrame = i + 1 < lines.size && FRAME_LINE.matches(lines[i + 1])
                if (line.length == 3 && nextIsFrame) {
                    pendingLen = line.toInt(16)          // length header of a multi-frame message
                } else {
                    out.add(hexToInts(line, -1))         // ordinary single-frame payload
                }
            }
        }
        flush()
        return out.filter { it.isNotEmpty() }
    }

    private fun hexToInts(hex: String, maxLen: Int): IntArray {
        val even = if (hex.length % 2 == 0) hex else hex.dropLast(1)
        val all = IntArray(even.length / 2) { even.substring(it * 2, it * 2 + 2).toInt(16) }
        return if (maxLen in 0 until all.size) all.copyOfRange(0, maxLen) else all
    }

    /** Returns the bytes AFTER [prefix] from the first payload that starts with it exactly. */
    fun findResponse(payloads: List<IntArray>, vararg prefix: Int): IntArray? {
        for (p in payloads) {
            if (p.size >= prefix.size && prefix.indices.all { p[it] == prefix[it] }) {
                return p.copyOfRange(prefix.size, p.size)
            }
        }
        return null
    }

    /** Returns the NRC of a "7F <service> <nrc>" negative response, or null. */
    fun negativeResponse(payloads: List<IntArray>, service: Int): Int? {
        for (p in payloads) if (p.size >= 3 && p[0] == 0x7F && p[1] == service) return p[2]
        return null
    }

    // ------------------------------------------------- supported-ID bitmasks

    /**
     * Decode a 4-byte "supported PIDs / MIDs" bitmask for the block starting at [base].
     * Returns (ids, nextBlockSupported). Bit 32 is the "next block supported" flag, NOT an id.
     */
    fun decodeSupportMask(base: Int, data: IntArray): Pair<List<Int>, Boolean>? {
        if (data.size < 4) return null
        val ids = ArrayList<Int>()
        for (bit in 1..31) {
            val b = data[(bit - 1) / 8]
            val mask = 1 shl (7 - (bit - 1) % 8)
            if ((b and mask) != 0) ids.add(base + bit)
        }
        return ids to ((data[3] and 0x01) != 0)
    }

    // ------------------------------------------------------------------- VIN

    private val VIN_REGEX = Regex("[A-HJ-NPR-Z0-9]{17}")

    fun parseVin(payloads: List<IntArray>): String? {
        val chars = StringBuilder()
        for (p in payloads) {
            if (p.size >= 3 && p[0] == 0x49 && p[1] == 0x02) {
                for (k in 3 until p.size) {           // p[2] = count / sequence byte
                    val c = p[k]
                    if (c in 0x30..0x5A) chars.append(c.toChar())
                }
            }
        }
        return VIN_REGEX.find(chars.toString())?.value
    }

    // ------------------------------------------------------------------ DTCs

    /**
     * Mode 03 (stored) / 07 (pending) / 0A (permanent).
     * On CAN the byte after the service id is the DTC count; older protocols have no count byte.
     * Returns null if no ECU produced a valid reply.
     */
    fun parseDtcs(payloads: List<IntArray>, service: Int, isCan: Boolean): List<String>? {
        val resp = service + 0x40
        var any = false
        val codes = LinkedHashSet<String>()
        for (p in payloads) {
            if (p.isEmpty() || p[0] != resp) continue
            any = true
            var data = p.copyOfRange(1, p.size)
            if (isCan) {
                if (data.isEmpty()) continue
                val count = data[0]
                data = data.copyOfRange(1, data.size)
                if (data.size > count * 2) data = data.copyOfRange(0, count * 2)
            }
            var i = 0
            while (i + 1 < data.size) {
                if (data[i] != 0 || data[i + 1] != 0) codes.add(decodeDtc(data[i], data[i + 1]))
                i += 2
            }
        }
        return if (any) codes.toList() else null
    }

    fun decodeDtc(a: Int, b: Int): String {
        val letter = when ((a and 0xC0) shr 6) { 0 -> 'P'; 1 -> 'C'; 2 -> 'B'; else -> 'U' }
        val d1 = (a and 0x30) shr 4
        return String.format("%c%d%X%02X", letter, d1, a and 0x0F, b)
    }

    // ---------------------------------------------------------------- Mode 06

    data class Mode06Raw(
        val mid: Int, val tid: Int, val uasid: Int,
        val value: Int, val min: Int, val max: Int
    )

    data class Uas(val scale: Double, val unit: String)

    /**
     * Unit-and-Scaling IDs from SAE J1979 (the common subset). Unknown ids are NOT guessed:
     * they come back as raw counts labelled "UAS xx" so a wrong scale can never be shown silently.
     * VERIFY against your copy of J1979 / Wikipedia "OBD-II PIDs" before trusting rare UASIDs.
     */
    private val UAS_TABLE: Map<Int, Uas> = mapOf(
        0x01 to Uas(1.0, "counts"), 0x02 to Uas(0.1, "counts"), 0x03 to Uas(0.01, "counts"),
        0x04 to Uas(0.001, "counts"), 0x05 to Uas(0.0000305, "counts"), 0x06 to Uas(0.000305, "counts"),
        0x07 to Uas(0.25, "RPM"), 0x08 to Uas(0.01, "km/h"), 0x09 to Uas(1.0, "km/h"),
        0x0A to Uas(0.122, "mV"), 0x0B to Uas(0.001, "V"), 0x0C to Uas(0.01, "V"),
        0x0D to Uas(0.00390625, "mA"), 0x0E to Uas(0.001, "A"), 0x0F to Uas(0.01, "A"),
        0x10 to Uas(1.0, "ms"), 0x11 to Uas(100.0, "ms"), 0x12 to Uas(1.0, "s"),
        0x13 to Uas(1.0, "mΩ"), 0x14 to Uas(1.0, "Ω"), 0x15 to Uas(1.0, "kΩ"),
        0x16 to Uas(0.1, "°C"), 0x17 to Uas(0.01, "kPa"), 0x18 to Uas(0.0117, "kPa"),
        0x19 to Uas(0.079, "kPa"), 0x1A to Uas(1.0, "kPa"), 0x1B to Uas(10.0, "kPa"),
        0x1C to Uas(0.01, "°"), 0x1D to Uas(0.5, "°")
    )

    fun uasFor(uasid: Int): Uas = when {
        uasid < 0x80 -> UAS_TABLE[uasid]
        uasid in 0x81..0x86 || uasid in 0x8A..0x8F -> UAS_TABLE[uasid - 0x80]   // signed variants
        else -> null
    } ?: Uas(1.0, "UAS %02X".format(uasid))

    fun isSignedUas(uasid: Int) = uasid >= 0x80

    /** Mode 06 on CAN: 46 then repeated 9-byte blocks [MID TID UASID VAL(2) MIN(2) MAX(2)]. */
    fun parseMode06(payloads: List<IntArray>): List<Mode06Raw> {
        val out = ArrayList<Mode06Raw>()
        for (p in payloads) {
            if (p.isEmpty() || p[0] != 0x46) continue
            var i = 1
            while (i + 9 <= p.size) {
                val mid = p[i]
                val uas = p[i + 2]
                fun w(o: Int): Int {
                    val u = (p[i + o] shl 8) or p[i + o + 1]
                    return if (isSignedUas(uas)) u.toShort().toInt() else u
                }
                if (mid != 0) out.add(Mode06Raw(mid, p[i + 1], uas, w(3), w(5), w(7)))
                i += 9
            }
        }
        return out
    }

    /** Category strings match the filter chips in MainActivity (Misfire/Catalyst/O2 Sensor/EVAP System/EGR / VVT). */
    fun mid06Category(mid: Int): String = when (mid) {
        in 0x01..0x0C, in 0x41..0x4C -> "O2 Sensor"
        in 0x21..0x24, in 0x61..0x64 -> "Catalyst"
        in 0x31..0x38 -> "EGR / VVT"
        in 0x39..0x3D -> "EVAP System"
        in 0xA1..0xAD -> "Misfire"
        in 0x71..0x74 -> "Secondary Air"
        else -> "Other"
    }

    /** Names follow J1979 OBDMID assignments. */
    fun mid06Name(mid: Int): String {
        fun bank(base: Int, per: Int, mid: Int) = ((mid - base) / per) + 1
        return when (mid) {
            in 0x01..0x0C -> "O2 Sensor B${bank(0x01, 4, mid)}S${(mid - 0x01) % 4 + 1}"
            in 0x21..0x24 -> "Catalyst Monitor B${mid - 0x20}"
            in 0x31..0x34 -> "EGR Monitor B${mid - 0x30}"
            in 0x35..0x38 -> "VVT Monitor B${mid - 0x34}"
            0x39 -> "EVAP Leak 0.150\""
            0x3A -> "EVAP Leak 0.090\""
            0x3B -> "EVAP Leak 0.040\""
            0x3C -> "EVAP Leak 0.020\""
            0x3D -> "EVAP Purge Flow"
            in 0x41..0x4C -> "O2 Sensor Heater B${bank(0x41, 4, mid)}S${(mid - 0x41) % 4 + 1}"
            in 0x61..0x64 -> "Heated Catalyst B${mid - 0x60}"
            in 0x71..0x74 -> "Secondary Air ${mid - 0x70}"
            in 0x81..0x82 -> "Fuel System B${mid - 0x80}"
            in 0x83..0x84 -> "Boost Pressure B${mid - 0x82}"
            in 0x85..0x86 -> "NOx Absorber B${mid - 0x84}"
            in 0x90..0x91 -> "PM Filter B${mid - 0x8F}"
            0xA1 -> "General Misfire"
            in 0xA2..0xAD -> "Cylinder ${mid - 0xA1} Misfire"
            else -> "MID %02X".format(mid)
        }
    }

    fun tid06Name(mid: Int, tid: Int): String =
        if (mid in 0xA1..0xAD) when (tid) {
            0x0B -> "Misfire avg (last 10 drive cycles)"
            0x0C -> "Misfire count (this drive cycle)"
            else -> "Test %02X".format(tid)
        } else "Test %02X".format(tid)

    // ---------------------------------------------------------- Mode 22 (UDS)

    sealed class Uds {
        class Data(val bytes: IntArray) : Uds()
        class Negative(val nrc: Int) : Uds()
        object None : Uds()
    }

    /** Parse the reply to "22 <did>" -> 62 <did-hi> <did-lo> <data...>. */
    fun parseUds22(payloads: List<IntArray>, did: Int): Uds {
        val hi = (did shr 8) and 0xFF
        val lo = did and 0xFF
        findResponse(payloads, 0x62, hi, lo)?.let { return Uds.Data(it) }
        negativeResponse(payloads, 0x22)?.let { return Uds.Negative(it) }
        return Uds.None
    }
}
