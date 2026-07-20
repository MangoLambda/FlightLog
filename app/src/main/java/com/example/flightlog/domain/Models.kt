package com.example.flightlog.domain

enum class RideState { RECORDING, PAUSED, COMPLETED, INTERRUPTED }
enum class JumpStatus { PENDING, CONFIRMED, REJECTED }
enum class FlightKind { JUMP, DROP, UNCERTAIN }
enum class SensorQuality { FULL, ACCELEROMETER_ONLY, DEGRADED }
enum class AggregatePeriod { DAY, SEASON, LIFETIME }
enum class MountingMode { POCKET, BIKE_MOUNTED }
enum class TelemetryKind { GPS, MOTION }
enum class TrailState { CANDIDATE, SUGGESTED, CONFIRMED }
enum class SectionKind { TURN, ROUGH, MANUAL, WHOLE_TRAIL, SPLIT }
enum class SectionState { SUGGESTED, CONFIRMED }
enum class RoughnessKind { BIKE_ROUGHNESS, RIDER_DISTURBANCE }
enum class EffortInvalidReason { STOP, GPS_GAP }
enum class PauseZoneState { CANDIDATE, AUTOMATIC, USER_LOCKED, DISMISSED }
data class JumpEstimate(
    val flightTimeSeconds: Double,
    val heightMeters: Double,
    val distanceMeters: Double,
    val confidence: Int,
)

data class RideTotals(
    val rides: Int = 0,
    val distanceMeters: Double = 0.0,
    val movingTimeMillis: Long = 0,
    val confirmedJumps: Int = 0,
    val confirmedDrops: Int = 0,
    val confirmedUncertainFlights: Int = 0,
    val pendingJumps: Int = 0,
    val rejectedJumps: Int = 0,
    val flightTimeSeconds: Double = 0.0,
    val jumpedDistanceMeters: Double = 0.0,
)
