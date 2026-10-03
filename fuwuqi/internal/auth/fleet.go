package auth

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
)

// DeviceToken derives a distinct bearer token for each device from a shared
// deployment key. The relay can verify a new device without pre-registering it.
func DeviceToken(fleetKey, deviceID string) string {
	mac := hmac.New(sha256.New, []byte(fleetKey))
	mac.Write([]byte("remote-status-agent-v1:"))
	mac.Write([]byte(deviceID))
	return hex.EncodeToString(mac.Sum(nil))
}
