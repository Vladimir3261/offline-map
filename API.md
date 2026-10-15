# Vehicle odometer API — RouteOdo 0.3

This is the firmware contract supplied on 10 September 2026. The car owns its odometer counter and runtime epoch. The Android app owns navigation sessions, routes, alignment, and distance along the route. Diagnostic sessions on the car have no effect on navigation.

## Endpoints and authentication

- Public: `https://f10.skyspacex.com/api/odometer`
- Car Wi-Fi / Pi hotspot: `http://192.168.100.106:8088/api/odometer`
- Optional SSE: append `/stream` to either URL.

```http
GET /api/odometer HTTP/1.1
Authorization: Bearer YOUR_TOKEN
Accept: application/json
Cache-Control: no-cache, no-store
```

Try LAN first with a one-second total timeout, then the public endpoint. The token is identical on both. The Android transport binds LAN sockets to the Pi’s Wi-Fi network when mobile data is the system’s default connection. After a LAN failure, polling retries it at most once every 30 seconds. A connection restart also probes LAN first.

The app obtains an initial GET sample, then attempts SSE. If unavailable, it returns to GET polling with a 500 ms delay between completed requests. There are no overlapping polls. Streaming is retried after a 30-second backoff. An SSE connection without sample data for ten seconds falls back to polling, including when keepalive comments still arrive.

## JSON response

Return `200`, `Content-Type: application/json`, and `Cache-Control: no-store`:

```json
{
  "t": 1789060085.784,
  "epoch": "9f3a1c6e",
  "odometer_m": 37104,
  "odometer_t": 1789060085.364,
  "speed_kmh": 47,
  "speed_t": 1789060085.751,
  "connected": true,
  "clock_synced": true,
  "resets": 0,
  "source": "n47d_dist_since_regen",
  "mapping_ver": 5
}
```

| Field | Meaning |
| --- | --- |
| `t` | Server UTC epoch seconds when this response was built, including fractional seconds. The reference clock for acquisition freshness. |
| `epoch` | Nonempty random runtime ID. Changes when the car runtime restarts, independently of navigation or diagnostic sessions. |
| `odometer_m` | Nonnegative integer metres, monotonic within one epoch. This is an accumulated runtime counter, not necessarily the vehicle’s lifetime odometer. |
| `odometer_t` | Acquisition time of the odometer anchor, normally updated about once per second. |
| `speed_kmh` | Nonnegative integer km/h, acquired at about 10 Hz. Used only for interpolation. |
| `speed_t` | Acquisition time of the speed sample. |
| `connected` | Whether the ECU link is up. When false, values may be last-known samples. |
| `clock_synced` | Car clock synchronization status. Does not substitute the phone clock for `t`. |
| `resets` | ECU counter resets already absorbed by the firmware. Does not itself trigger an app rebase. |
| `source`, `mapping_ver` | Firmware diagnostics describing the distance mapping. |

The four odometer/speed value and acquisition-time fields can be JSON `null` during startup. Zero is a valid measurement, not a missing-value sentinel. The app validates integer values, finite timestamps, and a maximum JSON response size of 64 KB. A decreasing counter within the same epoch is a contract violation and holds progress until a new epoch or manual Align.

The API has no navigation session ID, sequence requirement, route storage, history, latitude/longitude, heading, or VIN. Its internal ECU reset handling remains entirely on the car.

## Streaming

Use the same bearer header and `Accept: text/event-stream`. Every new sample is a complete JSON object:

```text
data: {"t":1789060085.784,"epoch":"9f3a1c6e","odometer_m":37104,"odometer_t":1789060085.364,"speed_kmh":47,"speed_t":1789060085.751,"connected":true,"clock_synced":true,"resets":0,"source":"n47d_dist_since_regen","mapping_ver":5}

: keepalive

```

Send at most ten samples per second. Keepalive comments every 15 seconds are transport activity, not fresh measurements. Repeated odometer values are expected between anchors and while stationary.

## Local navigation calculation

In version 0.5, Start/Stop are directly on the map. GPS Sync/Undo and Car connection are in **Menu**; calibration, diagnostics, and simulation are under **Menu → Advanced tools**. The protocol and navigation engine remain unchanged.

**Start route** creates a local navigation session. Its first connected, fresh, non-null odometer sample becomes `base_epoch` and `base_m`, with progress zero. Connecting the API alone does not start navigation.

```text
confirmed_travel = carried + (odometer_m - base_m)
```

Initially `carried = 0`. On an epoch change, preserve the accumulated confirmed travel in `carried`, then take the first usable anchor in the new epoch as `base_m`. It is a new origin, not movement. Put an orange gap marker at the current route position; metres driven during that restart gap are unknown. Keep the displayed position held if its last interpolated value was ahead of confirmed travel. An ordinary network outage with an unchanged epoch can recover from the next odometer reading.

Between anchor updates:

```text
age = t - odometer_t
estimate = confirmed_travel + (speed_kmh / 3.6) * age
displayed_travel = max(estimate, last_displayed_travel)
```

A new anchor snaps forward when ahead. If behind the displayed estimate, hold until a later anchor catches up; do not continue extrapolating while waiting for it. A new acquisition timestamp identifies a new anchor even when the integer distance is unchanged. Speed zero never prevents an increasing odometer from moving the marker. There is no additional speed filtering.

```text
distance_along_route = route_anchor
                     + scale * (displayed_travel - travel_anchor)
```

At route start both alignment anchors are zero. The default scale is 1.0; **Menu → Advanced tools → Align & calibration** allows 0.5–1.5 and persists it by the configured vehicle endpoint, shared across LAN/public transport. GPS Sync sets new route/travel alignment anchors. Undo restores the previous alignment while retaining all subsequent distance and car epoch changes. Route geometry converts distance along the polyline to the calculated map coordinate and route bearing. The car API supplies neither.

The route, local session, epoch/baseline, carried distance, display hold, gap markers, scale, and undo snapshot persist. Stream-driven saves are coalesced to once per second; explicit alignment and lifecycle changes save immediately. An app restart resumes a running local session. **Stop route** freezes navigation; another Start route creates a new baseline at route distance zero.

## Freshness and failures

All acquisition ages use `t` from the latest response, never the phone’s wall clock. Interpolation advances only when a new response supplies a server time.

| Condition | Behavior |
| --- | --- |
| `t - speed_t > 1.0 s`, or speed/timestamp null | No speed interpolation. Hold between anchors; fresh odometer anchors can still advance. |
| `t - odometer_t > 5 s`, odometer/timestamp null, or `connected == false` | Hold; mark stale or waiting for first odometer sample. |
| Acquisition timestamp later than `t` | Treat that sample as unusable. |
| No usable sample response for 10 seconds | Show **Car offline** and hold. Silence duration uses Android monotonic elapsed time, not UTC. |
| 401 or 403 | Stop requests and show authentication failure. Explicitly update credentials or press Start route to retry. No automatic retry loop, including foreground resume. |
| 503 | Show **Odometer unavailable**; retry on the normal polling interval. |
| 502, other HTTP errors, or connection failures | Hold and retry; show connection unavailable, then car offline after silence. |
| Invalid successful JSON | Hold and show invalid odometer response. |

## Token storage

Both endpoint defaults are included in the app. Enter the token in **Menu → Car connection → Bearer token → Save connection**. It is stored as AES-GCM encrypted preferences using an Android Keystore key. It is never placed in URLs, logged, sent to address/routing/map providers, or embedded in source/APK defaults. App backup is disabled. Redirects are rejected.

Bearer tokens are allowed over HTTPS or exactly the car’s HTTP host/port/path above (including its stream). Release Android network configuration permits cleartext only to that LAN host. Debug builds also permit unauthenticated local HTTP test servers.

## Local test server

```sh
python3 tools/mock_api.py --speed-kmh 50 --odometer-m 37104
```

The server implements the same JSON and SSE contract, with one-second integer anchors and 10 Hz speed. Every process launch generates a new epoch. Restart it mid-route to test rebasing and orange gap markers; no new app session is needed.

For the Android emulator, set the API URL to `http://10.0.2.2:8765/api/odometer`, leave the token empty, and disable **Try car LAN first**. For a USB device:

```sh
adb reverse tcp:8765 tcp:8765
```

Then use `http://127.0.0.1:8765/api/odometer`. The mock is explicitly simulated; it has no OBD connection. Options:

- `--speed-kmh 0`: stationary anchors.
- `--initial-delay 3`: nullable values during startup.
- `--no-stream`: 404 on SSE, exercising polling fallback.
- `--disconnected`: frozen last-known values with ECU link down.
- `--unavailable`: 503 on both endpoints.

For command-line authentication tests, an optional `ROUTEODO_MOCK_TOKEN` environment variable enables bearer checks. Do not enter an authenticated arbitrary HTTP mock URL in the app; its only permitted bearer-over-HTTP endpoint is the car LAN address.
