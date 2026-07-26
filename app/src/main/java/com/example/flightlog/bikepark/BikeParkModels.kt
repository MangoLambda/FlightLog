package com.example.flightlog.bikepark

import kotlin.math.hypot
import kotlin.math.abs
import kotlin.math.cos

enum class ParkZoneType { SUMMIT, BOTTOM, EXCLUSION }

enum class ParkDayState {
    INACTIVE,
    WAITING_IN_SUMMIT,
    PENDING_START,
    RECORDING_RUN,
    WAITING_FOR_SUMMIT,
}

enum class ParkSamplingProfile { LOW_POWER, NORMAL }

fun ParkDayState.samplingProfile(): ParkSamplingProfile = when (this) {
    ParkDayState.WAITING_FOR_SUMMIT, ParkDayState.INACTIVE -> ParkSamplingProfile.LOW_POWER
    ParkDayState.WAITING_IN_SUMMIT, ParkDayState.PENDING_START, ParkDayState.RECORDING_RUN ->
        ParkSamplingProfile.NORMAL
}

data class GeoPoint(val latitude: Double, val longitude: Double)

data class ParkZoneDraft(
    val id: Long = 0,
    val name: String,
    val type: ParkZoneType,
    val vertices: List<GeoPoint>,
    val corridorWidthMeters: Double = 20.0,
)

object ParkGeometry {
    fun encode(points: List<GeoPoint>): String =
        points.joinToString(";") { "${it.latitude},${it.longitude}" }

    fun decode(encoded: String): List<GeoPoint> = encoded.split(';').mapNotNull { value ->
        val parts = value.split(',')
        if (parts.size != 2) null else {
            val latitude = parts[0].toDoubleOrNull()
            val longitude = parts[1].toDoubleOrNull()
            if (latitude == null || longitude == null) null else GeoPoint(latitude, longitude)
        }
    }

    fun contains(point: GeoPoint, polygon: List<GeoPoint>): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var previous = polygon.last()
        polygon.forEach { current ->
            val crosses = (current.latitude > point.latitude) != (previous.latitude > point.latitude) &&
                point.longitude < (previous.longitude - current.longitude) *
                (point.latitude - current.latitude) /
                (previous.latitude - current.latitude) + current.longitude
            if (crosses) inside = !inside
            previous = current
        }
        return inside
    }

    fun polygonsOverlap(first: List<GeoPoint>, second: List<GeoPoint>): Boolean {
        if (first.size < 3 || second.size < 3) return false
        if (first.any { contains(it, second) } || second.any { contains(it, first) }) return true
        return first.indices.any { firstIndex ->
            val a = first[firstIndex]
            val b = first[(firstIndex + 1) % first.size]
            second.indices.any { secondIndex ->
                segmentsIntersect(a, b, second[secondIndex], second[(secondIndex + 1) % second.size])
            }
        }
    }

    /** Produces an editable corridor around a recorded centerline. */
    fun bufferPath(path: List<GeoPoint>, widthMeters: Double = 20.0): List<GeoPoint> {
        if (path.size < 2) return emptyList()
        val halfWidth = widthMeters.coerceAtLeast(2.0) / 2.0
        val left = mutableListOf<GeoPoint>()
        val right = mutableListOf<GeoPoint>()
        path.forEachIndexed { index, point ->
            val before = path[(index - 1).coerceAtLeast(0)]
            val after = path[(index + 1).coerceAtMost(path.lastIndex)]
            val meanLatitude = Math.toRadians(point.latitude)
            val north = (after.latitude - before.latitude) * 111_320.0
            val east = (after.longitude - before.longitude) * 111_320.0 * cos(meanLatitude)
            val length = kotlin.math.hypot(east, north).coerceAtLeast(0.001)
            val offsetNorth = east / length * halfWidth
            val offsetEast = -north / length * halfWidth
            val latitudeOffset = offsetNorth / 111_320.0
            val longitudeOffset = offsetEast / (111_320.0 * cos(meanLatitude).coerceAtLeast(0.01))
            left += GeoPoint(point.latitude + latitudeOffset, point.longitude + longitudeOffset)
            right += GeoPoint(point.latitude - latitudeOffset, point.longitude - longitudeOffset)
        }
        return left + right.asReversed()
    }

    fun resizeCorridor(polygon: List<GeoPoint>, widthMeters: Double): List<GeoPoint> {
        if (polygon.size < 4 || polygon.size % 2 != 0) return polygon
        val sideSize = polygon.size / 2
        val left = polygon.take(sideSize)
        val right = polygon.drop(sideSize).asReversed()
        val centerline = left.zip(right) { a, b ->
            GeoPoint((a.latitude + b.latitude) / 2.0, (a.longitude + b.longitude) / 2.0)
        }
        return bufferPath(centerline, widthMeters)
    }

    fun validate(
        summits: List<ParkZoneDraft>,
        bottoms: List<ParkZoneDraft>,
        exclusions: List<ParkZoneDraft>,
    ): String? {
        if (summits.isEmpty()) return "Add at least one summit zone"
        if (bottoms.isEmpty()) return "Add at least one bottom zone"
        if ((summits + bottoms + exclusions).any { it.vertices.size < 3 }) return "Every zone needs at least three points"
        if (summits.any { summit -> bottoms.any { polygonsOverlap(summit.vertices, it.vertices) } }) {
            return "Summit and bottom zones cannot overlap"
        }
        if (exclusions.any { exclusion -> bottoms.any { polygonsOverlap(exclusion.vertices, it.vertices) } }) {
            return "Exclusion and bottom zones cannot overlap"
        }
        return null
    }

    private fun segmentsIntersect(a: GeoPoint, b: GeoPoint, c: GeoPoint, d: GeoPoint): Boolean {
        fun orientation(p: GeoPoint, q: GeoPoint, r: GeoPoint): Double =
            (q.longitude - p.longitude) * (r.latitude - p.latitude) -
                (q.latitude - p.latitude) * (r.longitude - p.longitude)
        val o1 = orientation(a, b, c)
        val o2 = orientation(a, b, d)
        val o3 = orientation(c, d, a)
        val o4 = orientation(c, d, b)
        fun onSegment(p: GeoPoint, q: GeoPoint, r: GeoPoint): Boolean =
            q.latitude in minOf(p.latitude, r.latitude)..maxOf(p.latitude, r.latitude) &&
                q.longitude in minOf(p.longitude, r.longitude)..maxOf(p.longitude, r.longitude)
        if (o1 * o2 < 0 && o3 * o4 < 0) return true
        return abs(o1) < 1e-12 && onSegment(a, c, b) ||
            abs(o2) < 1e-12 && onSegment(a, d, b) ||
            abs(o3) < 1e-12 && onSegment(c, a, d) ||
            abs(o4) < 1e-12 && onSegment(c, b, d)
    }
}
internal fun nearestPointIndex(
    points: List<Pair<Double, Double>>,
    x: Double,
    y: Double,
    maximumDistance: Double,
): Int? = points.indices
    .map { index -> index to hypot(points[index].first - x, points[index].second - y) }
    .minByOrNull { it.second }
    ?.takeIf { it.second <= maximumDistance }
    ?.first
