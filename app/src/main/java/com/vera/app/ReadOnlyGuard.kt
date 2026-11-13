package com.vera.app

/**
 * Last line of defence: decides whether a string about to be sent to the adapter is READ-ONLY.
 * Put ONE check at the top of sendAtCommand() so no code path (hand-written or AI-generated)
 * can write to, reconfigure, or actuate anything on the vehicle.
 *
 * Allowed:
 *   - ELM327 "AT..." adapter commands (these configure the adapter, not the car)
 *   - Approved STN read/filter commands ("STM", "STP", "STF", "STFAC", "STFA", "STN", "STDI", "STI")
 *   - Explicitly BLOCKS "STPX" (raw CAN frame transmit) and any unauthorized ST command
 *   - 01 current data        02 freeze frame        03 stored DTCs     06 on-board monitor results
 *   - 07 pending DTCs        09 vehicle info (VIN)  0A permanent DTCs
 *   - 19 UDS read DTCs       21 / 22 read data by identifier
 *   - Allows single trailing response-count digit (e.g. "010C1") for rapid single-frame queries
 *
 * Everything else is blocked, including: 04 clear DTCs, 08 control, 10 session control,
 * 11 reset, 14 clear DTCs (UDS), 27 security access, 2E write, 2F IO control, 31 routines,
 * 3E tester present, STPX (CAN inject), and 34/36/37 programming.
 */
object ReadOnlyGuard {

    private val ALLOWED_SERVICES = setOf(0x01, 0x02, 0x03, 0x06, 0x07, 0x09, 0x0A, 0x19, 0x21, 0x22)
    private val ALLOWED_ST_PREFIXES = listOf("STM", "STP", "STF", "STFAC", "STFA", "STN", "STDI", "STI")

    fun isAllowed(command: String): Boolean {
        val c = command.trim().uppercase().replace(" ", "")
        if (c.isEmpty()) return false
        if (c.startsWith("AT")) return true // ELM adapter configuration only
        if (c.startsWith("ST")) {
            // Block STPX (which sends arbitrary raw CAN transmit packets) and any unapproved ST command
            return ALLOWED_ST_PREFIXES.any { c.startsWith(it) } && !c.startsWith("STPX")
        }

        // Allow single-digit response count (e.g. 010C1, 010D1) for fast ELM327 single-frame queries
        val isOddLength = ((c.length % 2) != 0)
        val lastChar = c.last()
        val hasTrailingCount = isOddLength && (c.length >= 3) && (lastChar >= '1') && (lastChar <= '9')
        val hexPart = if (hasTrailingCount) c.dropLast(1) else c

        // Hex requests must be whole bytes made only of hex digits.
        if (((hexPart.length % 2) != 0) || !hexPart.all { it.isDigit() || ((it in 'A'..'F')) }) return false

        val service = hexPart.substring(0, 2).toInt(16)
        return service in ALLOWED_SERVICES
    }
}
