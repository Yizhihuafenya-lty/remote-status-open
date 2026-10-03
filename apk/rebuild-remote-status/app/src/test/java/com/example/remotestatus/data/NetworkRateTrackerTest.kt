package com.example.remotestatus.data

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkRateTrackerTest {
    private fun snapshot(rx: Long, tx: Long): Snapshot = Snapshot(
        deviceId = "node",
        hostname = "node",
        collectedAt = "",
        uptimeSeconds = 0.0,
        cpu = CpuSpec(0.0, 1),
        memory = MemorySpec(0, 0, 0.0, 0, 0),
        filesystems = emptyList(),
        network = listOf(NetworkCounters(rx, tx))
    )

    @Test
    fun `first sample has no invented speed`() {
        val rate = NetworkRateTracker().rate("node", snapshot(100, 200), 1_000)
        assertEquals(0.0, rate.rxBytesPerSecond, 0.0)
        assertEquals(0.0, rate.txBytesPerSecond, 0.0)
    }

    @Test
    fun `speed is computed from counter delta over elapsed time`() {
        val tracker = NetworkRateTracker()
        tracker.rate("node", snapshot(1_000, 2_000), 1_000)
        val rate = tracker.rate("node", snapshot(4_000, 8_000), 3_000)
        assertEquals(1_500.0, rate.rxBytesPerSecond, 0.0)
        assertEquals(3_000.0, rate.txBytesPerSecond, 0.0)
    }

    @Test
    fun `counter reset does not create negative speed`() {
        val tracker = NetworkRateTracker()
        tracker.rate("node", snapshot(5_000, 5_000), 1_000)
        val rate = tracker.rate("node", snapshot(10, 10), 2_000)
        assertEquals(0.0, rate.rxBytesPerSecond, 0.0)
        assertEquals(0.0, rate.txBytesPerSecond, 0.0)
    }
}
