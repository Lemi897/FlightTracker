package com.example.flighttracker

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

data class Airport(
    val ident: String,
    val type: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val elevationFt: Int?,
    val isoCountry: String,
    val municipality: String?,
    val icaoCode: String?,
    val iataCode: String?,
    val scheduledService: Boolean,
    val wikipediaLink: String?
)

data class Runway(
    val airportIdent: String,
    val lengthFt: Int?,
    val widthFt: Int?,
    val surface: String?,
    val lighted: Boolean,
    val closed: Boolean
)

private fun parseCsvLine(line: String): List<String> {
    val fields = mutableListOf<String>()
    val current = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            c == '"' -> {
                if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                    current.append('"'); i++
                } else inQuotes = !inQuotes
            }
            c == ',' && !inQuotes -> { fields.add(current.toString()); current.clear() }
            else -> current.append(c)
        }
        i++
    }
    fields.add(current.toString())
    return fields
}

private fun parseAirportRow(index: Map<String, Int>, fields: List<String>): Airport? {
    fun get(col: String): String? = index[col]?.let { fields.getOrNull(it) }?.trim('"')

    val lat = get("latitude_deg")?.toDoubleOrNull()
    val lon = get("longitude_deg")?.toDoubleOrNull()
    if (lat == null || lon == null) return null

    return Airport(
        ident = get("ident") ?: "",
        type = get("type") ?: "",
        name = get("name") ?: "Unknown",
        lat = lat,
        lon = lon,
        elevationFt = get("elevation_ft")?.toIntOrNull(),
        isoCountry = get("iso_country") ?: "",
        municipality = get("municipality"),
        icaoCode = get("gps_code")?.takeIf { it.isNotBlank() },
        iataCode = get("iata_code")?.takeIf { it.isNotBlank() },
        scheduledService = get("scheduled_service")?.equals("yes", ignoreCase = true) == true,
        wikipediaLink = get("wikipedia_link")?.takeIf { it.isNotBlank() }
    )
}

private fun parseRunwayRow(index: Map<String, Int>, fields: List<String>): Runway? {
    fun get(col: String): String? = index[col]?.let { fields.getOrNull(it) }?.trim('"')
    val airportIdent = get("airport_ident")?.takeIf { it.isNotBlank() } ?: return null

    return Runway(
        airportIdent = airportIdent,
        lengthFt = get("length_ft")?.toIntOrNull(),
        widthFt = get("width_ft")?.toIntOrNull(),
        surface = get("surface")?.takeIf { it.isNotBlank() },
        lighted = get("lighted") == "1",
        closed = get("closed") == "1"
    )
}

fun loadKenyaAirports(context: Context): List<Airport> {
    val airports = mutableListOf<Airport>()
    context.assets.open("kenya_airports.csv").use { stream ->
        BufferedReader(InputStreamReader(stream)).use { reader ->
            val header = reader.readLine() ?: return emptyList()
            val columns = parseCsvLine(header)
            val index = columns.withIndex().associate { (i, name) -> name to i }

            reader.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                parseAirportRow(index, parseCsvLine(line))?.let { airports.add(it) }
            }
        }
    }
    return airports
}

private val ourAirportsHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(120, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .build()

suspend fun fetchWorldwideAirports(): List<Airport> = withContext(Dispatchers.IO) {
    val request = Request.Builder()
        .url("https://davidmegginson.github.io/ourairports-data/airports.csv")
        .build()

    val response = ourAirportsHttpClient.newCall(request).execute()
    val body = response.body ?: return@withContext emptyList()

    val airports = mutableListOf<Airport>()
    BufferedReader(InputStreamReader(body.byteStream())).use { reader ->
        val header = reader.readLine() ?: return@withContext emptyList()
        val columns = parseCsvLine(header)
        val index = columns.withIndex().associate { (i, name) -> name to i }

        reader.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            parseAirportRow(index, parseCsvLine(line))?.let { airports.add(it) }
        }
    }
    airports
}

suspend fun fetchRunways(): List<Runway> = withContext(Dispatchers.IO) {
    val request = Request.Builder()
        .url("https://davidmegginson.github.io/ourairports-data/runways.csv")
        .build()

    val response = ourAirportsHttpClient.newCall(request).execute()
    val body = response.body ?: return@withContext emptyList()

    val runways = mutableListOf<Runway>()
    BufferedReader(InputStreamReader(body.byteStream())).use { reader ->
        val header = reader.readLine() ?: return@withContext emptyList()
        val columns = parseCsvLine(header)
        val index = columns.withIndex().associate { (i, name) -> name to i }

        reader.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            parseRunwayRow(index, parseCsvLine(line))?.let { runways.add(it) }
        }
    }
    runways
}