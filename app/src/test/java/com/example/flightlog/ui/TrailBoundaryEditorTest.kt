package com.example.flightlog.ui

import com.example.flightlog.data.SpatialProfileEntity
import com.example.flightlog.data.TrackPointEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailBoundaryEditorTest {
    private val distances = (0..20).map { it * 5.0 }

    @Test fun boundsSnapToPermanentFiveMeterBins() {
        assertEquals(TrailBounds(10.0, 90.0), snapTrailBounds(12.1, 88.2, distances))
    }

    @Test fun boundsStayUsableWhenHandlesMeet() {
        assertEquals(TrailBounds(50.0, 60.0), snapTrailBounds(52.0, 52.0, distances))
        assertEquals(TrailBounds(90.0, 100.0), snapTrailBounds(99.0, 99.0, distances))
    }

    @Test fun boundsRejectRoutesTooShortForASection() {
        assertNull(snapTrailBounds(0.0, 5.0, listOf(0.0, 5.0)))
    }

    @Test fun draggingOneMapHandleNeverCrossesTheOther() {
        val initial = TrailBounds(20.0, 80.0)
        assertEquals(TrailBounds(70.0, 80.0), moveTrailStart(initial, 95.0, distances))
        assertEquals(TrailBounds(20.0, 30.0), moveTrailEnd(initial, 5.0, distances))
    }

    @Test fun mapDragSnapsToNearestRoutePoint() {
        val route = listOf(
            point(1_000, 45.5000, -73.5000),
            point(2_000, 45.5010, -73.5000),
            point(3_000, 45.5020, -73.5000),
        )
        assertEquals(2_000L, nearestRoutePoint(route, 45.5012, -73.5001)?.recordedAt)
    }

    @Test fun elevationProfileUsesAltitudeAndDrawsHigherValuesUpward() {
        val profiles = listOf(
            profile(distance = 0.0, altitude = 100.0),
            profile(distance = 5.0, altitude = 110.0),
            profile(distance = 10.0, altitude = 130.0),
        )

        val preview = elevationPreview(profiles)

        assertEquals(0f, preview.first().progress)
        assertEquals(1f, preview.last().progress)
        assertTrue(elevationYFraction(preview.last().elevationFraction) < elevationYFraction(preview.first().elevationFraction))
    }

    @Test fun elevationProfileIsUnavailableWithoutRecordedAltitude() {
        assertEquals(emptyList<ElevationPreviewPoint>(), elevationPreview(listOf(profile(0.0, null), profile(5.0, null))))
    }

    @Test fun selectedMapPreviewContainsOnlyTheChosenRange() {
        val profiles = distances.mapIndexed { index, distance ->
            SpatialProfileEntity(
                rideId = 1,
                distanceBin = index,
                distanceMeters = distance,
                recordedAt = index * 1_000L,
                latitude = 45.0,
                longitude = -73.0 + index * .00001,
                speedMps = 4.0,
                accuracyMeters = 3f,
            )
        }

        assertEquals(
            listOf(20.0, 25.0, 30.0),
            profilesWithinBounds(profiles, TrailBounds(20.0, 30.0)).map { it.distanceMeters },
        )
    }

    @Test fun savedTrailBoundariesRemainHighlightedAfterEditing() {
        val profiles = distances.map { distance -> profile(distance, 100.0 + distance) }

        val mapRoute = buildTrailMapRoute(profiles, startMeters = 20.0, endMeters = 30.0)

        assertEquals(distances.size, mapRoute.fullRoute.size)
        assertEquals(listOf(20_000L, 25_000L, 30_000L), mapRoute.highlightedRoute.map { it.recordedAt })
    }

    @Test fun stopMarkersRequireTenSecondsStayOnTrailAndDeduplicateRepeatedStops() {
        val confirmedRoute = listOf(
            point(1_000, 45.5000, -73.5000),
            point(2_000, 45.5010, -73.5000),
        )
        val profiles = listOf(
            profile(10.0, 100.0).copy(
                rideId = 1, recordedAt = 10_000, latitude = 45.50002, longitude = -73.5000,
                observedSpanMillis = 12_000,
            ),
            profile(10.0, 100.0).copy(
                rideId = 2, recordedAt = 20_000, latitude = 45.50003, longitude = -73.5000,
                observedSpanMillis = 18_000,
            ),
            profile(20.0, 100.0).copy(
                rideId = 3, recordedAt = 30_000, latitude = 45.5005, longitude = -73.5000,
                observedSpanMillis = 9_999,
            ),
            profile(30.0, 100.0).copy(
                rideId = 4, recordedAt = 40_000, latitude = 46.0000, longitude = -73.5000,
                observedSpanMillis = 30_000,
            ),
        )

        val stops = buildTrailStopPoints(profiles, confirmedRoute)

        assertEquals(1, stops.size)
        assertEquals(2L, stops.single().rideId)
    }

    private fun point(recordedAt: Long, latitude: Double, longitude: Double) = TrackPointEntity(
        rideId = 1,
        recordedAt = recordedAt,
        latitude = latitude,
        longitude = longitude,
        altitudeMeters = null,
        speedMps = 4.0,
        bearingDegrees = null,
        accuracyMeters = 3f,
    )

    private fun profile(distance: Double, altitude: Double?) = SpatialProfileEntity(
        rideId = 1,
        distanceBin = (distance / 5.0).toInt(),
        distanceMeters = distance,
        recordedAt = distance.toLong() * 1_000L,
        latitude = 45.0,
        longitude = -73.0,
        altitudeMeters = altitude,
        speedMps = 4.0,
        accuracyMeters = 3f,
    )
}
