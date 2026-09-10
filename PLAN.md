# RouteOdo implementation plan and decisions

Updated 11 September 2026. README.md describes the app; API.md is the current firmware contract.

## Confirmed design

- Native Android phone/tablet app, Android 8.0+.
- OpenStreetMap-derived maps through MapLibre/OpenFreeMap, online by default, with route and Kyiv offline packs.
- Photon street-address lookup, optional GPS starting point, and locally saved Home/Work/custom places.
- OSRM driving routes calculated online and saved locally. A real Kyiv route is bundled for first-use testing.
- Progress is distance along the chosen route, assuming the driver follows it. Geometry supplies the calculated coordinate and route bearing.
- Fullscreen driving overlays, a separate Services screen, bundled day/night styles, and a route-bearing chevron with independent raw GPS dot/accuracy circle.
- The car provides metre-resolution odometer anchors and speed, plus acquisition timestamps and a runtime epoch. No car-side navigation sessions, coordinates, heading, or VIN.
- LAN-first authenticated GET/SSE transport, one-second LAN timeout, public fallback, and 500 ms polling fallback when streaming is unavailable.
- Start/Stop route controls a navigation session entirely in the app.
- Manual GPS Sync and Undo change alignment. The independent raw GPS debug marker remains independent at every distance; an overlap ring keeps both sources visible.

## Calculation and persistence

Confirmed travel is the odometer increase from a local baseline plus confirmed distance carried across car runtime restarts. A changed epoch rebases and marks a gap; outage movement is not guessed. Within an unchanged epoch, a later counter reading recovers distance after a network gap.

Speed smooths display between anchors using server-relative sample ages. Display is monotonic. A new anchor behind an estimate holds the marker until a later anchor catches up. Stale speed disables interpolation; stale odometer or ECU disconnection freezes progress. Ten seconds without samples shows car offline. Authentication errors halt automatic retries.

The route, local session, epoch/baseline, confirmed and displayed distances, gaps, calibration, and GPS undo state persist. The calibration factor defaults to 1.0 and is stored for the configured vehicle endpoint. Tokens use encrypted device preferences and are absent from repository/APK defaults.

Older saved counter formats retain route/alignment where possible but require a fresh navigation baseline. The bundled Berlin example migrates to Kyiv.

## Current delivery

Version 0.4 reorganizes presentation while preserving the 0.3 car transport, odometer engine, GPS Sync/Undo, and offline storage. See docs/DRIVING_UI.md for the control inventory and display semantics.

1. Address/GPS/saved-place route preparation and persisted driving geometry.
2. Firmware JSON/SSE integration, local navigation sessions, epoch gaps, interpolation, staleness, and calibration.
3. GPS comparison, manual correction, and rollback including subsequent epoch changes.
4. Online maps plus route/Kyiv downloads, pause/resume, refresh, progress, and removal.
5. Configured car endpoints, encrypted runtime token entry, matching mock server, and unit/HTTP tests.

## Device and first-drive validation

- Enter the real bearer token and test Pi hotspot access with mobile data still enabled, then public-only access.
- Verify starting stationary, nullable startup readings, creeping, ordinary network loss/recovery, car runtime restart, app restart, and stop/new start.
- Confirm 401 stops retries, 503 is visible, and unavailable streaming uses polling.
- Exercise Ukrainian street searches, saved Home/Work, fresh GPS start, and route preparation without GPS.
- Check cold offline map launch after each pack reports ready, including labels and route geometry.
- Test GPS Sync/Undo after additional travel and confirm raw GPS display never changes progress automatically.
- Compare accumulated odometer distance with a trusted GPS track to determine the vehicle calibration factor; it has not yet been measured.

## Later work

Voice guidance, background navigation, downloaded-pack library/cleanup, fully offline regional routing/address search, and broader device UX testing. These are not required by the present car API contract.
