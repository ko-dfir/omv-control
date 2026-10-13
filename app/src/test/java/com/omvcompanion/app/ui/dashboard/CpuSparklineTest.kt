package com.omvcompanion.app.ui.dashboard

import org.junit.Assert.*
import org.junit.Test

class CpuSparklineTest {
    @Test fun extremaLeaveRoomForTheStroke() {
        assertEquals(37f, cpuSparklineOffset(0f, 0.0, 100f, 40f, 3f).y, 0.001f)
        assertEquals(3f, cpuSparklineOffset(1f, 100.0, 100f, 40f, 3f).y, 0.001f)
        assertEquals(97f, cpuSparklineOffset(1f, 50.0, 100f, 40f, 3f).x, 0.001f)
    }
    @Test fun tinyCanvasNeverProducesCoordinatesOutsideIt() {
        val p = cpuSparklineOffset(1f, 0.0, 2f, 1f, 3f)
        assertTrue(p.x in 0f..2f && p.y in 0f..1f)
    }
}
