# FlightLog

FlightLog is an offline-first Android mountain-bike ride recorder. It stores GPS tracks locally, derives speed and distance, detects likely jumps from phone motion sensors, and lets the rider review estimates before they count toward statistics.

## Run

1. Open the project in the current stable Android Studio.
2. Select a physical device or API 29+ emulator.
3. Optionally provide an app-wide Thunderforest key by adding `THUNDERFOREST_API_KEY=your_key` to the untracked `local.properties` file. Riders can also bring their own key from Settings. Without either key, FlightLog shows the offline route canvas and an actionable map warning.
4. Run the `app` configuration and grant precise location and notification access.

The debug APK is also generated at `app/build/outputs/apk/debug/app-debug.apk` by:

```shell
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug
```

## Architecture

- Jetpack Compose single-activity UI
- Room database for rides, accepted track points, and reviewed jump events
- User-started location foreground service with persistent Pause and Finish actions
- MapLibre Native with selectable Thunderforest OpenCycleMap or Clean terrain tiles, a user-bounded rolling viewed-tile cache, a network-free fallback, and route/jump overlays
- GPX route and CSV jump export through Android's scoped `FileProvider`
- Spatial trail matching with suggested/confirmed trails and editable turn, rough-area, and manual sections
- Trend, two-run A/B, and theoretical virtual-best comparisons based on materialized 5 m profiles
- Versioned full-history `.flightlog.zip` backup/import with stable IDs and validated telemetry checksums

## Ride history and telemetry

During a ride, accepted GPS fixes remain ordinary Room rows so the live map can update cheaply. After completion, FlightLog delta-encodes and DEFLATE-compresses GPS data into checksummed, versioned chunks, creates permanent 5 m speed/quality profiles, and removes the redundant point rows. Accelerometer and gyroscope samples are recorded directly into compressed chunks rather than individual database rows. Raw motion is retained for 90 days after a permanent profile has been created; compressed GPS, profiles, trail passes, and section efforts are retained until app data is removed.

Trail passes are matched spatially and by direction, independently of the ride Start and Finish controls. Partial passes are supported. A stationary span or long GPS gap invalidates the affected section rather than the entire ride. Bike-mounted recordings expose a relative roughness score. Pocket recordings expose a lower-confidence rider-disturbance score and are never ranked against bike-mounted roughness.

Full backups are user-initiated and account-free. They include sensitive location history and are not encrypted by FlightLog, so they should be stored only in a trusted location. Import validates archive paths, size limits, schemas, numeric ranges, stable identifiers, and telemetry checksums before committing the transaction.

An app-wide API key can be injected into `BuildConfig` from `local.properties` or the `THUNDERFOREST_API_KEY` environment variable and is never committed by this project. A rider-provided key overrides it, is kept in app-private preferences with backups disabled, and is never included in ride exports. The app does not prefetch or redistribute map tiles. When the provider, key, or network is unavailable, a warning is shown and route recording continues over the neutral fallback canvas.

MapLibre's ambient cache retains tiles encountered during normal map use and reuses them while offline subject to provider cache headers. The rolling limit defaults to 250 MB and can be adjusted from 50 MB to 1 GB. Settings shows current database size and successful Thunderforest network tile loads for the current calendar month; it never stores tile coordinates or request URLs in usage metrics. Riders can clear the cache from Settings. Changing or removing a rider-provided key clears cached tile request records so the old credential is not retained in the cache database.

## Estimation limits

FlightLog provides separate Pocket and Bike-mounted profiles. Pocket mode tolerates fabric and body movement when the phone is carried in a snug front or zippered pocket; mounted mode expects a firmly secured phone and a cleaner sensor signal. Each profile has its own minimum estimated jump-height control. Loose cargo pockets and backpacks can let the phone move independently and cause false detections. A sustained low-acceleration interval followed by a landing impulse creates a pending jump; excessive in-flight rotation lowers its confidence. Flight time is the detected interval, relative height is estimated with `g × airtime² ÷ 8`, and horizontal distance is takeoff speed × airtime. Terrain, clothing, body movement, drops, GPS error, and non-symmetrical trajectories can materially affect results, so estimates require rider review.

## Verification

```shell
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```
