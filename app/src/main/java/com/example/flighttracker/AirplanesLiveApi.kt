package com.example.flighttracker

// AirplanesLiveApi.kt
import retrofit2.http.GET
import retrofit2.http.Path

interface AirplanesLiveApi {
    @GET("v2/point/{lat}/{lon}/{radiusNm}")
    suspend fun getAircraftNear(
        @Path("lat") lat: Double,
        @Path("lon") lon: Double,
        @Path("radiusNm") radiusNm: Int
    ): AirplanesLiveResponse
}