package com.example.flightlog.export

import com.example.flightlog.data.JumpEventEntity
import com.example.flightlog.data.RideEntity
import com.example.flightlog.data.TrackPointEntity
import java.time.Instant
import java.util.Locale

object RideExporter {
    fun gpx(ride: RideEntity, points: List<TrackPointEntity>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<gpx version=\"1.1\" creator=\"FlightLog\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        append("  <trk><name>FlightLog ride ${ride.id}</name><trkseg>\n")
        points.forEach { point ->
            append(String.format(Locale.US, "    <trkpt lat=\"%.7f\" lon=\"%.7f\">", point.latitude, point.longitude))
            point.altitudeMeters?.let { append(String.format(Locale.US, "<ele>%.2f</ele>", it)) }
            append("<time>${Instant.ofEpochMilli(point.recordedAt)}</time></trkpt>\n")
        }
        append("  </trkseg></trk>\n</gpx>\n")
    }

    fun jumpCsv(jumps: List<JumpEventEntity>): String = buildString {
        append("jump_id,takeoff_utc,status,confidence,flight_seconds,height_meters,distance_meters,sensor_quality\n")
        jumps.forEach { jump ->
            append(listOf(
                jump.id,
                Instant.ofEpochMilli(jump.takeoffAt),
                jump.status,
                jump.confidence,
                String.format(Locale.US, "%.3f", jump.displayFlightSeconds),
                String.format(Locale.US, "%.3f", jump.displayHeightMeters),
                String.format(Locale.US, "%.3f", jump.displayDistanceMeters),
                jump.sensorQuality,
            ).joinToString(","))
            append('\n')
        }
    }
}
