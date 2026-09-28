package com.example.flighttracker

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class Aircraft(
    val hex: String,
    val flight: String? = null,
    val r: String? = null,
    val t: String? = null,
    val desc: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    @SerialName("alt_baro") val altBaroRaw: JsonElement? = null,
    val gs: Double? = null,
    val track: Double? = null,
    val dst: Double? = null,
    val dir: Double? = null,
    @SerialName("baro_rate") val baroRate: Double? = null,
    val squawk: String? = null,
    val emergency: String? = null,
    val category: String? = null,
    val seen: Double? = null
) {
    val callsign: String? get() = flight?.trim()?.takeIf { it.isNotEmpty() }

    val isOnGround: Boolean get() =
        (altBaroRaw as? JsonPrimitive)?.let { it.isString && it.content == "ground" } ?: false

    val altitudeFeet: Int? get() =
        (altBaroRaw as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()?.toInt()

    val verticalTrend: String get() = when {
        baroRate == null -> "Level"
        baroRate > 100 -> "Climbing"
        baroRate < -100 -> "Descending"
        else -> "Level"
    }

    val isEmergencySquawk: Boolean get() = squawk in setOf("7500", "7600", "7700")
}

@Serializable
data class AirplanesLiveResponse(
    val ac: List<Aircraft> = emptyList(),
    val total: Int = 0
)