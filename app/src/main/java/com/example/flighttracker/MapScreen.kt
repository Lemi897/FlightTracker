package com.example.flighttracker

import android.graphics.RectF
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.gson.JsonObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.cos
import kotlin.math.sin

private val NAIROBI = LatLng(-1.286389, 36.817223)
private const val GLOBAL_SOURCE_ID = "global-aircraft-source"
private const val GLOBAL_LAYER_ID = "global-aircraft-layer"
private const val TRAIL_SOURCE_ID = "aircraft-trails-source"
private const val TRAIL_LAYER_ID = "aircraft-trails-layer"
private const val AIRPORT_SOURCE_ID = "airport-source"
private const val AIRPORT_LAYER_ID = "airport-layer"
private const val SATELLITE_SOURCE_ID = "satellite-source"
private const val SATELLITE_LAYER_ID = "satellite-layer"
private const val COCKPIT_TARGET_SOURCE_ID = "cockpit-target-source"
private const val COCKPIT_TARGET_LAYER_ID = "cockpit-target-layer"
private const val COCKPIT_PLANE_ICON_ID = "cockpit-plane-icon"
private const val HISTORY_TRAIL_SOURCE_ID = "history-trail-source"
private const val HISTORY_TRAIL_LAYER_ID = "history-trail-layer"
private const val HISTORY_AIRPORTS_SOURCE_ID = "history-airports-source"
private const val HISTORY_AIRPORTS_LAYER_ID = "history-airports-layer"
private const val MAX_TRAIL_POINTS = 20
private const val KNOTS_TO_MS = 0.514444
private const val EARTH_RADIUS_M = 6_371_000.0
private const val COCKPIT_ZOOM = 17.0
private const val COCKPIT_TILT = 65.0
// ~30fps — fast enough that the continuously-changing dead-reckoned position itself
// reads as smooth motion, rather than relying on (and fighting) a per-step animation.
private const val COCKPIT_FRAME_DELAY_MS = 33L
// How much the camera bearing eases toward the aircraft's real heading each frame —
// lower is smoother/slower to catch up, higher is snappier. Applied via shortest-path
// angle delta so it never spins the long way around past the 359°/0° wrap.
private const val COCKPIT_BEARING_SMOOTHING = 0.2
// Camera looks this far ahead of the aircraft along its heading, so it reads as looking
// into the direction of travel rather than sitting dead-centered on the nose.
private const val COCKPIT_LEAD_DISTANCE_M = 400.0
private const val NORMAL_AIRCRAFT_COLOR = "#5B8DBE"

// FLIR heat tiers, coolest to hottest, shared between local plane icons and global dots
private val FLIR_TIER_COLORS = listOf("#B71C1C", "#FF6D00", "#FFC400", "#FFFDE7")
private val FLIR_TIER_THRESHOLDS = listOf(5000, 15000, 30000) // feet; tier 0 below first, tier 3 above last

private fun flirTierIndex(altitudeFeet: Int): Int {
    for (i in FLIR_TIER_THRESHOLDS.indices) {
        if (altitudeFeet < FLIR_TIER_THRESHOLDS[i]) return i
    }
    return FLIR_TIER_COLORS.size - 1
}

private data class TrackedFix(
    val lat: Double,
    val lon: Double,
    val speedMs: Double,
    val headingDeg: Double,
    val fixTimeMs: Long
)

private fun tintedBitmap(source: android.graphics.Bitmap, tintColor: Int): android.graphics.Bitmap {
    val tinted = source.copy(source.config ?: android.graphics.Bitmap.Config.ARGB_8888, true)
    val canvas = android.graphics.Canvas(tinted)
    val paint = android.graphics.Paint().apply {
        colorFilter = android.graphics.PorterDuffColorFilter(tintColor, android.graphics.PorterDuff.Mode.SRC_IN)
    }
    canvas.drawBitmap(tinted, 0f, 0f, paint)
    return tinted
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    modifier: Modifier = Modifier,
    aircraft: List<Aircraft> = emptyList(),
    globalAircraft: List<GlobalAircraft> = emptyList(),
    airports: List<Airport> = emptyList(),
    runways: List<Runway> = emptyList(),
    onExitToGlobe: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    BackHandler(onBack = onExitToGlobe)

    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context)
    }
    val mapboxToken = remember { context.getString(R.string.mapbox_access_token) }
    val coroutineScope = rememberCoroutineScope()
    var flightHistoryResult by remember { mutableStateOf<FlightHistoryResult?>(null) }
    var flightHistoryQueriedFor by remember { mutableStateOf<String?>(null) }
    var flightHistoryLoading by remember { mutableStateOf(false) }
    var showFlightHistorySheet by remember { mutableStateOf(false) }
    val flightHistoryCache = remember { mutableMapOf<String, FlightHistoryResult?>() }

    fun requestFlightHistory(id: String) {
        showFlightHistorySheet = true
        flightHistoryQueriedFor = id
        if (flightHistoryCache.containsKey(id)) {
            flightHistoryResult = flightHistoryCache[id]
            flightHistoryLoading = false
            return
        }
        flightHistoryResult = null
        flightHistoryLoading = true
        coroutineScope.launch {
            val result = try {
                fetchFlightHistory(id)
            } catch (e: Exception) {
                Log.e("FlightTracker", "HISTORY $id: fetch failed", e)
                null
            }
            flightHistoryCache[id] = result
            if (flightHistoryQueriedFor == id) {
                flightHistoryResult = result
                flightHistoryLoading = false
            }
        }
    }

    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var mapStyle by remember { mutableStateOf<Style?>(null) }
    var selectedHex by remember { mutableStateOf<String?>(null) }
    var selectedAirportIdent by remember { mutableStateOf<String?>(null) }
    var selectedGlobalIcao24 by remember { mutableStateOf<String?>(null) }
    var wikipediaSummary by remember { mutableStateOf<WikipediaSummary?>(null) }
    var isLoadingWikipedia by remember { mutableStateOf(false) }
    var cockpitModeActive by remember { mutableStateOf(false) }
    var cockpitAircraftHex by remember { mutableStateOf<String?>(null) }
    var showCockpitExtraInfo by remember { mutableStateOf(false) }
    var flirModeActive by remember { mutableStateOf(false) }
    val markers = remember { mutableMapOf<String, Marker>() }
    val airportsByIdent = remember { mutableMapOf<String, Airport>() }
    val globalAircraftByIcao24 = remember { mutableMapOf<String, GlobalAircraft>() }
    val trackedFixes = remember { mutableMapOf<String, TrackedFix>() }
    val globalTrackedFixes = remember { mutableMapOf<String, TrackedFix>() }
    val trailHistories = remember { mutableMapOf<String, MutableList<LatLng>>() }

    val runwaysByIdent = remember(runways) { runways.groupBy { it.airportIdent } }

    val normalPlaneIcon = remember { IconFactory.getInstance(context).fromBitmap(vectorToBitmap(context, R.drawable.ic_plane)) }
    val flirIconTiers = remember {
        val base = vectorToBitmap(context, R.drawable.ic_plane)
        FLIR_TIER_COLORS.map { hex ->
            IconFactory.getInstance(context).fromBitmap(tintedBitmap(base, android.graphics.Color.parseColor(hex)))
        }
    }

    DisposableEffect(lifecycleOwner) {
        val lifecycle = lifecycleOwner.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(null)
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { mapView }
    ) { view ->
        view.getMapAsync { map ->
            if (mapLibreMap == null) {
                map.setStyle(Style.Builder().fromUri("https://tiles.openfreemap.org/styles/liberty")) { loadedStyle ->
                    map.cameraPosition = CameraPosition.Builder()
                        .target(LatLng(10.0, 20.0))
                        .zoom(1.2)
                        .build()

                    // Mapbox Satellite, added once, hidden by default. Inserted at the very
                    // bottom of the layer stack (index 0) so it sits beneath every existing
                    // vector layer. Toggled visible only during Cockpit View — see the
                    // cockpitModeActive effect below. Mapbox requires attribution when this
                    // tileset is shown; see the attribution Text near the cockpit HUD.
                    val satelliteTileUrl =
                        "https://api.mapbox.com/v4/mapbox.satellite/{z}/{x}/{y}@2x.jpg90?access_token=$mapboxToken"
                    loadedStyle.addSource(RasterSource(SATELLITE_SOURCE_ID, TileSet("tileset", satelliteTileUrl), 256))
                    val backgroundLayerId = loadedStyle.layers.firstOrNull()?.id
                    val satelliteLayer = RasterLayer(SATELLITE_LAYER_ID, SATELLITE_SOURCE_ID).withProperties(
                        PropertyFactory.visibility(Property.NONE)
                    )
                    if (backgroundLayerId != null) {
                        loadedStyle.addLayerAbove(satelliteLayer, backgroundLayerId)
                    } else {
                        loadedStyle.addLayerAt(satelliteLayer, 0)
                    }

                    // The tracked aircraft in Cockpit View gets a real rotating plane icon
                    // instead of the plain circle dot global aircraft normally render as.
                    // Empty until Cockpit View actually populates it — see the cockpit
                    // camera loop below.
                    loadedStyle.addImage(COCKPIT_PLANE_ICON_ID, vectorToBitmap(context, R.drawable.ic_plane))
                    loadedStyle.addSource(GeoJsonSource(COCKPIT_TARGET_SOURCE_ID, FeatureCollection.fromFeatures(emptyArray())))
                    loadedStyle.addLayer(
                        SymbolLayer(COCKPIT_TARGET_LAYER_ID, COCKPIT_TARGET_SOURCE_ID).withProperties(
                            PropertyFactory.iconImage(COCKPIT_PLANE_ICON_ID),
                            PropertyFactory.iconRotate(Expression.get("bearing")),
                            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                            PropertyFactory.iconAllowOverlap(true),
                            PropertyFactory.iconSize(1.0f)
                        )
                    )

                    // Flight history trail — empty until "Flight History" is actually tapped
                    // and a real path comes back. Purple, distinct from the cyan live trails.
                    loadedStyle.addSource(GeoJsonSource(HISTORY_TRAIL_SOURCE_ID, FeatureCollection.fromFeatures(emptyArray())))
                    loadedStyle.addLayer(
                        LineLayer(HISTORY_TRAIL_LAYER_ID, HISTORY_TRAIL_SOURCE_ID).withProperties(
                            PropertyFactory.lineColor("#9B59B6"),
                            PropertyFactory.lineWidth(4f)
                        )
                    )
                    loadedStyle.addSource(GeoJsonSource(HISTORY_AIRPORTS_SOURCE_ID, FeatureCollection.fromFeatures(emptyArray())))
                    loadedStyle.addLayer(
                        CircleLayer(HISTORY_AIRPORTS_LAYER_ID, HISTORY_AIRPORTS_SOURCE_ID).withProperties(
                            PropertyFactory.circleRadius(7f),
                            PropertyFactory.circleColor(Expression.get("markerColor")),
                            PropertyFactory.circleStrokeColor("#FFFFFF"),
                            PropertyFactory.circleStrokeWidth(1.5f)
                        )
                    )

                    mapStyle = loadedStyle
                }
                map.setOnMarkerClickListener { marker ->
                    selectedHex = markers.entries.firstOrNull { it.value.id == marker.id }?.key
                    true
                }
                map.addOnMapClickListener { latLng ->
                    val screenPoint = map.projection.toScreenLocation(latLng)
                    val tolerance = 12f
                    val tapArea = RectF(
                        screenPoint.x - tolerance, screenPoint.y - tolerance,
                        screenPoint.x + tolerance, screenPoint.y + tolerance
                    )

                    val airportFeatures = map.queryRenderedFeatures(tapArea, AIRPORT_LAYER_ID)
                    val tappedIdent = airportFeatures.firstOrNull()?.getStringProperty("ident")
                    if (tappedIdent != null) {
                        selectedAirportIdent = tappedIdent
                        return@addOnMapClickListener true
                    }

                    val globalFeatures = map.queryRenderedFeatures(tapArea, GLOBAL_LAYER_ID)
                    val tappedIcao = globalFeatures.firstOrNull()?.getStringProperty("icao24")
                    if (tappedIcao != null) {
                        selectedGlobalIcao24 = tappedIcao
                        return@addOnMapClickListener true
                    }

                    false
                }
                mapLibreMap = map
            }
        }
    }

    LaunchedEffect(cockpitModeActive, mapStyle) {
        val satelliteLayer = mapStyle?.getLayer(SATELLITE_LAYER_ID) as? RasterLayer ?: return@LaunchedEffect
        satelliteLayer.setProperties(
            PropertyFactory.visibility(if (cockpitModeActive) Property.VISIBLE else Property.NONE)
        )
    }

    // Keeps an in-progress flight's trail growing while the sheet is open, instead of
    // freezing at whatever was flown at the exact moment the button was pressed. A
    // completed flight is genuine history and is deliberately never re-fetched here.
    LaunchedEffect(showFlightHistorySheet, flightHistoryQueriedFor) {
        val id = flightHistoryQueriedFor ?: return@LaunchedEffect
        while (showFlightHistorySheet && flightHistoryQueriedFor == id) {
            delay(60_000L)
            if (!showFlightHistorySheet || flightHistoryQueriedFor != id) break
            if (flightHistoryResult !is FlightHistoryResult.InProgress) break
            try {
                val refreshed = fetchFlightHistory(id)
                flightHistoryCache[id] = refreshed
                if (flightHistoryQueriedFor == id) {
                    flightHistoryResult = refreshed
                }
            } catch (e: Exception) {
                Log.e("FlightTracker", "HISTORY $id: refresh failed", e)
            }
        }
    }

    LaunchedEffect(flightHistoryResult, mapStyle, airports) {
        val style = mapStyle ?: return@LaunchedEffect
        val trailSource = style.getSourceAs<GeoJsonSource>(HISTORY_TRAIL_SOURCE_ID)
        val airportsSource = style.getSourceAs<GeoJsonSource>(HISTORY_AIRPORTS_SOURCE_ID)
        val result = flightHistoryResult

        if (result == null) {
            trailSource?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
            airportsSource?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
            return@LaunchedEffect
        }

        val path = when (result) {
            is FlightHistoryResult.Completed -> result.path
            is FlightHistoryResult.InProgress -> result.path
        }
        val points = path.mapNotNull { wp ->
            if (wp.lat != null && wp.lon != null) Point.fromLngLat(wp.lon, wp.lat) else null
        }
        trailSource?.setGeoJson(
            if (points.size >= 2) {
                FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(points)))
            } else {
                FeatureCollection.fromFeatures(emptyArray())
            }
        )

        val markerFeatures = mutableListOf<Feature>()
        if (result is FlightHistoryResult.Completed) {
            val depCoords = airportCoordinatesFor(airports, result.departureAirportIcao)
                ?: points.firstOrNull()?.let { it.latitude() to it.longitude() }
            val arrCoords = airportCoordinatesFor(airports, result.arrivalAirportIcao)
                ?: points.lastOrNull()?.let { it.latitude() to it.longitude() }
            depCoords?.let { (lat, lon) ->
                val props = JsonObject().apply { addProperty("markerColor", "#2ECC71") }
                markerFeatures.add(Feature.fromGeometry(Point.fromLngLat(lon, lat), props))
            }
            arrCoords?.let { (lat, lon) ->
                val props = JsonObject().apply { addProperty("markerColor", "#E74C3C") }
                markerFeatures.add(Feature.fromGeometry(Point.fromLngLat(lon, lat), props))
            }
        }
        airportsSource?.setGeoJson(FeatureCollection.fromFeatures(markerFeatures.toTypedArray()))
    }

    LaunchedEffect(mapLibreMap) {
        val map = mapLibreMap ?: return@LaunchedEffect
        delay(30_000)
        map.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(NAIROBI)
                    .zoom(12.0)
                    .build()
            ),
            10_000
        )
    }

    LaunchedEffect(airports, mapStyle) {
        val style = mapStyle ?: return@LaunchedEffect
        if (airports.isEmpty()) return@LaunchedEffect

        airports.forEach { airportsByIdent[it.ident] = it }

        val features = airports.map { airport ->
            val properties = JsonObject().apply { addProperty("ident", airport.ident) }
            Feature.fromGeometry(Point.fromLngLat(airport.lon, airport.lat), properties)
        }
        val collection = FeatureCollection.fromFeatures(features)

        val existingSource = style.getSourceAs<GeoJsonSource>(AIRPORT_SOURCE_ID)
        if (existingSource != null) {
            existingSource.setGeoJson(collection)
        } else {
            style.addSource(GeoJsonSource(AIRPORT_SOURCE_ID, collection))
            style.addLayer(
                CircleLayer(AIRPORT_LAYER_ID, AIRPORT_SOURCE_ID).withProperties(
                    PropertyFactory.circleRadius(3f),
                    PropertyFactory.circleColor("#FF9800"),
                    PropertyFactory.circleOpacity(0.8f)
                )
            )
        }
    }

    LaunchedEffect(selectedAirportIdent) {
        wikipediaSummary = null
        val link = airportsByIdent[selectedAirportIdent]?.wikipediaLink
        if (link != null) {
            isLoadingWikipedia = true
            wikipediaSummary = fetchWikipediaSummary(link)
            isLoadingWikipedia = false
        }
    }

    // Re-runs on FLIR toggle too, so every visible marker re-tints to its own altitude tier immediately
    LaunchedEffect(aircraft, mapLibreMap, flirModeActive) {
        val map = mapLibreMap ?: return@LaunchedEffect
        val now = System.currentTimeMillis()
        val seenHexes = mutableSetOf<String>()

        aircraft.forEach { ac ->
            val lat = ac.lat
            val lon = ac.lon
            if (lat != null && lon != null) {
                seenHexes.add(ac.hex)
                trackedFixes[ac.hex] = TrackedFix(
                    lat = lat,
                    lon = lon,
                    speedMs = (ac.gs ?: 0.0) * KNOTS_TO_MS,
                    headingDeg = ac.track ?: 0.0,
                    fixTimeMs = now
                )

                val history = trailHistories.getOrPut(ac.hex) { mutableListOf() }
                history.add(LatLng(lat, lon))
                if (history.size > MAX_TRAIL_POINTS) history.removeAt(0)

                val icon = if (flirModeActive) {
                    flirIconTiers[flirTierIndex(ac.altitudeFeet ?: 0)]
                } else {
                    normalPlaneIcon
                }

                val existing = markers[ac.hex]
                if (existing != null) {
                    existing.position = LatLng(lat, lon)
                    existing.icon = icon
                } else {
                    markers[ac.hex] = map.addMarker(
                        MarkerOptions()
                            .position(LatLng(lat, lon))
                            .icon(icon)
                    )
                }
            }
        }

        val stale = markers.keys - seenHexes
        stale.forEach { hex ->
            markers[hex]?.let { map.removeMarker(it) }
            markers.remove(hex)
            trackedFixes.remove(hex)
            trailHistories.remove(hex)
        }

        mapStyle?.let { style ->
            val trailFeatures = trailHistories.values
                .filter { it.size >= 2 }
                .map { points ->
                    val lineString = LineString.fromLngLats(points.map { Point.fromLngLat(it.longitude, it.latitude) })
                    Feature.fromGeometry(lineString)
                }
            val trailCollection = FeatureCollection.fromFeatures(trailFeatures)

            val existingTrailSource = style.getSourceAs<GeoJsonSource>(TRAIL_SOURCE_ID)
            if (existingTrailSource != null) {
                existingTrailSource.setGeoJson(trailCollection)
            } else {
                style.addSource(GeoJsonSource(TRAIL_SOURCE_ID, trailCollection))
                style.addLayer(
                    LineLayer(TRAIL_LAYER_ID, TRAIL_SOURCE_ID).withProperties(
                        PropertyFactory.lineColor("#00E5FF"),
                        PropertyFactory.lineWidth(3f),
                        PropertyFactory.lineOpacity(0.85f)
                    )
                )
            }
        }
    }

    LaunchedEffect(mapLibreMap) {
        if (mapLibreMap == null) return@LaunchedEffect
        while (true) {
            val now = System.currentTimeMillis()
            trackedFixes.forEach { (hex, fix) ->
                val elapsedSec = (now - fix.fixTimeMs) / 1000.0
                val distanceM = fix.speedMs * elapsedSec
                val headingRad = Math.toRadians(fix.headingDeg)
                val latRad = Math.toRadians(fix.lat)

                val deltaLat = (distanceM * cos(headingRad)) / EARTH_RADIUS_M * (180.0 / Math.PI)
                val deltaLon = (distanceM * sin(headingRad)) / (EARTH_RADIUS_M * cos(latRad)) * (180.0 / Math.PI)

                markers[hex]?.position = LatLng(fix.lat + deltaLat, fix.lon + deltaLon)
            }
            delay(100L)
        }
    }

    // Cockpit follow — now eases between positions instead of hard-jumping, for smoother motion
    LaunchedEffect(cockpitModeActive) {
        if (!cockpitModeActive) return@LaunchedEffect
        val map = mapLibreMap ?: return@LaunchedEffect
        val style = mapStyle
        var smoothedBearing: Double? = null
        // Snap to a tight starting zoom on entry. After this, the loop below reads the
        // map's live zoom each frame instead of re-forcing this constant, so pinch-zoom
        // gestures during Cockpit View actually stick instead of being overwritten ~30
        // times a second.
        map.moveCamera(CameraUpdateFactory.zoomTo(COCKPIT_ZOOM))
        try {
            while (cockpitModeActive) {
                val hex = cockpitAircraftHex
                val fix = if (hex != null) (trackedFixes[hex] ?: globalTrackedFixes[hex]) else null
                if (fix == null) {
                    cockpitModeActive = false
                    break
                }
                val now = System.currentTimeMillis()
                val elapsedSec = (now - fix.fixTimeMs) / 1000.0
                val distanceM = fix.speedMs * elapsedSec
                val headingRad = Math.toRadians(fix.headingDeg)
                val latRad = Math.toRadians(fix.lat)
                val deltaLat = (distanceM * cos(headingRad)) / EARTH_RADIUS_M * (180.0 / Math.PI)
                val deltaLon = (distanceM * sin(headingRad)) / (EARTH_RADIUS_M * cos(latRad)) * (180.0 / Math.PI)
                val aircraftLat = fix.lat + deltaLat
                val aircraftLon = fix.lon + deltaLon

                // Ease the camera bearing toward the real heading instead of snapping to it —
                // shortest angular path so a turn from, say, 350° to 10° rotates 20° forward,
                // never the long way around through 180°.
                val current = smoothedBearing ?: fix.headingDeg
                var angleDelta = (fix.headingDeg - current) % 360.0
                if (angleDelta > 180.0) angleDelta -= 360.0
                if (angleDelta < -180.0) angleDelta += 360.0
                val newBearing = (current + angleDelta * COCKPIT_BEARING_SMOOTHING + 360.0) % 360.0
                smoothedBearing = newBearing

                // Local aircraft already render with a real plane icon marker — only global
                // aircraft need the dot-to-icon swap, since they normally render as plain dots.
                val isGlobalTarget = hex != null && trackedFixes[hex] == null
                val targetSource = style?.getSourceAs<GeoJsonSource>(COCKPIT_TARGET_SOURCE_ID)
                if (isGlobalTarget) {
                    val properties = JsonObject().apply { addProperty("bearing", newBearing) }
                    val feature = Feature.fromGeometry(Point.fromLngLat(aircraftLon, aircraftLat), properties)
                    targetSource?.setGeoJson(FeatureCollection.fromFeatures(arrayOf(feature)))
                } else {
                    targetSource?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
                }

                // Offset the camera target ahead of the aircraft along the (smoothed) heading —
                // a lead/chase-cam framing rather than dead-centering on the nose.
                val leadRad = Math.toRadians(newBearing)
                val leadLatRad = Math.toRadians(aircraftLat)
                val leadDeltaLat = (COCKPIT_LEAD_DISTANCE_M * cos(leadRad)) / EARTH_RADIUS_M * (180.0 / Math.PI)
                val leadDeltaLon = (COCKPIT_LEAD_DISTANCE_M * sin(leadRad)) / (EARTH_RADIUS_M * cos(leadLatRad)) * (180.0 / Math.PI)

                map.moveCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                            .target(LatLng(aircraftLat + leadDeltaLat, aircraftLon + leadDeltaLon))
                            .zoom(map.cameraPosition.zoom)
                            .tilt(COCKPIT_TILT)
                            .bearing(newBearing)
                            .build()
                    )
                )
                delay(COCKPIT_FRAME_DELAY_MS)
            }
        } finally {
            style?.getSourceAs<GeoJsonSource>(COCKPIT_TARGET_SOURCE_ID)?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
        }
    }

    LaunchedEffect(globalAircraft) {
        val now = System.currentTimeMillis()
        globalAircraft.forEach { ac ->
            val lat = ac.lat
            val lon = ac.lon
            if (lat != null && lon != null) {
                globalAircraftByIcao24[ac.icao24] = ac
                globalTrackedFixes[ac.icao24] = TrackedFix(
                    lat = lat,
                    lon = lon,
                    speedMs = ac.velocityMs ?: 0.0,
                    headingDeg = ac.headingDeg ?: 0.0,
                    fixTimeMs = now
                )
            }
        }
    }

    LaunchedEffect(mapStyle) {
        val style = mapStyle ?: return@LaunchedEffect
        while (true) {
            val now = System.currentTimeMillis()
            val features = globalTrackedFixes
                .filterKeys { icao -> !(cockpitModeActive && icao == cockpitAircraftHex) }
                .map { (icao, fix) ->
                    val elapsedSec = (now - fix.fixTimeMs) / 1000.0
                    val distanceM = fix.speedMs * elapsedSec
                    val headingRad = Math.toRadians(fix.headingDeg)
                    val latRad = Math.toRadians(fix.lat)
                    val deltaLat = (distanceM * cos(headingRad)) / EARTH_RADIUS_M * (180.0 / Math.PI)
                    val deltaLon = (distanceM * sin(headingRad)) / (EARTH_RADIUS_M * cos(latRad)) * (180.0 / Math.PI)
                    val altitudeFt = (globalAircraftByIcao24[icao]?.altitudeMeters ?: 0.0) * 3.28084
                    val properties = JsonObject().apply {
                        addProperty("icao24", icao)
                        addProperty("altitudeFt", altitudeFt)
                    }
                    Feature.fromGeometry(Point.fromLngLat(fix.lon + deltaLon, fix.lat + deltaLat), properties)
                }
            val collection = FeatureCollection.fromFeatures(features)

            val existingSource = style.getSourceAs<GeoJsonSource>(GLOBAL_SOURCE_ID)
            if (existingSource != null) {
                existingSource.setGeoJson(collection)
            } else {
                style.addSource(GeoJsonSource(GLOBAL_SOURCE_ID, collection))
                style.addLayer(
                    CircleLayer(GLOBAL_LAYER_ID, GLOBAL_SOURCE_ID).withProperties(
                        PropertyFactory.circleRadius(2.5f),
                        PropertyFactory.circleColor(NORMAL_AIRCRAFT_COLOR),
                        PropertyFactory.circleOpacity(0.85f)
                    )
                )
            }
            delay(500L)
        }
    }

    // Data-driven heat color for the global dots — real altitude, not a fixed color, when FLIR is active
    LaunchedEffect(flirModeActive, mapStyle) {
        val style = mapStyle ?: return@LaunchedEffect
        val layer = style.getLayerAs<CircleLayer>(GLOBAL_LAYER_ID) ?: return@LaunchedEffect
        if (flirModeActive) {
            layer.setProperties(
                PropertyFactory.circleColor(
                    Expression.interpolate(
                        Expression.linear(),
                        Expression.get("altitudeFt"),
                        Expression.stop(0, Expression.color(android.graphics.Color.parseColor(FLIR_TIER_COLORS[0]))),
                        Expression.stop(FLIR_TIER_THRESHOLDS[0], Expression.color(android.graphics.Color.parseColor(FLIR_TIER_COLORS[1]))),
                        Expression.stop(FLIR_TIER_THRESHOLDS[1], Expression.color(android.graphics.Color.parseColor(FLIR_TIER_COLORS[2]))),
                        Expression.stop(FLIR_TIER_THRESHOLDS[2], Expression.color(android.graphics.Color.parseColor(FLIR_TIER_COLORS[3])))
                    )
                )
            )
        } else {
            layer.setProperties(PropertyFactory.circleColor(NORMAL_AIRCRAFT_COLOR))
        }
    }

    if (flirModeActive) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(color = Color(0xFF00FF00).copy(alpha = 0.35f))
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
                    center = center,
                    radius = size.minDimension * 0.75f
                )
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (!cockpitModeActive) {
            Button(
                onClick = onExitToGlobe,
                modifier = Modifier.align(Alignment.TopStart).padding(top = 40.dp, start = 16.dp)
            ) {
                Text("← Globe")
            }
        }
        Button(
            onClick = { flirModeActive = !flirModeActive },
            modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp)
        ) {
            Text(if (flirModeActive) "Exit FLIR Mode" else "FLIR Mode")
        }
    }

    if (cockpitModeActive) {
        val hudLocalAircraft = aircraft.firstOrNull { it.hex == cockpitAircraftHex }
        val hudGlobalAircraft = if (hudLocalAircraft == null) {
            globalAircraft.firstOrNull { it.icao24 == cockpitAircraftHex }
        } else null
        val hudFix = cockpitAircraftHex?.let { trackedFixes[it] ?: globalTrackedFixes[it] }
        Box(modifier = Modifier.fillMaxSize()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 40.dp)
            ) {
                Button(
                    onClick = {
                        cockpitModeActive = false
                        cockpitAircraftHex = null
                        mapLibreMap?.moveCamera(
                            CameraUpdateFactory.newCameraPosition(
                                CameraPosition.Builder().tilt(0.0).bearing(0.0).zoom(12.0).build()
                            )
                        )
                    }
                ) {
                    Text("Exit Cockpit View")
                }
                Button(
                    onClick = {
                        // Opens a distinct panel, not the same modal — camera keeps
                        // following live underneath either way.
                        showCockpitExtraInfo = true
                    }
                ) {
                    Text("Info")
                }
            }

            Text(
                "© Mapbox",
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.4f))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )

            if (hudFix != null) {
                Column(
                    modifier = Modifier.align(Alignment.BottomStart)
                        .padding(20.dp)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(12.dp)
                ) {
                    val label = hudLocalAircraft?.callsign?.trim()?.takeIf { it.isNotBlank() }
                        ?: hudGlobalAircraft?.callsign?.trim()?.takeIf { it.isNotBlank() }
                        ?: cockpitAircraftHex
                        ?: "—"
                    val altText = hudLocalAircraft?.altitudeFeet?.let { "$it ft" }
                        ?: hudGlobalAircraft?.altitudeMeters?.let { "${(it * 3.28084).toInt()} ft" }
                        ?: "—"
                    Text(label, color = Color.White, style = MaterialTheme.typography.titleSmall)
                    Text("ALT  $altText", color = Color(0xFF00FF00))
                    Text("SPD  ${(hudFix.speedMs / KNOTS_TO_MS).toInt()} kt", color = Color(0xFF00FF00))
                    Text("HDG  ${hudFix.headingDeg.toInt()}°", color = Color(0xFF00FF00))
                }
            }
        }
    }

    val selectedAircraft = aircraft.firstOrNull { it.hex == selectedHex }
    if (selectedAircraft != null) {
        ModalBottomSheet(onDismissRequest = { selectedHex = null }) {
            val code = airlineCodeFor(selectedAircraft.callsign)
            val airlineName = airlineNameFor(selectedAircraft.callsign) ?: "Unknown carrier"
            val airlineCountry = airlineCountryFor(selectedAircraft.callsign) ?: countryForIcao24(selectedAircraft.hex)
            val alt = if (selectedAircraft.isOnGround) "On ground" else "${selectedAircraft.altitudeFeet} ft"

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
                        if (airlineCountry != null) {
                            AsyncImage(
                                model = flagUrlFor(airlineCountry),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Text(code ?: "?", color = Color.White)
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(airlineName, style = MaterialTheme.typography.titleMedium)
                        Text(selectedAircraft.callsign ?: selectedAircraft.hex, style = MaterialTheme.typography.bodySmall)
                    }
                }

                if (airlineCountry != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Flag: ${airlineCountry.name}")
                }

                if (selectedAircraft.isEmergencySquawk) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "⚠ Squawk ${selectedAircraft.squawk} — this is a real emergency code",
                        color = Color.Red,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text("Aircraft: ${selectedAircraft.desc ?: selectedAircraft.t ?: "unknown"}")
                selectedAircraft.r?.let { Text("Registration: $it") }
                categoryLabelFor(selectedAircraft.category)?.let { Text("Category: $it") }
                Text("Altitude: $alt")
                Text("Speed: ${selectedAircraft.gs?.toInt() ?: "—"} kt")
                Text("Heading: ${selectedAircraft.track?.toInt() ?: "—"}°")
                Text("Trend: ${selectedAircraft.verticalTrend}")
                selectedAircraft.squawk?.let { Text("Squawk: $it") }
                Text("Distance: ${selectedAircraft.dst} nm")
                selectedAircraft.seen?.let { Text("Updated ${it.toInt()}s ago") }

                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = {
                    cockpitAircraftHex = selectedAircraft.hex
                    cockpitModeActive = true
                    selectedHex = null
                }) {
                    Text("Cockpit View")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = {
                    requestFlightHistory(selectedAircraft.hex)
                    selectedHex = null
                }) {
                    Text("Flight History")
                }
            }
        }
    }

    val selectedAirport = airportsByIdent[selectedAirportIdent]
    if (selectedAirport != null) {
        ModalBottomSheet(onDismissRequest = { selectedAirportIdent = null }) {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)) {
                Text(selectedAirport.name, style = MaterialTheme.typography.titleMedium)
                Text("Type: ${selectedAirport.type}")
                Text("Country: ${selectedAirport.isoCountry}")
                selectedAirport.icaoCode?.let { Text("ICAO: $it") }
                selectedAirport.iataCode?.let { Text("IATA: $it") }
                selectedAirport.municipality?.let { Text("Municipality: $it") }
                selectedAirport.elevationFt?.let { Text("Elevation: $it ft") }
                Text("Scheduled commercial service: ${if (selectedAirport.scheduledService) "Yes" else "No"}")

                if (selectedAirport.wikipediaLink != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    when {
                        isLoadingWikipedia -> Text("Loading summary…", style = MaterialTheme.typography.bodySmall)
                        wikipediaSummary != null -> {
                            Text(wikipediaSummary!!.extract, style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Source: Wikipedia", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        }
                        else -> {}
                    }
                }

                val airportRunways = runwaysByIdent[selectedAirport.ident].orEmpty()
                if (airportRunways.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Runways:", style = MaterialTheme.typography.titleSmall)
                    airportRunways.forEach { rw ->
                        val lengthText = rw.lengthFt?.let { "$it ft" } ?: "length unknown"
                        val surfaceText = rw.surface ?: "surface unknown"
                        val statusText = if (rw.closed) " (closed)" else ""
                        Text("• $lengthText, $surfaceText$statusText")
                    }
                }
            }
        }
    }

    val selectedGlobalAircraft = globalAircraftByIcao24[selectedGlobalIcao24]
    if (selectedGlobalAircraft != null) {
        ModalBottomSheet(onDismissRequest = { selectedGlobalIcao24 = null }) {
            val airlineName = airlineNameFor(selectedGlobalAircraft.callsign)
            val airlineCountry = airlineCountryFor(selectedGlobalAircraft.callsign) ?: countryForIcao24(selectedGlobalAircraft.icao24)

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
                        if (airlineCountry != null) {
                            AsyncImage(
                                model = flagUrlFor(airlineCountry),
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
                        val label = selectedGlobalAircraft.callsign?.trim()?.takeIf { it.isNotBlank() } ?: selectedGlobalAircraft.icao24
                        Text(label, style = MaterialTheme.typography.titleMedium)
                        Text("ICAO24: ${selectedGlobalAircraft.icao24}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                airlineName?.let { Text("Airline: $it") }
                airlineCountry?.let { Text("Flag: ${it.name}") }
                selectedGlobalAircraft.altitudeMeters?.let { Text("Altitude: ${(it * 3.28084).toInt()} ft") }
                selectedGlobalAircraft.velocityMs?.let { Text("Speed: ${(it * 1.94384).toInt()} kt") }
                selectedGlobalAircraft.headingDeg?.let { Text("Heading: ${it.toInt()}°") }
                Text("On ground: ${if (selectedGlobalAircraft.onGround) "Yes" else "No"}")

                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = {
                    cockpitAircraftHex = selectedGlobalAircraft.icao24
                    cockpitModeActive = true
                    selectedGlobalIcao24 = null
                }) {
                    Text("Cockpit View")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = {
                    requestFlightHistory(selectedGlobalAircraft.icao24)
                    selectedGlobalIcao24 = null
                }) {
                    Text("Flight History")
                }
            }
        }
    }

    if (showFlightHistorySheet) {
        ModalBottomSheet(onDismissRequest = {
            showFlightHistorySheet = false
            flightHistoryQueriedFor = null
        }) {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)) {
                Text("Flight History", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(12.dp))
                when {
                    flightHistoryLoading -> {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Looking up flight history…")
                    }
                    flightHistoryResult == null -> {
                        Text("No recent history for this aircraft.")
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "It hasn't completed a flight in the last 90 minutes and isn't currently airborne.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    else -> when (val result = flightHistoryResult) {
                        is FlightHistoryResult.Completed -> {
                            val depName = airportNameFor(airports, result.departureAirportIcao)
                                ?: result.departureAirportIcao ?: "Unknown airport"
                            val arrName = airportNameFor(airports, result.arrivalAirportIcao)
                                ?: result.arrivalAirportIcao ?: "Unknown airport"
                            Text("$depName → $arrName", style = MaterialTheme.typography.bodyLarge)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Departed: ${formatUtcDate(result.departureTimeSec)}, ${formatUtcTime(result.departureTimeSec)}")
                            Text("Arrived: ${formatUtcDate(result.arrivalTimeSec)}, ${formatUtcTime(result.arrivalTimeSec)}")
                            Spacer(modifier = Modifier.height(8.dp))
                            val durationSec = result.arrivalTimeSec - result.departureTimeSec
                            Text("Duration: ${formatDuration(durationSec)}")
                            val distanceKm = totalDistanceKm(result.path)
                            if (distanceKm > 0) {
                                Text("Distance flown: ${distanceKm.toInt()} km")
                                if (durationSec > 0) {
                                    val avgSpeedKmh = distanceKm / (durationSec / 3600.0)
                                    Text("Average speed: ${avgSpeedKmh.toInt()} km/h")
                                }
                            }
                            if (result.path.size < 2) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "Track data wasn't available for this flight, so no path is shown on the map.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        is FlightHistoryResult.InProgress -> {
                            Text("Currently in flight", style = MaterialTheme.typography.bodyLarge)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("In the air since: ${formatUtcDate(result.startTimeSec)}, ${formatUtcTime(result.startTimeSec)}")
                            Text("${result.path.size} waypoints tracked so far")
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "No known destination yet — that's only available once this flight lands.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        null -> {}
                    }
                }
            }
        }
    }

    if (showCockpitExtraInfo) {
        ModalBottomSheet(onDismissRequest = { showCockpitExtraInfo = false }) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                Text("More about this aircraft", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "This panel is a placeholder for now — the real content (trip history, " +
                            "type facts, capacity) needs a proper design pass before it's built.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}