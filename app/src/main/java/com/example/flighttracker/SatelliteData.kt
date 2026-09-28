package com.example.flighttracker

import com.neosensory.tlepredictionengine.TlePredictionEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private val tleHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .build()

class SatelliteDataUnchanged : Exception("CelesTrak reports data has not updated since last successful download")

data class SatelliteTle(
    val name: String,
    val line1: String,
    val line2: String
)

suspend fun fetchSatelliteTles(group: String = "stations"): List<SatelliteTle> = withContext(Dispatchers.IO) {
    val request = Request.Builder()
        .url("https://celestrak.org/NORAD/elements/gp.php?GROUP=$group&FORMAT=tle")
        .build()

    val response = tleHttpClient.newCall(request).execute()
    val body = response.body?.string() ?: return@withContext emptyList()

    if (body.contains("has not updated since your last successful", ignoreCase = true)) {
        throw SatelliteDataUnchanged()
    }

    if (body.length < 500) {
        android.util.Log.w("FlightTracker", "Suspiciously short satellite response body: $body")
    }

    val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val satellites = mutableListOf<SatelliteTle>()
    var i = 0
    while (i + 2 < lines.size) {
        satellites.add(SatelliteTle(name = lines[i], line1 = lines[i + 1], line2 = lines[i + 2]))
        i += 3
    }
    satellites
}

data class SatellitePosition(
    val name: String,
    val lat: Double,
    val lon: Double,
    val altitudeKm: Double
)

suspend fun computeSatellitePositions(satellites: List<SatelliteTle>): List<SatellitePosition> =
    withContext(Dispatchers.Default) {
        satellites.mapNotNull { sat ->
            try {
                val coords = TlePredictionEngine.getSatellitePosition(sat.line1, sat.line2, true)
                SatellitePosition(name = sat.name, lat = coords[0], lon = coords[1], altitudeKm = coords[2])
            } catch (e: Exception) {
                null
            }
        }
    }