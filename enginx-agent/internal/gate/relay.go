package gate

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"time"
)

// Relay asks the platform to mail a code. An interface so tests need no platform.
type Relay interface {
	SendCode(ctx context.Context, site *Site, email, code string) error
}

// HTTPRelay posts to the site's relay URL.
type HTTPRelay struct {
	Client *http.Client
	Now    func() time.Time
}

type relayRequest struct {
	SiteID    string `json:"siteId"`
	Email     string `json:"email"`
	Code      string `json:"code"`
	Timestamp int64  `json:"timestamp"`
	Signature string `json:"signature"`
}

// relaySignature mirrors GateKeyDerivation.relaySignature on the platform.
func relaySignature(site *Site, email, code string, timestamp int64) string {
	mac := hmac.New(sha256.New, site.relayKey)
	mac.Write([]byte(site.ID + "\n" + email + "\n" + code + "\n" + strconv.FormatInt(timestamp, 10)))
	return hex.EncodeToString(mac.Sum(nil))
}

func (r HTTPRelay) SendCode(ctx context.Context, site *Site, email, code string) error {
	now := time.Now
	if r.Now != nil {
		now = r.Now
	}
	ts := now().Unix()
	body, _ := json.Marshal(relayRequest{SiteID: site.ID, Email: email, Code: code, Timestamp: ts,
		Signature: relaySignature(site, email, code, ts)})

	ctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, site.RelayURL, bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	client := r.Client
	if client == nil {
		client = http.DefaultClient
	}
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusNoContent && resp.StatusCode != http.StatusOK {
		detail, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
		return fmt.Errorf("the platform answered %d: %s", resp.StatusCode, bytes.TrimSpace(detail))
	}
	return nil
}
