package com.example.flighttracker

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

private val radioHttpClient = OkHttpClient.Builder().build()
private val radioJson = Json { ignoreUnknownKeys = true }

@Serializable
data class RadioStation(
    @SerialName("stationuuid") val uuid: String,
    val name: String,
    val url: String,
    @SerialName("url_resolved") val urlResolved: String? = null,
    val country: String? = null,
    val tags: String? = null,
    @SerialName("geo_lat") val geoLat: Double? = null,
    @SerialName("geo_long") val geoLong: Double? = null
)

suspend fun fetchStationsWithLocation(limit: Int = 500): List<RadioStation> = withContext(Dispatchers.IO) {
    val request = Request.Builder()
        .url("https://de1.api.radio-browser.info/json/stations/search?order=clickcount&reverse=true&limit=$limit")
        .header("User-Agent", "FlightTrackerApp/1.0 (personal project)")
        .build()

    val response = radioHttpClient.newCall(request).execute()
    val body = response.body?.string() ?: return@withContext emptyList()

    val allStations = radioJson.decodeFromString<List<RadioStation>>(body)
    allStations.filter { station ->
        val lat = station.geoLat
        val lon = station.geoLong
        lat != null && lon != null && !(lat == 0.0 && lon == 0.0)
    }
}