package dev.routeodo.core

import dev.routeodo.data.JsonCodec
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TripTrackerTest {
    private fun frame(m: Long? = 1000, t: Double = 100.0, anchorT: Double? = t, speed: Int? = 0,
        speedT: Double? = t, epoch: String = "a", connected: Boolean = true) =
        CarSample(t, epoch, m, anchorT, speed, speedT, connected)
    private fun started(scale: Double = 1.0) = TripTracker(TripState(scale = scale)).apply { start(); accept(frame()) }

    @Test fun connectingDoesNotStartAndStartWaitsForTheFirstUsableAnchor() {
        val tracker = TripTracker()
        tracker.accept(frame(2000)); assertFalse(tracker.state.running)
        tracker.start(); tracker.accept(frame(null, anchorT = null))
        assertNull(tracker.state.baseM)
        tracker.accept(frame(3000)); tracker.accept(frame(3010, 101.0))
        assertEquals(10.0, tracker.state.progressMeters, 0.0)
    }

    @Test fun stationaryAndCreepingReadingsTrustTheAnchor() {
        val tracker = started()
        repeat(10) { assertTrue(tracker.accept(frame(t = 101.0 + it)).accepted) }
        assertEquals(0.0, tracker.state.progressMeters, 0.0)
        tracker.accept(frame(1001, 112.0, speed = 0))
        assertEquals(1.0, tracker.state.progressMeters, 0.0)
    }

    @Test fun smoothingUsesServerTimeAndLagsHoldUntilAnAnchorOvertakes() {
        val tracker = started()
        tracker.accept(frame(t = 100.5, anchorT = 100.0, speed = 36))
        assertEquals(5.0, tracker.state.progressMeters, 1e-8)
        tracker.accept(frame(1004, 101.0, speed = 36))
        assertEquals(5.0, tracker.state.progressMeters, 0.0)
        tracker.accept(frame(1004, 101.9, anchorT = 101.0, speed = 36))
        assertEquals(5.0, tracker.state.progressMeters, 0.0)
        tracker.accept(frame(1010, 102.0, speed = 36))
        tracker.accept(frame(1010, 102.1, anchorT = 102.0, speed = 36))
        assertEquals(11.0, tracker.state.progressMeters, 1e-8)
    }

    @Test fun staleSpeedStopsSmoothingButFreshOdometerStillAdvances() {
        val tracker = started()
        tracker.accept(frame(t = 101.0, anchorT = 100.0, speed = 36, speedT = 100.0))
        assertEquals(10.0, tracker.state.progressMeters, 0.0)
        tracker.accept(frame(t = 101.001, anchorT = 100.0, speed = 36, speedT = 100.0))
        assertEquals(10.0, tracker.state.progressMeters, 0.0)
        tracker.accept(frame(1020, 102.0, speed = null, speedT = null))
        assertEquals(20.0, tracker.state.progressMeters, 0.0)
    }

    @Test fun staleAnchorsDisconnectedEcuAndFutureTimestampsHoldPosition() {
        val tracker = started()
        tracker.accept(frame(t = 105.0, anchorT = 100.0, speed = 36))
        assertEquals(50.0, tracker.state.progressMeters, 0.0)
        assertFalse(tracker.accept(frame(t = 105.001, anchorT = 100.0, speed = 36)).accepted)
        assertFalse(tracker.accept(frame(2000, 106.0, connected = false)).accepted)
        assertFalse(tracker.accept(frame(2000, 99.0, anchorT = 100.0)).accepted)
        assertEquals(50.0, tracker.state.progressMeters, 0.0)
    }

    @Test fun networkGapWithinEpochRecoversAllAnchorDistance() {
        val tracker = started()
        tracker.accept(frame(5000, 200.0))
        assertEquals(4000.0, tracker.state.progressMeters, 0.0)
        assertTrue(tracker.state.gaps.isEmpty())
    }

    @Test fun epochRestartCarriesConfirmedDistanceAndMarksOneGap() {
        val tracker = started()
        tracker.accept(frame(1100, 101.0))
        tracker.accept(frame(7000, 102.0, epoch = "b"))
        assertEquals(100.0, tracker.state.progressMeters, 0.0)
        assertEquals(7000L, tracker.state.baseM)
        assertEquals(100.0, tracker.state.gaps.single().progressMeters, 0.0)
        tracker.accept(frame(7010, 103.0, epoch = "b"))
        assertEquals(110.0, tracker.state.progressMeters, 0.0)
    }

    @Test fun nullSamplesAfterRestartDoNotGuessOrDuplicateTheGap() {
        val tracker = started()
        tracker.accept(frame(1100, 101.0))
        repeat(3) { tracker.accept(frame(null, 102.0 + it, anchorT = null, epoch = "b")) }
        assertEquals(100.0, tracker.state.progressMeters, 0.0)
        assertEquals(1, tracker.state.gaps.size)
        tracker.accept(frame(0, 105.0, epoch = "b"))
        tracker.accept(frame(20, 106.0, epoch = "b"))
        assertEquals(120.0, tracker.state.progressMeters, 0.0)
    }

    @Test fun estimateBeforeEpochGapIsHeldUntilConfirmedDistanceCatchesIt() {
        val tracker = started()
        tracker.accept(frame(t = 100.5, anchorT = 100.0, speed = 36))
        tracker.accept(frame(0, 101.0, epoch = "b"))
        tracker.accept(frame(2, 102.0, epoch = "b"))
        assertEquals(5.0, tracker.state.progressMeters, 0.0)
        tracker.accept(frame(8, 103.0, epoch = "b"))
        assertEquals(8.0, tracker.state.progressMeters, 0.0)
    }

    @Test fun calibrationAppliesToAnchorAndInterpolatedDistance() {
        val tracker = started(1.02)
        tracker.accept(frame(1100, 101.0))
        assertEquals(102.0, tracker.state.progressMeters, 1e-8)
        tracker.accept(frame(1100, 101.5, anchorT = 101.0, speed = 36))
        assertEquals(107.1, tracker.state.progressMeters, 1e-8)
    }

    @Test fun undoGpsKeepsTravelAndEpochGapAfterTheCorrection() {
        val tracker = started()
        tracker.accept(frame(1100, 101.0))
        val before = tracker.state
        tracker.anchor(frame(1100, 101.0), 800.0)
        tracker.accept(frame(1120, 102.0))
        tracker.accept(frame(0, 103.0, epoch = "b"))
        tracker.accept(frame(30, 104.0, epoch = "b"))
        assertEquals(850.0, tracker.state.progressMeters, 0.0)
        tracker.restoreAlignment(before)
        assertEquals(150.0, tracker.state.progressMeters, 0.0)
        assertEquals("b", tracker.state.baseEpoch)
        assertEquals(1, tracker.state.gaps.size)
        tracker.accept(frame(40, 105.0, epoch = "b"))
        assertEquals(160.0, tracker.state.progressMeters, 0.0)
    }

    @Test fun restartPersistsCalibrationAnchorsAndUndoAlignment() {
        val tracker = started(1.02)
        tracker.accept(frame(1050, 101.0))
        val before = JsonCodec.state(JsonCodec.state(tracker.state))
        tracker.anchor(frame(1050, 101.0), 900.0)
        val restarted = TripTracker(JsonCodec.state(JsonCodec.state(tracker.state)))
        restarted.accept(frame(1150, 102.0)); restarted.restoreAlignment(before)
        assertEquals(153.0, restarted.state.progressMeters, 1e-8)
        assertEquals(tracker.state.navigationSessionId, restarted.state.navigationSessionId)
    }

    @Test fun decreasingValueWithinEpochRequiresAlignmentOrNewEpoch() {
        val tracker = started()
        tracker.accept(frame(1100, 101.0))
        assertFalse(tracker.accept(frame(10, 102.0)).accepted)
        assertFalse(tracker.accept(frame(1200, 103.0)).accepted)
        tracker.accept(frame(0, 104.0, epoch = "b"))
        tracker.accept(frame(10, 105.0, epoch = "b"))
        assertEquals(110.0, tracker.state.progressMeters, 0.0)
    }

    @Test fun stopAndNewStartDoNotReuseTheOldBaselineOrUndo() {
        val tracker = started()
        val before = tracker.state
        tracker.accept(frame(1100, 101.0)); tracker.stop(); tracker.accept(frame(9000, 102.0))
        assertEquals(100.0, tracker.state.progressMeters, 0.0)
        tracker.start(); tracker.accept(frame(10000, 103.0)); tracker.accept(frame(10025, 104.0))
        assertEquals(25.0, tracker.state.progressMeters, 0.0)
        assertNotEquals(before.navigationSessionId, tracker.state.navigationSessionId)
        assertThrows(IllegalArgumentException::class.java) { tracker.restoreAlignment(before) }
    }

    @Test fun legacyStateKeepsPositionButRequiresANewProtocolAnchor() {
        val restored = TripTracker(JsonCodec.state(JSONObject().put("progressMeters", 500).put("scale", 1.0)))
        restored.accept(frame(123456000))
        assertFalse(restored.state.running)
        assertEquals(500.0, restored.state.progressMeters, 0.0)
    }
}
