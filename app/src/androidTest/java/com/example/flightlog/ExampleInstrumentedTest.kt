package com.example.flightlog

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FlightLogSmokeTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test fun firstRunExplainsMountingAndEstimates() {
        composeRule.onNodeWithText("Ride ready, data honest").assertIsDisplayed()
        composeRule.onNodeWithText("I understand").assertIsDisplayed()
    }
}
