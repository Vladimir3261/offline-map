package dev.routeodo.ui

import dev.routeodo.core.*
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.File

class DrivingStateTest {
    @Test fun appearancePersistsAndUnknownSettingsUseSystem() {
        var stored: String? = null
        fun store() = AppearanceSettings({ stored }, { stored = it })
        assertEquals(AppearanceMode.SYSTEM, store().mode)
        store().mode = AppearanceMode.NIGHT
        assertEquals(AppearanceMode.NIGHT, store().mode)
        store().mode = AppearanceMode.DAY
        assertEquals(AppearanceMode.DAY, store().mode)
        stored = "obsolete-setting"
        assertEquals(AppearanceMode.SYSTEM, store().mode)
    }
    @Test fun appearanceRespectsExplicitChoiceAcrossSystemChanges() {
        assertTrue(AppearanceMode.NIGHT.isNight(false))
        assertFalse(AppearanceMode.DAY.isNight(true))
        assertTrue(AppearanceMode.SYSTEM.isNight(true))
        assertFalse(AppearanceMode.SYSTEM.isNight(false))
    }
    @Test fun gesturesSuspendFollowUntilExplicitRecenter() {
        val camera = FollowState()
        val position = RoutePosition(Coordinate(50.4, 30.5), 90.0, 0)
        assertTrue(camera.shouldMove(position, 0))
        camera.browse()
        assertFalse(camera.shouldMove(position, 1000))
        camera.recenter()
        assertTrue(camera.shouldMove(position, 1100))
        assertFalse(camera.shouldMove(position, 3000))
    }
    @Test fun cameraDoesNotRestartItsAnimationForEverySpeedSampleOrNorthWrap() {
        val camera = FollowState()
        val a = RoutePosition(Coordinate(50.4, 30.5), 359.0, 0)
        val b = a.copy(coordinate = Coordinate(50.401, 30.5))
        assertTrue(camera.shouldMove(a, 0))
        assertFalse(camera.shouldMove(b, 100))
        assertTrue(camera.shouldMove(b, 650))
        assertFalse(camera.shouldMove(b.copy(bearing = 1.0), 1500))
        assertTrue(camera.shouldMove(b.copy(bearing = 30.0), 2000))
    }
    @Test fun overlapDependsOnScreenFootprintsAndDisplayDensity() {
        assertTrue(MarkerOverlap.visibleOverlap(0.0, 0.0, 1.0))
        assertTrue(MarkerOverlap.visibleOverlap(30.0, 0.0, 1.0))
        assertFalse(MarkerOverlap.visibleOverlap(30.0, 30.0, 1.0))
        assertTrue(MarkerOverlap.visibleOverlap(30.0, 30.0, 2.0))
    }
    @Test fun bundledStylesReuseOfflineResourcesAndExcludePoiAndHostedIcons() {
        for (appearance in listOf("day", "night")) {
            val style = JSONObject(File("src/main/assets/styles/routeodo-$appearance.json").readText())
            assertEquals(8, style.getInt("version"))
            assertEquals("https://tiles.openfreemap.org/planet", style.getJSONObject("sources").getJSONObject("openmaptiles").getString("url"))
            assertEquals("https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf", style.getString("glyphs"))
            assertFalse(style.has("sprite"))
            val layers = style.getJSONArray("layers")
            val ids = mutableSetOf<String>()
            for (i in 0 until layers.length()) {
                val layer = layers.getJSONObject(i)
                assertTrue(ids.add(layer.getString("id")))
                assertNotEquals("poi", layer.optString("source-layer"))
                layer.optJSONObject("layout")?.optJSONArray("text-font")?.let { fonts ->
                    for (j in 0 until fonts.length()) assertTrue(fonts.getString(j) in listOf("Noto Sans Regular", "Noto Sans Bold", "Noto Sans Italic"))
                }
            }
            assertTrue(ids.contains("street-names-major"))
        }
    }
}

class DrivingPresenterTest {
    private val route = Route("r", "test", listOf(Coordinate(50.4, 30.5), Coordinate(50.5, 30.5)), emptyList())
    private val fix = GpsFix(route.points.first(), 22.0, 10_000)
    private val sample = CarSample(100.0, "e", 100, 100.0, 47, 100.0, true)
    private fun input() = DrivingInputs(route, 0.0, TripState(running = true), PositionDebug.compare(route.points.first(), fix, 10_000),
        10_000, latest = sample, latestAt = 10_000, transport = "LAN · live stream")
    private fun view(input: DrivingInputs) = DrivingPresenter.present(input) { "${it.toInt()} m" }.screen

    @Test fun comparisonNeverClaimsGpsFusionOrHidesCloseCoordinates() {
        val screen = view(input())
        assertEquals("ODO + GPS", screen.source)
        assertEquals("GPS ↔ ODO: 0 m", screen.comparison)
        assertEquals("GPS accuracy: ±22 m", screen.accuracy)
        assertEquals("47", screen.speed)
    }
    @Test fun staleGpsAndSilentCarAreExplicitAndSpeedIsNotPresentedAsCurrent() {
        val staleGps = PositionDebug.compare(route.points.first(), fix, 16_000)
        val screen = view(input().copy(debug = staleGps, now = 16_000, latestAt = 16_000))
        assertEquals("GPS STALE", screen.source)
        assertEquals("GPS stale", screen.comparison)
        val offline = view(input().copy(now = 20_000))
        assertEquals("CAR OFFLINE", offline.source)
        assertEquals("—", offline.speed)
        assertTrue(offline.warning)
    }
    @Test fun knownApiErrorsAreNotMislabelledAsCarOffline() {
        val screen = view(input().copy(now = 30_000, carIssue = dev.routeodo.data.CarFeedIssue.AUTH))
        assertEquals("API AUTH ERROR", screen.source)
        assertEquals("API authentication failed", screen.connection)
    }
    @Test fun previewIsExplicitEvenWhenGpsIsLive() {
        val screen = view(input().copy(live = false, simRunning = true))
        assertEquals("PREVIEW", screen.source)
        assertEquals("50", screen.speed)
        assertTrue(screen.notice.contains("simulated"))
        assertTrue(screen.preview)
    }
}
