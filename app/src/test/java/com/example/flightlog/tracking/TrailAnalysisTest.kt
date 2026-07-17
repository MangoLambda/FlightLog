package com.example.flightlog.tracking

import com.example.flightlog.data.SpatialProfileEntity
import com.example.flightlog.data.TrailSectionEntity
import com.example.flightlog.data.TrailEntity
import com.example.flightlog.data.TrailPassEntity
import com.example.flightlog.data.TrailStopObservationEntity
import com.example.flightlog.domain.SectionKind
import com.example.flightlog.domain.EffortInvalidReason
import com.example.flightlog.domain.MountingMode
import com.example.flightlog.domain.RoughnessKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class TrailAnalysisTest {
    @Test fun spatialProfilesRetainRecordedAltitude() {
        val points = (0 until 10).map { index ->
            com.example.flightlog.data.TrackPointEntity(
                rideId = 1, recordedAt = index * 1_000L,
                latitude = 45.5 + index * .0001, longitude = -73.5,
                altitudeMeters = 100.0 + index, speedMps = 5.0,
                bearingDegrees = 0f, accuracyMeters = 3f,
            )
        }

        val profiles = TrailAnalysis.spatialProfiles(1, points, emptyList(), MountingMode.BIKE_MOUNTED)

        assertTrue(profiles.all { it.altitudeMeters != null })
        assertEquals(100.0, profiles.first().altitudeMeters!!, .01)
        assertTrue(profiles.last().altitudeMeters!! > profiles.first().altitudeMeters!!)
    }

    @Test fun altitudeReprocessingDoesNotErasePermanentRoughness() {
        val previous = profileLine(rideId = 1, count = 3).map { profile ->
            profile.copy(
                roughnessScore = 4.2,
                roughnessKind = RoughnessKind.BIKE_ROUGHNESS,
                roughnessConfidence = 80,
            )
        }
        val rebuilt = previous.map { profile ->
            profile.copy(altitudeMeters = 125.0, roughnessScore = null, roughnessKind = null, roughnessConfidence = null)
        }

        val merged = TrailAnalysis.preservePermanentSignals(rebuilt, previous)

        assertTrue(merged.all { it.altitudeMeters == 125.0 })
        assertTrue(merged.all { it.roughnessScore == 4.2 && it.roughnessKind == RoughnessKind.BIKE_ROUGHNESS })
    }

    @Test fun matchesSameDirectionDespiteImpreciseRideBoundaries() {
        val canonical = profileLine(rideId = 1, count = 120)
        val leading = profileLine(rideId = 2, count = 20, latitudeStart = 45.4998)
        val repeated = profileLine(rideId = 2, count = 120, timeStart = 10_000)
        val trailing = profileLine(rideId = 2, count = 20, latitudeStart = 45.5012, timeStart = 40_000)
        assertNotNull(TrailAnalysis.match(leading + repeated + trailing, canonical))
        assertNull(TrailAnalysis.match(repeated.reversed(), canonical))
    }

    @Test fun matchesASelectedConnectorWhoseDistancesDoNotStartAtZero() {
        val fullCanonical = profileLine(rideId = 1, count = 120)
        val connector = fullCanonical.filter { it.distanceMeters in 300.0..450.0 }
        val candidate = connector.map { it.copy(rideId = 2) }

        assertNotNull(TrailAnalysis.match(candidate, connector))
    }

    @Test fun fullCoverageRequiresContinuousSelectedGeometry() {
        val canonical = profileLine(rideId = 1, count = 80)
        val complete = profileLine(rideId = 2, count = 80).zip(canonical)
        val forked = complete.filterIndexed { index, _ -> index !in 30..36 }

        assertTrue(TrailAnalysis.hasContinuousRangeCoverage(complete, 25.0, 350.0))
        assertFalse(TrailAnalysis.hasContinuousRangeCoverage(forked, 25.0, 350.0))
    }

    @Test fun partialRideDoesNotQualifyAsACompleteRun() {
        val canonical = profileLine(rideId = 1, count = 80)
        val partial = profileLine(rideId = 2, count = 40).zip(canonical.take(40))

        assertFalse(TrailAnalysis.hasContinuousRangeCoverage(partial, 25.0, 350.0))
    }

    @Test fun interruptedSectionIsInvalidButNeighborCanRemainValid() {
        val canonical = profileLine(rideId = 1, count = 80)
        val candidate = profileLine(rideId = 2, count = 80).mapIndexed { index, point ->
            if (index == 30) point.copy(observedSpanMillis = 12_000) else point
        }
        val normalized = candidate.zip(canonical)
        val stopped = TrailSectionEntity(id = 1, trailId = 1, name = "Stopped", kind = SectionKind.MANUAL, startMeters = 100.0, endMeters = 200.0)
        val clear = TrailSectionEntity(id = 2, trailId = 1, name = "Clear", kind = SectionKind.MANUAL, startMeters = 250.0, endMeters = 350.0)
        val stoppedEffort = TrailAnalysis.effort(1, stopped, normalized)!!
        assertFalse(stoppedEffort.valid)
        assertEquals(EffortInvalidReason.STOP, stoppedEffort.invalidReason)
        assertTrue(TrailAnalysis.effort(1, clear, normalized)!!.valid)
    }

    @Test fun slowContinuousTravelIsNotMistakenForAGpsGap() {
        val canonical = profileLine(rideId = 1, count = 20)
        val slow = profileLine(rideId = 2, count = 20).mapIndexed { index, profile ->
            profile.copy(recordedAt = index * 6_000L, speedMps = 1.2, maximumSampleGapMillis = 1_000)
        }
        val section = TrailSectionEntity(
            id = 1, trailId = 1, name = "Slow turn", kind = SectionKind.TURN,
            startMeters = 0.0, endMeters = 50.0,
        )

        val effort = TrailAnalysis.effort(1, section, slow.zip(canonical))!!

        assertTrue(effort.valid)
        assertNull(effort.invalidReason)
    }

    @Test fun actualRawGpsGapInvalidatesOnlyTheAffectedSection() {
        val canonical = profileLine(rideId = 1, count = 30)
        val candidate = profileLine(rideId = 2, count = 30).mapIndexed { index, profile ->
            if (index == 10) profile.copy(maximumSampleGapMillis = 16_000) else profile
        }
        val affected = TrailSectionEntity(
            id = 1, trailId = 1, name = "Gap", kind = SectionKind.MANUAL,
            startMeters = 25.0, endMeters = 75.0,
        )
        val clear = affected.copy(id = 2, name = "Clear", startMeters = 90.0, endMeters = 140.0)

        val affectedEffort = TrailAnalysis.effort(1, affected, candidate.zip(canonical))!!

        assertFalse(affectedEffort.valid)
        assertEquals(EffortInvalidReason.GPS_GAP, affectedEffort.invalidReason)
        assertTrue(TrailAnalysis.effort(1, clear, candidate.zip(canonical))!!.valid)
    }

    @Test fun spatialProfilesPreserveActualRawSampleGap() {
        val points = listOf(0L, 1_000L, 7_000L).mapIndexed { index, timestamp ->
            com.example.flightlog.data.TrackPointEntity(
                rideId = 1, recordedAt = timestamp, latitude = 45.5 + index * .0001,
                longitude = -73.5, altitudeMeters = null, speedMps = 5.0,
                bearingDegrees = 0f, accuracyMeters = 3f,
            )
        }

        val profiles = TrailAnalysis.spatialProfiles(1, points, emptyList(), null)

        assertEquals(6_000L, profiles.maxOf { it.maximumSampleGapMillis })
    }

    @Test fun stopEventsRequireTenSecondsThreeSamplesAndSpatialStability() {
        val stable = listOf(0L, 5_000L, 10_000L).map { timestamp ->
            com.example.flightlog.data.TrackPointEntity(
                rideId = 1, recordedAt = timestamp, latitude = 45.5, longitude = -73.5,
                altitudeMeters = null, speedMps = 0.1, bearingDegrees = null, accuracyMeters = 3f,
            )
        }
        val dropout = listOf(0L, 16_000L, 32_000L).map { timestamp ->
            stable.first().copy(recordedAt = timestamp)
        }

        assertEquals(1, TrailAnalysis.stopEvents(1, "ride", stable).size)
        assertTrue(TrailAnalysis.stopEvents(1, "ride", stable.dropLast(1)).isEmpty())
        assertTrue(TrailAnalysis.stopEvents(1, "ride", dropout).isEmpty())
    }

    @Test fun balancedPauseEvidenceActivatesAtTwoOfThreeButNotTwoOfFour() {
        val trail = TrailEntity(
            id = 1, name = "Trail", canonicalRideId = 1, lengthMeters = 500.0,
            startMeters = 0.0, endMeters = 500.0,
        )
        fun pass(id: Long) = TrailPassEntity(
            id = id, trailId = 1, rideId = id, startedAt = 0, endedAt = 1_000,
            startMeters = 0.0, endMeters = 500.0, matchConfidence = 90, interrupted = false,
        )
        fun observation(passId: Long) = TrailStopObservationEntity(
            trailId = 1, passId = passId, stopEventId = passId, distanceMeters = 200.0,
            startMeters = 198.0, endMeters = 202.0, durationMillis = 20_000, confidence = 90,
        )

        val active = TrailAnalysis.pauseZoneCandidates(
            trail, listOf(observation(1), observation(2)), listOf(pass(1), pass(2), pass(3)),
        ).single()
        val inactive = TrailAnalysis.pauseZoneCandidates(
            trail, listOf(observation(1), observation(2)), listOf(pass(1), pass(2), pass(3), pass(4)),
        ).single()
        val partialFourth = pass(4).copy(startMeters = 300.0)
        val stillActive = TrailAnalysis.pauseZoneCandidates(
            trail, listOf(observation(1), observation(2)), listOf(pass(1), pass(2), pass(3), partialFourth),
        ).single()

        assertTrue(active.active)
        assertFalse(inactive.active)
        assertTrue(stillActive.active)
    }

    @Test fun plausibleFifteenSecondGapIsEstimatedButSixteenSecondsIsRejected() {
        val canonical = profileLine(rideId = 1, count = 30)
        val section = TrailSectionEntity(
            id = 1, trailId = 1, name = "Split", kind = SectionKind.SPLIT,
            startMeters = 25.0, endMeters = 75.0,
        )
        val bridged = profileLine(rideId = 2, count = 30).mapIndexed { index, profile ->
            if (index == 10) profile.copy(maximumSampleGapMillis = 15_000) else profile
        }
        val rejected = bridged.mapIndexed { index, profile ->
            if (index == 10) profile.copy(maximumSampleGapMillis = 16_000) else profile
        }

        val estimated = TrailAnalysis.effort(1, section, bridged.zip(canonical))!!
        val invalid = TrailAnalysis.effort(1, section, rejected.zip(canonical))!!

        assertTrue(estimated.valid && estimated.estimated)
        assertEquals(15_000L, estimated.bridgedGapMillis)
        assertFalse(invalid.valid)
        assertEquals(EffortInvalidReason.GPS_GAP, invalid.invalidReason)
    }

    @Test fun neutralPauseLeavesAdjacentSplitsValidButMarksDownstreamContextRested() {
        val canonical = profileLine(rideId = 1, count = 50)
        val candidate = profileLine(rideId = 2, count = 50)
        val first = TrailSectionEntity(
            id = 1, trailId = 1, name = "Split 1", kind = SectionKind.SPLIT,
            startMeters = 0.0, endMeters = 100.0,
        )
        val second = first.copy(id = 2, name = "Split 2", startMeters = 110.0, endMeters = 200.0)

        val firstEffort = TrailAnalysis.effort(1, first, candidate.zip(canonical), listOf(105.0))!!
        val secondEffort = TrailAnalysis.effort(1, second, candidate.zip(canonical), listOf(105.0))!!

        assertTrue(firstEffort.valid && firstEffort.reachedWithoutPriorStop)
        assertTrue(secondEffort.valid)
        assertFalse(secondEffort.reachedWithoutPriorStop)
    }

    @Test fun repeatedStopsInOnePassCountOnceAndEndpointPausesAreIgnored() {
        val trail = TrailEntity(
            id = 1, name = "Trail", canonicalRideId = 1, lengthMeters = 500.0,
            startMeters = 0.0, endMeters = 500.0,
        )
        val passes = listOf(1L, 2L).map { id ->
            TrailPassEntity(
                id = id, trailId = 1, rideId = id, startedAt = 0, endedAt = 1_000,
                startMeters = 0.0, endMeters = 500.0, matchConfidence = 90, interrupted = false,
            )
        }
        fun observation(passId: Long, distance: Double, duration: Long) = TrailStopObservationEntity(
            trailId = 1, passId = passId, stopEventId = duration, distanceMeters = distance,
            startMeters = distance, endMeters = distance, durationMillis = duration, confidence = 90,
        )

        val candidates = TrailAnalysis.pauseZoneCandidates(
            trail,
            listOf(
                observation(1, 200.0, 10_000), observation(1, 202.0, 20_000),
                observation(2, 201.0, 15_000), observation(1, 20.0, 15_000), observation(2, 21.0, 15_000),
            ),
            passes,
        )

        assertEquals(1, candidates.size)
        assertEquals(2, candidates.single().supportCount)
        assertEquals(17_500L, candidates.single().medianPauseMillis)
    }

    @Test fun pocketAndMountedSignalsRemainSeparateAndDifferentlyQualified() {
        val points = (0 until 80).map { index ->
            com.example.flightlog.data.TrackPointEntity(
                rideId = 1, recordedAt = index * 100L, latitude = 45.5 + index * .00001,
                longitude = -73.5, altitudeMeters = null, speedMps = 8.0,
                bearingDegrees = 0f, accuracyMeters = 3f,
            )
        }
        val motion = (0 until 400).map { index ->
            MotionSample(index * 20L, if (index % 2 == 0) 0f else 6f, 0f, 9.8f, 0f, 0f, 1f)
        }
        val mounted = TrailAnalysis.spatialProfiles(1, points, motion, MountingMode.BIKE_MOUNTED).first { it.roughnessScore != null }
        val pocket = TrailAnalysis.spatialProfiles(2, points, motion, MountingMode.POCKET).first { it.roughnessScore != null }
        assertTrue(mounted.roughnessKind == RoughnessKind.BIKE_ROUGHNESS)
        assertTrue(pocket.roughnessKind == RoughnessKind.RIDER_DISTURBANCE)
        assertTrue(mounted.roughnessConfidence!! > pocket.roughnessConfidence!!)
        assertTrue(mounted.roughnessScore!! > pocket.roughnessScore!!)
    }

    private fun profileLine(
        rideId: Long, count: Int, latitudeStart: Double = 45.5, timeStart: Long = 0,
    ) = (0 until count).map { index ->
        SpatialProfileEntity(
            rideId = rideId, distanceBin = index, distanceMeters = index * 5.0,
            recordedAt = timeStart + index * 500L,
            latitude = latitudeStart + index * 0.00001, longitude = -73.5,
            speedMps = 10.0, accuracyMeters = 3f,
        )
    }
}
