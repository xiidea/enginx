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

// Modes the gate serves.
const (
	ModeEmailOTP = "EMAIL_OTP"
	ModeOIDC     = "OIDC"
	// ModeBasic is basic authentication on the gate's login page: the site's users and their
	// SHA-512-crypt hashes, checked here rather than by NGINX's auth_basic.
	ModeBasic = "BASIC"
)

// Site is one protected site, as the platform rendered it into the bundle.
type Site struct {
	ID         string
	Domain     string
	Mode       string
	SessionTTL time.Duration
	// Email sign-in.
	Allowed  []string
	RelayURL string
	// Identity provider sign-in.
	OIDC *OIDCConfig
	// Basic authentication on a login page: username to hash.
	BasicUsers map[string]string

	sessionKey []byte
	relayKey   []byte
	// policy fingerprints who the site lets in. A session carries the fingerprint it was issued
	// under, so changing the policy ends every session made under the old one.
	policy string
}

type siteFile struct {
	Version           int         `json:"version"`
	SiteID            string      `json:"siteId"`
	Domain            string      `json:"domain"`
	Mode              string      `json:"mode"`
	Key               string      `json:"key"`
	SessionTTLSeconds int64       `json:"sessionTtlSeconds"`
	AllowedEmails     []string    `json:"allowedEmails"`
	RelayURL          string      `json:"relayUrl"`
	OIDC              *OIDCConfig `json:"oidc"`
	BasicUsers        []basicUser `json:"basicUsers"`
}

type basicUser struct {
	Username string `json:"username"`
	Hash     string `json:"hash"`
}

// OIDCConfig is the platform's identity provider, and who among its users may enter this site.
type OIDCConfig struct {
	Issuer       string       `json:"issuer"`
	Backchannel  string       `json:"backchannel"`
	ClientID     string       `json:"clientId"`
	ClientSecret string       `json:"clientSecret"`
	Scopes       string       `json:"scopes"`
	AnyUser      bool         `json:"anyUser"`
	Users        []string     `json:"users"`
	RealmRoles   []string     `json:"realmRoles"`
	ClientRoles  []ClientRole `json:"clientRoles"`
}

// ClientRole is a role on one client: resource_access.<client>.roles.
type ClientRole struct {
	Client string `json:"client"`
	Role   string `json:"role"`
}

// admits applies the site's policy to a signed-in identity: anyone, or a match on any entry.
func (c *OIDCConfig) admits(email, username string, realm map[string]bool, clients map[string]map[string]bool) bool {
	if c.AnyUser {
		return true
	}
	email, username = normaliseEmail(email), strings.ToLower(username)
	for _, user := range c.Users {
		if (email != "" && user == email) || (username != "" && user == username) {
			return true
		}
	}
	for _, role := range c.RealmRoles {
		if realm[role] {
			return true
		}
	}
	for _, role := range c.ClientRoles {
		if clients[role.Client][role.Role] {
			return true
		}
	}
	return false
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
	switch f.Mode {
	case ModeEmailOTP:
		if f.RelayURL == "" {
			return nil, fmt.Errorf("relayUrl is required for email sign-in")
		}
	case ModeBasic:
		if len(f.BasicUsers) == 0 {
			return nil, fmt.Errorf("basicUsers is required for basic authentication")
		}
	case ModeOIDC:
		if f.OIDC == nil || f.OIDC.Issuer == "" || f.OIDC.ClientID == "" {
			return nil, fmt.Errorf("oidc.issuer and oidc.clientId are required for identity provider sign-in")
		}
		if f.OIDC.Scopes == "" {
			f.OIDC.Scopes = "openid email profile"
		}
	default:
		return nil, fmt.Errorf("unsupported mode %q", f.Mode)
	}
	key, err := base64.StdEncoding.DecodeString(f.Key)
	if err != nil || len(key) != 32 {
		return nil, fmt.Errorf("the key is not 32 bytes of base64")
	}
	if f.SiteID == "" || f.Domain == "" || f.SessionTTLSeconds <= 0 {
		return nil, fmt.Errorf("siteId, domain and sessionTtlSeconds are required")
	}
	site := &Site{
		ID:         f.SiteID,
		Domain:     f.Domain,
		Mode:       f.Mode,
		SessionTTL: time.Duration(f.SessionTTLSeconds) * time.Second,
		Allowed:    f.AllowedEmails,
		RelayURL:   f.RelayURL,
		OIDC:       f.OIDC,
		BasicUsers: basicUserMap(f.BasicUsers),
		sessionKey: derive(key, sessionLabel),
		relayKey:   derive(key, relayLabel),
	}
	site.policy = policyFingerprint(site)
	return site, nil
}

// policyFingerprint names a site's access policy, keyed by the site's session key. Basic
// authentication has none: each of its sessions is bound to its own user's password instead,
// which also ends it when that user is removed.
func policyFingerprint(site *Site) string {
	var policy any
	switch site.Mode {
	case ModeEmailOTP:
		policy = site.Allowed
	case ModeOIDC:
		policy = struct {
			AnyUser     bool
			Users       []string
			RealmRoles  []string
			ClientRoles []ClientRole
		}{site.OIDC.AnyUser, site.OIDC.Users, site.OIDC.RealmRoles, site.OIDC.ClientRoles}
	default:
		return ""
	}
	canonical, _ := json.Marshal(policy)
	return sign(site.sessionKey, "policy\n"+string(canonical))[:22]
}

func basicUserMap(users []basicUser) map[string]string {
	if len(users) == 0 {
		return nil
	}
	out := make(map[string]string, len(users))
	for _, u := range users {
		out[u.Username] = u.Hash
	}
	return out
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
