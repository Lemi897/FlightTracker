package com.example.flighttracker

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

private val shipJson = Json { ignoreUnknownKeys = true }

@Serializable
data class ShipMetaData(
    val MMSI: Long? = null,
    val ShipName: String? = null
)

@Serializable
data class ShipPositionReport(
    val Latitude: Double? = null,
    val Longitude: Double? = null,
    val Sog: Double? = null,
    val Cog: Double? = null
)

@Serializable
data class ShipMessagePayload(
    val PositionReport: ShipPositionReport? = null
)

@Serializable
data class AisStreamMessage(
    val MessageType: String,
    val MetaData: ShipMetaData? = null,
    val Message: ShipMessagePayload? = null
)

data class Ship(
    val mmsi: Long,
    val name: String?,
    val lat: Double,
    val lon: Double,
    val speedKnots: Double?,
    val courseDeg: Double?,
    val flagState: CountryInfo?
)

class AisStreamClient(
    private val apiKey: String,
    private val onShipUpdate: (Ship) -> Unit
) {
    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null

    fun connect() {
        val request = Request.Builder()
            .url("wss://stream.aisstream.io/v0/stream")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val subscription = """{"APIKey": "$apiKey", "BoundingBoxes": [[[0.5, 103.0], [1.5, 104.5]]]}"""
                webSocket.send(subscription)
                Log.d("FlightTracker", "AIS subscription sent")
            }

            // AISStream's own docs confirm the server sends data frames as BINARY websocket
            // frames whose payload happens to be UTF-8 JSON ("decode the frame bytes before
            // parsing JSON"). OkHttp routes binary frames to THIS overload, not the text one
            // below. We were only overriding the text one, so every real data frame from
            // AISStream was silently swallowed by OkHttp's no-op default — connection open,
            // subscription sent, zero data, no error. That's the whole bug.
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleRawMessage(bytes.utf8())
            }

            // Kept as a fallback in case AISStream ever sends a text frame (e.g. some
            // future status/error message type) instead of binary.
            override fun onMessage(webSocket: WebSocket, text: String) {
                handleRawMessage(text)
            }

            private fun handleRawMessage(text: String) {
                try {
                    val parsed = shipJson.decodeFromString<AisStreamMessage>(text)
                    Log.d("FlightTracker", "AIS raw message type: ${parsed.MessageType}")

                    // SubscriptionConfirmation (and possibly other future control messages)
                    // carry no MetaData/Message payload — nothing to extract, so stop here
                    // instead of letting the nulls below throw.
                    val metaData = parsed.MetaData ?: return
                    val report = parsed.Message?.PositionReport ?: return
                    val lat = report.Latitude ?: return
                    val lon = report.Longitude ?: return
                    val mmsi = metaData.MMSI ?: return

                    onShipUpdate(
                        Ship(
                            mmsi = mmsi,
                            name = metaData.ShipName?.trim()?.takeIf { it.isNotBlank() },
                            lat = lat,
                            lon = lon,
                            speedKnots = report.Sog,
                            courseDeg = report.Cog,
                            flagState = countryForMmsi(mmsi)
                        )
                    )
                } catch (e: Exception) {
                    Log.e("FlightTracker", "AIS message parse failed: ${text.take(200)}", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("FlightTracker", "AIS WebSocket failed", t)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("FlightTracker", "AIS WebSocket closed: $reason")
            }
        })
    }

    fun disconnect() {
        webSocket?.close(1000, "Done")
        webSocket = null
    }
}