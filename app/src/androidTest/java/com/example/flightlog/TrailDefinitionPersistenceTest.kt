package com.example.flightlog

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.flightlog.data.FlightLogDatabase
import com.example.flightlog.data.RideEntity
import com.example.flightlog.data.RideRepository
import com.example.flightlog.data.SpatialProfileEntity
import com.example.flightlog.data.TrailDefinitionDraft
import com.example.flightlog.data.TrailEntity
import com.example.flightlog.data.TrailSectionEntity
import com.example.flightlog.domain.RideState
import com.example.flightlog.domain.SectionKind
import com.example.flightlog.domain.SectionState
import com.example.flightlog.domain.TrailState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrailDefinitionPersistenceTest {
    private lateinit var database: FlightLogDatabase
    private lateinit var repository: RideRepository

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            FlightLogDatabase::class.java,
        ).build()
        repository = RideRepository(database)
    }

    @After fun tearDown() = database.close()

    @Test fun destructiveBoundaryEditRequiresConfirmationAndPreservesContainedSection() = runBlocking {
        val rideId = insertRideWithProfiles()
        val trailId = database.dao().insertTrail(TrailEntity(
            name = "Original",
            state = TrailState.CONFIRMED,
            canonicalRideId = rideId,
            lengthMeters = 100.0,
            startMeters = 0.0,
            endMeters = 100.0,
        ))
        database.dao().insertSection(TrailSectionEntity(
            trailId = trailId, name = "Whole trail", kind = SectionKind.WHOLE_TRAIL,
            state = SectionState.CONFIRMED, startMeters = 0.0, endMeters = 100.0,
        ))
        database.dao().insertSection(TrailSectionEntity(
            trailId = trailId, name = "Keep", kind = SectionKind.MANUAL,
            state = SectionState.CONFIRMED, startMeters = 30.0, endMeters = 40.0,
        ))
        database.dao().insertSection(TrailSectionEntity(
            trailId = trailId, name = "Remove", kind = SectionKind.MANUAL,
            state = SectionState.CONFIRMED, startMeters = 5.0, endMeters = 15.0,
        ))
        val draft = TrailDefinitionDraft(trailId, "Edited", rideId, 20.0, 80.0)
        val impact = repository.previewTrailDefinition(draft)

        assertEquals(listOf("Remove"), impact.removedItems.map { it.name })
        val rejected = runCatching { repository.saveTrailDefinition(draft, emptySet()) }
        assertTrue(rejected.isFailure)
        assertEquals(0.0, database.dao().allTrails().single().startMeters, .01)

        repository.saveTrailDefinition(draft, impact.removedItems.mapTo(hashSetOf()) { it.key })

        val saved = database.dao().allTrails().single()
        assertEquals("Edited", saved.name)
        assertEquals(20.0, saved.startMeters, .01)
        assertEquals(listOf("Keep", "Whole trail"), database.dao().sections(trailId).map { it.name }.sorted())
    }

    @Test fun creatingTrailFromOneRideProducesAConfirmedVisibleDefinition() = runBlocking {
        val rideId = insertRideWithProfiles()
        val draft = TrailDefinitionDraft(null, "B-line", rideId, 10.0, 90.0)

        val trailId = repository.saveTrailDefinition(draft, emptySet())

        val trail = database.dao().allTrails().firstOrNull { it.id == trailId }
        assertNotNull(trail)
        assertEquals(TrailState.CONFIRMED, trail!!.state)
        assertEquals(rideId, trail.canonicalRideId)
        assertTrue(database.dao().sections(trailId).any { it.kind == SectionKind.WHOLE_TRAIL })
    }

    private suspend fun insertRideWithProfiles(): Long {
        val rideId = database.dao().insertRide(RideEntity(
            startedAt = 1_000,
            endedAt = 20_000,
            state = RideState.COMPLETED,
            distanceMeters = 100.0,
        ))
        database.dao().insertSpatialProfiles((0..20).map { index ->
            SpatialProfileEntity(
                rideId = rideId,
                distanceBin = index,
                distanceMeters = index * 5.0,
                recordedAt = index * 1_000L,
                latitude = 45.5 + index * .00001,
                longitude = -73.5,
                speedMps = 5.0,
                accuracyMeters = 3f,
            )
        })
        return rideId
    }
}
