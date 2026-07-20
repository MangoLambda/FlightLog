package com.example.flightlog.tracking

import com.example.flightlog.data.FeatureObservationEntity
import com.example.flightlog.data.PhysicalFeatureEntity
import com.example.flightlog.domain.FeatureAssignmentState
import com.example.flightlog.domain.FlightKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicalFeatureMatcherTest {
    @Test fun `nearby aligned feature is automatically selected`() {
        val matches = PhysicalFeatureMatcher.ranked(observation(), FlightKind.JUMP, listOf(
            feature(1, 45.0, -73.0, 90.0), feature(2, 45.001, -73.001, 270.0),
        ))
        assertEquals(1L, PhysicalFeatureMatcher.automatic(matches)?.featureId)
    }

    @Test fun `ambiguous nearby features require review`() {
        val matches = PhysicalFeatureMatcher.ranked(observation(), FlightKind.JUMP, listOf(
            feature(1, 45.0, -73.00001, 90.0), feature(2, 45.0, -72.99999, 90.0),
        ))
        assertNull(PhysicalFeatureMatcher.automatic(matches))
        assertTrue(matches.first().confidence >= PhysicalFeatureMatcher.REVIEW_CONFIDENCE)
    }

    @Test fun `drop is not matched to jump`() {
        assertTrue(PhysicalFeatureMatcher.ranked(observation(), FlightKind.DROP, listOf(feature(1, 45.0, -73.0, 90.0))).isEmpty())
    }

    private fun observation() = FeatureObservationEntity(
        jumpId = 1, assignmentState = FeatureAssignmentState.UNGROUPED, matchConfidence = 0,
        latitude = 45.0, longitude = -73.0, gpsAccuracyMeters = 3f, approachBearingDegrees = 90.0,
        airtimeSeconds = .5, heightMeters = .3, distanceMeters = 2.0,
    )
    private fun feature(id: Long, latitude: Double, longitude: Double, bearing: Double) = PhysicalFeatureEntity(
        id = id, name = "Jump $id", kind = FlightKind.JUMP, latitude = latitude, longitude = longitude,
        approachBearingDegrees = bearing, confidence = 80,
    )
}
