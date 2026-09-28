package com.example.flighttracker

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.create
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory

private val json = Json { ignoreUnknownKeys = true }

private val airplanesLiveHttpClient = OkHttpClient.Builder()
    .addInterceptor { chain ->
        val requestWithHeader = chain.request().newBuilder()
            .header("User-Agent", "FlightTrackerApp/1.0 (personal project)")
            .build()
        chain.proceed(requestWithHeader)
    }
    .build()

val airplanesLiveApi: AirplanesLiveApi by lazy {
    Retrofit.Builder()
        .baseUrl("https://api.airplanes.live/")
        .client(airplanesLiveHttpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create<AirplanesLiveApi>()
}

/** General-purpose client for plain (non-Retrofit) HTTP calls, e.g. fetching flag images. */
val sharedHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .addInterceptor { chain ->
            val requestWithHeader = chain.request().newBuilder()
                .header("User-Agent", "FlightTrackerApp/1.0 (personal project)")
                .build()
            chain.proceed(requestWithHeader)
        }
        .build()
}