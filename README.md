# RouteOdo

Android navigation along a selected driving route using the vehicle odometer, assuming the driver follows that route. GPS is optional: it can be displayed for comparison or explicitly used to correct the alignment. Android 8.0+ (API 26), Kotlin, MapLibre, and OpenStreetMap data.

Version **0.4.0** adds a fullscreen driving interface, bundled day/night maps, a directional calculated-position chevron, and an independent raw GPS dot with an accuracy circle. All car transport and positioning behavior from 0.3 is retained. [UI design and preserved flows](docs/DRIVING_UI.md).

## Driving screen and Services

The map fills the screen behind a large maneuver card, a compact GPS comparison, and the speed/source/remaining-distance panel. The blue route has a dark outline; its travelled portion is muted. Follow uses calculated route bearing at a 48° tilt. Dragging, rotating, or pinching the map suspends follow; **Recenter** restores it. GPS never controls the follow camera.

**Services** opens a separate screen with **Back to map** at the top. All prior controls remain available there: Route & saved places, Start/Stop route, GPS Sync/Undo, Align & calibration, API & connection, Retry, API diagnostics, Offline & online maps, Position details / Show both, Appearance, and Preview with its slider/Drive/±100 m controls. Map long-press start/destination/alignment selection still works.

Choose **Services → Appearance → System / Day / Night**. The choice persists locally. Styles and new application icons are bundled with the APK. Existing map pack identities and their resource manifests are retained, so day/night appearance shares the same offline tile/font storage. Neither style needs a RouteOdo style server.

The source label **ODO + GPS** means odometer positioning with live GPS displayed for comparison; it does not mean automatic GPS correction. **GPS stale**, **CAR OFFLINE**, **API CONNECTING**, and **PREVIEW** are explicit states. Unknown/stale speed shows a dash. Preview speed is simulated and labeled as such.

## Plan and test a real route

1. Install the latest debug APK over the existing app. On first use, the map shows a real 6.1 km Kyiv driving route from the railway station area to Podil. Updating an old Berlin demo switches it to Kyiv; your own saved route is retained.
2. Choose **Services → Route & saved places → Plan route / saved places**. Type a street, number, and city for **A**, press Search, and select the matching result. For example: `Хрещатик 22, Київ`. You can instead use **Use current GPS for A** or pick a previously saved place.
3. Search for **B** and select it. For example: `Володимирська 60, Київ`. Search results include the full address so you can distinguish matching streets in different cities. Search supports local names and Latin spellings; coverage depends on OpenStreetMap.
4. Press **Calculate route** and review it on the map. Route calculation uses real road geometry and driving maneuvers. The app saves the route automatically.
5. Open **Services → API & connection**, enter your bearer token, and keep the prefilled public endpoint and **Try car LAN first** enabled. At the route start, press **Services → Start route**. It obtains a fresh reading and begins a local navigation session at route distance zero. Connecting the API alone does not move the marker.
6. Keep the app in the foreground. Odometer increases move the green marker and update the next-turn display. **Stop route** ends navigation; Start route again establishes a new baseline. A running session resumes across app restarts. Reconnection in the same car epoch catches up from the odometer; a car runtime restart rebases and marks a distance gap.

You can inspect any calculated route without the vehicle: open **Services → Preview**, then use Drive, the slider, or +100 m. The PREVIEW badge distinguishes simulated movement from the actual odometer feed. Planning a real route does not require using the simulator.

Long-pressing the map still offers **Set route start**, **Set destination**, and **Align marker here**. **Align** accepts metres along the route and a calibration multiplier from 0.5 to 1.5. API mode requires a started route and a fresh odometer reading for alignment.

## Saved places

After selecting an address or GPS start, press **Save address** and name it **Home**, **Work**, or any custom name. **Saved places** is available for both A and B. Saving the same name updates that place. The saved-place picker also allows removal.

Names, addresses, and coordinates stay on the device across app restarts and updates. Choosing a saved place needs no geocoding request and works offline. Calculating a new driving route between saved places still needs internet. There is no Google account or cross-device synchronization in this version.

## Online maps and offline downloads

The map normally loads and caches data automatically over the internet. You do not need to download Kyiv before browsing it. These are map-data updates from the provider, not traffic updates or immediate propagation of every OpenStreetMap edit.

**Services → Offline & online maps** provides two independent downloads:

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

The comparison card shows separation and GPS accuracy in real time. Tap it for both coordinates and **Show both**, which fits both markers on screen. Follow returns the camera to the calculated position. Reported accuracy never changes either coordinate. There is no geographic merge threshold.

**GPS Sync** uses an independent fresh GPS request and shows a route-alignment preview. Choose the intended route occurrence at crossings or loops, then Apply sync. Distance driven while the preview is open is retained. No correction happens automatically.

**Undo Sync** restores the prior alignment and adds odometer distance driven since the correction. One level survives app restart within the same local navigation session. A new sync replaces it. Starting/stopping a route, changing route or mode, manual alignment, and preview seeking clear it.

Example: progress is 2,000 m, GPS Sync changes it to 2,300 m, and then you travel another 100 m. Undo puts the marker at **2,100 m**.

GPS fixes and API responses arrive independently, so correction accuracy also depends on API latency and odometer resolution. GPS debug data is not sent to the odometer API. Using GPS as route start sends the selected coordinate to the routing service when you calculate; address queries go to the geocoder. Online map requests identify the viewed area to the map provider.

## Services and build

Default services need no API keys for light personal testing:

- [Photon](https://photon.komoot.io/) for address lookup. Searches occur only when you press Search, are throttled locally, and repeated queries are cached for ten minutes. Results favor Kyiv but can include other cities; select the intended address. The public service offers no availability guarantee and limits extensive use.
- [OSRM](https://project-osrm.org/docs/v5.24.0/api/) for driving routes. Its public server is for testing and community use.
- [OpenFreeMap](https://openfreemap.org/) for the OpenStreetMap-derived vector map, rendered by [MapLibre Native](https://maplibre.org/maplibre-native/android/api/).

Change Photon and OSRM base URLs under **Services → Route & saved places → Plan route / saved places → Search and route service settings**. Use suitable hosted or self-managed services before wider deployment. Odometer credentials are not sent to these services. OpenStreetMap attribution remains available on the map and in the address form.

Use Android Studio with JDK 17 and SDK 36, or:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Enter the bearer token through the API screen. Tokens are encrypted using an Android Keystore AES-GCM key, are not embedded in source or APK defaults, and are never logged or placed in URLs. App backup is disabled. Release builds allow HTTPS plus plain HTTP only to the configured car LAN host; debug builds additionally permit unauthenticated local HTTP test servers. The car LAN endpoint is the only HTTP destination allowed to receive the bearer token.

## Verification and remaining scope

The unit and HTTP tests cover odometer baselines, nullable startup samples, epoch rebasing and gap markers, server-time staleness, interpolation and monotonic holds, calibration, GPS rollback across epoch changes, authentication retry latching, SSE and polling fallback, saved places, stale search selections, actual Ukrainian address parsing, query encoding/caching, route geometry, persistence, independent GPS display/staleness, appearance persistence, and gesture/follow camera state. The included Kyiv route and address fixture were obtained from OSRM and Photon on 10 September 2026.

Android 15 emulator checks cover an in-place upgrade with the old route pack, offline day/night rendering, independent and overlapping GPS markers, stale GPS, camera fitting, Services safe areas, and portrait/landscape Preview driving. See [driving UI notes](docs/DRIVING_UI.md).

On your phone/tablet, verify: address selection and GPS start; saved Home/Work after restart; Start route with the real odometer; stationary values; network loss/recovery; GPS Sync and Undo after movement; both offline packs; portrait/landscape layouts; independent markers and recenter; and a cold launch without internet after downloads report ready. Real GPS hardware, Pi connectivity, and driving behavior still need validation on your phone/tablet and BMW.

This version has English controls and maneuver text, with native road names. Voice guidance, background navigation, fully offline route calculation, offline address indexing, traffic information, and automatic deviation detection are not implemented.

[OpenStreetMap attribution](https://www.openstreetmap.org/copyright) · [Photon API and usage](https://github.com/komoot/photon) · [OSRM service policy](https://github.com/Project-OSRM/osrm-backend/wiki/Api-usage-policy) · [MapLibre offline regions](https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.offline/-offline-region/index.html)
