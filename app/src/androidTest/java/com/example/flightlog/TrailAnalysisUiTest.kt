package com.example.flightlog

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.flightlog.data.SectionEffortEntity
import com.example.flightlog.data.TrailPassEntity
import com.example.flightlog.data.TrailSectionEntity
import com.example.flightlog.domain.SectionKind
import com.example.flightlog.domain.SectionState
import com.example.flightlog.ui.IdealRunCard
import com.example.flightlog.ui.SplitOverviewCard
import com.example.flightlog.ui.theme.FlightLogTheme
import org.junit.Rule
import org.junit.Test

class TrailAnalysisUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun splitOverviewLeadsWithUsefulMetricsAndHidesSecondaryDetails() {
        val now = System.currentTimeMillis()
        val passes = listOf(pass(1, now), pass(2, now - 86_400_000L))
        val efforts = listOf(
            effort(1, 10, 42_000, nonstop = false),
            effort(2, 10, 40_000, nonstop = true),
        )
        compose.setContent {
            FlightLogTheme {
                SplitOverviewCard(
                    section = split(10, "Split 1"),
                    passes = passes,
                    allEfforts = efforts,
                    features = emptyList(),
                    imperial = false,
                    noEarlierStops = false,
                    onNoEarlierStops = {},
                    onEdit = {},
                )
            }
        }

        compose.onNodeWithText("LATEST").assertExists()
        compose.onNodeWithText("PERSONAL BEST").assertExists()
        compose.onNodeWithText("No earlier stops").assertExists()
        compose.onNode(hasText("Progress")).assertDoesNotExist()
        compose.onNode(hasText("Best")).assertDoesNotExist()
        compose.onNode(hasContentDescription("Trend chart", substring = true)).assertExists()
        compose.onNodeWithText("More details").performClick()
        compose.onNode(hasText("Average", substring = true)).assertExists()
    }

    @Test
    fun idealRunIsExpandableAndExplicitlyTheoretical() {
        val passes = listOf(pass(1, 1_750_000_000_000, fullRun = true))
        val efforts = listOf(
            effort(1, 10, 30_000, nonstop = true),
            effort(1, 99, 35_000, nonstop = true),
        )
        compose.setContent {
            FlightLogTheme {
                IdealRunCard(
                    splitSections = listOf(split(10, "Split 1")),
                    passes = passes,
                    efforts = efforts,
                    wholeTrailSection = split(99, "Whole trail", SectionKind.WHOLE_TRAIL),
                )
            }
        }

        compose.onNodeWithText("Ideal run").assertExists()
        compose.onNodeWithText("View").performClick()
        compose.onNodeWithText("This is theoretical and was not recorded as one ride.").assertExists()
    }

    private fun pass(id: Long, startedAt: Long, fullRun: Boolean = false) = TrailPassEntity(
        id = id,
        trailId = 1,
        rideId = id,
        startedAt = startedAt,
        endedAt = startedAt + 60_000,
        startMeters = 0.0,
        endMeters = 100.0,
        matchConfidence = 90,
        interrupted = false,
        fullRunEligible = fullRun,
    )

    private fun split(id: Long, name: String, kind: SectionKind = SectionKind.SPLIT) = TrailSectionEntity(
        id = id,
        trailId = 1,
        name = name,
        kind = kind,
        state = SectionState.CONFIRMED,
        startMeters = 0.0,
        endMeters = 100.0,
    )

    private fun effort(passId: Long, sectionId: Long, elapsed: Long, nonstop: Boolean) = SectionEffortEntity(
        passId = passId,
        sectionId = sectionId,
        elapsedMillis = elapsed,
        entrySpeedMps = 5.0,
        minimumSpeedMps = 4.0,
        averageSpeedMps = 6.0,
        exitSpeedMps = 7.0,
        maximumSpeedMps = 8.0,
        sampleQuality = 90,
        lateralOffsetMeters = 0.0,
        lateralUncertaintyMeters = 2.0,
        valid = true,
        reachedWithoutPriorStop = nonstop,
    )
}
