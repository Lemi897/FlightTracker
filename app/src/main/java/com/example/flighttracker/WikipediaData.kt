package com.example.flighttracker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

private val wikipediaHttpClient = OkHttpClient.Builder().build()
private val wikipediaJson = Json { ignoreUnknownKeys = true }

@Serializable
data class WikipediaSummary(
    val title: String,
    val extract: String
)

private fun extractTitleFromWikipediaUrl(url: String): String? {
    return url.substringAfterLast("/wiki/", "").takeIf { it.isNotBlank() }
}

suspend fun fetchWikipediaSummary(wikipediaUrl: String): WikipediaSummary? = withContext(Dispatchers.IO) {
    val title = extractTitleFromWikipediaUrl(wikipediaUrl) ?: return@withContext null

    val request = Request.Builder()
        .url("https://en.wikipedia.org/api/rest_v1/page/summary/$title")
        .header("User-Agent", "FlightTrackerApp/1.0 (personal project)")
        .build()

    val response = wikipediaHttpClient.newCall(request).execute()
    if (!response.isSuccessful) return@withContext null
    val body = response.body?.string() ?: return@withContext null

    try {
        wikipediaJson.decodeFromString<WikipediaSummary>(body)
    } catch (e: Exception) {
        null
    }
}