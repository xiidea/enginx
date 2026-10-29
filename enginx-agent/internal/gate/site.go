// Package gate decides, for NGINX, whether a visitor to a protected site may through.
//
// NGINX asks on every request (auth_request) and the gate answers from a signed cookie alone, so
// a protected site never waits on the management plane. The management plane is reached only to
// mail a sign-in code, and the gate proves which site it speaks for by signing that request with
// a key only the platform and this host hold.
package gate

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

// Derivation labels. Part of the protocol shared with the platform's GateKeyDerivation: the site
// key is never used directly, so a cookie can never verify as a relay request or the reverse.
const (
	relayLabel   = "enginx-gate relay v1"
	sessionLabel = "enginx-gate session v1"
)

// Site is one protected site, as the platform rendered it into the bundle.
type Site struct {
	ID         string
	Domain     string
	Mode       string
	SessionTTL time.Duration
	Allowed    []string
	RelayURL   string

	sessionKey []byte
	relayKey   []byte
}

type siteFile struct {
	Version           int      `json:"version"`
	SiteID            string   `json:"siteId"`
	Domain            string   `json:"domain"`
	Mode              string   `json:"mode"`
	Key               string   `json:"key"`
	SessionTTLSeconds int64    `json:"sessionTtlSeconds"`
	AllowedEmails     []string `json:"allowedEmails"`
	RelayURL          string   `json:"relayUrl"`
}

func derive(key []byte, label string) []byte {
	mac := hmac.New(sha256.New, key)
	mac.Write([]byte(label))
	return mac.Sum(nil)
}

func parseSite(data []byte) (*Site, error) {
	var f siteFile
	if err := json.Unmarshal(data, &f); err != nil {
		return nil, err
	}
	if f.Version != 1 {
		return nil, fmt.Errorf("unsupported version %d", f.Version)
	}
	if f.Mode != "EMAIL_OTP" {
		return nil, fmt.Errorf("unsupported mode %q", f.Mode)
	}
	key, err := base64.StdEncoding.DecodeString(f.Key)
	if err != nil || len(key) != 32 {
		return nil, fmt.Errorf("the key is not 32 bytes of base64")
	}
	if f.SiteID == "" || f.Domain == "" || f.RelayURL == "" || f.SessionTTLSeconds <= 0 {
		return nil, fmt.Errorf("siteId, domain, relayUrl and sessionTtlSeconds are required")
	}
	return &Site{
		ID:         f.SiteID,
		Domain:     f.Domain,
		Mode:       f.Mode,
		SessionTTL: time.Duration(f.SessionTTLSeconds) * time.Second,
		Allowed:    f.AllowedEmails,
		RelayURL:   f.RelayURL,
		sessionKey: derive(key, sessionLabel),
		relayKey:   derive(key, relayLabel),
	}, nil
}

// Admits reports whether an address is on the site's allow-list: an exact entry, or *@domain for
// exactly that domain. The platform checks again before mailing anything.
func (s *Site) Admits(email string) bool {
	email = normaliseEmail(email)
	at := strings.LastIndexByte(email, '@')
	if at <= 0 {
		return false
	}
	for _, entry := range s.Allowed {
		if entry == email {
			return true
		}
		if strings.HasPrefix(entry, "*@") && entry[1:] == email[at:] {
			return true
		}
	}
	return false
}

func normaliseEmail(email string) string {
	return strings.ToLower(strings.TrimSpace(email))
}

// LoadSites reads every site file in dir. A file that does not parse is reported and skipped:
// one bad file must not take every other protected site on the host down with it.
func LoadSites(dir string) (map[string]*Site, []error) {
	sites := map[string]*Site{}
	var problems []error
	entries, err := os.ReadDir(dir)
	if err != nil {
		if os.IsNotExist(err) {
			return sites, nil
		}
		return sites, []error{err}
	}
	for _, entry := range entries {
		if entry.IsDir() || !strings.HasSuffix(entry.Name(), ".json") {
			continue
		}
		data, err := os.ReadFile(filepath.Join(dir, entry.Name()))
		if err != nil {
			problems = append(problems, fmt.Errorf("%s: %w", entry.Name(), err))
			continue
		}
		site, err := parseSite(data)
		if err != nil {
			problems = append(problems, fmt.Errorf("%s: %w", entry.Name(), err))
			continue
		}
		sites[site.ID] = site
	}
	return sites, problems
}

// dirSignature changes whenever the set of site files does, including when the `current` symlink
// above them is swapped to a new release: every release's files are new files.
func dirSignature(dir string) string {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return "missing"
	}
	var parts []string
	for _, entry := range entries {
		info, err := os.Stat(filepath.Join(dir, entry.Name()))
		if err != nil {
			continue
		}
		parts = append(parts, fmt.Sprintf("%s:%d:%d", entry.Name(), info.Size(), info.ModTime().UnixNano()))
	}
	sort.Strings(parts)
	resolved, _ := filepath.EvalSymlinks(dir)
	return resolved + "|" + strings.Join(parts, ",")
}
