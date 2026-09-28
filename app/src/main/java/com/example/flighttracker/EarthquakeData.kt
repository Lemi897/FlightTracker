package com.example.flighttracker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

private val earthquakeHttpClient = OkHttpClient.Builder().build()
private val earthquakeJson = Json { ignoreUnknownKeys = true }

@Serializable
data class EarthquakeProperties(
    val mag: Double? = null,
    val place: String? = null,
    val time: Long = 0L,
    val title: String? = null,
    val type: String? = null,
    val tsunami: Int? = null,
    val alert: String? = null,
    val felt: Int? = null,
    val sig: Int? = null,
    val status: String? = null,
    val url: String? = null
)

@Serializable
data class EarthquakeGeometry(
    val coordinates: List<Double>
)

@Serializable
data class EarthquakeFeature(
    val properties: EarthquakeProperties,
    val geometry: EarthquakeGeometry,
    val id: String
)

@Serializable
data class EarthquakeFeedResponse(
    val features: List<EarthquakeFeature> = emptyList()
)

data class Earthquake(
    val id: String,
    val magnitude: Double?,
    val place: String,
    val lat: Double,
    val lon: Double,
    val depthKm: Double?,
    val timeMs: Long,
    val isTsunamiRisk: Boolean,
    val alertLevel: String?,
    val feltCount: Int?,
    val significance: Int?,
    val status: String?,
    val usgsUrl: String?
)

suspend fun fetchNearbySeismicity(lat: Double, lon: Double, excludeId: String, radiusKm: Int = 200): List<Earthquake> = withContext(Dispatchers.IO) {
    val sevenDaysAgoMs = System.currentTimeMillis() - (7L * 24 * 60 * 60 * 1000)
    val startTime = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(sevenDaysAgoMs))

    val request = Request.Builder()
        .url("https://earthquake.usgs.gov/fdsnws/event/1/query?format=geojson&latitude=$lat&longitude=$lon&maxradiuskm=$radiusKm&starttime=$startTime&orderby=time")
        .build()

    val response = earthquakeHttpClient.newCall(request).execute()
    val body = response.body?.string() ?: return@withContext emptyList()

    val parsed = earthquakeJson.decodeFromString<EarthquakeFeedResponse>(body)
    parsed.features.mapNotNull { feature ->
        if (feature.id == excludeId) return@mapNotNull null
        val coords = feature.geometry.coordinates
        if (coords.size < 2) return@mapNotNull null

        Earthquake(
            id = feature.id,
            magnitude = feature.properties.mag,
            place = feature.properties.place ?: "Unknown location",
            lat = coords[1],
            lon = coords[0],
            depthKm = coords.getOrNull(2),
            timeMs = feature.properties.time,
            isTsunamiRisk = feature.properties.tsunami == 1,
            alertLevel = feature.properties.alert,
            feltCount = feature.properties.felt,
            significance = feature.properties.sig,
            status = feature.properties.status,
            usgsUrl = feature.properties.url
        )
    }
}

suspend fun fetchRecentEarthquakes(): List<Earthquake> = withContext(Dispatchers.IO) {
    val request = Request.Builder()
        .url("https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/all_day.geojson")
        .build()

    val response = earthquakeHttpClient.newCall(request).execute()
    val body = response.body?.string() ?: return@withContext emptyList()

    val parsed = earthquakeJson.decodeFromString<EarthquakeFeedResponse>(body)
    parsed.features.mapNotNull { feature ->
        val coords = feature.geometry.coordinates
        if (coords.size < 2) return@mapNotNull null

        Earthquake(
            id = feature.id,
            magnitude = feature.properties.mag,
            place = feature.properties.place ?: "Unknown location",
            lat = coords[1],
            lon = coords[0],
            depthKm = coords.getOrNull(2),
            timeMs = feature.properties.time,
            isTsunamiRisk = feature.properties.tsunami == 1,
            alertLevel = feature.properties.alert,
            feltCount = feature.properties.felt,
            significance = feature.properties.sig,
            status = feature.properties.status,
            usgsUrl = feature.properties.url
        )
    }
}