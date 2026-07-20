package com.example.flightlog.data

import androidx.room.TypeConverter
import com.example.flightlog.domain.JumpStatus
import com.example.flightlog.domain.FlightKind
import com.example.flightlog.domain.RideState
import com.example.flightlog.domain.SensorQuality
import com.example.flightlog.domain.MountingMode
import com.example.flightlog.domain.RoughnessKind
import com.example.flightlog.domain.EffortInvalidReason
import com.example.flightlog.domain.PauseZoneState
import com.example.flightlog.domain.SectionKind
import com.example.flightlog.domain.SectionState
import com.example.flightlog.domain.TelemetryKind
import com.example.flightlog.domain.TrailState

class Converters {
    @TypeConverter fun rideState(value: String) = RideState.valueOf(value)
    @TypeConverter fun rideState(value: RideState) = value.name
    @TypeConverter fun jumpStatus(value: String) = JumpStatus.valueOf(value)
    @TypeConverter fun jumpStatus(value: JumpStatus) = value.name
    @TypeConverter fun flightKind(value: String?) = value?.let(FlightKind::valueOf)
    @TypeConverter fun flightKind(value: FlightKind?) = value?.name
    @TypeConverter fun sensorQuality(value: String) = SensorQuality.valueOf(value)
    @TypeConverter fun sensorQuality(value: SensorQuality) = value.name
    @TypeConverter fun mountingMode(value: String?) = value?.let(MountingMode::valueOf)
    @TypeConverter fun mountingMode(value: MountingMode?) = value?.name
    @TypeConverter fun telemetryKind(value: String) = TelemetryKind.valueOf(value)
    @TypeConverter fun telemetryKind(value: TelemetryKind) = value.name
    @TypeConverter fun trailState(value: String) = TrailState.valueOf(value)
    @TypeConverter fun trailState(value: TrailState) = value.name
    @TypeConverter fun sectionKind(value: String) = SectionKind.valueOf(value)
    @TypeConverter fun sectionKind(value: SectionKind) = value.name
    @TypeConverter fun sectionState(value: String) = SectionState.valueOf(value)
    @TypeConverter fun sectionState(value: SectionState) = value.name
    @TypeConverter fun roughnessKind(value: String?) = value?.let(RoughnessKind::valueOf)
    @TypeConverter fun roughnessKind(value: RoughnessKind?) = value?.name
    @TypeConverter fun effortInvalidReason(value: String?) = value?.let(EffortInvalidReason::valueOf)
    @TypeConverter fun effortInvalidReason(value: EffortInvalidReason?) = value?.name
    @TypeConverter fun pauseZoneState(value: String) = PauseZoneState.valueOf(value)
    @TypeConverter fun pauseZoneState(value: PauseZoneState) = value.name
}
