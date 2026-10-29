package gate

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"strings"
	"time"
)

// SessionCookie is host-only and HTTPS-only by its prefix: a browser refuses to set a __Host-
// cookie that is not Secure, has a Domain, or has a Path other than /.
const SessionCookie = "__Host-enginx_session"

type sessionClaims struct {
	Site    string `json:"s"`
	Email   string `json:"e"`
	User    string `json:"u,omitempty"`
	Expires int64  `json:"x"`
}

// identity is who a session belongs to.
type identity struct {
	Email string
	User  string
}

var errInvalidSession = errors.New("invalid session")

// issueSession signs a session for one site. Stateless: the gate keeps nothing, so a restart
// signs nobody out, and rotating the site's key on the platform signs out everyone.
func issueSession(site *Site, who identity, now time.Time) (string, time.Time) {
	expires := now.Add(site.SessionTTL)
	payload, _ := json.Marshal(sessionClaims{Site: site.ID, Email: who.Email, User: who.User, Expires: expires.Unix()})
	encoded := base64.RawURLEncoding.EncodeToString(payload)
	return encoded + "." + sign(site.sessionKey, encoded), expires
}

// verifySession returns who is signed in, or an error for anything not issued for this site by a
// holder of its current key and still in date.
func verifySession(site *Site, value string, now time.Time) (identity, error) {
	encoded, signature, found := strings.Cut(value, ".")
	if !found || !hmac.Equal([]byte(signature), []byte(sign(site.sessionKey, encoded))) {
		return identity{}, errInvalidSession
	}
	payload, err := base64.RawURLEncoding.DecodeString(encoded)
	if err != nil {
		return identity{}, errInvalidSession
	}
	var claims sessionClaims
	if err := json.Unmarshal(payload, &claims); err != nil {
		return identity{}, errInvalidSession
	}
	// The site is checked as well as the signature, though keys are per site already: it costs
	// nothing, and keeps a session from moving if two sites ever came to share a key.
	if claims.Site != site.ID || now.Unix() >= claims.Expires || (claims.Email == "" && claims.User == "") {
		return identity{}, errInvalidSession
	}
	return identity{Email: claims.Email, User: claims.User}, nil
}

func sign(key []byte, message string) string {
	mac := hmac.New(sha256.New, key)
	mac.Write([]byte(message))
	return base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
}
