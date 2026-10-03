//go:build linux

package metrics

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"remotestatus/internal/model"
)

type cpuCounters struct {
	total uint64
	idle  uint64
}

// Collector reads Linux's procfs plus filesystem statistics. It keeps only a
// small CPU counter delta in memory; no raw process data leaves the host.
type Collector struct {
	mu       sync.Mutex
	previous cpuCounters
	hasPrev  bool
}

func New() *Collector { return &Collector{} }

func (c *Collector) Collect(_ context.Context, deviceID string) (model.Snapshot, error) {
	if deviceID == "" {
		return model.Snapshot{}, errors.New("device id is required")
	}
	hostname, err := os.Hostname()
	if err != nil {
		return model.Snapshot{}, fmt.Errorf("hostname: %w", err)
	}

	up, err := readUptime()
	if err != nil {
		return model.Snapshot{}, err
	}
	load, err := readLoad()
	if err != nil {
		return model.Snapshot{}, err
	}
	cpu, err := c.readCPU()
	if err != nil {
		return model.Snapshot{}, err
	}
	memory, err := readMemory()
	if err != nil {
		return model.Snapshot{}, err
	}
	filesystems := readFilesystems()
	network := readNetwork()
	processes := readProcesses()
	temperatures := readTemperatures()
	diskIO := readDiskIO()
	pressure := readPressure()
	connections := readConnections()

	return model.Snapshot{
		DeviceID:     deviceID,
		Hostname:     hostname,
		Collected:    time.Now().UTC(),
		UptimeSec:    up,
		Load:         load,
		CPU:          cpu,
		Memory:       memory,
		Filesystems:  filesystems,
		Network:      network,
		Processes:    processes,
		Temperatures: temperatures,
		DiskIO:       diskIO,
		Pressure:     pressure,
		Connections:  connections,
	}, nil
}

func readUptime() (float64, error) {
	b, err := os.ReadFile("/proc/uptime")
	if err != nil {
		return 0, fmt.Errorf("read uptime: %w", err)
	}
	fields := strings.Fields(string(b))
	if len(fields) == 0 {
		return 0, errors.New("invalid /proc/uptime")
	}
	v, err := strconv.ParseFloat(fields[0], 64)
	if err != nil {
		return 0, fmt.Errorf("parse uptime: %w", err)
	}
	return v, nil
}

func readLoad() ([3]float64, error) {
	var load [3]float64
	b, err := os.ReadFile("/proc/loadavg")
	if err != nil {
		return load, fmt.Errorf("read loadavg: %w", err)
	}
	fields := strings.Fields(string(b))
	if len(fields) < 3 {
		return load, errors.New("invalid /proc/loadavg")
	}
	for i := range load {
		load[i], err = strconv.ParseFloat(fields[i], 64)
		if err != nil {
			return load, fmt.Errorf("parse loadavg: %w", err)
		}
	}
	return load, nil
}

func (c *Collector) readCPU() (model.CPU, error) {
	b, err := os.ReadFile("/proc/stat")
	if err != nil {
		return model.CPU{}, fmt.Errorf("read stat: %w", err)
	}
	var current cpuCounters
	for _, line := range strings.Split(string(b), "\n") {
		fields := strings.Fields(line)
		if len(fields) < 5 || fields[0] != "cpu" {
			continue
		}
		for _, f := range fields[1:] {
			v, parseErr := strconv.ParseUint(f, 10, 64)
			if parseErr == nil {
				current.total += v
			}
		}
		idle, _ := strconv.ParseUint(fields[4], 10, 64)
		current.idle = idle
		if len(fields) > 5 {
			iowait, _ := strconv.ParseUint(fields[5], 10, 64)
			current.idle += iowait
		}
		break
	}
	if current.total == 0 {
		return model.CPU{}, errors.New("invalid /proc/stat cpu line")
	}

	c.mu.Lock()
	prev, hasPrev := c.previous, c.hasPrev
	c.previous, c.hasPrev = current, true
	c.mu.Unlock()

	usage := 0.0
	if hasPrev && current.total > prev.total {
		totalDelta := current.total - prev.total
		idleDelta := current.idle - prev.idle
		if idleDelta > totalDelta {
			idleDelta = totalDelta
		}
		usage = (1 - float64(idleDelta)/float64(totalDelta)) * 100
	}
	return model.CPU{UsagePercent: usage, CoreCount: runtime.NumCPU()}, nil
}

func readMemory() (model.Memory, error) {
	b, err := os.ReadFile("/proc/meminfo")
	if err != nil {
		return model.Memory{}, fmt.Errorf("read meminfo: %w", err)
	}
	values := make(map[string]uint64)
	for _, line := range strings.Split(string(b), "\n") {
		fields := strings.Fields(line)
		if len(fields) < 2 {
			continue
		}
		v, parseErr := strconv.ParseUint(fields[1], 10, 64)
		if parseErr != nil {
			continue
		}
		// Linux reports most memory values in KiB.
		if len(fields) > 2 && strings.EqualFold(fields[2], "kb") {
			v *= 1024
		}
		values[strings.TrimSuffix(fields[0], ":")] = v
	}
	total := values["MemTotal"]
	available := values["MemAvailable"]
	if total == 0 {
		return model.Memory{}, errors.New("invalid /proc/meminfo")
	}
	usedPercent := (1 - float64(available)/float64(total)) * 100
	if usedPercent < 0 {
		usedPercent = 0
	}
	return model.Memory{
		TotalBytes:     total,
		AvailableBytes: available,
		UsedPercent:    usedPercent,
		SwapTotalBytes: values["SwapTotal"],
		SwapFreeBytes:  values["SwapFree"],
	}, nil
}

func readFilesystems() []model.Filesystem {
	f, err := os.Open("/proc/mounts")
	if err != nil {
		return nil
	}
	defer f.Close()
	seen := make(map[string]bool)
	result := make([]model.Filesystem, 0, 16)
	scanner := bufio.NewScanner(f)
	for scanner.Scan() && len(result) < 64 {
		fields := strings.Fields(scanner.Text())
		if len(fields) < 3 {
			continue
		}
		device, mount, fsType := fields[0], fields[1], fields[2]
		if seen[mount] || skipFilesystem(fsType) {
			continue
		}
		var stat syscall.Statfs_t
		if syscall.Statfs(mount, &stat) != nil || stat.Blocks == 0 {
			continue
		}
		blockSize := uint64(stat.Bsize)
		total := stat.Blocks * blockSize
		free := stat.Bavail * blockSize
		used := total - stat.Bfree*blockSize
		usedPercent := 0.0
		if total > 0 {
			usedPercent = float64(used) / float64(total) * 100
		}
		seen[mount] = true
		result = append(result, model.Filesystem{Mount: mount, Filesystem: device, TotalBytes: total, UsedBytes: used, FreeBytes: free, UsedPercent: usedPercent})
	}
	return result
}

func skipFilesystem(fsType string) bool {
	switch fsType {
	case "proc", "sysfs", "devtmpfs", "devpts", "cgroup", "cgroup2", "pstore", "debugfs", "tracefs", "securityfs", "configfs", "fusectl", "mqueue", "hugetlbfs", "binfmt_misc", "autofs":
		return true
	default:
		return false
	}
}

func readNetwork() []model.Network {
	b, err := os.ReadFile("/proc/net/dev")
	if err != nil {
		return nil
	}
	result := make([]model.Network, 0, 8)
	for _, line := range strings.Split(string(b), "\n") {
		parts := strings.SplitN(line, ":", 2)
		if len(parts) != 2 {
			continue
		}
		name := strings.TrimSpace(parts[0])
		fields := strings.Fields(parts[1])
		if name == "" || len(fields) < 11 {
			continue
		}
		v := make([]uint64, 11)
		valid := true
		for i := range v {
			v[i], err = strconv.ParseUint(fields[i], 10, 64)
			if err != nil {
				valid = false
				break
			}
		}
		if !valid {
			continue
		}
		result = append(result, model.Network{Interface: name, RxBytes: v[0], RxPackets: v[1], RxErrors: v[2], TxBytes: v[8], TxPackets: v[9], TxErrors: v[10]})
	}
	return result
}

func readProcesses() model.ProcessInfo {
	entries, err := os.ReadDir("/proc")
	if err != nil {
		return model.ProcessInfo{}
	}
	count := 0
	for _, entry := range entries {
		if entry.IsDir() && isDigits(entry.Name()) {
			count++
		}
	}
	return model.ProcessInfo{Count: count}
}

func readTemperatures() []model.Temperature {
	zones, err := filepath.Glob("/sys/class/thermal/thermal_zone*/temp")
	if err != nil {
		return nil
	}
	result := make([]model.Temperature, 0, len(zones))
	for _, tempPath := range zones {
		b, readErr := os.ReadFile(tempPath)
		if readErr != nil {
			continue
		}
		milli, parseErr := strconv.ParseFloat(strings.TrimSpace(string(b)), 64)
		if parseErr != nil {
			continue
		}
		celsius := milli / 1000
		if celsius < -100 || celsius > 200 {
			continue
		}
		zoneDir := filepath.Dir(tempPath)
		zone := filepath.Base(zoneDir)
		zoneType := zone
		if b, typeErr := os.ReadFile(filepath.Join(zoneDir, "type")); typeErr == nil {
			if value := strings.TrimSpace(string(b)); value != "" {
				zoneType = value
			}
		}
		result = append(result, model.Temperature{Zone: zone, Type: zoneType, Celsius: celsius})
	}
	return result
}

func readDiskIO() []model.DiskIO {
	b, err := os.ReadFile("/proc/diskstats")
	if err != nil {
		return nil
	}
	result := make([]model.DiskIO, 0, 16)
	for _, line := range strings.Split(string(b), "\n") {
		fields := strings.Fields(line)
		if len(fields) < 14 {
			continue
		}
		device := fields[2]
		if isDiskPartition(device) {
			continue
		}
		values := make([]uint64, 11)
		valid := true
		for i := range values {
			values[i], err = strconv.ParseUint(fields[i+3], 10, 64)
			if err != nil {
				valid = false
				break
			}
		}
		if !valid {
			continue
		}
		result = append(result, model.DiskIO{
			Device: device, ReadOps: values[0], ReadBytes: values[2] * 512,
			WriteOps: values[4], WriteBytes: values[6] * 512, InFlight: values[8],
		})
		if len(result) >= 32 {
			break
		}
	}
	return result
}

func isDiskPartition(device string) bool {
	if device == "" {
		return true
	}
	partitionPath := filepath.Join("/sys/class/block", device, "partition")
	if _, err := os.Stat(partitionPath); err == nil {
		return true
	}
	for _, prefix := range []string{"loop", "ram", "fd", "sr", "dm-", "md", "zram"} {
		if strings.HasPrefix(device, prefix) {
			return true
		}
	}
	return false
}

func readPressure() model.PressureStats {
	return model.PressureStats{
		CPU:    readPressureFile("/proc/pressure/cpu"),
		Memory: readPressureFile("/proc/pressure/memory"),
		IO:     readPressureFile("/proc/pressure/io"),
	}
}

func readPressureFile(path string) model.Pressure {
	b, err := os.ReadFile(path)
	if err != nil {
		return model.Pressure{}
	}
	for _, line := range strings.Split(string(b), "\n") {
		fields := strings.Fields(line)
		if len(fields) == 0 || fields[0] != "some" {
			continue
		}
		var pressure model.Pressure
		for _, field := range fields[1:] {
			parts := strings.SplitN(field, "=", 2)
			if len(parts) != 2 {
				continue
			}
			value, parseErr := strconv.ParseFloat(parts[1], 64)
			if parseErr != nil {
				continue
			}
			switch parts[0] {
			case "avg10":
				pressure.Avg10 = value
			case "avg60":
				pressure.Avg60 = value
			case "avg300":
				pressure.Avg300 = value
			}
		}
		return pressure
	}
	return model.Pressure{}
}

func readConnections() model.ConnectionStats {
	var result model.ConnectionStats
	for _, path := range []string{"/proc/net/tcp", "/proc/net/tcp6"} {
		b, err := os.ReadFile(path)
		if err != nil {
			continue
		}
		for _, line := range strings.Split(string(b), "\n")[1:] {
			fields := strings.Fields(line)
			if len(fields) < 4 {
				continue
			}
			result.Total++
			switch fields[3] {
			case "0A": // TCP_LISTEN
				result.Listening++
			case "01": // TCP_ESTABLISHED
				result.Established++
			}
		}
	}
	return result
}

func isDigits(s string) bool {
	if s == "" {
		return false
	}
	for _, r := range s {
		if r < '0' || r > '9' {
			return false
		}
	}
	return true
}
