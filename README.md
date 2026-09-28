# FlightTracker ✈️

A real-time Android app that turns a 3D globe into a live view of what's moving around the planet: aircraft, satellites, ships, earthquakes and radio stations, plus a detailed look at Nairobi's airspace with a cockpit view.

Built with Kotlin and Jetpack Compose, and tested on a real Android 12 device.

<!-- Screenshots: add images to docs/screenshots/ and update the paths below -->
| Globe dashboard | Satellites | Nairobi airspace | Cockpit view |
|---|---|---|---|
| ![Globe](docs/screenshots/globe.jpg) | ![Satellites](docs/screenshots/satellites.jpg) | ![Nairobi](docs/screenshots/nairobi.jpg) | ![Cockpit](docs/screenshots/cockpit.jpg) |

## Features

### 🌍 Globe dashboard
An interactive 3D globe showing airports and airstrips worldwide, with five modes:

- **SAT (Satellites):** Loads satellites in orbit at their current positions while the globe rotates. Tap a satellite to see its name, latitude, longitude and altitude.
- **RADIO:** Public radio stations around the world, shown as red dots. Tap one to see where it broadcasts from and hit play to listen live.
- **QUAKE (Earthquakes):** Current earthquake activity from public USGS data, shown as orange/amber dots. Each one shows magnitude, depth, time, coordinates, significance score, review status and nearby seismicity. It can jump to the location on the map or open the event on the USGS website.
- **SHIPS:** Vessels worldwide as green dots, streamed live. Each shows its MMSI, flag of origin, speed, course and coordinates.
- **ENTER NAIROBI AIRSPACE:** Switches to a detailed map of Nairobi (below).

### 🛬 Nairobi airspace
A close-up map of the city's airspace:

- **Airports (orange dots):** Name, type, country, ICAO code, municipality, elevation, scheduled commercial service, runways, and background on the airport.
- **Aircraft (blue moving dots):** Flag, ICAO address, altitude, speed, heading and on-ground status, with two views:
  - **Cockpit view:** A pilot's-eye camera over satellite imagery showing where the aircraft is heading, with a **FLIR mode** thermal-style green filter.
  - **Flight history:** Departure and arrival, duration, distance flown and average speed.

## Tech stack

- **Language & UI:** Kotlin, Jetpack Compose
- **Maps:** Mapbox (3D globe, satellite imagery), MapLibre (Nairobi map)
- **Networking:** Retrofit/OkHttp for REST APIs, OkHttp WebSockets for live ship data
- **Media:** Media3 / ExoPlayer for radio streaming
- **Images:** Coil
- **Satellites:** TLE orbital prediction ([tle-prediction-engine](https://github.com/neosensory/tle-prediction-engine))

## Data sources

- [OpenSky Network](https://opensky-network.org/) for aircraft positions, tracks and flight history
- [airplanes.live](https://airplanes.live/) for additional aircraft data
- [AISStream](https://aisstream.io/) for live ship positions (AIS)
- [USGS Earthquake Hazards Program](https://earthquake.usgs.gov/) for earthquake data
- Wikipedia for airport background
- Public radio station APIs for live streams

## Engineering notes

A few problems worth knowing about if you build something similar:

- **OkHttp silently drops binary WebSocket frames** if you only override the text `onMessage`. AISStream sends binary frames, so the `ByteString` overload is required.
- **Coil 3 broke the whole project's Kotlin stdlib resolution** through Kotlin Multiplatform classpath conflicts. The project uses Coil 2.7.0.
- **Blocking OkHttp calls inside `LaunchedEffect`** must be wrapped in `withContext(Dispatchers.IO)` to keep them off the main thread.
- **No secrets in the repo:** all API keys are read from `local.properties` at build time and exposed via `BuildConfig`.

## Building it yourself

You'll need your own (free) API keys.

1. Clone the repo:
```bash
   git clone https://github.com/Lemi897/FlightTracker.git
```

2. Add your keys to `local.properties` in the project root (this file is gitignored):
```properties
   OPENSKY_CLIENT_ID=your_client_id
   OPENSKY_CLIENT_SECRET=your_client_secret
   AISSTREAM_API_KEY=your_aisstream_key
```

3. Add your Mapbox **public** token: copy `mapbox_access_token.xml.example` to `app/src/main/res/values/mapbox_access_token.xml` and replace the placeholder.

4. If your Mapbox SDK version requires a download token, add it to `~/.gradle/gradle.properties` (outside the project):
```properties
   MAPBOX_DOWNLOADS_TOKEN=your_secret_download_token
```

5. Open in Android Studio and run on a device or emulator. The project builds with JDK 21.

## Author

**Lemi Karobia Wambui** · [GitHub](https://github.com/Lemi897)
