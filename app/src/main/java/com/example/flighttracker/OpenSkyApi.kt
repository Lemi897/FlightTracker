package com.example.flighttracker

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.create
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

private val openSkyJson = Json { ignoreUnknownKeys = true }

@Serializable
data class OpenSkyTokenResponse(val access_token: String, val expires_in: Int)

interface OpenSkyAuthApi {
    @FormUrlEncoded
    @POST("auth/realms/opensky-network/protocol/openid-connect/token")
    suspend fun getToken(
        @Field("grant_type") grantType: String = "client_credentials",
        @Field("client_id") clientId: String,
        @Field("client_secret") clientSecret: String
    ): OpenSkyTokenResponse
}

private val openSkyAuthApi: OpenSkyAuthApi by lazy {
    Retrofit.Builder()
        .baseUrl("https://auth.opensky-network.org/")
        .addConverterFactory(openSkyJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create<OpenSkyAuthApi>()
}

@Serializable
data class OpenSkyStatesResponse(val time: Long, val states: List<List<JsonElement>>? = null)

interface OpenSkyDataApi {
    @GET("api/states/all")
    suspend fun getAllStates(@Header("Authorization") bearerToken: String): OpenSkyStatesResponse

    // Only returns COMPLETED flights — OpenSky's own docs say this is updated by an
    // overnight batch process, so a flight currently in progress will never show up
    // here. Max 2-day window per the API's own limit.
    @GET("api/flights/aircraft")
    suspend fun getFlightsForAircraft(
        @Header("Authorization") bearerToken: String,
        @Query("icao24") icao24: String,
        @Query("begin") begin: Long,
        @Query("end") end: Long
    ): List<OpenSkyFlight>

    // Explicitly documented as "experimental" by OpenSky — can be out of order at
    // any time. time=0 requests the LIVE track for an aircraft currently in flight;
    // any other timestamp within a known flight's window returns that flight's path.
    @GET("api/tracks/all")
    suspend fun getTrack(
        @Header("Authorization") bearerToken: String,
        @Query("icao24") icao24: String,
        @Query("time") time: Long
    ): OpenSkyTrackResponse
}

private val openSkyDataApi: OpenSkyDataApi by lazy {
    Retrofit.Builder()
        .baseUrl("https://opensky-network.org/")
        .addConverterFactory(openSkyJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create<OpenSkyDataApi>()
}

data class GlobalAircraft(
    val icao24: String,
    val callsign: String?,
    val lat: Double?,
    val lon: Double?,
    val altitudeMeters: Double?,
    val onGround: Boolean,
    val velocityMs: Double?,
    val headingDeg: Double?
)

private fun JsonElement?.textOrNull(): String? {
    if (this == null || this is JsonNull) return null
    return (this as? JsonPrimitive)?.content
}
private fun JsonElement?.doubleOrNull(): Double? = textOrNull()?.toDoubleOrNull()
private fun JsonElement?.boolOrNull(): Boolean? {
    if (this == null || this is JsonNull) return null
    return (this as? JsonPrimitive)?.booleanOrNull
}

@Serializable
data class OpenSkyFlight(
    val icao24: String,
    val firstSeen: Long,
    val estDepartureAirport: String? = null,
    val lastSeen: Long,
    val estArrivalAirport: String? = null,
    val callsign: String? = null
)

@Serializable
data class OpenSkyTrackResponse(
    val icao24: String,
    val startTime: Double,
    val endTime: Double,
    val callsign: String? = null,
    val path: List<List<JsonElement>> = emptyList()
)

data class FlightWaypoint(
    val timeSec: Long,
    val lat: Double?,
    val lon: Double?,
    val altitudeM: Double?,
    val trackDeg: Double?,
    val onGround: Boolean
)

private fun parseWaypoint(point: List<JsonElement>): FlightWaypoint = FlightWaypoint(
    timeSec = point.getOrNull(0).textOrNull()?.toLongOrNull() ?: 0L,
    lat = point.getOrNull(1).doubleOrNull(),
    lon = point.getOrNull(2).doubleOrNull(),
    altitudeM = point.getOrNull(3).doubleOrNull(),
    trackDeg = point.getOrNull(4).doubleOrNull(),
    onGround = point.getOrNull(5).boolOrNull() ?: false
)

sealed class FlightHistoryResult {
    data class Completed(
        val departureAirportIcao: String?,
        val departureTimeSec: Long,
        val arrivalAirportIcao: String?,
        val arrivalTimeSec: Long,
        val callsign: String?,
        val path: List<FlightWaypoint>
    ) : FlightHistoryResult()

    data class InProgress(
        val startTimeSec: Long,
        val callsign: String?,
        val path: List<FlightWaypoint>
    ) : FlightHistoryResult()
}

/**
 * Flight history for an aircraft: a completed flight from the last 90 minutes if one
 * exists (real departure/arrival airports and times, full path), otherwise falls back
 * to the LIVE in-progress track if the aircraft is currently flying. A live flight has
 * no known destination — that only exists once OpenSky's overnight batch processes it
 * the following day. Returns null if neither is available.
 */
suspend fun fetchFlightHistory(icao24: String): FlightHistoryResult? {
    val token = openSkyAuthApi.getToken(
        clientId = BuildConfig.OPENSKY_CLIENT_ID,
        clientSecret = BuildConfig.OPENSKY_CLIENT_SECRET
    )
    val bearer = "Bearer ${token.access_token}"
    val nowSec = System.currentTimeMillis() / 1000
    // OpenSky's actual documented limit for this endpoint is 2 HOURS, not 2 days —
    // an earlier version of this code assumed 2 days, which is why every request was
    // getting rejected with HTTP 400 regardless of how much the window was shrunk.
    val lookbackSec = nowSec - (90 * 60)

    val flights = try {
        openSkyDataApi.getFlightsForAircraft(
            bearerToken = bearer,
            icao24 = icao24,
            begin = lookbackSec,
            end = nowSec
        )
    } catch (e: Exception) {
        android.util.Log.w("FlightTracker", "HISTORY $icao24: /flights/aircraft call failed", e)
        emptyList()
    }

    val latestFlight = flights.maxByOrNull { it.firstSeen }
    android.util.Log.d("FlightTracker", "HISTORY $icao24: /flights/aircraft returned ${flights.size} flight(s)")
    if (latestFlight != null) {
        // /tracks is documented by OpenSky as experimental — a failure here shouldn't
        // hide the real departure/arrival info we already have from /flights.
        val path = try {
            openSkyDataApi.getTrack(
                bearerToken = bearer,
                icao24 = icao24,
                time = latestFlight.firstSeen
            ).path.map { parseWaypoint(it) }
        } catch (e: Exception) {
            android.util.Log.w("FlightTracker", "HISTORY $icao24: /tracks/all (completed) call failed", e)
            emptyList()
        }
        return FlightHistoryResult.Completed(
            departureAirportIcao = latestFlight.estDepartureAirport,
            departureTimeSec = latestFlight.firstSeen,
            arrivalAirportIcao = latestFlight.estArrivalAirport,
            arrivalTimeSec = latestFlight.lastSeen,
            callsign = latestFlight.callsign,
            path = path
        )
    }

    // No completed flight in the last 90 minutes — try the live track instead.
    val liveTrack = try {
        openSkyDataApi.getTrack(bearerToken = bearer, icao24 = icao24, time = 0L)
    } catch (e: Exception) {
        android.util.Log.w("FlightTracker", "HISTORY $icao24: /tracks/all (live) call failed", e)
        null
    } ?: return null

    if (liveTrack.path.isEmpty()) return null
    return FlightHistoryResult.InProgress(
        startTimeSec = liveTrack.startTime.toLong(),
        callsign = liveTrack.callsign,
        path = liveTrack.path.map { parseWaypoint(it) }
    )
}

private fun parseStateVector(vec: List<JsonElement>): GlobalAircraft? {
    val icao24 = vec.getOrNull(0).textOrNull() ?: return null
    return GlobalAircraft(
        icao24 = icao24,
        callsign = vec.getOrNull(1).textOrNull()?.trim()?.takeIf { it.isNotEmpty() },
        lon = vec.getOrNull(5).doubleOrNull(),
        lat = vec.getOrNull(6).doubleOrNull(),
        altitudeMeters = vec.getOrNull(7).doubleOrNull(),
        onGround = vec.getOrNull(8).boolOrNull() ?: false,
        velocityMs = vec.getOrNull(9).doubleOrNull(),
        headingDeg = vec.getOrNull(10).doubleOrNull()
    )
}

suspend fun fetchGlobalAircraftSnapshot(): List<GlobalAircraft> {
    val token = openSkyAuthApi.getToken(
        clientId = BuildConfig.OPENSKY_CLIENT_ID,
        clientSecret = BuildConfig.OPENSKY_CLIENT_SECRET
    )
    val response = openSkyDataApi.getAllStates(bearerToken = "Bearer ${token.access_token}")
    return response.states.orEmpty().mapNotNull { parseStateVector(it) }
}