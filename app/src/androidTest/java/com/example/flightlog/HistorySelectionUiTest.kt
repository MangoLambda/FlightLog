package com.example.flightlog

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.example.flightlog.data.BulkRideDeletePreview
import com.example.flightlog.data.BulkRideDeleteResult
import com.example.flightlog.data.RideEntity
import com.example.flightlog.domain.RideState
import com.example.flightlog.ui.theme.FlightLogTheme
import org.junit.Rule
import org.junit.Test

class HistorySelectionUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun selectAllUpdatesCountAndOpensBulkDeleteConfirmation() {
        composeRule.setContent {
            FlightLogTheme {
                HistoryScreen(
                    rides = listOf(ride(1), ride(2)),
                    imperial = false,
                    onRide = {},
                    onPreviewDelete = { ids ->
                        BulkRideDeletePreview(ids, emptyList(), emptyList(), emptySet())
                    },
                    onDelete = { preview ->
                        BulkRideDeleteResult(preview.rideIds.size, 0, 0)
                    },
                )
            }
        }

        composeRule.onNodeWithText("Select").performClick()
        composeRule.onNodeWithText("0 selected").assertIsDisplayed()
        composeRule.onNodeWithText("Select all").performClick()
        composeRule.onNodeWithText("2 selected").assertIsDisplayed()
        composeRule.onNodeWithText("Clear all").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Delete selected rides").performClick()
        composeRule.onNodeWithText("Delete 2 rides?").assertIsDisplayed()
        composeRule.onNodeWithText("Delete permanently").assertIsDisplayed()
    }

    @Test fun cancelSelectionReturnsToNormalHistoryMode() {
        composeRule.setContent {
            FlightLogTheme {
                HistoryScreen(
                    rides = listOf(ride(1)),
                    imperial = false,
                    onRide = {},
                    onPreviewDelete = { ids -> BulkRideDeletePreview(ids, emptyList(), emptyList(), emptySet()) },
                    onDelete = { BulkRideDeleteResult(1, 0, 0) },
                )
            }
        }

        composeRule.onNodeWithText("Select").performClick()
        composeRule.onNodeWithContentDescription("Cancel selection").performClick()

        composeRule.onNodeWithText("Ride history").assertIsDisplayed()
        composeRule.onNodeWithText("Select").assertIsDisplayed()
    }

    @Test fun longPressEntersSelectionAndSelectsTheRide() {
        composeRule.setContent {
            FlightLogTheme {
                HistoryScreen(
                    rides = listOf(ride(1)),
                    imperial = false,
                    onRide = {},
                    onPreviewDelete = { ids -> BulkRideDeletePreview(ids, emptyList(), emptyList(), emptySet()) },
                    onDelete = { BulkRideDeleteResult(1, 0, 0) },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Ride ", substring = true).performTouchInput { longClick() }

        composeRule.onNodeWithText("1 selected").assertIsDisplayed()
    }

    @Test fun eachRideDisplaysItsSavedDataSize() {
        composeRule.setContent {
            FlightLogTheme {
                HistoryScreen(
                    rides = listOf(ride(1)),
                    imperial = false,
                    rideStorageBytes = mapOf(1L to 2_048L),
                    onRide = {},
                    onPreviewDelete = { ids -> BulkRideDeletePreview(ids, emptyList(), emptyList(), emptySet()) },
                    onDelete = { BulkRideDeleteResult(1, 0, 0) },
                )
            }
        }

        composeRule.onNodeWithText("About 2.0 KB saved").assertIsDisplayed()
    }

    private fun ride(id: Long) = RideEntity(
        id = id,
        startedAt = id * 1_000,
        endedAt = id * 1_000 + 500,
        state = RideState.COMPLETED,
        distanceMeters = 100.0,
        movingTimeMillis = 10_000,
    )
}
