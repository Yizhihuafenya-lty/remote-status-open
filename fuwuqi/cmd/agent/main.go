package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"crypto/tls"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"remotestatus/internal/auth"
	"remotestatus/internal/metrics"
	"remotestatus/internal/model"
)

type config struct {
	relayURL      string
	deviceID      string
	deviceToken   string
	interval      time.Duration
	allowInsecure bool
}

func loadConfig() (config, error) {
	cfg := config{relayURL: strings.TrimRight(strings.TrimSpace(os.Getenv("AGENT_RELAY_URL")), "/"), deviceID: strings.TrimSpace(os.Getenv("AGENT_DEVICE_ID")), deviceToken: strings.TrimSpace(os.Getenv("AGENT_DEVICE_TOKEN")), allowInsecure: strings.EqualFold(os.Getenv("AGENT_ALLOW_INSECURE"), "true")}
	if cfg.deviceID == "" {
		var err error
		cfg.deviceID, err = autoDeviceID()
		if err != nil {
			return config{}, err
		}
	}
	if cfg.deviceToken == "" {
		fleetKey := strings.TrimSpace(os.Getenv("AGENT_FLEET_KEY"))
		if fleetKey != "" {
			cfg.deviceToken = auth.DeviceToken(fleetKey, cfg.deviceID)
		}
	}
	if cfg.relayURL == "" || cfg.deviceToken == "" {
		return config{}, errors.New("AGENT_RELAY_URL and AGENT_FLEET_KEY (or AGENT_DEVICE_TOKEN) are required")
	}
	u, err := url.Parse(cfg.relayURL)
	if err != nil || (u.Scheme != "https" && !(cfg.allowInsecure && u.Scheme == "http")) || u.Host == "" {
		return config{}, errors.New("AGENT_RELAY_URL must be an https URL (http only with AGENT_ALLOW_INSECURE=true)")
	}
	cfg.interval = time.Second
	if raw := strings.TrimSpace(os.Getenv("AGENT_INTERVAL")); raw != "" {
		interval, parseErr := time.ParseDuration(raw)
		if parseErr != nil || interval < time.Second {
			return config{}, fmt.Errorf("invalid AGENT_INTERVAL: %w", parseErr)
		}
		cfg.interval = interval
	}
	return cfg, nil
}

func autoDeviceID() (string, error) {
	for _, path := range []string{"/etc/machine-id", "/var/lib/dbus/machine-id"} {
		value, err := os.ReadFile(path)
		if err == nil && strings.TrimSpace(string(value)) != "" {
			hash := sha256.Sum256([]byte(strings.TrimSpace(string(value))))
			return "node-" + hex.EncodeToString(hash[:16]), nil
		}
	}
	return "", errors.New("machine-id not found; set AGENT_DEVICE_ID explicitly")
}

func main() {
	log.SetFlags(log.LstdFlags | log.LUTC)
	cfg, err := loadConfig()
	if err != nil {
		log.Fatal(err)
	}
	collector := metrics.New()
	transport := &http.Transport{TLSClientConfig: &tls.Config{MinVersion: tls.VersionTLS12, InsecureSkipVerify: cfg.allowInsecure}}
	client := &http.Client{Transport: transport, Timeout: 15 * time.Second}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	log.Printf("agent started: device=%s interval=%s outbound-only", cfg.deviceID, cfg.interval)
	ticker := time.NewTicker(cfg.interval)
	defer ticker.Stop()
	for {
		if err := report(ctx, cfg, collector, client); err != nil {
			log.Printf("report failed: %v", err)
		}
		select {
		case <-ctx.Done():
			log.Print("agent stopped")
			return
		case <-ticker.C:
		}
	}
}

func report(ctx context.Context, cfg config, collector *metrics.Collector, client *http.Client) error {
	snapshot, err := collector.Collect(ctx, cfg.deviceID)
	if err != nil {
		return err
	}
	payload, err := json.Marshal(model.Report{Snapshot: snapshot})
	if err != nil {
		return fmt.Errorf("encode report: %w", err)
	}
	endpoint := cfg.relayURL + "/v1/agent/report/" + url.PathEscape(cfg.deviceID)
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(payload))
	if err != nil {
		return fmt.Errorf("create request: %w", err)
	}
	request.Header.Set("Authorization", "Bearer "+cfg.deviceToken)
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Accept", "application/json")
	response, err := client.Do(request)
	if err != nil {
		return fmt.Errorf("send report: %w", err)
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		body, _ := io.ReadAll(io.LimitReader(response.Body, 512))
		return fmt.Errorf("relay returned %s: %s", response.Status, strings.TrimSpace(string(body)))
	}
	return nil
}
