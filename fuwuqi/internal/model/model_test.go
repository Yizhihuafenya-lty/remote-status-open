package model

import (
	"encoding/json"
	"testing"
	"time"
)

func TestSnapshotJSONRoundTrip(t *testing.T) {
	in := Snapshot{DeviceID: "node-1", Collected: time.Unix(42, 0).UTC(), CPU: CPU{UsagePercent: 12.5}}
	b, err := json.Marshal(in)
	if err != nil {
		t.Fatal(err)
	}
	var out Snapshot
	if err := json.Unmarshal(b, &out); err != nil {
		t.Fatal(err)
	}
	if out.DeviceID != in.DeviceID || out.CPU.UsagePercent != in.CPU.UsagePercent {
		t.Fatalf("round trip mismatch: %#v", out)
	}
}
