package com.example.flightlog.tracking

import com.example.flightlog.domain.SensorQuality
import com.example.flightlog.domain.MountingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JumpDetectorTest {
    @Test fun detectsSustainedLowAccelerationFollowedByLanding() {
        var result: Triple<Long, Long, Int>? = null
        val detector = JumpDetector(SensorQuality.FULL) { takeoff, landing, confidence ->
            result = Triple(takeoff, landing, confidence)
        }
        detector.onAcceleration(0, 0f, 0f, 3f)
        detector.onAcceleration(120_000_000, 0f, 0f, 3f)
        detector.onAcceleration(600_000_000, 0f, 0f, 18f)
        assertEquals(0L, result?.first)
        assertEquals(600_000_000L, result?.second)
        assertTrue((result?.third ?: 0) >= 70)
    }

    @Test fun ignoresShortBumpAndVeryLongGap() {
        var detections = 0
        val detector = JumpDetector(SensorQuality.FULL) { _, _, _ -> detections++ }
        detector.onAcceleration(0, 0f, 0f, 3f)
        detector.onAcceleration(50_000_000, 0f, 0f, 18f)
        detector.onAcceleration(1_000_000_000, 0f, 0f, 3f)
        detector.onAcceleration(1_120_000_000, 0f, 0f, 3f)
        detector.onAcceleration(4_000_000_000, 0f, 0f, 18f)
        assertEquals(0, detections)
    }

    @Test fun acceptsSofterPocketLandingAndPenalizesIndependentRotation() {
        var stableConfidence = 0
        val stable = JumpDetector(SensorQuality.FULL) { _, _, confidence -> stableConfidence = confidence }
        stable.onAcceleration(0, 0f, 0f, 5f)
        stable.onAcceleration(90_000_000, 0f, 0f, 5f)
        stable.onAcceleration(600_000_000, 0f, 0f, 14f)

        var looseConfidence = 0
        val loose = JumpDetector(SensorQuality.FULL) { _, _, confidence -> looseConfidence = confidence }
        loose.onAcceleration(0, 0f, 0f, 5f)
        loose.onAcceleration(90_000_000, 0f, 0f, 5f)
        loose.onGyroscope(12f, 0f, 0f)
        loose.onAcceleration(600_000_000, 0f, 0f, 14f)

        assertTrue(stableConfidence > 0)
        assertTrue(looseConfidence < stableConfidence)
    }

    @Test fun appliesMinimumEstimatedHeightBeforeCreatingJump() {
        var rejectedDetection = 0
        val rejecting = JumpDetector(
            sensorQuality = SensorQuality.FULL,
            minimumJumpHeightMeters = 0.5,
        ) { _, _, _ -> rejectedDetection++ }
        rejecting.onAcceleration(0, 0f, 0f, 3f)
        rejecting.onAcceleration(90_000_000, 0f, 0f, 3f)
        rejecting.onAcceleration(600_000_000, 0f, 0f, 18f)

        var acceptedDetection = 0
        val accepting = JumpDetector(
            sensorQuality = SensorQuality.FULL,
            minimumJumpHeightMeters = 0.4,
        ) { _, _, _ -> acceptedDetection++ }
        accepting.onAcceleration(0, 0f, 0f, 3f)
        accepting.onAcceleration(90_000_000, 0f, 0f, 3f)
        accepting.onAcceleration(600_000_000, 0f, 0f, 18f)

        assertEquals(0, rejectedDetection)
        assertEquals(1, acceptedDetection)
    }

    @Test fun mountedProfileUsesCleanTakeoffAndStrongerLanding() {
        var detections = 0
        val detector = JumpDetector(
            sensorQuality = SensorQuality.FULL,
            mountingMode = MountingMode.BIKE_MOUNTED,
            minimumJumpHeightMeters = 0.1,
        ) { _, _, _ -> detections++ }
        detector.onAcceleration(0, 0f, 0f, 4f)
        detector.onAcceleration(70_000_000, 0f, 0f, 4f)
        detector.onAcceleration(500_000_000, 0f, 0f, 16f)

        assertEquals(1, detections)
    }
}
