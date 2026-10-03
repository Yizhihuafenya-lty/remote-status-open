param(
    [string]$RelayUrl = 'http://127.0.0.1:50001',
    [string]$FleetKey = 'demo-fleet-key-change-me'
)

$ErrorActionPreference = 'Continue'
$counter = 0
while ($true) {
    $counter++
    foreach ($node in @(
        @{ Id = 'node-1'; Host = 'web-demo-01'; CpuBase = 34; MemBase = 52; DiskBase = 61 },
        @{ Id = 'node-2'; Host = 'db-demo-02'; CpuBase = 17; MemBase = 71; DiskBase = 78 },
        @{ Id = 'node-3'; Host = 'worker-demo-03'; CpuBase = 26; MemBase = 43; DiskBase = 35 }
    )) {
        $cpu = $node.CpuBase + (($counter * 7) % 17)
        $now = [DateTime]::UtcNow.ToString('o')
        $snapshot = @{
            device_id = $node.Id
            hostname = $node.Host
            collected_at = $now
            uptime_seconds = 86400 + $counter * 10
            load_average = @(0.41, 0.36, 0.29)
            cpu = @{ usage_percent = $cpu; core_count = 8 }
            memory = @{ total_bytes = 17179869184; available_bytes = 8246337208; used_percent = $node.MemBase; swap_total_bytes = 2147483648; swap_free_bytes = 1610612736 }
            filesystems = @(@{ mount = '/'; filesystem = '/dev/vda1'; total_bytes = 107374182400; used_bytes = 65598201856; free_bytes = 41775980544; used_percent = $node.DiskBase })
            network = @(@{ interface = 'eth0'; rx_bytes = 548939284 + $counter * 8192; tx_bytes = 219873456 + $counter * 4096; rx_packets = 0; tx_packets = 0; rx_errors = 0; tx_errors = 0 })
            processes = @{ count = 142 + ($counter % 8) }
            temperatures = @(@{ zone = 'thermal_zone0'; type = 'CPU package'; celsius = 47 + ($counter % 6) })
            disk_io = @(@{ device = 'vda'; read_bytes = 4831838208 + $counter * 1048576; write_bytes = 2717908992 + $counter * 524288; read_ops = 18432 + $counter; write_ops = 9216 + $counter; in_flight = 0 })
            pressure = @{ cpu = @{ avg10 = 0.2; avg60 = 0.4; avg300 = 0.3 }; memory = @{ avg10 = 0.1; avg60 = 0.2; avg300 = 0.2 }; io = @{ avg10 = 0.5; avg60 = 0.6; avg300 = 0.4 } }
            connections = @{ total = 38 + ($counter % 5); listening = 8; established = 22 + ($counter % 4) }
        }
        $body = @{ snapshot = $snapshot } | ConvertTo-Json -Depth 8 -Compress
        try {
            $hmac = [System.Security.Cryptography.HMACSHA256]::new([System.Text.Encoding]::UTF8.GetBytes($FleetKey))
            try {
                $message = [System.Text.Encoding]::UTF8.GetBytes("remote-status-agent-v1:$($node.Id)")
                $derivedToken = -join ($hmac.ComputeHash($message) | ForEach-Object { $_.ToString('x2') })
            } finally { $hmac.Dispose() }
            $headers = @{ Authorization = "Bearer $derivedToken" }
            Invoke-RestMethod -Method Post -Uri "$RelayUrl/v1/agent/report/$($node.Id)" -Headers $headers -ContentType 'application/json' -Body $body | Out-Null
        } catch {
            Write-Output "Report failed for $($node.Id): $_"
        }
    }
    Start-Sleep -Seconds 10
}
