package gate

import (
	"context"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

const testSiteID = "aaaaaaaa-0000-0000-0000-000000000001"

const testSiteFile = `{
  "version": 1,
  "siteId": "aaaaaaaa-0000-0000-0000-000000000001",
  "domain": "private.example.com",
  "mode": "EMAIL_OTP",
  "key": "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=",
  "sessionTtlSeconds": 3600,
  "allowedEmails": [
    "*@example.com",
    "alice@partner.org"
  ],
  "relayUrl": "https://enginx.example.com/api/v1/agents/gate/otp-mail"
}
`

func testSite(t *testing.T) *Site {
	t.Helper()
	site, err := parseSite([]byte(testSiteFile))
	if err != nil {
		t.Fatalf("parsing the site: %v", err)
	}
	return site
}

// The same vector is asserted by GateKeyDerivationTest on the platform: the two sides must agree
// byte for byte, or every code request is refused as forged.
func TestRelaySignatureMatchesThePlatform(t *testing.T) {
	got := relaySignature(testSite(t), "bob@example.com", "123456", 1700000000)
	want := "69d6c01cb6c98268a679c774549cd8befb1411289ae913a4a8c9303ea824c77e"
	if got != want {
		t.Fatalf("signature %s, want %s", got, want)
	}
}

func TestAllowList(t *testing.T) {
	site := testSite(t)
	for email, want := range map[string]bool{
		"alice@partner.org":        true,
		" Alice@Partner.org ":      true,
		"bob@partner.org":          false,
		"bob@example.com":          true,
		"bob@sub.example.com":      false,
		"bob@evil-example.com":     false,
		"bob@example.com.evil.org": false,
		"example.com":              false,
	} {
		if got := site.Admits(email); got != want {
			t.Errorf("Admits(%q) = %v, want %v", email, got, want)
		}
	}
}

func TestSessions(t *testing.T) {
	site := testSite(t)
	now := time.Unix(1700000000, 0)
	value, _ := issueSession(site, identity{Email: "bob@example.com", User: "bob"}, now)

	if who, err := verifySession(site, value, now.Add(time.Minute)); err != nil || who.Email != "bob@example.com" || who.User != "bob" {
		t.Fatalf("a fresh session: %+v %v", who, err)
	}
	if _, err := verifySession(site, value, now.Add(site.SessionTTL)); err == nil {
		t.Fatal("an expired session was accepted")
	}
	tampered := strings.Replace(value, value[:4], "AAAA", 1)
	if _, err := verifySession(site, tampered, now); err == nil {
		t.Fatal("a tampered session was accepted")
	}

	rotated, _ := parseSite([]byte(strings.Replace(testSiteFile, "AQIDBAUG", "BQIDBAUG", 1)))
	if _, err := verifySession(rotated, value, now); err == nil {
		t.Fatal("a session survived a key rotation")
	}
}

func TestRedirectsStayOnTheSite(t *testing.T) {
	for raw, want := range map[string]string{
		"rd=/app?x=1&y=2":         "/app?x=1&y=2",
		"rd=//evil.example/":      "/",
		"rd=/\\evil.example":      "/",
		"rd=https://evil.example": "/",
		"rd=/\t/evil.example":     "/",
		"rd=/ok\x7f":              "/",
		"":                        "/",
		"other=1":                 "/",
	} {
		if got := redirectFromQuery(raw); got != want {
			t.Errorf("redirectFromQuery(%q) = %q, want %q", raw, got, want)
		}
	}
}

type fakeRelay struct {
	mu   sync.Mutex
	sent map[string]string
}

func (f *fakeRelay) SendCode(_ context.Context, _ *Site, email, code string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.sent[email] = code
	return nil
}

func newTestServer(t *testing.T) (*Server, *fakeRelay) {
	t.Helper()
	relay := &fakeRelay{sent: map[string]string{}}
	server := NewServer(relay)
	server.SetSites(map[string]*Site{testSiteID: testSite(t)})
	return server, relay
}

func request(method, target string, form url.Values, cookies ...*http.Cookie) *http.Request {
	var r *http.Request
	if form != nil {
		r = httptest.NewRequest(method, target, strings.NewReader(form.Encode()))
		r.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	} else {
		r = httptest.NewRequest(method, target, nil)
	}
	r.Host = "private.example.com"
	r.Header.Set("X-Enginx-Site", testSiteID)
	r.Header.Set("X-Real-IP", "192.0.2.1")
	for _, c := range cookies {
		r.AddCookie(c)
	}
	return r
}

func cookie(res *http.Response, name string) *http.Cookie {
	for _, c := range res.Cookies() {
		if c.Name == name {
			return c
		}
	}
	return nil
}

func TestSignInEndToEnd(t *testing.T) {
	server, relay := newTestServer(t)
	handler := server.Handler()

	check := httptest.NewRecorder()
	handler.ServeHTTP(check, request("GET", "/check", nil))
	if check.Code != http.StatusUnauthorized {
		t.Fatalf("check without a session: %d", check.Code)
	}

	login := httptest.NewRecorder()
	handler.ServeHTTP(login, request("POST", "/.enginx/auth/login",
		url.Values{"email": {"Bob@Example.com"}, "rd": {"/reports?q=1"}}))
	server.Wait()
	flow := cookie(login.Result(), FlowCookie)
	code := relay.sent["bob@example.com"]
	if flow == nil || len(code) != 6 {
		t.Fatalf("login: flow %v, code %q", flow, code)
	}

	wrong := httptest.NewRecorder()
	handler.ServeHTTP(wrong, request("POST", "/.enginx/auth/verify", url.Values{"code": {"000000"}}, flow))
	if cookie(wrong.Result(), SessionCookie) != nil {
		t.Fatal("a wrong code signed in")
	}

	verify := httptest.NewRecorder()
	handler.ServeHTTP(verify, request("POST", "/.enginx/auth/verify", url.Values{"code": {code}}, flow))
	session := cookie(verify.Result(), SessionCookie)
	if verify.Code != http.StatusSeeOther || verify.Header().Get("Location") != "/reports?q=1" || session == nil {
		t.Fatalf("verify: %d to %q, session %v", verify.Code, verify.Header().Get("Location"), session)
	}
	if !session.Secure || !session.HttpOnly {
		t.Fatal("the session cookie must be Secure and HttpOnly")
	}

	check = httptest.NewRecorder()
	handler.ServeHTTP(check, request("GET", "/check", nil, session))
	if check.Code != http.StatusNoContent || check.Header().Get("X-Enginx-Email") != "bob@example.com" {
		t.Fatalf("check with a session: %d %q", check.Code, check.Header().Get("X-Enginx-Email"))
	}

	// A code works once.
	again := httptest.NewRecorder()
	handler.ServeHTTP(again, request("POST", "/.enginx/auth/verify", url.Values{"code": {code}}, flow))
	if cookie(again.Result(), SessionCookie) != nil {
		t.Fatal("a code was accepted twice")
	}
}

func TestAnAddressOffTheListLooksTheSameAndGetsNothing(t *testing.T) {
	server, relay := newTestServer(t)
	handler := server.Handler()

	allowed := httptest.NewRecorder()
	handler.ServeHTTP(allowed, request("POST", "/.enginx/auth/login", url.Values{"email": {"bob@example.com"}}))
	refused := httptest.NewRecorder()
	handler.ServeHTTP(refused, request("POST", "/.enginx/auth/login", url.Values{"email": {"mallory@evil.org"}}))
	server.Wait()

	if allowed.Code != refused.Code || cookie(refused.Result(), FlowCookie) == nil {
		t.Fatalf("the replies differ: %d vs %d", allowed.Code, refused.Code)
	}
	if strings.ReplaceAll(allowed.Body.String(), "bob@example.com", "X") !=
		strings.ReplaceAll(refused.Body.String(), "mallory@evil.org", "X") {
		t.Fatal("the page for an address off the list differs from the page for one on it")
	}
	if _, sent := relay.sent["mallory@evil.org"]; sent {
		t.Fatal("a code was sent to an address off the list")
	}
}

func TestFiveWrongCodesEndTheFlow(t *testing.T) {
	server, relay := newTestServer(t)
	handler := server.Handler()
	login := httptest.NewRecorder()
	handler.ServeHTTP(login, request("POST", "/.enginx/auth/login", url.Values{"email": {"bob@example.com"}}))
	server.Wait()
	flow := cookie(login.Result(), FlowCookie)

	for i := 0; i < maxCodeAttempt; i++ {
		handler.ServeHTTP(httptest.NewRecorder(), request("POST", "/.enginx/auth/verify", url.Values{"code": {"000000"}}, flow))
	}
	late := httptest.NewRecorder()
	handler.ServeHTTP(late, request("POST", "/.enginx/auth/verify", url.Values{"code": {relay.sent["bob@example.com"]}}, flow))
	if cookie(late.Result(), SessionCookie) != nil {
		t.Fatal("the right code worked after the attempts ran out")
	}
}

func TestCrossOriginFormsAreRefused(t *testing.T) {
	server, relay := newTestServer(t)
	r := request("POST", "/.enginx/auth/login", url.Values{"email": {"bob@example.com"}})
	r.Header.Set("Origin", "https://evil.example")
	res := httptest.NewRecorder()
	server.Handler().ServeHTTP(res, r)
	if res.Code != http.StatusForbidden || len(relay.sent) != 0 {
		t.Fatalf("cross-origin login: %d, sent %v", res.Code, relay.sent)
	}
}

func TestAnUnknownSiteIsRefused(t *testing.T) {
	server, _ := newTestServer(t)
	r := request("GET", "/check", nil)
	r.Header.Set("X-Enginx-Site", "someone-else")
	res := httptest.NewRecorder()
	server.Handler().ServeHTTP(res, r)
	if res.Code != http.StatusUnauthorized {
		t.Fatalf("unknown site: %d", res.Code)
	}
}

func TestLoadSitesSkipsABadFileButKeepsTheRest(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "private.example.com.json"), []byte(testSiteFile), 0o640); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "broken.example.com.json"), []byte("{"), 0o640); err != nil {
		t.Fatal(err)
	}
	sites, problems := LoadSites(dir)
	if len(sites) != 1 || len(problems) != 1 {
		t.Fatalf("sites %d, problems %v", len(sites), problems)
	}
}

func TestSameOriginIgnoresThePort(t *testing.T) {
	for origin, want := range map[string]bool{
		"":                                    true,
		"https://private.example.com":         true,
		"https://private.example.com:18453":   true,
		"https://PRIVATE.example.com":         true,
		"http://private.example.com":          false,
		"https://evil.example":                false,
		"https://private.example.com.evil.io": false,
	} {
		r := httptest.NewRequest("POST", "/.enginx/auth/login", nil)
		r.Host = "private.example.com"
		if origin != "" {
			r.Header.Set("Origin", origin)
		}
		if got := sameOrigin(r); got != want {
			t.Errorf("sameOrigin(%q) = %v, want %v", origin, got, want)
		}
	}
}

// A full store makes room rather than refusing: refusing would let anyone stop every sign-in.
func TestAFullFlowStoreEvictsTheOldest(t *testing.T) {
	store := newCodeStore()
	now := time.Unix(1700000000, 0)
	first, _ := store.start(testSiteID, "first@example.com", "111111", "/", now)
	for i := 1; i < maxPending; i++ {
		if _, err := store.start(testSiteID, "x@example.com", "", "/", now.Add(time.Second)); err != nil {
			t.Fatal(err)
		}
	}
	latest, err := store.start(testSiteID, "latest@example.com", "222222", "/", now.Add(2*time.Second))
	if err != nil {
		t.Fatalf("a full store refused a new sign-in: %v", err)
	}
	if _, ok := store.verify(first, testSiteID, "111111", now.Add(3*time.Second)); ok {
		t.Fatal("the oldest flow was kept")
	}
	if _, ok := store.verify(latest, testSiteID, "222222", now.Add(3*time.Second)); !ok {
		t.Fatal("the newest flow was lost")
	}
}

// A browser posts a page's form with `Origin: null` when the page's referrer policy is
// no-referrer, so the sign-in pages must not use it: their own forms would be refused as
// cross-origin. Found in a real browser; a test client sets Origin by hand and never sees it.
func TestSignInPagesKeepTheOriginOnTheirOwnForms(t *testing.T) {
	server, _ := newTestServer(t)
	res := httptest.NewRecorder()
	server.Handler().ServeHTTP(res, request("GET", "/.enginx/auth/login?rd=/", nil))

	if got := res.Header().Get("Referrer-Policy"); got != "same-origin" {
		t.Errorf("Referrer-Policy = %q, want same-origin", got)
	}
	if strings.Contains(res.Body.String(), "no-referrer") {
		t.Error("the page still asks for no-referrer, which makes its own forms post Origin: null")
	}

	// And null itself stays refused: a sandboxed frame or a data: URL sends it too.
	r := request("POST", "/.enginx/auth/login", url.Values{"email": {"bob@example.com"}})
	r.Header.Set("Origin", "null")
	refused := httptest.NewRecorder()
	server.Handler().ServeHTTP(refused, r)
	if refused.Code != http.StatusForbidden {
		t.Errorf("Origin: null got %d, want 403", refused.Code)
	}
}
