package com.superdownloader.app.engine

import com.superdownloader.app.data.Priority
import com.superdownloader.app.data.SettingsState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentPlannerTest {
    @Test fun splitCoversWholeFileWithoutGaps() {
        val total = 10_000_019L
        val parts = SegmentPlanner.initialSplit(total, 8, 512 * 1024)
        assertEquals(8, parts.size)
        assertEquals(0L, parts.first().start)
        assertEquals(total - 1, parts.last().end)
        parts.zipWithNext().forEach { (a, b) -> assertEquals(a.end + 1, b.start) }
    }

    @Test fun smallFileGetsFewerParts() {
        assertEquals(1, SegmentPlanner.initialSplit(100_000, 8, 512 * 1024).size)
        assertEquals(2, SegmentPlanner.initialSplit(1_100_000, 8, 512 * 1024).size)
    }

    @Test fun hugeFileOver4GbUses64BitOffsets() {
        val total = 6_710_000_000L
        val parts = SegmentPlanner.initialSplit(total, 16, 512 * 1024)
        assertEquals(total - 1, parts.last().end)
        assertTrue(parts.last().start > Int.MAX_VALUE)
    }

    @Test fun resumeOffsets() {
        assertEquals(1_500L, SegmentPlanner.resumeFrom(1_000, 500))
        assertEquals("bytes=1500-1999", SegmentPlanner.rangeHeader(1_000, 500, 1_999))
        assertEquals("bytes=1500-", SegmentPlanner.rangeHeader(1_000, 500, -1))
        assertEquals(500L, SegmentPlanner.remaining(1_000, 500, 1_999))
    }

    @Test fun stealSplitsHalfOfRemaining() {
        val mid = SegmentPlanner.stealPoint(0, 1_000_000, 9_999_999, 2 * 1024 * 1024)
        assertEquals(1_000_000L + 9_000_000L / 2, mid)
        assertNull(SegmentPlanner.stealPoint(0, 9_000_000, 9_999_999, 2 * 1024 * 1024))
        assertNull(SegmentPlanner.stealPoint(0, 0, -1, 1))
    }
}

class PriorityLimiterTest {
    private val s = SettingsState(lowCapKbps = 1024, snailCapKbps = 128, lowConnections = 2, snailConnections = 1)

    @Test fun highAndNormalAreUncapped() {
        assertEquals(0L, s.priorityCap(Priority.HIGH))
        assertEquals(0L, s.priorityCap(Priority.NORMAL))
        assertEquals(Int.MAX_VALUE, s.priorityConnections(Priority.HIGH))
    }

    @Test fun lowAndSnailCaps() {
        assertEquals(1024L * 1024, s.priorityCap(Priority.LOW))
        assertEquals(128L * 1024, s.priorityCap(Priority.SNAIL))
        assertEquals(2, s.priorityConnections(Priority.LOW))
        assertEquals(1, s.priorityConnections(Priority.SNAIL))
    }

    @Test fun unlimitedLimiterHasNoDelay() = runBlocking {
        val l = SpeedLimiter()
        val t0 = System.nanoTime()
        repeat(10_000) { l.acquire(256 * 1024) }
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 500)
    }

    @Test fun cappedLimiterThrottles() = runBlocking {
        val l = SpeedLimiter()
        l.bytesPerSec = 200_000
        l.acquire(200_000) // drains the initial bucket
        val t0 = System.nanoTime()
        repeat(4) { l.acquire(50_000) } // 200 KB more at 200 KB/s ≈ 1 s
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("took $ms ms", ms in 700..2500)
    }
}
