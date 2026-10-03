package com.example.remotestatus.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RenamePayloadTest {
    @Test
    fun `rename payload keeps a name longer than the old 40 character limit`() {
        val long = "demo-v6-node-咸鱼-1G512MB-1.08-测试超长名称-0123456789"
        val payload = buildRenamePayload(long)
        assertTrue(payload.contains(long))
    }

    @Test
    fun `device list parsing keeps long relay names intact`() {
        val long = "demo-v6-node-咸鱼-1G512MB-1.08-额外后缀"
        val payload = """{"devices":[{"device_id":"node-x","hostname":"h","name":"$long","online":true,"cpu_usage_percent":1,"memory_used_percent":2}]}"""
        val device = JsonParser.parseDeviceSummaries(payload).single()
        assertEquals(long, device.name)
    }

    @Test
    fun `rename payload carries only the new name`() {
        val payload = buildRenamePayload("  生产库  ")
        assertTrue(payload.contains("\"name\":\"生产库\""))
    }

    @Test
    fun `add target payload keeps the ipv6 address unbracketed`() {
        val relay = Relay(RelayId.V6, "2001:db8::10", 50001)
        val payload = buildAddTargetPayload(relay, "node-x", "[2001:db8::11]", 50000, "v6 节点")

        assertTrue(payload.contains("\"address\":\"2001:db8::11\""))
        assertTrue(payload.contains("\"port\":50000"))
        assertTrue(payload.contains("\"device_id\":\"node-x\""))
    }

    @Test
    fun `detail configuration exposes relay ownership and address`() {
        val relay = Relay(RelayId.V6, "2001:db8::10", 50001)
        val device = Device(
            relay,
            DeviceSummary("node-1", "host-1", "示例服务器", "2001:db8::11", 50000, null, true, 0.0, 0.0)
        )
        val snapshot = Snapshot(
            deviceId = "node-1",
            hostname = "host-1",
            collectedAt = "2026-10-02T00:00:00Z",
            uptimeSeconds = 3_600.0,
            cpu = CpuSpec(5.0, 4),
            memory = MemorySpec(1_073_741_824, 536_870_912, 50.0, 0, 0),
            filesystems = emptyList(),
            network = emptyList()
        )

        val rows = basicConfigurationLines(device, snapshot).toMap()

        assertEquals("示例服务器", rows["服务器"])
        assertEquals("V6 · http://[2001:db8::10]:50001", rows["所属中继"])
        assertEquals("2001:db8::11:50000", rows["地址"])
        assertEquals("1小时 0分钟", rows["运行时长"])
    }
}
