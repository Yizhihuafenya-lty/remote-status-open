package com.example.remotestatus.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AggregateTest {
    private val v4 = Relay(RelayId.V4, "192.0.2.10", 50001)
    private val v6 = Relay(RelayId.V6, "2001:db8::10", 50001)

    private fun device(relay: Relay, id: String, online: Boolean = true): Device = Device(
        relay,
        DeviceSummary(id, id, id, null, null, null, online, 0.0, 0.0)
    )

    private fun metric(device: Device, cores: Int, cpu: Double, total: Long, available: Long, rx: Long = 0, tx: Long = 0): DeviceMetric = DeviceMetric(
        device,
        Snapshot(
            deviceId = device.summary.deviceId,
            hostname = device.summary.hostname,
            collectedAt = "2026-10-02T00:00:00Z",
            uptimeSeconds = 60.0,
            cpu = CpuSpec(cpu, cores),
            memory = MemorySpec(total, available, 0.0, 0, 0),
            filesystems = emptyList(),
            network = listOf(NetworkCounters(rx, tx))
        )
    )

    @Test
    fun `aggregates cpu memory and bandwidth across both relays`() {
        val first = device(v4, "one")
        val second = device(v6, "two")
        val metrics = mapOf(
            first.id to metric(first, 2, 50.0, 2_000, 500, rx = 1_000, tx = 500),
            second.id to metric(second, 6, 25.0, 6_000, 1_500, rx = 2_000, tx = 1_000)
        )
        val rates = mapOf(
            first.id to NetworkRate(100.0, 50.0),
            second.id to NetworkRate(300.0, 150.0)
        )

        val totals = Aggregate.totals(listOf(first, second), metrics, rates)

        assertEquals(8, totals.totalCores)
        assertEquals(2.5, totals.usedCores, 0.001)
        assertEquals(31.25, totals.cpuPercent, 0.001)
        assertEquals(8_000, totals.totalMemoryBytes)
        assertEquals(6_000, totals.usedMemoryBytes)
        assertEquals(75.0, totals.memoryPercent, 0.001)
        assertEquals(400.0, totals.downloadBytesPerSecond, 0.001)
        assertEquals(200.0, totals.uploadBytesPerSecond, 0.001)
        assertEquals(600.0, totals.bandwidthBytesPerSecond, 0.001)
        assertEquals(4_500, totals.cumulativeBytes)
    }

    @Test
    fun `missing metrics never create false zero cores or memory`() {
        val first = device(v4, "one")
        val second = device(v6, "two")
        val totals = Aggregate.totals(listOf(first, second), emptyMap(), emptyMap())

        assertEquals(2, totals.totalDevices)
        assertEquals(0, totals.totalCores)
        assertEquals(0, totals.totalMemoryBytes)
        assertEquals(0.0, totals.cpuPercent, 0.0)
    }
}
