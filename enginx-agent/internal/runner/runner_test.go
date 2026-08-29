package runner

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/xiidea/enginx/enginx-agent/internal/client"
	"github.com/xiidea/enginx/enginx-agent/internal/config"
)

func runnerFor(t *testing.T, serverURL, registrationToken string) *Runner {
	t.Helper()
	cfg := config.Config{
		ServerURL:         serverURL,
		RegistrationToken: registrationToken,
		TokenFile:         filepath.Join(t.TempDir(), "nested", "agent-token"),
		InstanceName:      "nginx-test",
		Environment:       "TEST",
		HeartbeatInterval: time.Second,
	}
	return New(cfg, nil)
}

// Enrolment happens once in a host's life. If a restart enrolled again, a registration token
// minted for one host would be spent by that host rebooting.
func TestEnrolsOnceAndReusesTheStoredToken(t *testing.T) {
	registrations := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		registrations++
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(client.RegisterResponse{
			InstanceID: "11111111-2222-3333-4444-555555555555",
			Name:       "nginx-test",
			AgentToken: "enginx-agt-secret",
		})
	}))
	defer server.Close()

	first := runnerFor(t, server.URL, "enginx-reg-token")
	token, err := first.establishToken(context.Background())
	if err != nil {
		t.Fatalf("first enrolment failed: %v", err)
	}
	if token != "enginx-agt-secret" {
		t.Fatalf("got token %q", token)
	}

	// A second runner pointed at the same file must not call register at all.
	second := New(first.cfg, nil)
	again, err := second.establishToken(context.Background())
	if err != nil {
		t.Fatalf("second start failed: %v", err)
	}
	if again != "enginx-agt-secret" {
		t.Fatalf("stored token not reused, got %q", again)
	}
	if registrations != 1 {
		t.Fatalf("registered %d times, expected once", registrations)
	}
}

// The token is a credential. Anything on the host that can read it can collect every site's
// private key, because a configuration bundle contains them.
func TestStoredTokenIsNotWorldReadable(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(client.RegisterResponse{AgentToken: "enginx-agt-secret"})
	}))
	defer server.Close()

	r := runnerFor(t, server.URL, "enginx-reg-token")
	if _, err := r.establishToken(context.Background()); err != nil {
		t.Fatalf("enrolment failed: %v", err)
	}

	info, err := os.Stat(r.cfg.TokenFile)
	if err != nil {
		t.Fatalf("token file missing: %v", err)
	}
	if mode := info.Mode().Perm(); mode != 0o600 {
		t.Fatalf("token file mode is %o, expected 600", mode)
	}
}

// Without a stored token and without a registration token there is nothing to try, and saying so
// is more useful than retrying an enrolment that cannot succeed.
func TestRefusesToStartWithNothingToPresent(t *testing.T) {
	r := runnerFor(t, "https://enginx.example.com/api/v1", "")

	if _, err := r.establishToken(context.Background()); err == nil {
		t.Fatal("expected enrolment to be refused")
	}
}

// A revoked credential is not a transient failure: retrying cannot fix it, and a runner that
// cannot tell the two apart hides the one condition an operator has to act on.
func TestRejectedTokenIsDistinguishableFromAnOutage(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusForbidden)
	}))
	defer server.Close()

	err := client.New(server.URL, time.Second).WithToken("enginx-agt-revoked").
		Heartbeat(context.Background(), client.HeartbeatRequest{})

	if err == nil {
		t.Fatal("expected the heartbeat to fail")
	}
	if err != client.ErrUnauthorized {
		t.Fatalf("got %v, expected ErrUnauthorized", err)
	}
}
