package com.example.flightlog

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.flightlog.data.FlightLogDatabase
import com.example.flightlog.data.RideEntity
import com.example.flightlog.data.RideRepository
import com.example.flightlog.data.SpatialProfileEntity
import com.example.flightlog.data.TrailEntity
import com.example.flightlog.data.TrailPassEntity
import com.example.flightlog.data.TrailSectionEntity
import com.example.flightlog.domain.RideState
import com.example.flightlog.domain.SectionKind
import com.example.flightlog.domain.SectionState
import com.example.flightlog.domain.TrailState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BulkDeleteRideTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: FlightLogDatabase
    private lateinit var repository: RideRepository

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, FlightLogDatabase::class.java).build()
        repository = RideRepository(database)
    }

    @After fun tearDown() = database.close()

    @Test fun referenceTrailIsReassignedBeforeItsRideIsDeleted() = runBlocking {
        val originalRideId = insertRideWithProfiles(1_000)
        val replacementRideId = insertRideWithProfiles(2_000)
        val trailId = insertTrail(originalRideId)
        database.dao().insertPass(pass(trailId, replacementRideId, confidence = 92))

        val preview = repository.previewBulkRideDeletion(setOf(originalRideId))
        assertEquals(replacementRideId, preview.reassignments.single().replacementRideId)

        val result = repository.applyBulkRideDeletion(preview)

        assertEquals(1, result.deletedRideCount)
        assertEquals(1, result.reassignedTrailCount)
        assertNull(database.dao().ride(originalRideId))
        assertNotNull(database.dao().ride(replacementRideId))
        assertEquals(replacementRideId, database.dao().allTrails().single().canonicalRideId)
        assertEquals(trailId, database.dao().allTrails().single().id)
        assertTrue(database.dao().sections(trailId).any { it.name == "Manual feature" })
    }

    @Test fun trailWithoutARemainingCompleteRideIsDeletedWithItsReference() = runBlocking {
        val originalRideId = insertRideWithProfiles(1_000)
        val partialRideId = insertRideWithProfiles(2_000)
        val trailId = insertTrail(originalRideId)
        database.dao().insertPass(pass(trailId, partialRideId, confidence = 92).copy(completeCoverage = false))

        val preview = repository.previewBulkRideDeletion(setOf(originalRideId))
        assertEquals(listOf(trailId), preview.deletedTrails.map { it.trailId })

        repository.applyBulkRideDeletion(preview)

        assertNull(database.dao().ride(originalRideId))
        assertNotNull(database.dao().ride(partialRideId))
        assertTrue(database.dao().allTrails().isEmpty())
    }

    @Test fun changedTrailCandidatesRequireANewConfirmationPreview() = runBlocking {
        val originalRideId = insertRideWithProfiles(1_000)
        val firstCandidateId = insertRideWithProfiles(2_000)
        val betterCandidateId = insertRideWithProfiles(3_000)
        val trailId = insertTrail(originalRideId)
        database.dao().insertPass(pass(trailId, firstCandidateId, confidence = 80))
        val stalePreview = repository.previewBulkRideDeletion(setOf(originalRideId))
        database.dao().insertPass(pass(trailId, betterCandidateId, confidence = 95))

        val result = runCatching { repository.applyBulkRideDeletion(stalePreview) }

        assertTrue(result.isFailure)
        assertNotNull(database.dao().ride(originalRideId))
        assertEquals(originalRideId, database.dao().allTrails().single().canonicalRideId)
    }

    private suspend fun insertRideWithProfiles(startedAt: Long): Long {
        val rideId = database.dao().insertRide(RideEntity(
            startedAt = startedAt,
            endedAt = startedAt + 5_000,
            state = RideState.COMPLETED,
            distanceMeters = 100.0,
        ))
        database.dao().insertSpatialProfiles((0..20).map { index ->
            SpatialProfileEntity(
                rideId = rideId,
                distanceBin = index,
                distanceMeters = index * 5.0,
                recordedAt = startedAt + index * 200,
                latitude = 45.5 + index * .0001,
                longitude = -73.5,
                speedMps = 5.0,
                accuracyMeters = 3f,
            )
        })
        return rideId
    }

    private suspend fun insertTrail(canonicalRideId: Long): Long {
        val trailId = database.dao().insertTrail(TrailEntity(
            name = "Saved trail",
            state = TrailState.CONFIRMED,
            canonicalRideId = canonicalRideId,
            lengthMeters = 100.0,
            startMeters = 0.0,
            endMeters = 100.0,
        ))
        database.dao().insertSection(TrailSectionEntity(
            trailId = trailId,
            name = "Whole trail",
            kind = SectionKind.WHOLE_TRAIL,
            state = SectionState.CONFIRMED,
            startMeters = 0.0,
            endMeters = 100.0,
        ))
        database.dao().insertSection(TrailSectionEntity(
            trailId = trailId,
            name = "Manual feature",
            kind = SectionKind.MANUAL,
            state = SectionState.CONFIRMED,
            startMeters = 30.0,
            endMeters = 50.0,
        ))
        return trailId
    }

    private fun pass(trailId: Long, rideId: Long, confidence: Int) = TrailPassEntity(
        trailId = trailId,
        rideId = rideId,
        startedAt = rideId * 1_000,
        endedAt = rideId * 1_000 + 5_000,
        startMeters = 0.0,
        endMeters = 100.0,
        matchConfidence = confidence,
        interrupted = false,
        completeCoverage = true,
        hasReversal = false,
    )
}
