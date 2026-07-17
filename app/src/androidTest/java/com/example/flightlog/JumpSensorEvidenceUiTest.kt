package com.example.flightlog

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.example.flightlog.tracking.JumpEstimateMethod
import com.example.flightlog.tracking.JumpSensorAnalysis
import com.example.flightlog.tracking.OrientationSource
import com.example.flightlog.tracking.PressureQuality
import com.example.flightlog.tracking.SensorRateSummary
import com.example.flightlog.ui.theme.FlightLogTheme
import org.junit.Rule
import org.junit.Test

class JumpSensorEvidenceUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun evidenceShowsRequestedAndDeliveredRatesAndFusionMethod() {
        composeRule.setContent {
            FlightLogTheme {
                SensorEvidenceCard(
                    analysis = JumpSensorAnalysis(
                        accelerometerRate = rate(100, 99.8, 170),
                        gyroscopeRate = rate(100, 98.2, 168),
                        orientationRate = rate(100, 100.0, 171),
                        pressureRate = rate(25, 25.0, 43),
                        orientationSource = OrientationSource.GAME_ROTATION_VECTOR,
                        orientationCoverage = .98,
                        maximumRotationDegrees = 14.0,
                        pressureQuality = PressureQuality.ACCEPTED,
                        barometricHeightMeters = .5,
                        airtimeHeightMeters = .44,
                        fusedHeightMeters = .46,
                        estimateMethod = JumpEstimateMethod.AIRTIME_BAROMETER,
                        estimatedConfidence = 90,
                        worldVerticalAcceleration = emptyList(),
                        relativePressureHeight = emptyList(),
                    ),
                    imperial = false,
                )
            }
        }

        composeRule.onNodeWithText("Sensor evidence").assertIsDisplayed()
        composeRule.onNodeWithText("99.8 Hz / 100 requested").assertIsDisplayed()
        composeRule.onNodeWithText("25.0 Hz / 25 requested").assertIsDisplayed()
        composeRule.onNodeWithText("Height method: Airtime + barometer").assertIsDisplayed()
        composeRule.onNodeWithText("Pressure: accepted").assertIsDisplayed()
    }

    private fun rate(requested: Int, delivered: Double, samples: Int) = SensorRateSummary(
        requestedHz = requested,
        deliveredHz = delivered,
        sampleCount = samples,
        maximumGapMillis = 10,
    )
}
