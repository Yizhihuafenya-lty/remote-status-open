package com.example.remotestatus.data

import org.junit.Assert.assertEquals
import org.junit.Test

class JsonParserTest {
    @Test
    fun `parses relay device list payload`() {
        val payload = """
            {
              "devices": [
                {
                  "device_id": "node-1",
                  "hostname": "web-01",
                  "name": "Web",
                  "address": "192.0.2.20",
                  "port": 50000,
                  "collected_at": "2026-10-02T00:00:00Z",
                  "online": true,
                  "cpu_usage_percent": 12.5,
                  "memory_used_percent": 25.0
                }
              ]
            }
        """.trimIndent()

        val device = JsonParser.parseDeviceSummaries(payload).single()
        assertEquals("node-1", device.deviceId)
        assertEquals("Web", device.name)
        assertEquals(50000, device.port)
        assertEquals(12.5, device.cpuPercent, 0.0)
    }

    @Test
    fun `parses snapshot wrapper and network counters`() {
        val payload = """
            {
              "snapshot": {
                "device_id": "node-1",
                "hostname": "web-01",
                "collected_at": "2026-10-02T00:00:00Z",
                "uptime_seconds": 120,
                "cpu": {"usage_percent": 30, "core_count": 4},
                "memory": {"total_bytes": 1024, "available_bytes": 256, "used_percent": 75},
                "filesystems": [{"mount": "/", "filesystem": "/dev/sda1", "total_bytes": 2048, "used_bytes": 1024, "free_bytes": 1024, "used_percent": 50}],
                "network": [{"interface": "eth0", "rx_bytes": 100, "tx_bytes": 200}]
              }
            }
        """.trimIndent()

        val snapshot = JsonParser.parseSnapshot(payload)
        assertEquals("node-1", snapshot.deviceId)
        assertEquals(4, snapshot.cpu.coreCount)
        assertEquals(1024, snapshot.memory.totalBytes)
        assertEquals(1, snapshot.network.size)
        assertEquals(200, snapshot.network.single().txBytes)
    }
}
