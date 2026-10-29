package api

import (
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// listenProtected starts an HTTP server on 127.0.0.1 for the marker, and an HTTPS one for the
// protection check, answering unauthenticated requests with the given handler.
func listenProtected(t *testing.T, marker string, protected http.HandlerFunc) (httpPort, httpsPort int) {
	t.Helper()
	plain := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == SiteMarkerPath {
			_, _ = w.Write([]byte(marker + "\n"))
			return
		}
		w.WriteHeader(http.StatusMovedPermanently)
	}))
	t.Cleanup(plain.Close)
	secure := httptest.NewTLSServer(protected)
	t.Cleanup(secure.Close)
	return plain.Listener.Addr().(*net.TCPAddr).Port, secure.Listener.Addr().(*net.TCPAddr).Port
}

func verifyProtected(t *testing.T, protection string, handler http.HandlerFunc) verifyResult {
	t.Helper()
	marker := "aaaaaaaa-0000-0000-0000-000000000001 v1 0123456789ab"
	httpPort, httpsPort := listenProtected(t, marker, handler)
	body := verifyRequest{Sites: []verifyTarget{{ServerName: "private.example.com", Marker: marker, Protection: protection}},
		Port: httpPort}
	response := postJSON(t, (&Server{protectedPort: httpsPort}).handleVerify, "/agent/v1/verify", body)
	var decoded verifyResponse
	decode(t, response, &decoded)
	if len(decoded.Results) != 1 {
		t.Fatalf("expected 1 result, got %d", len(decoded.Results))
	}
	return decoded.Results[0]
}

func TestBasicAuthThatRefusesIsEnforced(t *testing.T) {
	result := verifyProtected(t, "BASIC", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("WWW-Authenticate", `Basic realm="Restricted"`)
		w.WriteHeader(http.StatusUnauthorized)
	})
	if result.ProtectionEnforced == nil || !*result.ProtectionEnforced {
		t.Fatalf("expected protection to hold, got %+v", result)
	}
}

func TestAGateThatRedirectsToSignInIsEnforced(t *testing.T) {
	result := verifyProtected(t, "GATE", func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, "/.enginx/auth/login?rd="+r.URL.Path, http.StatusFound)
	})
	if result.ProtectionEnforced == nil || !*result.ProtectionEnforced {
		t.Fatalf("expected protection to hold, got %+v", result)
	}
}

// What a `return 200` in a host's custom file looks like from outside: one path answered.
func TestAPathAnsweredWithoutSigningInIsReported(t *testing.T) {
	result := verifyProtected(t, "BASIC", func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/.enginx-verify-") {
			w.WriteHeader(http.StatusOK)
			return
		}
		w.Header().Set("WWW-Authenticate", `Basic realm="Restricted"`)
		w.WriteHeader(http.StatusUnauthorized)
	})
	if result.ProtectionEnforced == nil || *result.ProtectionEnforced {
		t.Fatalf("expected protection to be reported broken, got %+v", result)
	}
	if !strings.Contains(result.ProtectionDetail, "answered 200 without signing in") {
		t.Errorf("detail = %q", result.ProtectionDetail)
	}
}

func TestAPublicSiteIsNotAskedAboutProtection(t *testing.T) {
	result := verifyProtected(t, "", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	})
	if result.ProtectionEnforced != nil {
		t.Fatalf("a public site reported protection: %+v", result)
	}
}

func TestSignInRedirects(t *testing.T) {
	for location, want := range map[string]bool{
		"/.enginx/auth/login?rd=/x":                            true,
		"https://private.example.com/.enginx/auth/login?rd=/x": true,
		"https://private.example.com:8443/.enginx/auth/login":  true,
		"https://evil.example/.enginx/auth/login":              false,
		"/somewhere-else":                                      false,
	} {
		if got := redirectsToSignIn(location, "private.example.com"); got != want {
			t.Errorf("redirectsToSignIn(%q) = %v, want %v", location, got, want)
		}
	}
}

// What VERIFY saw for a newly added site: the first answers come from a worker that predates it,
// through the catch-all, before the reload's new workers take over.
func TestAnOldWorkersAnswerIsWaitedOut(t *testing.T) {
	answered := 0
	result := verifyProtected(t, "BASIC", func(w http.ResponseWriter, r *http.Request) {
		answered++
		if answered <= 3 {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		w.Header().Set("WWW-Authenticate", `Basic realm="Restricted"`)
		w.WriteHeader(http.StatusUnauthorized)
	})
	if result.ProtectionEnforced == nil || !*result.ProtectionEnforced {
		t.Fatalf("a site that refuses once the reload settles was reported unprotected: %+v", result)
	}
}

func TestAProtectionThatNeverHoldsStillFails(t *testing.T) {
	saved := protectionSettle
	protectionSettle = 600 * time.Millisecond
	t.Cleanup(func() { protectionSettle = saved })

	result := verifyProtected(t, "BASIC", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	})
	if result.ProtectionEnforced == nil || *result.ProtectionEnforced {
		t.Fatalf("a site that never refuses was reported protected: %+v", result)
	}
}
