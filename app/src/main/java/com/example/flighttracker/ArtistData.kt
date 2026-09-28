package com.example.flighttracker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

private val artistHttpClient = OkHttpClient.Builder().build()
private val artistJson = Json { ignoreUnknownKeys = true }

@Serializable
data class MusicBrainzArea(val name: String)

@Serializable
data class MusicBrainzArtist(
    val name: String,
    val country: String? = null,
    val area: MusicBrainzArea? = null,
    @SerialName("begin-area") val beginArea: MusicBrainzArea? = null
)

@Serializable
data class MusicBrainzSearchResponse(
    val artists: List<MusicBrainzArtist> = emptyList()
)

@Serializable
data class NominatimResult(
    val lat: String,
    val lon: String
)

data class ArtistLocation(
    val name: String,
    val placeName: String,
    val country: String?,
    val lat: Double,
    val lon: Double
)

private suspend fun searchArtist(name: String): MusicBrainzArtist? = withContext(Dispatchers.IO) {
    val request = Request.Builder()
        .url("https://musicbrainz.org/ws/2/artist/?query=${name.replace(" ", "%20")}&fmt=json&limit=1")
        .header("User-Agent", "FlightTrackerApp/1.0 (personal project)")
        .build()

    val response = artistHttpClient.newCall(request).execute()
    val body = response.body?.string() ?: return@withContext null

    try {
        artistJson.decodeFromString<MusicBrainzSearchResponse>(body).artists.firstOrNull()
    } catch (e: Exception) {
        null
    }
}

private suspend fun geocodePlace(placeName: String): Pair<Double, Double>? = withContext(Dispatchers.IO) {
    val request = Request.Builder()
        .url("https://nominatim.openstreetmap.org/search?q=${placeName.replace(" ", "%20")}&format=json&limit=1")
        .header("User-Agent", "FlightTrackerApp/1.0 (personal project)")
        .build()

    val response = artistHttpClient.newCall(request).execute()
    val body = response.body?.string() ?: return@withContext null

    try {
        val first = artistJson.decodeFromString<List<NominatimResult>>(body).firstOrNull() ?: return@withContext null
        val lat = first.lat.toDoubleOrNull() ?: return@withContext null
        val lon = first.lon.toDoubleOrNull() ?: return@withContext null
        lat to lon
    } catch (e: Exception) {
        null
    }
}

suspend fun fetchArtistLocations(artistNames: List<String>): List<ArtistLocation> = withContext(Dispatchers.IO) {
    val results = mutableListOf<ArtistLocation>()

    for (name in artistNames) {
        val artist = searchArtist(name) ?: continue
        delay(1100L)

        val place = artist.beginArea?.name ?: artist.area?.name ?: continue
        val coords = geocodePlace(place) ?: continue
        delay(1100L)

        results.add(
            ArtistLocation(
                name = artist.name,
                placeName = place,
                country = artist.country,
                lat = coords.first,
                lon = coords.second
            )
        )
    }

    results
}