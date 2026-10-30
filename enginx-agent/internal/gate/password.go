package gate

import (
	"crypto/sha256"
	"encoding/hex"
	"log/slog"
	"net/http"
	"strings"
	"sync"
	"time"
)

// dummyHash is checked when a username is unknown, so a refusal takes as long for a name that does
// not exist as for a wrong password: the form cannot be used to learn which users there are.
const dummyHash = "$6$enginxdummysalt$H3pmmOzPDMSwFHtUBdkCJKcKkpgNJoQd8F3sKAXzEL1bkOb2BD9d.W4p0xp8rAoDpJ3ZqCSPrKdj/fXSYqJSK1"

// checkPassword reports whether the user exists and the password is theirs, in about the same
// time either way.
func checkPassword(site *Site, username, password string) bool {
	hash, known := site.BasicUsers[username]
	if !known {
		verifySHA512Crypt(password, dummyHash)
		return false
	}
	return verifySHA512Crypt(password, hash)
}

// allowPasswordAttempt spends one attempt for this client and this username. Both, so a guess at
// one user from many addresses, and at many users from one address, are each bounded.
func (s *Server) allowPasswordAttempt(site *Site, r *http.Request, username string, now time.Time) bool {
	return s.passwordByIP.allow(site.ID+"|"+r.Header.Get("X-Real-IP"), now) &&
		s.passwordByUser.allow(site.ID+"|"+username, now)
}

func (s *Server) handlePasswordLogin(w http.ResponseWriter, r *http.Request, site *Site) {
	if !sameOrigin(r) {
		http.Error(w, "cross-origin request refused", http.StatusForbidden)
		return
	}
	redirect := safeRedirect(r.PostFormValue("rd"))
	username := strings.TrimSpace(r.PostFormValue("username"))
	password := r.PostFormValue("password")
	now := s.now()

	if !s.allowPasswordAttempt(site, r, username, now) {
		render(w, http.StatusTooManyRequests, page{Domain: site.Domain, Step: "password", Redirect: redirect,
			Note: "Too many attempts. Wait a few minutes and try again."})
		return
	}
	if username == "" || !checkPassword(site, username, password) {
		slog.Info("basic sign-in refused", "site", site.Domain, "user", username)
		render(w, http.StatusUnauthorized, page{Domain: site.Domain, Step: "password", Redirect: redirect,
			Username: username, Note: "That username and password do not match."})
		return
	}

	value, expires := issueSession(site, identity{User: username, Proof: passwordProof(site, username)}, now)
	http.SetCookie(w, &http.Cookie{Name: SessionCookie, Value: value, Path: "/", Expires: expires,
		MaxAge: int(site.SessionTTL.Seconds()), Secure: true, HttpOnly: true, SameSite: http.SameSiteLaxMode})
	slog.Info("signed in", "site", site.Domain, "user", username)
	http.Redirect(w, r, redirect, http.StatusSeeOther)
}

// basicHeader accepts an Authorization: Basic header on a site whose users sign in on a login
// page, so a script with credentials keeps working without a cookie. Its failures count against
// the same limits as the form's; a header checked a moment ago is not checked again.
//
// From scripts only. A browser that ever saw the site's basic-auth prompt keeps sending the
// credentials it was given there, unasked, on every request; accepting them would let it past the
// login page for good, out of reach of sign-out and of signing everyone out. Every current browser
// marks its requests with Sec-Fetch-Mode, which a page cannot remove; curl, wget and the HTTP
// clients scripts use do not send it. A browser is therefore sent to sign in, like any other.
func (s *Server) basicHeader(site *Site, r *http.Request) (identity, bool) {
	if r.Header.Get("Sec-Fetch-Mode") != "" {
		return identity{}, false
	}
	username, password, ok := r.BasicAuth()
	if !ok || username == "" {
		return identity{}, false
	}
	now := s.now()
	key := headerKey(site, username, password)
	if s.checkedHeaders.valid(key, now) && passwordProofStill(site, username, s.checkedHeaders.proof(key)) {
		return identity{User: username}, true
	}
	if !s.allowPasswordAttempt(site, r, username, now) || !checkPassword(site, username, password) {
		return identity{}, false
	}
	s.checkedHeaders.remember(key, passwordProof(site, username), now)
	return identity{User: username}, true
}

func passwordProofStill(site *Site, username, proof string) bool {
	return proof != "" && passwordProof(site, username) == proof
}

// headerKey names a username and password without holding the password.
func headerKey(site *Site, username, password string) string {
	digest := sha256.Sum256([]byte(site.ID + "\x00" + username + "\x00" + password))
	return hex.EncodeToString(digest[:])
}

type headerCache struct {
	mu      sync.Mutex
	ttl     time.Duration
	entries map[string]headerEntry
}

type headerEntry struct {
	proof   string
	expires time.Time
}

func newHeaderCache(ttl time.Duration) *headerCache {
	return &headerCache{ttl: ttl, entries: map[string]headerEntry{}}
}

func (c *headerCache) valid(key string, now time.Time) bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	entry, ok := c.entries[key]
	return ok && now.Before(entry.expires)
}

func (c *headerCache) proof(key string) string {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.entries[key].proof
}

func (c *headerCache) remember(key, proof string, now time.Time) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if len(c.entries) > 10000 {
		c.entries = map[string]headerEntry{}
	}
	c.entries[key] = headerEntry{proof: proof, expires: now.Add(c.ttl)}
}
