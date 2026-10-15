# Navigation road downloads verification

Verified 16 October 2026 (Europe/Kiev).

The experimental switch defaults to off. With a selected route and internet, enabling it downloads missing navigation roads automatically. The same Download/Refresh/Cancel controls are available in Experimental navigation and Offline maps. Downloaded graphs retain bounds, age, one-way connections, and supported turn restrictions across restart. Failed downloads leave the previous saved graph intact. Background recalculation preserves odometer distance received while finding a path and rejects results from an older predictor/session.

## Automated checks

- 103 unit tests passed, zero failures, errors, or skips.
- `:app:assembleDebug` passed.
- `:app:lintDebug` passed (zero errors/fatal findings; existing warnings remain).
- `git diff --check` passed.

New tests cover OSM access filtering, mode overrides, one-way/reverse/roundabout directions, grade-separated crossings, no-turn/only-turn/U-turn restrictions, conservative conditional and via-way restrictions, missing geometry and partial-response rejection, graph metadata/restriction round trips, projected destinations between road nodes, HTTPS downloader request shape without credentials, busy-service fallback, cancellation before and during requests, oversized responses, coverage bounds, and route connectivity validation. The original road-prediction tests also pass.

The build ran from `/private/tmp/routeodo-navigation-build` with SDK 36 and the existing cached Gradle runtime. The installed JetBrains JDK hit a C1 JIT crash during the initial lint run. Re-running with `-XX:TieredStopAtLevel=1`, a 2 GB heap, and two Gradle workers completed successfully; repository build settings were not changed.

## Live service limitations

A small live download near the bundled Kyiv route was attempted separately. The main Overpass service timed out; the Private.coffee mirror returned a busy-server response. These checks did not establish a successful live download. The default downloader now permits one mirror attempt on network errors, HTTP 429, or HTTP 5xx, with cancellation and no further automatic retry loop. Service availability remains outside the app's control. No real-driving or device sensor validation was performed for this change.

## Installable artifact

`dist/RouteOdo-0.5.0-navigation-debug.apk`

SHA-256: `a5dfc10e95163a60aec75f042325df1ceaac2c30ea3c25c891d7218047fdbbb1`

Usage: install the APK, select a route online, enable **Menu → Experimental navigation → Predict roads and recalculate offline**, and wait for **Ready offline**. If both services are busy, retry **Download navigation roads** later. Map display packs are downloaded separately.
