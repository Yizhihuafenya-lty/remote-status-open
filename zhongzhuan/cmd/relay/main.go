package main

import (
	"crypto/sha256"
	"crypto/subtle"
	"crypto/tls"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"remotestatus/internal/auth"
	"remotestatus/internal/model"
)

type config struct {
	listen        string
	agentTokens   map[string]string
	fleetKey      string
	appTokens     []string
	ttl           time.Duration
	tlsCert       string
	tlsKey        string
	allowInsecure bool
	stateFile     string
}

type targetConfig struct {
	ID       string `json:"id"`
	DeviceID string `json:"device_id,omitempty"`
	Address  string `json:"address"`
	Port     int    `json:"port"`
	Scheme   string `json:"scheme"`
	Name     string `json:"name,omitempty"`
}

type relayState struct {
	Names   map[string]string `json:"names"`
	Targets []targetConfig    `json:"targets"`
}

type snapshotStore struct {
	mu        sync.RWMutex
	byID      map[string]model.Snapshot
	seenAt    map[string]time.Time
	names     map[string]string
	targets   map[string]targetConfig
	stateFile string
}

func newSnapshotStore() *snapshotStore {
	return &snapshotStore{byID: make(map[string]model.Snapshot), seenAt: make(map[string]time.Time), names: make(map[string]string), targets: make(map[string]targetConfig)}
}

func newPersistentSnapshotStore(path string) (*snapshotStore, error) {
	s := newSnapshotStore()
	s.stateFile = strings.TrimSpace(path)
	if s.stateFile == "" {
		return s, nil
	}
	b, err := os.ReadFile(s.stateFile)
	if os.IsNotExist(err) {
		return s, nil
	}
	if err != nil {
		return nil, fmt.Errorf("read relay state: %w", err)
	}
	var state relayState
	if err := json.Unmarshal(b, &state); err != nil {
		return nil, fmt.Errorf("decode relay state: %w", err)
	}
	for id, name := range state.Names {
		if validID(id) && strings.TrimSpace(name) != "" {
			s.names[id] = strings.TrimSpace(name)
		}
	}
	for _, target := range state.Targets {
		if target.ID != "" && target.Address != "" && target.Port > 0 {
			s.targets[target.ID] = target
		}
	}
	return s, nil
}

func (s *snapshotStore) put(snapshot model.Snapshot) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.byID[snapshot.DeviceID] = snapshot
	s.seenAt[snapshot.DeviceID] = time.Now().UTC()
}

func (s *snapshotStore) persistLocked() error {
	if s.stateFile == "" {
		return nil
	}
	targets := make([]targetConfig, 0, len(s.targets))
	for _, target := range s.targets {
		targets = append(targets, target)
	}
	sort.Slice(targets, func(i, j int) bool { return targets[i].ID < targets[j].ID })
	state, err := json.MarshalIndent(relayState{Names: s.names, Targets: targets}, "", "  ")
	if err != nil {
		return err
	}
	dir := filepath.Dir(s.stateFile)
	if err := os.MkdirAll(dir, 0750); err != nil {
		return err
	}
	tmp := s.stateFile + ".tmp"
	if err := os.WriteFile(tmp, state, 0600); err != nil {
		return err
	}
	return os.Rename(tmp, s.stateFile)
}

func (s *snapshotStore) setName(id, name string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if name == "" {
		delete(s.names, id)
	} else {
		s.names[id] = name
	}
	for key, target := range s.targets {
		if target.DeviceID == id || key == id {
			target.Name = name
			s.targets[key] = target
		}
	}
	return s.persistLocked()
}

func (s *snapshotStore) upsertTarget(target targetConfig) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.targets[target.ID] = target
	if target.DeviceID != "" && target.Name != "" {
		s.names[target.DeviceID] = target.Name
	}
	return s.persistLocked()
}

func (s *snapshotStore) bindTarget(targetID, deviceID string) (targetConfig, bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	target, ok := s.targets[targetID]
	if !ok {
		return targetConfig{}, false, nil
	}
	target.DeviceID = deviceID
	s.targets[targetID] = target
	if target.Name != "" {
		s.names[deviceID] = target.Name
	}
	return target, true, s.persistLocked()
}

func (s *snapshotStore) targetList() []targetConfig {
	s.mu.RLock()
	defer s.mu.RUnlock()
	result := make([]targetConfig, 0, len(s.targets))
	for _, target := range s.targets {
		result = append(result, target)
	}
	sort.Slice(result, func(i, j int) bool { return result[i].ID < result[j].ID })
	return result
}

func (s *snapshotStore) get(id string) (model.Snapshot, time.Time, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	snapshot, ok := s.byID[id]
	return snapshot, s.seenAt[id], ok
}

func (s *snapshotStore) list(ttl time.Duration) []model.DeviceSummary {
	s.mu.RLock()
	defer s.mu.RUnlock()
	result := make([]model.DeviceSummary, 0, len(s.byID)+len(s.targets))
	now := time.Now()
	for id, snapshot := range s.byID {
		seen := s.seenAt[id]
		target := s.targetForDeviceLocked(id)
		name := s.names[id]
		if name == "" && target.Name != "" {
			name = target.Name
		}
		result = append(result, model.DeviceSummary{
			DeviceID:          id,
			Hostname:          snapshot.Hostname,
			Name:              name,
			Address:           target.Address,
			Port:              target.Port,
			TargetID:          target.ID,
			Collected:         snapshot.Collected,
			Online:            now.Sub(seen) <= ttl,
			CPUUsagePercent:   snapshot.CPU.UsagePercent,
			MemoryUsedPercent: snapshot.Memory.UsedPercent,
		})
	}
	for _, target := range s.targets {
		id := target.DeviceID
		if id == "" {
			id = target.ID
		}
		if _, exists := s.byID[id]; exists {
			continue
		}
		name := target.Name
		if name == "" {
			name = target.Address
		}
		result = append(result, model.DeviceSummary{DeviceID: id, Hostname: name, Name: name, Address: target.Address, Port: target.Port, TargetID: target.ID, Online: false})
	}
	sort.Slice(result, func(i, j int) bool { return result[i].DeviceID < result[j].DeviceID })
	return result
}

func (s *snapshotStore) targetForDeviceLocked(deviceID string) targetConfig {
	for _, target := range s.targets {
		if target.DeviceID == deviceID {
			return target
		}
	}
	return targetConfig{}
}

func (s *snapshotStore) hasTargetDevice(deviceID string) bool {
	s.mu.RLock()
	defer s.mu.RUnlock()
	for _, target := range s.targets {
		if target.DeviceID == deviceID {
			return true
		}
	}
	return false
}

type server struct {
	cfg   config
	store *snapshotStore
}

func (s *server) routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", s.health)
	mux.HandleFunc("/v1/agent/report/", s.agentReport)
	mux.HandleFunc("/v1/devices", s.devices)
	mux.HandleFunc("/v1/devices/", s.deviceRoutes)
	mux.HandleFunc("/v1/targets", s.targets)
	return securityHeaders(mux)
}

func (s *server) health(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (s *server) agentReport(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	deviceID := strings.Trim(strings.TrimPrefix(r.URL.Path, "/v1/agent/report/"), "/")
	if !validID(deviceID) {
		writeError(w, http.StatusBadRequest, "invalid device id")
		return
	}
	token, ok := bearerToken(r)
	want, configured := s.cfg.agentTokens[deviceID]
	if !configured && s.cfg.fleetKey != "" {
		want, configured = auth.DeviceToken(s.cfg.fleetKey, deviceID), true
	}
	if !configured || !ok || !secureEqual(token, want) {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	if !s.store.hasTargetDevice(deviceID) {
		writeError(w, http.StatusForbidden, "device is not enabled by the app")
		return
	}
	var report model.Report
	decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 2<<20))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&report); err != nil {
		writeError(w, http.StatusBadRequest, "invalid report")
		return
	}
	if report.Snapshot.DeviceID != deviceID || report.Snapshot.Hostname == "" {
		writeError(w, http.StatusBadRequest, "report identity mismatch")
		return
	}
	if report.Snapshot.Collected.IsZero() || report.Snapshot.Collected.After(time.Now().Add(2*time.Minute)) {
		writeError(w, http.StatusBadRequest, "invalid collected_at")
		return
	}
	s.store.put(report.Snapshot)
	w.WriteHeader(http.StatusNoContent)
}

func (s *server) devices(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/v1/devices" {
		writeError(w, http.StatusNotFound, "not found")
		return
	}
	if r.Method != http.MethodGet {
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	if !s.authorizeApp(r) {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"devices": s.store.list(s.cfg.ttl)})
}

func (s *server) deviceRoutes(w http.ResponseWriter, r *http.Request) {
	path := strings.Trim(strings.TrimPrefix(r.URL.Path, "/v1/devices/"), "/")
	if strings.HasSuffix(path, "/name") {
		s.deviceName(w, r)
		return
	}
	s.deviceMetrics(w, r)
}

func (s *server) deviceName(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPatch && r.Method != http.MethodPut {
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	if !s.authorizeApp(r) {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	id := strings.TrimSuffix(strings.Trim(strings.TrimPrefix(r.URL.Path, "/v1/devices/"), "/"), "/name")
	if !validID(id) {
		writeError(w, http.StatusBadRequest, "invalid device id")
		return
	}
	var body struct {
		Name string `json:"name"`
	}
	decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4<<10))
	if err := decoder.Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "invalid name")
		return
	}
	name := strings.TrimSpace(body.Name)
	if err := s.store.setName(id, name); err != nil {
		writeError(w, http.StatusInternalServerError, "save name failed")
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"device_id": id, "name": name})
}

type targetRequest struct {
	DeviceID string `json:"device_id"`
	Address  string `json:"address"`
	Port     int    `json:"port"`
	Name     string `json:"name"`
}

func (s *server) targets(w http.ResponseWriter, r *http.Request) {
	if !s.authorizeApp(r) {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	switch r.Method {
	case http.MethodGet:
		writeJSON(w, http.StatusOK, map[string]any{"targets": s.store.targetList()})
		return
	case http.MethodPost:
		if s.cfg.fleetKey == "" {
			writeError(w, http.StatusServiceUnavailable, "RELAY_FLEET_KEY is required for target connections")
			return
		}
	default:
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	var body targetRequest
	decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 8<<10))
	if err := decoder.Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "invalid target")
		return
	}
	if body.DeviceID == "" {
		writeError(w, http.StatusBadRequest, "device_id is required; add the server in the app first")
		return
	}
	if !validID(body.DeviceID) {
		writeError(w, http.StatusBadRequest, "invalid device id")
		return
	}
	scheme, address, port, err := normalizeTarget(body.Address, body.Port, s.cfg.allowInsecure)
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	target := targetConfig{ID: targetID(scheme, address, port), DeviceID: body.DeviceID, Address: address, Port: port, Scheme: scheme, Name: strings.TrimSpace(body.Name)}
	if err := s.store.upsertTarget(target); err != nil {
		writeError(w, http.StatusInternalServerError, "save target failed")
		return
	}
	writeJSON(w, http.StatusCreated, target)
}

func normalizeTarget(raw string, port int, allowInsecure bool) (string, string, int, error) {
	value := strings.TrimSpace(strings.TrimRight(raw, "/"))
	if value == "" {
		return "", "", 0, errors.New("address is required")
	}
	if !strings.Contains(value, "://") {
		scheme := "https"
		if allowInsecure {
			scheme = "http"
		}
		value = scheme + "://" + value
	}
	u, err := url.Parse(value)
	if err != nil || u.Hostname() == "" {
		return "", "", 0, errors.New("invalid address")
	}
	if u.Scheme != "https" && u.Scheme != "http" {
		return "", "", 0, errors.New("only HTTP and HTTPS are supported")
	}
	if u.Scheme == "http" && !allowInsecure {
		return "", "", 0, errors.New("HTTP target requires RELAY_ALLOW_INSECURE=true")
	}
	if u.Path != "" && u.Path != "/" || u.RawQuery != "" || u.Fragment != "" {
		return "", "", 0, errors.New("target address must not contain a path or query")
	}
	if u.Port() != "" {
		parsedPort, parseErr := strconv.Atoi(u.Port())
		if parseErr != nil {
			return "", "", 0, errors.New("invalid target port")
		}
		if port != 0 && port != parsedPort {
			return "", "", 0, errors.New("address port and port field do not match")
		}
		port = parsedPort
	}
	if port == 0 {
		port = 50000
	}
	if port < 1 || port > 65535 {
		return "", "", 0, errors.New("target port must be between 1 and 65535")
	}
	return u.Scheme, u.Hostname(), port, nil
}

func targetID(scheme, address string, port int) string {
	sum := sha256.Sum256([]byte(scheme + "://" + address + ":" + strconv.Itoa(port)))
	return "target-" + hex.EncodeToString(sum[:8])
}

func (s *server) deviceMetrics(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	if !s.authorizeApp(r) {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	id := strings.Trim(strings.TrimPrefix(r.URL.Path, "/v1/devices/"), "/")
	id = strings.TrimSuffix(id, "/metrics")
	id = strings.Trim(id, "/")
	if !validID(id) {
		writeError(w, http.StatusBadRequest, "invalid device id")
		return
	}
	snapshot, seen, ok := s.store.get(id)
	if !ok {
		writeError(w, http.StatusNotFound, "device not found")
		return
	}
	age := time.Since(seen).Seconds()
	writeJSON(w, http.StatusOK, map[string]any{
		"snapshot":    snapshot,
		"online":      age <= s.cfg.ttl.Seconds(),
		"age_seconds": age,
	})
}

func (s *server) authorizeApp(r *http.Request) bool {
	token, ok := bearerToken(r)
	if !ok {
		return false
	}
	for _, candidate := range s.cfg.appTokens {
		if secureEqual(token, candidate) {
			return true
		}
	}
	return false
}

func loadConfig() (config, error) {
	cfg := config{
		listen:        envOr("RELAY_LISTEN", ":50001"),
		agentTokens:   parseAgentTokens(os.Getenv("RELAY_AGENT_TOKENS")),
		fleetKey:      strings.TrimSpace(os.Getenv("RELAY_FLEET_KEY")),
		appTokens:     parseList(os.Getenv("RELAY_APP_TOKENS")),
		tlsCert:       os.Getenv("RELAY_TLS_CERT_FILE"),
		tlsKey:        os.Getenv("RELAY_TLS_KEY_FILE"),
		allowInsecure: strings.EqualFold(os.Getenv("RELAY_ALLOW_INSECURE"), "true"),
		stateFile:     envOr("RELAY_STATE_FILE", "relay-state.json"),
	}
	cfg.ttl = 90 * time.Second
	if raw := os.Getenv("RELAY_DATA_TTL"); raw != "" {
		ttl, err := time.ParseDuration(raw)
		if err != nil || ttl <= 0 {
			return config{}, fmt.Errorf("invalid RELAY_DATA_TTL: %w", err)
		}
		cfg.ttl = ttl
	}
	if len(cfg.agentTokens) == 0 && cfg.fleetKey == "" {
		return config{}, errors.New("RELAY_FLEET_KEY or RELAY_AGENT_TOKENS is required")
	}
	if len(cfg.appTokens) == 0 {
		return config{}, errors.New("RELAY_APP_TOKENS is required")
	}
	if !cfg.allowInsecure && (cfg.tlsCert == "" || cfg.tlsKey == "") {
		return config{}, errors.New("RELAY_TLS_CERT_FILE and RELAY_TLS_KEY_FILE are required unless RELAY_ALLOW_INSECURE=true")
	}
	return cfg, nil
}

func main() {
	log.SetFlags(log.LstdFlags | log.LUTC)
	cfg, err := loadConfig()
	if err != nil {
		log.Fatal(err)
	}
	store, err := newPersistentSnapshotStore(cfg.stateFile)
	if err != nil {
		log.Fatal(err)
	}
	app := &server{cfg: cfg, store: store}
	srv := &http.Server{Addr: cfg.listen, Handler: app.routes(), ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 15 * time.Second, WriteTimeout: 15 * time.Second, IdleTimeout: 60 * time.Second, MaxHeaderBytes: 16 << 10}
	log.Printf("relay listening on %s (insecure=%t)", cfg.listen, cfg.allowInsecure)
	if cfg.allowInsecure {
		log.Fatal(srv.ListenAndServe())
	}
	tlsConfig := &tls.Config{MinVersion: tls.VersionTLS12}
	srv.TLSConfig = tlsConfig
	log.Fatal(srv.ListenAndServeTLS(cfg.tlsCert, cfg.tlsKey))
}

func bearerToken(r *http.Request) (string, bool) {
	value := strings.TrimSpace(r.Header.Get("Authorization"))
	if len(value) < len("Bearer ")+1 || !strings.EqualFold(value[:len("Bearer ")], "Bearer ") {
		return "", false
	}
	token := strings.TrimSpace(value[len("Bearer "):])
	return token, token != ""
}

func secureEqual(a, b string) bool {
	if len(a) != len(b) {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(a), []byte(b)) == 1
}

func parseAgentTokens(raw string) map[string]string {
	result := make(map[string]string)
	for _, item := range strings.Split(raw, ",") {
		parts := strings.SplitN(strings.TrimSpace(item), "=", 2)
		if len(parts) == 2 && validID(strings.TrimSpace(parts[0])) && strings.TrimSpace(parts[1]) != "" {
			result[strings.TrimSpace(parts[0])] = strings.TrimSpace(parts[1])
		}
	}
	return result
}

func parseList(raw string) []string {
	result := make([]string, 0)
	for _, item := range strings.Split(raw, ",") {
		if item = strings.TrimSpace(item); item != "" {
			result = append(result, item)
		}
	}
	return result
}

func validID(value string) bool {
	if value == "" || len(value) > 100 {
		return false
	}
	for _, r := range value {
		if (r < 'a' || r > 'z') && (r < 'A' || r > 'Z') && (r < '0' || r > '9') && r != '-' && r != '_' && r != '.' {
			return false
		}
	}
	return true
}

func envOr(key, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(key)); value != "" {
		return value
	}
	return fallback
}

func securityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		next.ServeHTTP(w, r)
	})
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

func writeError(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, map[string]string{"error": message})
}
