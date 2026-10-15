# Everyday voice location

The map always shows **Удерживайте и говорите** in browsing, route preview and navigation. Hold, describe what you see once, and release. The agent estimates immediately from that report, speaks a short Russian result with a confidence score, and shows the best match on the map in purple. It may then offer one optional confirmation question. No preliminary interview or demand for a current landmark is required. Speaking place names in another language does not change the default Russian response language; an explicit spoken request can change it.

The microphone records only during a deliberate press. Release sends one complete audio turn. Moving a finger away, Android touch cancellation, Close or backgrounding discards unsubmitted audio. A very short tap is ignored. A report is capped at 30 seconds. Keyboard/TalkBack activation toggles recording for accessibility. Pressing again interrupts playback and starts a new report. The first report is buffered in memory while the network connects, so it is not lost during setup. Credentials are configured once via the on-map gear or Menu → Voice settings, outside the normal reporting flow.

## Estimate first

The starting instructions treat each report as complete information. For “supermarket on the right”, the agent searches nearby supermarkets, ranks candidates against the approximate position, heading, side, route and other supplied clues, and returns its best estimate. For a named shop/street it uses Photon. No requirement remains to say “I am alongside it now”. Past sightings are accepted. Before the result it must not ask for street names, timing, direction or more information. After the result at most one brief, optional confirmation about a feature actually returned by search is allowed. Silence does not trigger more questions. Spoken answers are limited by instruction to 35 words; there is no startup greeting.

Each report is limited to two searches and five tool calls before a final response is forced without tools. Photon requests have a five-second timeout, nearby category requests six seconds. These are upper bounds for individual requests, not a guarantee for total voice latency. Empty/failed searches should produce a brief honest no-match result instead of invented coordinates or an interview.

Confidence is a model-provided **heuristic match score**, not a calibrated probability or measured accuracy. Scores are validated and capped at 95, at 70 for category-only evidence, and at 55 for geometrically uncertain estimates. Multiple plausible branches and weak clues should reduce the score. Finding a plausible candidate never automatically changes navigation.

## Movement and map

At press time capture the approximate coordinate, route occurrence, estimated heading, odometer travel, monotonic timestamp, navigation session and recent speed if available. Searches stay centered on that observation point even if the car moves during the report or lookup. Search results expose proximity, relative side and route projection. The current context is included with the audio so the agent can usually search immediately.

During a selected route, project the matched landmark onto a plausible route occurrence and add odometer movement since the report began. If the driver explicitly provides a past-sighting time, use recent speed as a rough estimate for that earlier interval. No timing question is required; otherwise use zero additional past time and the prompt lowers confidence. This is an approximation, not historical speed integration. Ambiguous route occurrences, distant/off-route matches and missing speed for a timed past sighting are marked uncertain. Without a route, show an approximate landmark area rather than inventing a movement direction.

The purple estimate advances along the selected route using subsequent odometer movement, expires after one minute, and remains distinct from raw GPS and the active vehicle marker. Tap the confidence/result row to review. A suitable route estimate can be explicitly applied, retaining movement while the review is open and preserving Undo. Session/route changes and stale estimates prevent applying. Uncertain or off-route matches remain area estimates. Browsing can use an estimate as a route starting area.

## Tools and providers

- `location_context`: fresh current state and frozen report observation.
- `search_places`: named Photon search biased to the report point, filtered by a bounded radius, at most 30 returned candidates (Photon itself returns up to 15).
- `nearby_places`: bounded category queries against the fixed HTTPS Overpass endpoint. Supported categories include supermarket, shop, fuel, pharmacy, cafe, restaurant, bank, school, hospital, parking and bus stop. Radius is 100–3,000 m; responses are capped at 100 objects, sorted locally with at most 30 returned. Map coverage and truncated results are not exhaustive. No arbitrary query language is exposed to the model.
- `propose_location`: only an evidence ID from this report, a short reason, confidence and optional explicitly supplied sighting age. Opaque IDs and report isolation prevent invented coordinates and stale-report evidence from being reused.

OpenAI Realtime uses manual turns (`turn_detection=null`), audio commit on release and explicit response generation. Playback interruption flushes local audio and truncates the conversation at played duration. The foreground connection may remain available between reports for up to 90 seconds; the microphone is stopped between presses. There is no automatic reconnect/resume of recording. Audio focus loss or connection failure closes the session. Default voice language is Russian; proper street/business names are preserved.

For personal use, the OpenAI API key is stored encrypted in the independent Android Keystore-backed `voice-openai` namespace and sent only to the fixed `api.openai.com` TLS endpoint. The model is configurable; the default is `gpt-realtime-2.1`. Voice and location context go to OpenAI; named queries go to configured Photon; category queries and approximate coordinates go to OpenStreetMap/Overpass. Audio buffers remain in memory only and are cleared after release/cancel. The app does not persist audio or transcripts. Car credentials are never sent to these providers.

Public search endpoints provide no availability guarantee. Paid provider adapters and production short-lived credential issuance are separate future work. A key alone does not make an arbitrary paid map service compatible with Photon or Overpass.

## Verification

Run `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug`. Regression tests cover press-only audio, cancellation and short taps, bounded recording, movement during speech/search, past-sighting estimates, uncertain/off-route matches, search budgets, report evidence isolation, category searches with no business name, relative side and bounded Overpass query/response handling.

On 16 October 2026, all 84 tests, debug APK build and lint passed using a temporary build mirror and the existing SDK/Gradle cache. Portrait and landscape map controls and separate voice settings were visually checked on an Android 15 emulator. Screenshots: [portrait](screenshots/voice-portrait.png), [landscape](screenshots/voice-landscape.png). Verification used the existing JBR on-stack-replacement workaround.

Live checks still need a billed API key and microphone hardware: Russian responses to Russian/other-language proper names, one-report estimates without preliminary questions, spoken confidence and optional confirmation, first-press connection setup, release/cancel and microphone indicator, interruption while speaking, car noise, slow network, changing routes and applying/undoing an estimate. Prompt compliance and the quality/calibration of model-generated confidence cannot be established by unit tests.

References: [OpenAI manual voice turns](https://developers.openai.com/api/docs/guides/realtime-conversations), [Overpass spatial queries](https://dev.overpass-api.de/overpass-doc/en/preface/design.html), [Photon](https://github.com/komoot/photon).
