package com.example.flighttracker

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val LOCAL_POLL_INTERVAL_MS = 5_000L
private const val SATELLITE_RETRY_DELAY_MS = 5 * 60 * 1000L
// Conservative interval: OpenSky's authenticated tier gives 4000 credits/day, and cost
// scales with the geographic area queried (a whole-world call, which this is, is the
// most expensive kind). 60s keeps well clear of that budget without guessing at the
// exact per-call cost, which isn't precisely documented anywhere public right now.
private const val GLOBAL_POLL_INTERVAL_MS = 60_000L

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            var aircraft by remember { mutableStateOf<List<Aircraft>>(emptyList()) }
            var globalAircraft by remember { mutableStateOf<List<GlobalAircraft>>(emptyList()) }
            var showGlobeIntro by remember { mutableStateOf(true) }
            var stationSatellites by remember { mutableStateOf<List<SatelliteTle>>(emptyList()) }
            var allSatellites by remember { mutableStateOf<List<SatelliteTle>>(emptyList()) }
            var allSatellitesFetched by remember { mutableStateOf(false) }
            var satelliteModeActive by remember { mutableStateOf(false) }
            var radioStations by remember { mutableStateOf<List<RadioStation>>(emptyList()) }
            var radioModeActive by remember { mutableStateOf(false) }
            var earthquakes by remember { mutableStateOf<List<Earthquake>>(emptyList()) }
            var earthquakeModeActive by remember { mutableStateOf(false) }
            var airports by remember { mutableStateOf<List<Airport>>(emptyList()) }
            var runways by remember { mutableStateOf<List<Runway>>(emptyList()) }
            var ships by remember { mutableStateOf<List<Ship>>(emptyList()) }
            var shipModeActive by remember { mutableStateOf(false) }
            val shipsByMmsi = remember { mutableMapOf<Long, Ship>() }

            val displayedSatellites = if (satelliteModeActive && allSatellitesFetched) {
                allSatellites
            } else {
                stationSatellites
            }

            MaterialTheme {
                Surface {
                    if (showGlobeIntro) {
                        GlobeScreen(
                            globalAircraft = globalAircraft,
                            satellites = displayedSatellites,
                            satelliteModeActive = satelliteModeActive,
                            isLoadingFullCatalog = satelliteModeActive && !allSatellitesFetched,
                            onToggleSatelliteMode = {
                                satelliteModeActive = !satelliteModeActive
                                if (satelliteModeActive) {
                                    radioModeActive = false
                                    earthquakeModeActive = false
                                    shipModeActive = false
                                }
                            },
                            radioStations = radioStations,
                            radioModeActive = radioModeActive,
                            onToggleRadioMode = {
                                radioModeActive = !radioModeActive
                                if (radioModeActive) {
                                    satelliteModeActive = false
                                    earthquakeModeActive = false
                                    shipModeActive = false
                                }
                            },
                            earthquakes = earthquakes,
                            earthquakeModeActive = earthquakeModeActive,
                            onToggleEarthquakeMode = {
                                earthquakeModeActive = !earthquakeModeActive
                                if (earthquakeModeActive) {
                                    satelliteModeActive = false
                                    radioModeActive = false
                                    shipModeActive = false
                                }
                            },
                            ships = ships,
                            shipModeActive = shipModeActive,
                            onToggleShipMode = {
                                shipModeActive = !shipModeActive
                                if (shipModeActive) {
                                    satelliteModeActive = false
                                    radioModeActive = false
                                    earthquakeModeActive = false
                                }
                            },
                            onEnterAirspace = { showGlobeIntro = false }
                        )
                    } else {
                        MapScreen(
                            aircraft = aircraft,
                            globalAircraft = globalAircraft,
                            airports = airports,
                            runways = runways,
                            onExitToGlobe = { showGlobeIntro = true }
                        )
                    }
                }
            }

            LaunchedEffect(Unit) {
                try {
                    val testCountry = CountryInfo("Panama", "PA")
                    val testUrl = flagUrlFor(testCountry)
                    withContext(Dispatchers.IO) {
                        val request = okhttp3.Request.Builder().url(testUrl).head().build()
                        val response = sharedHttpClient.newCall(request).execute()
                        Log.d(
                            "FlightTracker",
                            "Flag CDN test: $testUrl -> HTTP ${response.code}, " +
                                    "content-type=${response.header("Content-Type")}, " +
                                    "content-length=${response.header("Content-Length")}"
                        )
                        response.close()
                    }
                } catch (e: Exception) {
                    Log.e("FlightTracker", "Flag CDN test failed", e)
                }
            }

            LaunchedEffect(Unit) {
                while (true) {
                    try {
                        val response = airplanesLiveApi.getAircraftNear(-1.286389, 36.817223, 50)
                        aircraft = response.ac
                    } catch (e: Exception) {
                        Log.e("FlightTracker", "local fetch failed", e)
                    }
                    delay(LOCAL_POLL_INTERVAL_MS)
                }
            }

            LaunchedEffect(Unit) {
                var received = 0
                val aisClient = AisStreamClient(apiKey = BuildConfig.AISSTREAM_API_KEY) { ship ->
                    received++
                    shipsByMmsi[ship.mmsi] = ship
                    ships = shipsByMmsi.values.toList()
                    if (received <= 5) {
                        Log.d("FlightTracker", "SHIP ${ship.name ?: ship.mmsi} at ${ship.lat}, ${ship.lon} — ${ship.speedKnots ?: 0.0} kt — flag: ${ship.flagState?.name ?: "unknown (MID ${ship.mmsi / 1_000_000})"}")
                    }
                    if (received == 100) {
                        Log.d("FlightTracker", "AIS: 100 ship updates received so far")
                    }
                }
                Log.d("FlightTracker", "AIS API key length: ${BuildConfig.AISSTREAM_API_KEY.length}")
                aisClient.connect()
            }

            LaunchedEffect(Unit) {
                try {
                    val loaded = fetchWorldwideAirports()
                    airports = loaded
                    Log.d("FlightTracker", "Airports: ${loaded.size} loaded worldwide")
                } catch (e: Exception) {
                    Log.e("FlightTracker", "Airport load failed", e)
                }
            }

            LaunchedEffect(Unit) {
                try {
                    val loaded = fetchRunways()
                    runways = loaded
                    Log.d("FlightTracker", "Runways: ${loaded.size} loaded")
                } catch (e: Exception) {
                    Log.e("FlightTracker", "Runway load failed", e)
                }
            }

            LaunchedEffect(Unit) {
                try {
                    val fetched = fetchSatelliteTles(group = "stations")
                    stationSatellites = fetched
                    Log.d("FlightTracker", "Satellites: ${fetched.size} loaded")
                } catch (e: Exception) {
                    Log.e("FlightTracker", "Satellite fetch failed", e)
                }
            }

            LaunchedEffect(satelliteModeActive) {
                if (!satelliteModeActive) return@LaunchedEffect
                while (!allSatellitesFetched) {
                    try {
                        Log.d("FlightTracker", "Fetching full satellite catalog, this may take a while...")
                        val fetched = fetchSatelliteTles(group = "active")
                        allSatellites = fetched
                        allSatellitesFetched = true
                        if (fetched.size < 100) {
                            Log.w("FlightTracker", "Full satellite catalog suspiciously small (${fetched.size}) — likely a rate limit or bad response, not real data")
                        } else {
                            Log.d("FlightTracker", "Full satellite catalog: ${fetched.size} loaded")
                        }
                    } catch (e: SatelliteDataUnchanged) {
                        Log.w("FlightTracker", "CelesTrak says active catalog hasn't changed since our last download — retrying in 5 minutes")
                        delay(SATELLITE_RETRY_DELAY_MS)
                    } catch (e: Exception) {
                        Log.e("FlightTracker", "Full satellite catalog fetch failed, retrying in 5 minutes", e)
                        delay(SATELLITE_RETRY_DELAY_MS)
                    }
                }
            }

            LaunchedEffect(Unit) {
                try {
                    val quakes = fetchRecentEarthquakes()
                    earthquakes = quakes
                    Log.d("FlightTracker", "Earthquakes: ${quakes.size} loaded (last 24h)")
                } catch (e: Exception) {
                    Log.e("FlightTracker", "Earthquake fetch failed", e)
                }
            }

            LaunchedEffect(Unit) {
                try {
                    val stations = fetchStationsWithLocation(limit = 5000)
                    radioStations = stations
                    Log.d("FlightTracker", "Radio stations with location: ${stations.size} loaded")
                } catch (e: Exception) {
                    Log.e("FlightTracker", "Radio station fetch failed", e)
                }
            }

            LaunchedEffect(Unit) {
                try {
                    val testArtists = listOf("Drake", "Burna Boy", "Nirvana", "Sauti Sol")
                    val locations = fetchArtistLocations(testArtists)
                    Log.d("FlightTracker", "Artist locations resolved: ${locations.size} of ${testArtists.size}")
                    locations.forEach {
                        Log.d("FlightTracker", "ARTIST ${it.name} — ${it.placeName}, ${it.country} at ${it.lat}, ${it.lon}")
                    }
                } catch (e: Exception) {
                    Log.e("FlightTracker", "Artist location test failed", e)
                }
            }

            LaunchedEffect(Unit) {
                while (true) {
                    try {
                        val global = fetchGlobalAircraftSnapshot()
                        globalAircraft = global
                        Log.d("FlightTracker", "OpenSky: ${global.size} aircraft worldwide")
                    } catch (e: Exception) {
                        Log.e("FlightTracker", "OpenSky fetch failed", e)
                    }
                    delay(GLOBAL_POLL_INTERVAL_MS)
                }
            }
        }
    }
}