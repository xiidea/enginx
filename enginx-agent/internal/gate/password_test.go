package gate

import (
	"net/http"
	"net/http/httptest"
	"net/url"
	"testing"
)

const aliceHash = "$6$fixturesalt0001$nTLkJQUNn08kFVHCPBxOSfCbqF3lA60ZVOdyzJeqdQv4MeAjTOPRztNt1OcbBALrB.AcE2mRt.mh8cHpEl2UD1" // "secret"

func basicServer(t *testing.T) (*Server, *Site) {
	t.Helper()
	site := testSite(t)
	site.Mode = ModeBasic
	site.BasicUsers = map[string]string{"alice": aliceHash}
	site.policy = policyFingerprint(site)
	server := NewServer(&fakeRelay{sent: map[string]string{}})
	server.SetSites(map[string]*Site{testSiteID: site})
	return server, site
}

func passwordLogin(server *Server, username, password string) *httptest.ResponseRecorder {
	res := httptest.NewRecorder()
	server.Handler().ServeHTTP(res, request("POST", "/.enginx/auth/login",
		url.Values{"username": {username}, "password": {password}, "rd": {"/reports"}}))
	return res
}

func TestThePasswordFormSignsIn(t *testing.T) {
	server, _ := basicServer(t)

	form := httptest.NewRecorder()
	server.Handler().ServeHTTP(form, request("GET", "/.enginx/auth/login?rd=/reports", nil))
	if form.Code != http.StatusOK || form.Header().Get("WWW-Authenticate") != "" {
		t.Fatalf("the login page: %d, WWW-Authenticate %q (it must never prompt)", form.Code, form.Header().Get("WWW-Authenticate"))
	}

	if res := passwordLogin(server, "alice", "wrong"); cookie(res.Result(), SessionCookie) != nil || res.Code != http.StatusUnauthorized {
		t.Fatalf("a wrong password: %d", res.Code)
	}
	if res := passwordLogin(server, "mallory", "secret"); cookie(res.Result(), SessionCookie) != nil {
		t.Fatal("an unknown user signed in")
	}

	res := passwordLogin(server, "alice", "secret")
	session := cookie(res.Result(), SessionCookie)
	if res.Code != http.StatusSeeOther || res.Header().Get("Location") != "/reports" || session == nil {
		t.Fatalf("the right password: %d %q", res.Code, res.Header().Get("Location"))
	}
	check := httptest.NewRecorder()
	server.Handler().ServeHTTP(check, request("GET", "/check", nil, session))
	if check.Code != http.StatusNoContent || check.Header().Get("X-Enginx-User") != "alice" {
		t.Fatalf("check: %d %q", check.Code, check.Header().Get("X-Enginx-User"))
	}
}

// As with the browser's prompt, a changed password or a removed user takes effect at once.
func TestChangingThePasswordEndsTheSession(t *testing.T) {
	server, site := basicServer(t)
	session := cookie(passwordLogin(server, "alice", "secret").Result(), SessionCookie)

	changed := *site
	changed.BasicUsers = map[string]string{"alice": "$6$fixturesalt0002$SVJ/Qcuj5pOFgDaMim7YMeD0zspwX5SMLItbweCxpQAE8vOisbTGKkZoa6BxlnMLKacxFEmmhFpz8g7P7athb."}
	server.SetSites(map[string]*Site{testSiteID: &changed})

	check := httptest.NewRecorder()
	server.Handler().ServeHTTP(check, request("GET", "/check", nil, session))
	if check.Code != http.StatusUnauthorized {
		t.Fatalf("a session from before the password change still works: %d", check.Code)
	}
}

func TestAScriptCanStillSendBasicCredentials(t *testing.T) {
	server, _ := basicServer(t)
	for _, tc := range []struct {
		password string
		want     int
	}{{"secret", http.StatusNoContent}, {"secret", http.StatusNoContent}, {"wrong", http.StatusUnauthorized}} {
		r := request("GET", "/check", nil)
		r.SetBasicAuth("alice", tc.password)
		res := httptest.NewRecorder()
		server.Handler().ServeHTTP(res, r)
		if res.Code != tc.want {
			t.Fatalf("Authorization with %q: %d, want %d", tc.password, res.Code, tc.want)
		}
	}
}

// Without a limit, the header would be a way round the form's.
func TestGuessesAreLimitedThroughTheHeaderAsThroughTheForm(t *testing.T) {
	server, _ := basicServer(t)
	for i := 0; i < 10; i++ {
		r := request("GET", "/check", nil)
		r.SetBasicAuth("alice", "guess")
		server.Handler().ServeHTTP(httptest.NewRecorder(), r)
	}
	if res := passwordLogin(server, "alice", "secret"); res.Code != http.StatusTooManyRequests {
		t.Fatalf("after ten guesses at alice, the right password got %d, want 429", res.Code)
	}
}

// What the console's own testing found: a browser that had used the site's basic-auth prompt kept
// sending the cached credentials, so it was let in by the header and never needed a session — and
// signing everyone out could not reach it. Browsers are now sent to the login page; scripts are not.
func TestCachedBrowserCredentialsDoNotBypassTheLoginPage(t *testing.T) {
	server, _ := basicServer(t)

	browser := request("GET", "/check", nil)
	browser.SetBasicAuth("alice", "secret")
	browser.Header.Set("Sec-Fetch-Mode", "navigate")
	res := httptest.NewRecorder()
	server.Handler().ServeHTTP(res, browser)
	if res.Code != http.StatusUnauthorized {
		t.Fatalf("a browser with cached credentials got %d, want 401 (the login page)", res.Code)
	}

	script := request("GET", "/check", nil)
	script.SetBasicAuth("alice", "secret")
	res = httptest.NewRecorder()
	server.Handler().ServeHTTP(res, script)
	if res.Code != http.StatusNoContent {
		t.Fatalf("a script with credentials got %d, want 204", res.Code)
	}
}
