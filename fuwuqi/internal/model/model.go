package model

import "time"

// Snapshot is the read-only payload sent by an agent and returned to the app.
// Values are deliberately plain JSON so the Android client does not need a
// platform-specific serialization library.
type Snapshot struct {
	DeviceID     string          `json:"device_id"`
	Hostname     string          `json:"hostname"`
	Collected    time.Time       `json:"collected_at"`
	UptimeSec    float64         `json:"uptime_seconds"`
	Load         [3]float64      `json:"load_average"`
	CPU          CPU             `json:"cpu"`
	Memory       Memory          `json:"memory"`
	Filesystems  []Filesystem    `json:"filesystems"`
	Network      []Network       `json:"network"`
	Processes    ProcessInfo     `json:"processes"`
	Temperatures []Temperature   `json:"temperatures"`
	DiskIO       []DiskIO        `json:"disk_io"`
	Pressure     PressureStats   `json:"pressure"`
	Connections  ConnectionStats `json:"connections"`
}

type CPU struct {
	UsagePercent float64 `json:"usage_percent"`
	CoreCount    int     `json:"core_count"`
}

type Memory struct {
	TotalBytes     uint64  `json:"total_bytes"`
	AvailableBytes uint64  `json:"available_bytes"`
	UsedPercent    float64 `json:"used_percent"`
	SwapTotalBytes uint64  `json:"swap_total_bytes"`
	SwapFreeBytes  uint64  `json:"swap_free_bytes"`
}

type Filesystem struct {
	Mount       string  `json:"mount"`
	Filesystem  string  `json:"filesystem"`
	TotalBytes  uint64  `json:"total_bytes"`
	UsedBytes   uint64  `json:"used_bytes"`
	FreeBytes   uint64  `json:"free_bytes"`
	UsedPercent float64 `json:"used_percent"`
}

type Network struct {
	Interface string `json:"interface"`
	RxBytes   uint64 `json:"rx_bytes"`
	TxBytes   uint64 `json:"tx_bytes"`
	RxPackets uint64 `json:"rx_packets"`
	TxPackets uint64 `json:"tx_packets"`
	RxErrors  uint64 `json:"rx_errors"`
	TxErrors  uint64 `json:"tx_errors"`
}

type ProcessInfo struct {
	Count int `json:"count"`
}

// Temperature is a thermal zone exposed by Linux. Celsius is omitted from
// the payload when the kernel does not expose any readable thermal zones.
type Temperature struct {
	Zone    string  `json:"zone"`
	Type    string  `json:"type"`
	Celsius float64 `json:"celsius"`
}

type DiskIO struct {
	Device     string `json:"device"`
	ReadBytes  uint64 `json:"read_bytes"`
	WriteBytes uint64 `json:"write_bytes"`
	ReadOps    uint64 `json:"read_ops"`
	WriteOps   uint64 `json:"write_ops"`
	InFlight   uint64 `json:"in_flight"`
}

type Pressure struct {
	Avg10  float64 `json:"avg10"`
	Avg60  float64 `json:"avg60"`
	Avg300 float64 `json:"avg300"`
}

type PressureStats struct {
	CPU    Pressure `json:"cpu"`
	Memory Pressure `json:"memory"`
	IO     Pressure `json:"io"`
}

type ConnectionStats struct {
	Total       int `json:"total"`
	Listening   int `json:"listening"`
	Established int `json:"established"`
}

type Report struct {
	Snapshot Snapshot `json:"snapshot"`
}

type DeviceSummary struct {
	DeviceID          string    `json:"device_id"`
	Hostname          string    `json:"hostname"`
	Name              string    `json:"name"`
	Address           string    `json:"address,omitempty"`
	Port              int       `json:"port,omitempty"`
	TargetID          string    `json:"target_id,omitempty"`
	Collected         time.Time `json:"collected_at"`
	Online            bool      `json:"online"`
	CPUUsagePercent   float64   `json:"cpu_usage_percent"`
	MemoryUsedPercent float64   `json:"memory_used_percent"`
}
