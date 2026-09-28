package com.example.flighttracker

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val EARTH_RADIUS_KM = 6371.0

/** Resolves an ICAO airport code (e.g. "HKNW") to its real name via the loaded airport database. */
fun airportNameFor(airports: List<Airport>, icaoCode: String?): String? {
    if (icaoCode.isNullOrBlank()) return null
    return airports.firstOrNull { it.ident == icaoCode }?.name
}

fun airportCoordinatesFor(airports: List<Airport>, icaoCode: String?): Pair<Double, Double>? {
    if (icaoCode.isNullOrBlank()) return null
    val airport = airports.firstOrNull { it.ident == icaoCode } ?: return null
    return airport.lat to airport.lon
}

/** Formats a Unix timestamp (seconds) as UTC, e.g. "14:32 UTC" — deliberately UTC rather than
 * local time, since local time at each airport would need a timezone lookup this app doesn't
 * currently have data for. */
fun formatUtcTime(epochSec: Long): String {
    val formatter = SimpleDateFormat("HH:mm 'UTC'", Locale.US)
    formatter.timeZone = TimeZone.getTimeZone("UTC")
    return formatter.format(Date(epochSec * 1000))
}

fun formatUtcDate(epochSec: Long): String {
    val formatter = SimpleDateFormat("MMM d", Locale.US)
    formatter.timeZone = TimeZone.getTimeZone("UTC")
    return formatter.format(Date(epochSec * 1000))
}

fun formatDuration(seconds: Long): String {
    val totalMinutes = seconds / 60
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return EARTH_RADIUS_KM * c
}

/** Total distance flown, summing each consecutive waypoint-to-waypoint segment — the real
 * flown path, not just a straight line between departure and arrival. */
fun totalDistanceKm(path: List<FlightWaypoint>): Double {
    var total = 0.0
    for (i in 0 until path.size - 1) {
        val a = path[i]
        val b = path[i + 1]
        if (a.lat != null && a.lon != null && b.lat != null && b.lon != null) {
            total += haversineKm(a.lat, a.lon, b.lat, b.lon)
        }
    }
    return total
}