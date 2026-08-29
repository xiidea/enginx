package api

import (
	"crypto/x509"
	"encoding/pem"
	"os"
	"path/filepath"
	"sort"
	"time"
)

// CertificateStatus describes one certificate found in the active bundle.
type CertificateStatus struct {
	Path          string `json:"path"`
	Subject       string `json:"subject"`
	NotAfter      string `json:"notAfter"`
	DaysRemaining int    `json:"daysRemaining"`
	Status        string `json:"status"`
}

// StatusResponse is what GET /agent/v1/status returns.
type StatusResponse struct {
	AgentVersion     string              `json:"agentVersion"`
	NginxVersion     string              `json:"nginxVersion"`
	NginxRunning     bool                `json:"nginxRunning"`
	NginxMasterPID   int                 `json:"nginxMasterPid"`
	ActiveBundleID   string              `json:"activeBundleId,omitempty"`
	ActiveSince      string              `json:"activeSince,omitempty"`
	AvailableBundles []string            `json:"availableBundles"`
	ConfigTestOK     bool                `json:"configTestOk"`
	ConfigTestOutput string              `json:"configTestOutput,omitempty"`
	UptimeSeconds    int64               `json:"uptimeSeconds"`
	Certificates     []CertificateStatus `json:"certificates"`
}

// activeBundle resolves the `current` symlink to the release it points at.
func activeBundle(releasesDir string) (id string, since string) {
	link := filepath.Join(releasesDir, "current")
	target, err := os.Readlink(link)
	if err != nil {
		return "", ""
	}
	info, err := os.Lstat(link)
	if err == nil {
		since = info.ModTime().UTC().Format(time.RFC3339)
	}
	return filepath.Base(target), since
}

func availableBundles(releasesDir string) []string {
	entries, err := os.ReadDir(filepath.Join(releasesDir, "releases"))
	if err != nil {
		return []string{}
	}
	bundles := make([]string, 0, len(entries))
	for _, entry := range entries {
		if entry.IsDir() {
			bundles = append(bundles, entry.Name())
		}
	}
	sort.Strings(bundles)
	return bundles
}

// inspectCertificates reads the leaf of every fullchain in the active bundle. Only the public
// certificate is ever parsed; private keys are never opened, logged or reported.
func inspectCertificates(releasesDir string, now time.Time) []CertificateStatus {
	certsDir := filepath.Join(releasesDir, "current", "certs")
	results := make([]CertificateStatus, 0)

	entries, err := os.ReadDir(certsDir)
	if err != nil {
		return results
	}
	for _, entry := range entries {
		if !entry.IsDir() {
			continue
		}
		path := filepath.Join(certsDir, entry.Name(), "fullchain.pem")
		leaf, err := parseLeaf(path)
		if err != nil {
			continue
		}
		days := int(leaf.NotAfter.Sub(now).Hours() / 24)
		results = append(results, CertificateStatus{
			Path:          filepath.Join("certs", entry.Name(), "fullchain.pem"),
			Subject:       leaf.Subject.String(),
			NotAfter:      leaf.NotAfter.UTC().Format(time.RFC3339),
			DaysRemaining: days,
			Status:        certificateStatus(days),
		})
	}
	return results
}

func certificateStatus(daysRemaining int) string {
	switch {
	case daysRemaining < 0:
		return "EXPIRED"
	case daysRemaining <= 30:
		return "EXPIRING_SOON"
	default:
		return "VALID"
	}
}

func parseLeaf(path string) (*x509.Certificate, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	block, _ := pem.Decode(raw)
	if block == nil {
		return nil, os.ErrInvalid
	}
	return x509.ParseCertificate(block.Bytes)
}
