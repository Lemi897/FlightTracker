package com.example.flighttracker

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.mapbox.geojson.Feature
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.maps.MapboxExperimental
import com.mapbox.maps.RenderedQueryGeometry
import com.mapbox.maps.RenderedQueryOptions
import com.mapbox.maps.Style
import com.mapbox.maps.dsl.cameraOptions
import com.mapbox.maps.extension.compose.MapboxMap
import com.mapbox.maps.extension.compose.animation.viewport.rememberMapViewportState
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import coil.ImageLoader
import coil.request.ImageRequest
import com.mapbox.maps.extension.compose.annotation.generated.CircleAnnotationGroup
import com.mapbox.maps.extension.compose.annotation.generated.PointAnnotationGroup
import com.mapbox.maps.extension.compose.rememberMapState
import com.mapbox.maps.extension.compose.style.ColorValue
import com.mapbox.maps.extension.compose.style.DoubleValue
import com.mapbox.maps.extension.compose.style.MapStyle
import com.mapbox.maps.extension.compose.style.layers.generated.CircleLayer
import com.mapbox.maps.extension.compose.style.layers.generated.LineLayer
import com.mapbox.maps.extension.compose.style.sources.GeoJSONData
import com.mapbox.maps.extension.compose.style.sources.generated.rememberGeoJsonSourceState
import com.mapbox.maps.plugin.animation.MapAnimationOptions
import com.mapbox.maps.plugin.annotation.generated.CircleAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

private const val EARTH_RADIUS_M = 6_371_000.0
// Zoomed in past this shows flag badges instead of plain dots; must drop below the
// lower value before reverting — a small dead zone so hovering near the boundary
// doesn't flicker between the two.
private const val SHIP_BADGE_ZOOM_IN = 6.0
private const val SHIP_BADGE_ZOOM_OUT = 5.5
private const val GLOBE_TICK_MS = 500L
private const val SATELLITE_TICK_MS_SMALL = 2_000L
private const val SATELLITE_TICK_MS_LARGE = 10_000L
private const val LARGE_CATALOG_THRESHOLD = 500
private const val MAX_GLOBE_TRAIL_POINTS = 15

private const val REVEAL_DURATION_MS = 90_000L
private const val REVEAL_TICK_MS = 500L
private const val REVEAL_TICKS = (REVEAL_DURATION_MS / REVEAL_TICK_MS).toInt()
private const val SPIN_DEGREES_PER_TICK = 1.2

private data class GlobeFix(
    var lat: Double,
    var lon: Double,
    val speedMs: Double,
    val headingDeg: Double,
    val isRecognized: Boolean
)

private fun colorForMagnitude(magnitude: Double?): String {
    val mag = magnitude ?: 0.0
    return when {
        mag < 3.0 -> "#FFEB3B"
        mag < 4.5 -> "#FF9800"
        mag < 6.0 -> "#FF5722"
        else -> "#D32F2F"
    }
}

@OptIn(ExperimentalMaterial3Api::class, MapboxExperimental::class)
@Composable
fun GlobeScreen(
    globalAircraft: List<GlobalAircraft> = emptyList(),
    satellites: List<SatelliteTle> = emptyList(),
    satelliteModeActive: Boolean = false,
    isLoadingFullCatalog: Boolean = false,
    onToggleSatelliteMode: () -> Unit = {},
    radioStations: List<RadioStation> = emptyList(),
    radioModeActive: Boolean = false,
    onToggleRadioMode: () -> Unit = {},
    earthquakes: List<Earthquake> = emptyList(),
    earthquakeModeActive: Boolean = false,
    onToggleEarthquakeMode: () -> Unit = {},
    ships: List<Ship> = emptyList(),
    shipModeActive: Boolean = false,
    onToggleShipMode: () -> Unit = {},
    onEnterAirspace: () -> Unit
) {
    val globeAircraftSource = rememberGeoJsonSourceState()
    val globeTrailSource = rememberGeoJsonSourceState()
    val satelliteSource = rememberGeoJsonSourceState()
    val fixes = remember { mutableMapOf<String, GlobeFix>() }
    val trailHistories = remember { mutableMapOf<String, MutableList<Point>>() }
    var isRevealing by remember { mutableStateOf(false) }
    var revealProgressText by remember { mutableStateOf("") }
    var revealedCount by remember { mutableStateOf(0) }
    var selectedStation by remember { mutableStateOf<RadioStation?>(null) }
    var selectedEarthquake by remember { mutableStateOf<Earthquake?>(null) }
    var nearbyQuakes by remember { mutableStateOf<List<Earthquake>>(emptyList()) }
    var isLoadingNearby by remember { mutableStateOf(false) }
    var selectedSatellitePosition by remember { mutableStateOf<SatellitePosition?>(null) }
    var selectedGlobalIcao24 by remember { mutableStateOf<String?>(null) }
    var latestSatellitePositions by remember { mutableStateOf<List<SatellitePosition>>(emptyList()) }
    var selectedShip by remember { mutableStateOf<Ship?>(null) }
    val shipFlagBitmaps = remember { mutableStateMapOf<String, Bitmap>() }
    var shipBadgesVisible by remember { mutableStateOf(false) }

    val context = LocalContext.current

    LaunchedEffect(shipBadgesVisible, ships) {
        if (!shipBadgesVisible) return@LaunchedEffect
        val neededCountries = ships.mapNotNull { it.flagState }
            .distinctBy { it.iso2 }
            .filter { it.iso2 !in shipFlagBitmaps }
        if (neededCountries.isEmpty()) return@LaunchedEffect
        val imageLoader = ImageLoader.Builder(context).build()
        for (country in neededCountries) {
            try {
                val request = ImageRequest.Builder(context).data(flagUrlFor(country, widthPx = 80)).build()
                val bitmap = (imageLoader.execute(request).drawable as? BitmapDrawable)?.bitmap
                if (bitmap != null) {
                    shipFlagBitmaps[country.iso2] = bitmap
                }
            } catch (e: Exception) {
                Log.w("FlightTracker", "Ship badge flag load failed for ${country.iso2}", e)
            }
        }
    }
    val exoPlayer = remember { ExoPlayer.Builder(context).build() }
    var isPlaying by remember { mutableStateOf(false) }
    var nowPlayingUuid by remember { mutableStateOf<String?>(null) }

    val mapState = rememberMapState()
    val coroutineScope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    val mapViewportState = rememberMapViewportState {
        setCameraOptions { center(Point.fromLngLat(36.817223, -1.286389)); zoom(1.2) }
    }

    LaunchedEffect(mapViewportState.cameraState?.zoom) {
        val zoom = mapViewportState.cameraState?.zoom ?: 0.0
        if (zoom >= SHIP_BADGE_ZOOM_IN) {
            shipBadgesVisible = true
        } else if (zoom < SHIP_BADGE_ZOOM_OUT) {
            shipBadgesVisible = false
        }
    }

    val stationsWithPoints = remember(radioStations) {
        radioStations.mapNotNull { station ->
            val lat = station.geoLat
            val lon = station.geoLong
            if (lat != null && lon != null) Triple(station, lat, lon) else null
        }
    }

    val hideAircraftAndSatellites = satelliteModeActive || radioModeActive || earthquakeModeActive || shipModeActive

    LaunchedEffect(globalAircraft) {
        globalAircraft.forEach { ac ->
            val lat = ac.lat
            val lon = ac.lon
            if (lat != null && lon != null) {
                fixes[ac.icao24] = GlobeFix(
                    lat = lat, lon = lon,
                    speedMs = ac.velocityMs ?: 0.0,
                    headingDeg = ac.headingDeg ?: 0.0,
                    isRecognized = airlineNameFor(ac.callsign) != null
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(GLOBE_TICK_MS)
            if (hideAircraftAndSatellites) continue

            val elapsedSec = GLOBE_TICK_MS / 1000.0
            val dotFeatures = mutableListOf<Feature>()
            val trailFeatures = mutableListOf<Feature>()

            fixes.forEach { (icao, fix) ->
                val distanceM = fix.speedMs * elapsedSec
                val headingRad = Math.toRadians(fix.headingDeg)
                val latRad = Math.toRadians(fix.lat)
                val deltaLat = (distanceM * cos(headingRad)) / EARTH_RADIUS_M * (180.0 / Math.PI)
                val deltaLon = (distanceM * sin(headingRad)) / (EARTH_RADIUS_M * cos(latRad)) * (180.0 / Math.PI)
                fix.lat += deltaLat
                fix.lon += deltaLon

                val point = Point.fromLngLat(fix.lon, fix.lat)
                val properties = JsonObject().apply { addProperty("icao24", icao) }
                dotFeatures.add(Feature.fromGeometry(point, properties))

                if (fix.isRecognized) {
                    val history = trailHistories.getOrPut(icao) { mutableListOf() }
                    history.add(point)
                    if (history.size > MAX_GLOBE_TRAIL_POINTS) history.removeAt(0)
                    if (history.size >= 2) {
                        trailFeatures.add(Feature.fromGeometry(LineString.fromLngLats(history)))
                    }
                }
            }

            globeAircraftSource.data = GeoJSONData(dotFeatures)
            globeTrailSource.data = GeoJSONData(trailFeatures)
        }
    }

    LaunchedEffect(satellites) {
        if (satellites.isEmpty()) return@LaunchedEffect
        val isLargeCatalog = satellites.size > LARGE_CATALOG_THRESHOLD

        if (!isLargeCatalog) {
            revealedCount = satellites.size
        } else {
            isRevealing = true
            revealedCount = 0
            var bearing = 0.0
            val perTick = ceil(satellites.size / REVEAL_TICKS.toDouble()).toInt().coerceAtLeast(1)

            while (revealedCount < satellites.size) {
                revealedCount = (revealedCount + perTick).coerceAtMost(satellites.size)
                bearing = (bearing + SPIN_DEGREES_PER_TICK) % 360.0

                mapViewportState.setCameraOptions {
                    center(Point.fromLngLat(36.817223, -1.286389))
                    zoom(1.2)
                    bearing(bearing)
                }
                revealProgressText = "Tracking $revealedCount of ${satellites.size} satellites…"
                delay(REVEAL_TICK_MS)
            }

            isRevealing = false
            revealProgressText = ""
        }
    }

    LaunchedEffect(satellites) {
        if (satellites.isEmpty()) return@LaunchedEffect
        while (true) {
            val currentCount = revealedCount.coerceAtLeast(1)
            val subset = satellites.take(currentCount)
            val positions = computeSatellitePositions(subset)
            latestSatellitePositions = positions
            val features = positions.map { pos ->
                val properties = JsonObject().apply { addProperty("name", pos.name) }
                Feature.fromGeometry(Point.fromLngLat(pos.lon, pos.lat), properties)
            }
            satelliteSource.data = GeoJSONData(features)
            val tickMs = if (currentCount > LARGE_CATALOG_THRESHOLD) SATELLITE_TICK_MS_LARGE else SATELLITE_TICK_MS_SMALL
            delay(tickMs)
        }
    }

    LaunchedEffect(selectedEarthquake?.id) {
        nearbyQuakes = emptyList()
        val quake = selectedEarthquake ?: return@LaunchedEffect
        isLoadingNearby = true
        nearbyQuakes = fetchNearbySeismicity(quake.lat, quake.lon, quake.id)
        isLoadingNearby = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        MapboxMap(
            modifier = Modifier.fillMaxSize(),
            mapViewportState = mapViewportState,
            mapState = mapState,
            onMapClickListener = { clickedPoint ->
                coroutineScope.launch {
                    val screenCoordinate = mapState.pixelForCoordinate(clickedPoint)
                    try {
                        val satelliteResult = mapState.queryRenderedFeatures(
                            RenderedQueryGeometry(screenCoordinate),
                            RenderedQueryOptions(listOf("satellite-layer"), null)
                        )
                        val tappedName = satelliteResult.value?.firstOrNull()?.queriedFeature?.feature?.getStringProperty("name")
                        if (tappedName != null) {
                            selectedSatellitePosition = latestSatellitePositions.firstOrNull { it.name == tappedName }
                        } else {
                            val aircraftResult = mapState.queryRenderedFeatures(
                                RenderedQueryGeometry(screenCoordinate),
                                RenderedQueryOptions(listOf("global-aircraft-layer"), null)
                            )
                            val tappedIcao24 = aircraftResult.value?.firstOrNull()?.queriedFeature?.feature?.getStringProperty("icao24")
                            if (tappedIcao24 != null) {
                                selectedGlobalIcao24 = tappedIcao24
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("FlightTracker", "Globe tap query threw exception", e)
                    }
                }
                true
            },
            style = { MapStyle(style = Style.STANDARD) }
        ) {
            if (!hideAircraftAndSatellites) {
                CircleLayer(sourceState = globeAircraftSource, layerId = "global-aircraft-layer") {
                    circleColor = ColorValue(Color(0xFF5B8DBE))
                    circleRadius = DoubleValue(2.5)
                    circleOpacity = DoubleValue(0.85)
                }
                LineLayer(sourceState = globeTrailSource) {
                    lineColor = ColorValue(Color(0xFF00E5FF))
                    lineWidth = DoubleValue(2.0)
                    lineOpacity = DoubleValue(0.85)
                }
            }

            if (satelliteModeActive) {
                CircleLayer(sourceState = satelliteSource, layerId = "satellite-layer") {
                    circleColor = ColorValue(Color(0xFFFFFFFF))
                    circleRadius = DoubleValue(if (satellites.size > LARGE_CATALOG_THRESHOLD) 1.8 else 4.0)
                    circleOpacity = DoubleValue(0.9)
                }
            }

            if (radioModeActive) {
                CircleAnnotationGroup(
                    annotations = stationsWithPoints.map { (_, lat, lon) ->
                        CircleAnnotationOptions()
                            .withPoint(Point.fromLngLat(lon, lat))
                            .withCircleRadius(5.0)
                            .withCircleColor("#FF3D9A")
                    },
                    onClick = { clicked ->
                        val point = clicked.point
                        selectedStation = stationsWithPoints.firstOrNull { (_, lat, lon) ->
                            lat == point.latitude() && lon == point.longitude()
                        }?.first
                        true
                    }
                )
            }

            if (earthquakeModeActive) {
                CircleAnnotationGroup(
                    annotations = earthquakes.map { quake ->
                        CircleAnnotationOptions()
                            .withPoint(Point.fromLngLat(quake.lon, quake.lat))
                            .withCircleRadius(4.0 + ((quake.magnitude ?: 1.0).coerceIn(0.0, 8.0)))
                            .withCircleColor(colorForMagnitude(quake.magnitude))
                            .withCircleOpacity(0.75)
                    },
                    onClick = { clicked ->
                        val point = clicked.point
                        selectedEarthquake = earthquakes.firstOrNull {
                            it.lat == point.latitude() && it.lon == point.longitude()
                        }
                        true
                    }
                )
            }

            if (shipModeActive) {
                val badgedShips = if (shipBadgesVisible) {
                    ships.filter { it.flagState != null && shipFlagBitmaps.containsKey(it.flagState.iso2) }
                } else {
                    emptyList()
                }
                val plainShips = if (shipBadgesVisible) {
                    ships.filter { it.flagState == null || !shipFlagBitmaps.containsKey(it.flagState.iso2) }
                } else {
                    ships
                }

                if (plainShips.isNotEmpty()) {
                    CircleAnnotationGroup(
                        annotations = plainShips.map { ship ->
                            CircleAnnotationOptions()
                                .withPoint(Point.fromLngLat(ship.lon, ship.lat))
                                .withCircleRadius(5.0)
                                .withCircleColor("#00BFA5")
                                .withData(JsonPrimitive(ship.mmsi))
                        },
                        onClick = { clicked ->
                            val mmsi = clicked.getData()?.asLong
                            selectedShip = ships.firstOrNull { it.mmsi == mmsi }
                            true
                        }
                    )
                }

                if (badgedShips.isNotEmpty()) {
                    PointAnnotationGroup(
                        annotations = badgedShips.map { ship ->
                            PointAnnotationOptions()
                                .withPoint(Point.fromLngLat(ship.lon, ship.lat))
                                .withIconImage(shipFlagBitmaps.getValue(ship.flagState!!.iso2))
                                .withIconSize(0.4)
                                .withData(JsonPrimitive(ship.mmsi))
                        },
                        onClick = { clicked ->
                            val mmsi = clicked.getData()?.asLong
                            selectedShip = ships.firstOrNull { it.mmsi == mmsi }
                            true
                        }
                    )
                }
            }
        }

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isLoadingFullCatalog && !isRevealing) {
                Text("Loading full satellite catalog…", color = Color.White)
            }
            if (isRevealing) {
                Text(revealProgressText, color = Color.White)
            }
            val modeButtonColors = SegmentedButtonDefaults.colors(
                activeContainerColor = Color(0xFF00BFA5),
                activeContentColor = Color.White,
                activeBorderColor = Color.White,
                inactiveContainerColor = Color.Black.copy(alpha = 0.55f),
                inactiveContentColor = Color.White,
                inactiveBorderColor = Color.White.copy(alpha = 0.6f)
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = satelliteModeActive,
                    onClick = onToggleSatelliteMode,
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 4),
                    colors = modeButtonColors
                ) { Text("Sat") }
                SegmentedButton(
                    selected = radioModeActive,
                    onClick = onToggleRadioMode,
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 4),
                    colors = modeButtonColors
                ) { Text("Radio") }
                SegmentedButton(
                    selected = earthquakeModeActive,
                    onClick = onToggleEarthquakeMode,
                    shape = SegmentedButtonDefaults.itemShape(index = 2, count = 4),
                    colors = modeButtonColors
                ) { Text("Quake") }
                SegmentedButton(
                    selected = shipModeActive,
                    onClick = onToggleShipMode,
                    shape = SegmentedButtonDefaults.itemShape(index = 3, count = 4),
                    colors = modeButtonColors
                ) { Text("Ships") }
            }
            if (!satelliteModeActive && !radioModeActive && !earthquakeModeActive && !shipModeActive) {
                Button(onClick = onEnterAirspace) {
                    Text("Enter Nairobi Airspace")
                }
            }
        }
    }

    selectedStation?.let { station ->
        ModalBottomSheet(onDismissRequest = { selectedStation = null }) {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)) {
                Text(station.name, style = MaterialTheme.typography.titleMedium)
                station.country?.let { Text("Country: $it") }
                station.tags?.let { Text("Genre: $it") }
                Button(onClick = {
                    if (nowPlayingUuid == station.uuid && isPlaying) {
                        exoPlayer.pause()
                        isPlaying = false
                    } else {
                        val streamUrl = station.urlResolved?.takeIf { it.isNotBlank() } ?: station.url
                        exoPlayer.setMediaItem(MediaItem.fromUri(streamUrl))
                        exoPlayer.prepare()
                        exoPlayer.play()
                        isPlaying = true
                        nowPlayingUuid = station.uuid
                    }
                }) {
                    Text(if (nowPlayingUuid == station.uuid && isPlaying) "Pause" else "Play")
                }
            }
        }
    }

    selectedEarthquake?.let { quake ->
        ModalBottomSheet(onDismissRequest = { selectedEarthquake = null }) {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)) {
                Text(quake.place, style = MaterialTheme.typography.titleMedium)
                Text("Magnitude: ${quake.magnitude ?: "Unknown"}")
                quake.depthKm?.let { Text("Depth: ${"%.1f".format(it)} km") }
                val minutesAgo = (System.currentTimeMillis() - quake.timeMs) / 60000
                Text("Occurred: $minutesAgo minutes ago")
                Text("Coordinates: ${"%.3f".format(quake.lat)}, ${"%.3f".format(quake.lon)}")
                if (quake.isTsunamiRisk) {
                    Text("⚠ Tsunami advisory issued", color = Color(0xFFFF5722))
                }
                quake.alertLevel?.let { alert ->
                    val alertColor = when (alert.lowercase()) {
                        "green" -> Color(0xFF4CAF50)
                        "yellow" -> Color(0xFFFFC107)
                        "orange" -> Color(0xFFFF9800)
                        "red" -> Color(0xFFF44336)
                        else -> Color.Gray
                    }
                    Text("PAGER alert: ${alert.replaceFirstChar { it.uppercase() }}", color = alertColor)
                }
                quake.feltCount?.let { Text("Reported felt by: $it people") }
                quake.significance?.let { Text("Significance score: $it") }
                quake.status?.let { Text("Review status: ${it.replaceFirstChar { c -> c.uppercase() }}") }

                Spacer(modifier = Modifier.height(12.dp))
                when {
                    isLoadingNearby -> Text("Checking nearby seismicity…", style = MaterialTheme.typography.bodySmall)
                    nearbyQuakes.isNotEmpty() -> {
                        Text(
                            "Nearby seismicity (7 days, 200km): ${nearbyQuakes.size} other events",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        nearbyQuakes.take(3).forEach {
                            Text("• M${it.magnitude ?: "?"} — ${it.place}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    else -> Text("No other nearby seismicity in the last 7 days", style = MaterialTheme.typography.bodySmall)
                }

                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = {
                    val targetLat = quake.lat
                    val targetLon = quake.lon
                    selectedEarthquake = null
                    mapViewportState.flyTo(
                        cameraOptions = cameraOptions {
                            center(Point.fromLngLat(targetLon, targetLat))
                            zoom(6.0)
                        },
                        MapAnimationOptions.mapAnimationOptions { duration(3000) }
                    )
                }) {
                    Text("View on Map")
                }

                quake.usgsUrl?.let { link ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "View on USGS",
                        color = Color(0xFF5B8DBE),
                        modifier = Modifier.clickable {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                        }
                    )
                }
            }
        }
    }

    selectedSatellitePosition?.let { sat ->
        ModalBottomSheet(onDismissRequest = { selectedSatellitePosition = null }) {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)) {
                Text(sat.name, style = MaterialTheme.typography.titleMedium)
                Text("Latitude: ${"%.2f".format(sat.lat)}°")
                Text("Longitude: ${"%.2f".format(sat.lon)}°")
                Text("Altitude: ${"%.0f".format(sat.altitudeKm)} km")
            }
        }
    }

    selectedShip?.let { ship ->
        ModalBottomSheet(onDismissRequest = { selectedShip = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.04f))
                        )
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(20.dp))
                    .padding(20.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(56.dp).clip(CircleShape).background(Color(0xFF2A3A4A)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (ship.flagState != null) {
                            AsyncImage(
                                model = flagUrlFor(ship.flagState),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Text("?", color = Color.White)
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(ship.name ?: "Unnamed vessel", style = MaterialTheme.typography.titleMedium)
                        Text(
                            ship.flagState?.name ?: "Flag unknown (MID ${ship.mmsi / 1_000_000})",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text("MMSI: ${ship.mmsi}")
                ship.speedKnots?.let { Text("Speed: ${"%.1f".format(it)} kt") }
                ship.courseDeg?.let { Text("Course: ${"%.0f".format(it)}°") }
                Text("Coordinates: ${"%.4f".format(ship.lat)}, ${"%.4f".format(ship.lon)}")
            }
        }
    }

    selectedGlobalIcao24?.let { icao24 ->
        val ac = globalAircraft.firstOrNull { it.icao24 == icao24 }
        if (ac != null) {
            ModalBottomSheet(onDismissRequest = { selectedGlobalIcao24 = null }) {
                val airlineName = airlineNameFor(ac.callsign)
                val country = airlineCountryFor(ac.callsign) ?: countryForIcao24(ac.icao24)

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.04f))
                            )
                        )
                        .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(20.dp))
                        .padding(20.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(48.dp).clip(CircleShape).background(Color(0xFF5B8DBE)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (country != null) {
                                AsyncImage(
                                    model = flagUrlFor(country),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Text("?", color = Color.White)
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            val label = ac.callsign?.trim()?.takeIf { it.isNotBlank() } ?: ac.icao24
                            Text(label, style = MaterialTheme.typography.titleMedium)
                            Text("ICAO24: ${ac.icao24}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    airlineName?.let { Text("Airline: $it") }
                    country?.let { Text("Flag: ${it.name}") }
                    ac.altitudeMeters?.let { Text("Altitude: ${(it * 3.28084).toInt()} ft") }
                    ac.velocityMs?.let { Text("Speed: ${(it * 1.94384).toInt()} kt") }
                    ac.headingDeg?.let { Text("Heading: ${it.toInt()}°") }
                    Text("On ground: ${if (ac.onGround) "Yes" else "No"}")
                }
            }
        }
    }
}