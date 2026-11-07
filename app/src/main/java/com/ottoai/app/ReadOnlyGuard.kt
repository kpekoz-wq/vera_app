package com.ottoai.app

/**
 * Last line of defence: decides whether a string about to be sent to the adapter is READ-ONLY.
 * Put ONE check at the top of sendAtCommand() so no code path (hand-written or AI-generated)
 * can write to, reconfigure, or actuate anything on the vehicle.
 *
 *   suspend fun sendAtCommand(command: String, delayMs: Long = 100): String {
 *       if (!ReadOnlyGuard.isAllowed(command)) {
 *           Log.w(TAG, "BLOCKED non-read command: [$command]")
 *           return "BLOCKED"
 *       }
 *       ... existing code ...
 *   }
 *
 * Allowed:
 *   - ELM327 "AT..." adapter commands (these configure the adapter, not the car)
 *   - 01 current data        02 freeze frame        03 stored DTCs     06 on-board monitor results
 *   - 07 pending DTCs        09 vehicle info (VIN)  0A permanent DTCs
 *   - 19 UDS read DTCs       21 / 22 read data by identifier
 *
 * Everything else is blocked, including: 04 clear DTCs, 08 control, 10 session control,
 * 11 reset, 14 clear DTCs (UDS), 27 security access, 2E write, 2F IO control, 31 routines,
 * 3E tester present, and 34/36/37 programming.
 */
object ReadOnlyGuard {

    private val ALLOWED_SERVICES = setOf(0x01, 0x02, 0x03, 0x06, 0x07, 0x09, 0x0A, 0x19, 0x21, 0x22)

    fun isAllowed(command: String): Boolean {
        val c = command.trim().uppercase().replace(" ", "")
        if (c.isEmpty()) return false
        if (c.startsWith("AT")) return true           // adapter configuration only

        // Hex requests must be whole bytes made only of hex digits.
        if (c.length % 2 != 0 || !c.all { it in '0'..'9' || it in 'A'..'F' }) return false

        val service = c.substring(0, 2).toInt(16)
        return service in ALLOWED_SERVICES
    }
}
