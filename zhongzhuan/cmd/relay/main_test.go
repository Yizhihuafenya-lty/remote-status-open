package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"remotestatus/internal/auth"
	"remotestatus/internal/model"
)

func TestReportThenReadRequiresSeparateTokens(t *testing.T) {
	s := &server{cfg: config{agentTokens: map[string]string{"node-1": "agent-secret"}, appTokens: []string{"app-secret"}, ttl: time.Minute}, store: newSnapshotStore()}
	if err := s.store.upsertTarget(targetConfig{ID: "target-node-1", DeviceID: "node-1", Address: "10.0.0.1", Port: 50000, Scheme: "https"}); err != nil {
		t.Fatal(err)
	}
	handler := s.routes()
	snapshot := model.Snapshot{DeviceID: "node-1", Hostname: "server-a", Collected: time.Now().UTC(), CPU: model.CPU{UsagePercent: 20}}
	body, err := json.Marshal(model.Report{Snapshot: snapshot})
	if err != nil {
		t.Fatal(err)
	}

	wrong := httptest.NewRequest(http.MethodPost, "/v1/agent/report/node-1", bytes.NewReader(body))
	wrong.Header.Set("Authorization", "Bearer app-secret")
	wrongResponse := httptest.NewRecorder()
	handler.ServeHTTP(wrongResponse, wrong)
	if wrongResponse.Code != http.StatusUnauthorized {
		t.Fatalf("wrong token status = %d", wrongResponse.Code)
	}

	report := httptest.NewRequest(http.MethodPost, "/v1/agent/report/node-1", bytes.NewReader(body))
	report.Header.Set("Authorization", "Bearer agent-secret")
	reportResponse := httptest.NewRecorder()
	handler.ServeHTTP(reportResponse, report)
	if reportResponse.Code != http.StatusNoContent {
		t.Fatalf("report status = %d, body=%s", reportResponse.Code, reportResponse.Body.String())
	}

	read := httptest.NewRequest(http.MethodGet, "/v1/devices/node-1/metrics", nil)
	read.Header.Set("Authorization", "Bearer app-secret")
	readResponse := httptest.NewRecorder()
	handler.ServeHTTP(readResponse, read)
	if readResponse.Code != http.StatusOK || !strings.Contains(readResponse.Body.String(), "server-a") {
		t.Fatalf("read status=%d body=%s", readResponse.Code, readResponse.Body.String())
	}
}

func TestListDevicesReturnsMultipleServers(t *testing.T) {
	s := &server{cfg: config{agentTokens: map[string]string{"node-1": "a", "node-2": "b"}, appTokens: []string{"app-secret"}, ttl: time.Minute}, store: newSnapshotStore()}
	s.store.put(model.Snapshot{DeviceID: "node-1", Hostname: "server-a", Collected: time.Now().UTC(), CPU: model.CPU{UsagePercent: 12.5}, Memory: model.Memory{UsedPercent: 44.5}})
	s.store.put(model.Snapshot{DeviceID: "node-2", Hostname: "server-b", Collected: time.Now().UTC(), CPU: model.CPU{UsagePercent: 67.5}, Memory: model.Memory{UsedPercent: 72.5}})

	request := httptest.NewRequest(http.MethodGet, "/v1/devices", nil)
	request.Header.Set("Authorization", "Bearer app-secret")
	response := httptest.NewRecorder()
	s.routes().ServeHTTP(response, request)
	if response.Code != http.StatusOK {
		t.Fatalf("list status = %d, body=%s", response.Code, response.Body.String())
	}
	var body struct {
		Devices []model.DeviceSummary `json:"devices"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if len(body.Devices) != 2 {
		t.Fatalf("device count = %d, want 2", len(body.Devices))
	}
	if body.Devices[0].CPUUsagePercent == 0 && body.Devices[1].CPUUsagePercent == 0 {
		t.Fatal("device list did not include compact CPU usage")
	}
}

func TestNewFleetDeviceRequiresAppRegistration(t *testing.T) {
	const fleetKey = "fleet-secret-for-test"
	s := &server{cfg: config{fleetKey: fleetKey, appTokens: []string{"app-secret"}, ttl: time.Minute}, store: newSnapshotStore()}
	report, err := json.Marshal(model.Report{Snapshot: model.Snapshot{DeviceID: "node-new", Hostname: "new-linux-host", Collected: time.Now().UTC()}})
	if err != nil {
		t.Fatal(err)
	}
	request := httptest.NewRequest(http.MethodPost, "/v1/agent/report/node-new", bytes.NewReader(report))
	request.Header.Set("Authorization", "Bearer "+auth.DeviceToken(fleetKey, "node-new"))
	response := httptest.NewRecorder()
	s.routes().ServeHTTP(response, request)
	if response.Code != http.StatusForbidden {
		t.Fatalf("unregistered device report status=%d body=%s", response.Code, response.Body.String())
	}
	if err := s.store.upsertTarget(targetConfig{ID: "target-node-new", DeviceID: "node-new", Address: "10.0.0.2", Port: 50000, Scheme: "https"}); err != nil {
		t.Fatal(err)
	}
	request = httptest.NewRequest(http.MethodPost, "/v1/agent/report/node-new", bytes.NewReader(report))
	request.Header.Set("Authorization", "Bearer "+auth.DeviceToken(fleetKey, "node-new"))
	response = httptest.NewRecorder()
	s.routes().ServeHTTP(response, request)
	if response.Code != http.StatusNoContent {
		t.Fatalf("registered device report status=%d body=%s", response.Code, response.Body.String())
	}
	if len(s.store.list(time.Minute)) != 1 {
		t.Fatal("new device did not appear in list")
	}

	wrong := httptest.NewRequest(http.MethodPost, "/v1/agent/report/node-other", bytes.NewReader(report))
	wrong.Header.Set("Authorization", "Bearer "+auth.DeviceToken(fleetKey, "node-new"))
	wrongResponse := httptest.NewRecorder()
	s.routes().ServeHTTP(wrongResponse, wrong)
	if wrongResponse.Code != http.StatusUnauthorized {
		t.Fatalf("reused device token status=%d", wrongResponse.Code)
	}
}

func TestParseAgentTokens(t *testing.T) {
	got := parseAgentTokens("node-1=secret-1, node_2=secret-2, invalid token=x")
	if len(got) != 2 || got["node-1"] != "secret-1" || got["node_2"] != "secret-2" {
		t.Fatalf("unexpected tokens: %#v", got)
	}
}

func TestValidID(t *testing.T) {
	for _, value := range []string{"node-1", "db.prod_01"} {
		if !validID(value) {
			t.Errorf("expected valid id: %s", value)
		}
	}
	for _, value := range []string{"", "../etc", "node/id", strings.Repeat("x", 101)} {
		if validID(value) {
			t.Errorf("expected invalid id: %q", value)
		}
	}
}

func TestNameIsStoredAndReturnedAfterReload(t *testing.T) {
	path := filepath.Join(t.TempDir(), "relay-state.json")
	store, err := newPersistentSnapshotStore(path)
	if err != nil {
		t.Fatal(err)
	}
	store.put(model.Snapshot{DeviceID: "node-1", Hostname: "server-a", Collected: time.Now().UTC()})
	s := &server{cfg: config{appTokens: []string{"app-secret"}, ttl: time.Minute}, store: store}
	request := httptest.NewRequest(http.MethodPatch, "/v1/devices/node-1/name", strings.NewReader(`{"name":"生产库"}`))
	request.Header.Set("Authorization", "Bearer app-secret")
	response := httptest.NewRecorder()
	s.routes().ServeHTTP(response, request)
	if response.Code != http.StatusOK {
		t.Fatalf("rename status=%d body=%s", response.Code, response.Body.String())
	}

	reloaded, err := newPersistentSnapshotStore(path)
	if err != nil {
		t.Fatal(err)
	}
	list := reloaded.list(time.Minute)
	if len(list) != 0 {
		// Snapshots are intentionally memory-only, so a fresh process has no
		// metrics yet. The name itself is checked through the persisted map below.
		t.Fatalf("reloaded snapshot count=%d, want no in-memory snapshots", len(list))
	}
	if got := reloaded.names["node-1"]; got != "生产库" {
		t.Fatalf("reloaded name=%q, want 生产库", got)
	}
}

func TestTargetIsStoredAndReturnedAfterReload(t *testing.T) {
	path := filepath.Join(t.TempDir(), "relay-state.json")
	store, err := newPersistentSnapshotStore(path)
	if err != nil {
		t.Fatal(err)
	}
	s := &server{cfg: config{fleetKey: "fleet-secret", appTokens: []string{"app-secret"}, allowInsecure: true}, store: store}
	request := httptest.NewRequest(http.MethodPost, "/v1/targets", strings.NewReader(`{"device_id":"local-node","address":"127.0.0.1","port":50000,"name":"本机 Agent"}`))
	request.Header.Set("Authorization", "Bearer app-secret")
	response := httptest.NewRecorder()
	s.routes().ServeHTTP(response, request)
	if response.Code != http.StatusCreated {
		t.Fatalf("target status=%d body=%s", response.Code, response.Body.String())
	}
	var created targetConfig
	if err := json.Unmarshal(response.Body.Bytes(), &created); err != nil {
		t.Fatal(err)
	}
	if err := store.setName(created.ID, "本机 Agent 重命名"); err != nil {
		t.Fatal(err)
	}

	reloaded, err := newPersistentSnapshotStore(path)
	if err != nil {
		t.Fatal(err)
	}
	targets := reloaded.targetList()
	if len(targets) != 1 || targets[0].Address != "127.0.0.1" || targets[0].Port != 50000 || targets[0].Name != "本机 Agent 重命名" {
		t.Fatalf("reloaded targets=%#v", targets)
	}
}
