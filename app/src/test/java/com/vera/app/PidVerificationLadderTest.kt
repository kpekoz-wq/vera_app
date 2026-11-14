package com.vera.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PidVerificationLadderTest {

    @Test
    fun testChryslerBogus240DegreeTransmissionTempRejected() {
        // Chrysler 62TE bug reproduction:
        // On cold start, engine coolant is 4.4°C (40°F).
        // A bogus engine throttle PID raw value was parsed as 115.5°C (240°F).
        val bogusTransStream = Mode22StreamData(
            pid = Mode22PidDefinition("0120", "Transmission Fluid Temp", "Transmission", "°C", "Chrysler ATF", "Chrysler"),
            rawValue = 115.5 // 240°F
        )

        val verification = verifyMode22Physics(
            stream = bogusTransStream,
            coolantTempC = 4.4, // 40°F cold start
            ambientTempC = 4.4,
            rpm = 800
        )

        // Must be rejected as IMPLAUSIBLE
        assertEquals(PidVerificationStatus.IMPLAUSIBLE, verification)
        assertFalse(verification.isTrusted)
    }

    @Test
    fun testWarmedUpTransmissionTempAccepted() {
        // Warmed up vehicle: coolant is 90°C, trans temp is 85°C
        val normalTransStream = Mode22StreamData(
            pid = Mode22PidDefinition("01", "Transmission Fluid Temp", "Transmission", "°C", "Chrysler ATF (TCM)", "Chrysler", servicePrefix = "21"),
            rawValue = 85.0 // 185°F
        )

        val verification = verifyMode22Physics(
            stream = normalTransStream,
            coolantTempC = 90.0,
            ambientTempC = 20.0,
            rpm = 1500
        )

        assertEquals(PidVerificationStatus.VERIFIED, verification)
        assertTrue(verification.isTrusted)
    }

    @Test
    fun testEngineOilPressureChecks() {
        val oilPressurePid = Mode22PidDefinition("0125", "Engine Oil Pressure", "Engine Oil", "psi", "Oil Pressure", "Chrysler")

        // Engine stopped (0 RPM) with 45 psi reported is implausible
        val stoppedEngineHighPressure = Mode22StreamData(pid = oilPressurePid, rawValue = 45.0)
        val stoppedVerif = verifyMode22Physics(stoppedEngineHighPressure, 85.0, 20.0, rpm = 0)
        assertEquals(PidVerificationStatus.IMPLAUSIBLE, stoppedVerif)

        // Engine running (1200 RPM) with 45 psi is verified
        val runningEnginePressure = Mode22StreamData(pid = oilPressurePid, rawValue = 45.0)
        val runningVerif = verifyMode22Physics(runningEnginePressure, 85.0, 20.0, rpm = 1200)
        assertEquals(PidVerificationStatus.VERIFIED, runningVerif)
    }

    @Test
    fun testBatteryVoltageChecks() {
        val voltagePid = Mode22PidDefinition("0204", "Battery Voltage", "Electrical", "V", "Battery", "Chrysler")

        val normalVolt = Mode22StreamData(pid = voltagePid, rawValue = 14.2)
        assertEquals(PidVerificationStatus.VERIFIED, verifyMode22Physics(normalVolt, 85.0, 20.0))

        val crazyVolt = Mode22StreamData(pid = voltagePid, rawValue = 24.5)
        assertEquals(PidVerificationStatus.IMPLAUSIBLE, verifyMode22Physics(crazyVolt, 85.0, 20.0))
    }

    @Test
    fun testTpmsSleepingSensorIsCandidate() {
        val tpmsPid = Mode22PidDefinition("A020", "Tire Pressure FL", "Chassis", "psi", "Chrysler TPMS", "Chrysler")

        // 0.0 psi when vehicle is stationary is a sleeping centrifugal sensor -> CANDIDATE
        val sleepingSensor = Mode22StreamData(pid = tpmsPid, rawValue = 0.0)
        val sleepVerif = verifyMode22Physics(sleepingSensor, 85.0, 20.0)
        assertEquals(PidVerificationStatus.CANDIDATE, sleepVerif)
        assertFalse(sleepVerif.isTrusted)

        // 34.0 psi rolling pressure is VERIFIED
        val rollingSensor = Mode22StreamData(pid = tpmsPid, rawValue = 34.0)
        val rollVerif = verifyMode22Physics(rollingSensor, 85.0, 20.0)
        assertEquals(PidVerificationStatus.VERIFIED, rollVerif)
        assertTrue(rollVerif.isTrusted)
    }
}
