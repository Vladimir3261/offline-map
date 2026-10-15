# AI-assisted location by voice

This is a native Android prototype using OpenAI Realtime over TLS WebSocket and the existing Photon named-place search. It supports a continuous spoken conversation with server voice activity detection, spoken responses and interruption. The session runs only while the app is foregrounded and its voice dialog is open. It does not require GPS.

## Context and search

Do not preload the city catalogue into the language model. Each session receives a bounded instruction set and three tools:

- `location_context`: fresh approximate coordinate and its source, route name, route progress, next maneuver, monotonic time, movement distance, and car-feed freshness. A map center is explicitly labeled as an uncertain prior.
- `search_places(query, radius_m)`: reuse the configured Photon HTTPS endpoint, bias to the approximate coordinate, then filter by a 100–50,000 m radius (10,000 m default). At most 15 matches are returned with label, category, coordinates, source and opaque session-local evidence IDs. Search retains existing caching, response size limits and throttling. Results are not an exhaustive area inventory.
- `propose_location(place_id, reason, observation)`: only accepts a previously returned evidence ID, younger than two minutes, with `observation=at_place_now`. It stages a proposal, without altering navigation.

The agent asks one question at a time, in the user's language, and distinguishes duplicate shops by surrounding street, junction and direction. It can reason conversationally about “I passed X” and ask about timing, but this implementation requires a current landmark before producing a correction. Street geometry, time-stamped observation tracking, candidate probability fusion and automatic dead reckoning from past landmark sightings are not implemented. Tool argument validation restricts proposals to searched places; the model's claim that the user is alongside a landmark still needs human review.

During navigation, the prior comes from route progress and odometer movement, with a freshness flag. Otherwise use fresh GPS, or the map center. The odometer route position assumes the selected route is being followed. Search cannot discover every nearby place; business coverage and naming depend on OSM and provider data. Public Photon offers no availability guarantee. A service failure remains an error, not evidence that a landmark is absent. A returned shop may be inside a building; street matches cannot establish a particular point along a street.

## Applying a proposal

Review ends the voice session. Proposals older than a minute or followed by more than 50 m movement are rejected. Route, driving mode, guiding phase and navigation session must still match those captured when voice chat opened.

While navigating, reject landmarks more than 100 m from the selected route. Otherwise use the existing route projection chooser and Apply sync action, including session checks, movement since preview and Undo. The preview uses a 50 m selection tolerance, which is a UI heuristic, **not a measured accuracy**. No AI coordinate is presented as a GPS fix. While browsing, confirmation supplies the route planner's starting place. It does not initiate a drive.

## Credentials and services

For personal testing, enter an OpenAI API key directly in the voice dialog. It is encrypted using the existing Android Keystore mechanism with an independent `voice-openai` namespace; existing car credentials remain compatible. Only the fixed `api.openai.com` endpoint receives this key. There is no credential URL or redirect support. No secrets are embedded in the APK. The default model is `gpt-realtime-2.1`, configurable in the dialog; availability and billing must be checked for the user's API account.

For wider distribution, replace direct long-lived API keys with an authenticated backend issuing short-lived voice session credentials. Paid map providers require an explicit adapter for their request, authentication, result and licensing contracts; a key alone cannot make a Photon endpoint compatible. Current Photon searches remain unauthenticated. Existing car tokens are never sent to search or OpenAI.

## Audio and data

Audio uses mono 24 kHz PCM16 capture and playback, voice communication audio focus and hardware acoustic echo cancellation where available. Output playback is queued off the WebSocket callback; interruption flushes playback and sends a conversation truncation at the played duration. Slow uplink and excessive output queues terminate the session. Connection errors do not auto-reconnect or resume the microphone. End/Close/Forget key/backgrounding cancels the connection, tool executor and audio resources. Incoming calls/audio focus loss stop the session.

Voice is streamed to OpenAI; approximate location, route context, tool search results and spoken conversation also leave the device. Queries and approximate focus go to the configured Photon service. Audio/transcripts are not persisted by this app. Provider-side handling is governed by the respective service. The agent never receives the car bearer token.

## Verification

Automated tests cover filtering far-away branches, preview-only proposals, fabricated and expired evidence, past-sighting rejection, fresh context retrieval and argument limits. Run `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug`.

On 15 October 2026, the debug APK build, all 73 unit tests and Android lint passed. Verification used a temporary build mirror and the existing temporary Android SDK/Gradle cache because the repository tooling stalled at startup. The local JBR 17 JIT crashed during the first lint attempt; disabling on-stack replacement for the verification daemon resolved it. The emulator accepted installation but disconnected before a UI smoke check could finish. Live API voice and audio behavior remain unverified.

Live checks still required with an API key and Android microphone hardware:

1. Start with a known approximate location, name a nearby shop, distinguish duplicate branches and confirm a current landmark. Check both starting-point selection and navigation preview/Undo.
2. Say “I passed X” and give a street name alone. Neither should silently move the marker. Confirm empty and failed searches produce follow-up questions.
3. Speak over the agent, check interruption and car speaker echo; test Ukrainian and other required languages and car noise.
4. End, Close, background, revoke microphone permission, lose internet, and receive a phone call. Check microphone indicator clears and restart requires explicit action.
5. Review after movement, route/session changes or expiry. Stale proposals must be rejected. Test an off-route landmark and ambiguous route crossings.
6. Test invalid key, unavailable model and exhausted API billing. No secret should appear in messages or logs. Test Forget key and verify the car token survives.

References: [OpenAI Realtime conversations](https://developers.openai.com/api/docs/guides/realtime-conversations), [WebSocket connection](https://developers.openai.com/api/docs/guides/voice-websockets?voice-api=realtime), [Photon API and service policy](https://github.com/komoot/photon).
