# Fullscreen driving interface

## Existing flows inventoried before implementation

| Existing entry point | Function retained | New entry point |
| --- | --- | --- |
| Route toolbar/menu | Plan route, saved places, current GPS for A, service URLs, fit route, start from zero, Kyiv example, import/export | Services → Route & saved places |
| Map long press | Set start, set destination, manual alignment | Map long press; Services explains this shortcut |
| API toolbar | Endpoint, encrypted bearer token, LAN-first, connect, Preview | Services → API & connection; Preview controls |
| Start/Stop buttons | Fresh local baseline, cancel pending start, stop navigation | Services → Start/Stop route |
| GPS Sync/Undo | Fresh fix, route-occurrence preview, apply, one-level undo | Services → GPS Sync / Undo Sync |
| Align toolbar | Route-distance correction and vehicle calibration | Services → Align & calibration |
| Preview panel | Slider, simulated Drive/Pause, ±100 metres | Services → Preview controls |
| Maps toolbar | Route/Kyiv packs, download/resume, pause, refresh/delete, coverage, online refresh | Services → Offline & online maps |
| GPS strip | Raw/calculated coordinates, accuracy, separation, permissions, settings, Retry, Show both | Driving comparison card; Services → Position details |
| Follow button | Calculated-position following | Floating recenter button; gestures suspend follow |
| Connection/map strips | API status, map status, gap count | Compact driving status plus Services diagnostics |

The positioning engine and car transport are unchanged. New responsibilities live in focused UI, appearance, and map-rendering classes.

## Offline resource compatibility

RouteOdo day/night JSON is bundled in `app/src/main/assets/styles` and loaded with MapLibre `Style.Builder.fromJson`. Styles are original application assets, using the existing OpenFreeMap vector source and the same Noto Sans font stacks. POI sprites and terrain imagery are omitted.

Offline packs retain the existing OpenFreeMap Liberty download definition as a resource manifest. It downloads a superset of the tiles/fonts used by the bundled styles. Pack IDs, metadata, geometry, zoom ranges, and the MapLibre database remain unchanged. No `asset://` URL is passed to the offline downloader; this avoids depending on native offline support for application asset URLs. Both appearances use the same cached resources.

## Display semantics

GPS is independent from odometer navigation. A blue dot and geographic accuracy circle stay at the raw fix; a route-bearing chevron stays at the calculated point. No distance threshold merges coordinates or removes a fresh GPS feature. When their projected screen footprints overlap, the GPS ring expands so it remains visible underneath the chevron. Stale fixes are removed from live map layers after five seconds and labeled explicitly.

`ODO + GPS` means odometer navigation with a current GPS comparison, not sensor fusion. GPS Sync remains manual. Preview is explicitly labeled and its displayed speed is simulated.

Appearance selection (System / Day / Night) is stored in local preferences. Services provides all configuration and a clear Back to map action. Manual gestures suspend following; Recenter follows the calculated point at a 48-degree tilt and driving zoom.

## Verification notes

The Android 15 emulator upgrade check installs 0.3.0, downloads its 12 MB Kyiv example route pack, disables both Wi-Fi and mobile data, and upgrades in place. Both bundled appearances render the existing streets and glyphs without internet. No map database or stored route is cleared.

UI checks use controlled emulator GPS fixes (including 83.4 m separation, exact overlap, and ±22 m accuracy). They do not add a mock-location feature to the application. Expired fixes disappear from live layers and display GPS stale. Dragging suspends follow; Recenter restores it. Camera fitting includes overlay insets and GPS accuracy bounds. A new Kyiv-area download completed at 33 MB on 0.4.0. The existing local mock API also verified SSE-driven ODO + GPS navigation at 50 km/h, changing separation, and continuous following. Native window inset handling is applied to both driving and service screens.

Navigation/transport tests are retained. New tests cover independent GPS display, permission/stale states, appearance persistence, follow transitions/throttling, marker footprint overlap, offline resource compatibility, preview labeling, and API error labels. Screenshots and the final validation record accompany the debug APK.

The SDK uses the same camera-animation reason for compass clicks and application camera transitions. Follow is suspended on map gestures and explicit compass touch, while application-driven transitions retain it.

See [verification record](VERIFICATION_0.4.0.md) for build results and screenshots.
