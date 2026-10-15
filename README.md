# RouteOdo

Android navigation along a selected driving route using the vehicle odometer, assuming the driver follows that route. GPS is optional: it can be displayed for comparison or explicitly used to correct the alignment. Android 8.0+ (API 26), Kotlin, MapLibre, and OpenStreetMap data.

Version **0.5.0** makes everyday use map-first: live place search, saved-place shortcuts, an on-map route preview, and Start/Stop directly on the map. Day/night maps, independent GPS comparison, local storage, offline packs, and the existing odometer engine are retained. [Verification](docs/VERIFICATION_0.5.0.md).

## Find a place and drive

1. A fresh installation opens the Kyiv map. Tap **Where to?** and type an address, shop, café, landmark, or other place name. Suggestions update while typing; no Search button is required. Try `Сільпо` or `Хрещатик 22, Київ`.
2. Select a result. A fresh available GPS fix supplies **Your location** as the starting point. Without GPS, search the **Starting point** field, choose a saved place, or use **Choose on map**, then long-press the desired point. GPS is optional throughout.
3. Once both places are selected, the app calculates the route and returns to the map. Review the destination, estimated driving time (without traffic), distance, and origin. **Edit route** lets you change either place or swap them. A saved shortcut or map-picked pair can also be confirmed with **Preview route**.
4. Tap **Start** on the map. On first use, enter the car bearer token in the connection dialog and choose **Connect & start**. Later starts use the saved settings. The first fresh odometer reading establishes the new local session baseline.
5. Keep the app in the foreground. The calculated chevron follows the route as the odometer increases. **Stop** ends the session and returns to the browsing map. Reconnection in the same car epoch catches up; a runtime restart rebases and marks a distance gap.

The main map shows only the search/preview controls or, while navigating, the next maneuver, speed, remaining distance, Stop, compact GPS comparison, and actionable connection warnings. Dragging, rotating, or pinching suspends the calculated-position follow camera; **Recenter** restores it. In browsing mode, Recenter shows GPS if available or Kyiv; in route preview it fits the route.

**Menu (⋯)** provides New route, Saved places, GPS Sync/Undo during a drive, Offline maps, Appearance, Car connection, and Advanced tools. **Menu → Advanced tools** retains the detailed connection/position diagnostics, Align & calibration, retry, route import/export, provider settings, and simulation slider/Drive/±100 m controls. Every tools screen has a way back to the map.

To try a route without the car, choose **Advanced tools → Start simulation**. If no route is selected, this uses the bundled Kyiv example. Simulation has a persistent purple label and **Exit** on the map. Planning or previewing a real route does not start simulated movement.

Long-press the browsing map for **Directions here** or **Start here**. During navigation, manual alignment is also available. Alignment requires a fresh odometer reading in a running car session. No GPS correction happens automatically.

Choose **Menu → Appearance → System / Day / Night**. The setting, bundled styles/icons, and offline resource identities remain local and compatible with earlier installations. Your saved route and running session survive upgrades; a fresh installation does not open a demo drive.

## Saved places and live search

Tap **☆** beside a suggestion to save **Home**, **Work**, or any custom name. Saved places appear above search results and as shortcuts on the browsing map. They can supply either endpoint. Long-press a saved row in search to remove it; saving an existing name updates it.

Search starts after two characters, waits 400 ms after typing, and starts requests at least 1.1 seconds apart. It asks Photon for up to 15 nearby suggestions and caches matching query/location results for ten minutes in memory. Cancelled or older responses cannot replace the latest query. Results show names, categories, addresses, and straight-line proximity; availability and matching depend on OpenStreetMap/Photon. This is not Google's business catalogue and will not find every place Google Maps lists.

Saved names, addresses, and coordinates persist locally across app restarts and updates. Selecting a saved place requires no geocoding or internet, but calculating a new route still needs internet. Search errors leave saved-place choices available. There is no account, telemetry, trip history, or cloud synchronization.

## Find your position by voice

The map always shows **Удерживайте и говорите**. Hold it, describe what you see once, then release. The agent gives its best estimate and a confidence score in **Russian by default**. It uses the approximate position, heading, route, movement and nearby OSM places; “a supermarket on the right” is enough to attempt a match. Named places use Photon; category searches use Overpass. It must give a result before asking at most one optional confirmation. It does not require a new landmark alongside you or a preliminary interview.

The purple estimated position includes movement since you pressed Talk, and advances along the selected route as you drive. Tap the confidence/result row to review and optionally apply a suitable route correction with Undo, or use the area as a route starting point. Weak/off-route matches remain approximate. Confidence is a heuristic match score, not a calibrated GPS accuracy.

Configure your OpenAI API key once using the on-map gear or **Menu → Voice settings**. It is encrypted separately from the car token. Internet and a billed API account are required. Voice/location context go to OpenAI; searches and their approximate area go to Photon or Overpass. No audio is saved locally. The microphone records only while you hold the button; release submits, sliding away cancels, and backgrounding stops the session. Keyboard/TalkBack activation toggles recording. [Integration details and verification](docs/AI_LOCATION.md).

## Online maps and offline downloads

The map normally loads and caches data automatically over the internet. You do not need to download Kyiv before browsing it. These are map-data updates from the provider, not traffic updates or immediate propagation of every OpenStreetMap edit.

**Menu → Offline maps** provides two independent downloads:

| Pack | Coverage | Detail |
| --- | --- | --- |
| Route | About 750 m around the selected route, including nearby streets | Zoom 0–16 |
| Kyiv area | Rectangle from 50.21° to 50.62° N, 30.24° to 30.83° E, including much of the surrounding urban area | Zoom 0–15 |

Choose Download and wait for **Ready offline**. The screen shows progress and downloaded size. Each pack includes the required map style, labels/fonts, sprites, and tiles. Pause, resume, refresh, and removal are available independently. A pack pauses at 1 GB to avoid an accidental unlimited download; choose the route pack for a smaller download. Download size depends on the current map data.

Downloaded packs are retained separately from MapLibre's temporary browsing cache. The saved map continues to work without internet. The saved route geometry and distance calculation are also local. New address searches and route calculation need internet; the car feed works directly over the Pi’s Wi-Fi without internet, or through the public endpoint when internet is available. Without either connection, the marker holds.

**Refresh online map** revalidates the browsing cache and reloads the current map style. A pack's **Refresh** asks MapLibre to revalidate its resources and resumes downloading as needed. Saved resources are retained during refresh. Provider publishing and cache rules determine when newer data becomes available. Switching routes retains older downloaded route packs; a full pack library and automatic cleanup remain future work. Date-line-crossing route downloads are not supported.

## Odometer API

The public endpoint is preconfigured as `https://f10.skyspacex.com/api/odometer`. The app first tries `http://192.168.100.106:8088/api/odometer` with a one-second timeout, then falls back to the public URL. Both receive the same bearer header. LAN traffic binds to the Pi’s Wi-Fi when Android keeps mobile data as its default internet connection.

The app requests an initial JSON reading, then uses `/api/odometer/stream` when available. If streaming fails or is unsupported, it polls every 500 ms. A 401/403 stops automatic retries until you explicitly retry or update credentials. A 503 displays **Odometer unavailable**.

At **Start route**, the first fresh odometer anchor becomes the local baseline. Odometer increases are the ground truth; fresh speed smooths the position between anchors. The marker never moves backwards due to interpolation. When the car runtime’s `epoch` changes, the app carries confirmed distance forward, rebases the new odometer, and adds an orange gap marker. Distance during that restart gap is not estimated.

Acquisition ages use the response’s server `t`: speed older than one second stops interpolation; an odometer older than five seconds or `connected: false` holds a stale position. Ten seconds without sample responses displays **Car offline**, timed with Android’s monotonic elapsed-time clock. The phone’s UTC clock is never compared with car timestamps.

**Align** sets a calibration multiplier, default 1.0, saved for the configured vehicle endpoint. The same factor applies whether the connection uses LAN or internet. GPS Sync and Undo alter only the route alignment; they retain distance and epoch changes received afterward.

See [API.md](API.md) for the exact JSON contract, calculations, error behavior, and local test server.

## GPS comparison, manual sync, and undo

Allow **precise location while using the app** to see GPS debugging. The app uses the device's GNSS provider while foregrounded and stops the subscription when backgrounded. No background-location permission is requested. Denying location permission leaves odometer navigation available.

- **Blue:** the reported GPS coordinate, without snapping to a road.
- **Directional chevron:** the calculated position, rotated to the route bearing.
- **Blue dot and accuracy circle:** always at the fresh raw GPS coordinate, even within 50 m. An expanded blue ring remains visible under the chevron when their screen footprints overlap. A subtle line connects separated markers.
- **Older than five seconds:** GPS is hidden and marked stale until a fresh fix returns.

The compact comparison row shows separation and GPS accuracy in real time. Tap it for both coordinates and **Show both**, which fits both markers on screen. Recenter returns the camera to the calculated position. Reported accuracy never changes either coordinate. There is no geographic merge threshold.

**GPS Sync** uses an independent fresh GPS request and shows a route-alignment preview. Choose the intended route occurrence at crossings or loops, then Apply sync. Distance driven while the preview is open is retained. No correction happens automatically.

**Undo Sync** restores the prior alignment and adds odometer distance driven since the correction. One level survives app restart within the same local navigation session. A new sync replaces it. Starting/stopping a route, changing route or mode, manual alignment, and preview seeking clear it.

Example: progress is 2,000 m, GPS Sync changes it to 2,300 m, and then you travel another 100 m. Undo puts the marker at **2,100 m**.

GPS fixes and API responses arrive independently, so correction accuracy also depends on API latency and odometer resolution. GPS debug data is not sent to the odometer API. Using GPS as route start sends the selected coordinate to the routing service when you calculate; address queries go to the geocoder. Online map requests identify the viewed area to the map provider.

## Services and build

Default services need no API keys for light personal testing:

- [Photon](https://photon.komoot.io/) for live address and place suggestions. Searches are debounced, throttled, cancellable, and cached for ten minutes. Results favor the chosen origin or map area; include the city to disambiguate. The public service offers no availability guarantee and limits extensive use.
- [OSRM](https://project-osrm.org/docs/v5.24.0/api/) for driving routes. Its public server is for testing and community use.
- [OpenFreeMap](https://openfreemap.org/) for the OpenStreetMap-derived vector map, rendered by [MapLibre Native](https://maplibre.org/maplibre-native/android/api/).

Change Photon and OSRM base URLs under **Menu → Advanced tools → Route & saved places → Search & routing services**. Use suitable hosted or self-managed services before wider deployment. Odometer credentials are not sent to these services. OpenStreetMap attribution remains available on the map and in the address form.

Use Android Studio with JDK 17 and SDK 36, or:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Enter the bearer token through the API screen. Tokens are encrypted using an Android Keystore AES-GCM key, are not embedded in source or APK defaults, and are never logged or placed in URLs. App backup is disabled. Release builds allow HTTPS plus plain HTTP only to the configured car LAN host; debug builds additionally permit unauthenticated local HTTP test servers. The car LAN endpoint is the only HTTP destination allowed to receive the bearer token.

## Verification and remaining scope

The unit and HTTP tests cover odometer baselines, nullable startup samples, epoch rebasing and gap markers, server-time staleness, interpolation and monotonic holds, calibration, GPS rollback across epoch changes, authentication retry latching, SSE and polling fallback, saved places, stale search selections, actual Ukrainian address parsing, query encoding/caching, route geometry, persistence, independent GPS display/staleness, appearance persistence, and gesture/follow camera state. The included Kyiv route and address fixture were obtained from OSRM and Photon on 10 September 2026.

Android 15 emulator checks cover an in-place upgrade with the old route pack, offline day/night rendering, independent and overlapping GPS markers, stale GPS, camera fitting, Advanced tools safe areas, and portrait/landscape simulation driving. See [driving UI notes](docs/DRIVING_UI.md).

On your phone/tablet, verify: address selection and GPS start; saved Home/Work after restart; Start route with the real odometer; stationary values; network loss/recovery; GPS Sync and Undo after movement; both offline packs; portrait/landscape layouts; independent markers and recenter; and a cold launch without internet after downloads report ready. Real GPS hardware, Pi connectivity, and driving behavior still need validation on your phone/tablet and BMW.

This version has English controls and maneuver text, with native road names. Turn-by-turn spoken guidance, background navigation, fully offline route calculation, offline address indexing, traffic information, and automatic deviation detection are not implemented.

[OpenStreetMap attribution](https://www.openstreetmap.org/copyright) · [Photon API and usage](https://github.com/komoot/photon) · [OSRM service policy](https://github.com/Project-OSRM/osrm-backend/wiki/Api-usage-policy) · [MapLibre offline regions](https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.offline/-offline-region/index.html)

## Experimental GPS-free road prediction

**Menu → Experimental navigation → Predict roads and recalculate offline** enables the first experimental navigation module. It defaults to off and has not been validated while driving. Mount the phone securely; moving the phone independently of the vehicle corrupts turn measurements. Set a known starting position and route direction. The module uses gravity-projected gyroscope rotation and calibrated **confirmed odometer** increments; it does not use GPS, compass heading, or speed interpolation as prediction input.

Import a local road graph using **Import offline road graph (JSON)** before enabling it. Display map tiles are not a routing graph. Version 1 files contain `[longitude, latitude]` nodes and directed `[fromNodeIndex, toNodeIndex]` edges:

```json
{"version":1,"nodes":[[30.0,50.0],[30.0,50.001],[30.002,50.001]],"edges":[[0,1],[1,0],[1,2],[2,1]]}
```

Each edge is a straight drivable segment; split curved roads into segments. Junctions connect only through shared node indices. Crossings on bridges must use separate nodes. Exporters must omit prohibited directions and encode legal connections in topology. This initial format has no separate turn-restriction, lane, vehicle-access, or roundabout model, so graphs with such restrictions need a richer format before real navigation use. Import is limited to 10 MB, 50,000 nodes, and 100,000 directed edges. The last imported graph is stored locally and remains available offline. No automatic map-provider road-graph download is implemented.

A bounded candidate search starts within 50 m and 60° of the known route position/direction. Odometer distance advances candidates through connected roads, and relative gyro heading ranks possible turns. A matching measured turn can snap a moving candidate to a junction up to 25 m ahead to accommodate small odometer errors. Close alternatives retain low confidence. A sufficiently distinct candidate permits local shortest-distance recalculation to the original destination, at most once every five seconds, with geometry-derived left/right/turn-around instructions. The destination must be within 100 m of a reachable graph node. These confidence scores are heuristic, not calibrated probabilities.

When prediction is enabled, uncertain estimates suppress maneuver guidance. Missing roads, unavailable motion, odometer discontinuities, or a jump over 500 m require alignment. Backgrounding stops sensors; resuming a running session requires **Advanced tools → Align** (or explicit GPS Sync). The predictor is deliberately not persisted across restarts. Odometer totals survive recalculation; the new route starts at the predicted point. Turn recognition, sensor bias, phone movement, coarse odometer timing, and similar parallel roads remain live-testing limitations. Disable the option to return to selected-route odometer tracking.
