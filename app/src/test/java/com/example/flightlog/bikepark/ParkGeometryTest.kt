package com.example.flightlog.bikepark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParkGeometryTest {
    private val summit = ParkZoneDraft(
        name = "Summit",
        type = ParkZoneType.SUMMIT,
        vertices = square(40.0, -75.0, 0.001),
    )
    private val bottom = ParkZoneDraft(
        name = "Bottom",
        type = ParkZoneType.BOTTOM,
        vertices = square(39.99, -75.0, 0.001),
    )

    @Test
    fun pointInPolygonDistinguishesInteriorAndExterior() {
        assertTrue(ParkGeometry.contains(GeoPoint(40.0005, -74.9995), summit.vertices))
        assertFalse(ParkGeometry.contains(GeoPoint(39.0, -74.9995), summit.vertices))
    }

    @Test
    fun encodingRoundTripsCoordinates() {
        assertEquals(summit.vertices, ParkGeometry.decode(ParkGeometry.encode(summit.vertices)))
    }

    @Test
    fun recordedPathBecomesClosedUsableCorridor() {
        val corridor = ParkGeometry.bufferPath(
            listOf(GeoPoint(40.0, -75.0), GeoPoint(40.001, -75.0), GeoPoint(40.002, -75.0)),
            widthMeters = 20.0,
        )
        assertEquals(6, corridor.size)
        assertTrue(ParkGeometry.contains(GeoPoint(40.001, -75.0), corridor))
    }

    @Test
    fun summitMayOverlapExclusionButNotBottom() {
        val exclusion = ParkZoneDraft(
            name = "Lift exit",
            type = ParkZoneType.EXCLUSION,
            vertices = square(40.0004, -75.0002, 0.0004),
        )
        assertEquals(null, ParkGeometry.validate(listOf(summit), listOf(bottom), listOf(exclusion)))
        assertNotNull(ParkGeometry.validate(listOf(summit), listOf(summit.copy(type = ParkZoneType.BOTTOM)), emptyList()))
        assertNotNull(ParkGeometry.validate(listOf(summit), listOf(bottom), listOf(bottom.copy(type = ParkZoneType.EXCLUSION))))
    }

    @Test
    fun nearestPointRequiresPointInsideEraseRadius() {
        val points = listOf(10.0 to 10.0, 40.0 to 40.0, 100.0 to 100.0)

        assertEquals(1, nearestPointIndex(points, 43.0, 44.0, 8.0))
        assertEquals(null, nearestPointIndex(points, 70.0, 70.0, 8.0))
    }

    private fun square(latitude: Double, longitude: Double, size: Double) = listOf(
        GeoPoint(latitude, longitude),
        GeoPoint(latitude, longitude + size),
        GeoPoint(latitude + size, longitude + size),
        GeoPoint(latitude + size, longitude),
    )
}
