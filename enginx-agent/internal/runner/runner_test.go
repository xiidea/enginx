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

// The situation this exists for: a host's credential is revoked, an operator gives it a fresh
// registration token, and it must be able to come back on its own.
func TestRecoversOnceFromARejectedToken(t *testing.T) {
	registrations := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		registrations++
		_ = json.NewEncoder(w).Encode(client.RegisterResponse{AgentToken: "enginx-agt-reissued"})
	}))
	defer server.Close()

	r := runnerFor(t, server.URL, "enginx-reg-token")
	if err := os.MkdirAll(filepath.Dir(r.cfg.TokenFile), 0o700); err != nil {
		t.Fatalf("preparing the token file: %v", err)
	}
	if err := os.WriteFile(r.cfg.TokenFile, []byte("enginx-agt-revoked\n"), 0o600); err != nil {
		t.Fatalf("seeding a stale token: %v", err)
	}
	r.authenticated = r.client.WithToken("enginx-agt-revoked")

	if !r.recoverCredential(context.Background(), r.authenticated) {
		t.Fatal("expected the credential to be replaced")
	}
	if registrations != 1 {
		t.Fatalf("registered %d times, expected once", registrations)
	}

	// The new token has to reach disk, or the next restart enrols again and spends another.
	stored, err := r.readToken()
	if err != nil || stored != "enginx-agt-reissued" {
		t.Fatalf("token file holds %q (%v)", stored, err)
	}

	// Once. A runner that recovered on every rejection would spend a fresh registration on every
	// restart against a misconfigured server.
	if r.recoverCredential(context.Background(), r.authenticated) {
		t.Fatal("expected only one recovery attempt")
	}
	if registrations != 1 {
		t.Fatalf("registered %d times after a second attempt", registrations)
	}
}

// Without a registration token there is nothing to recover with, and pretending otherwise would
// turn a clear "an operator must act" into a silent retry loop.
func TestDoesNotRecoverWithoutARegistrationToken(t *testing.T) {
	r := runnerFor(t, "https://enginx.example.com/api/v1", "")
	r.authenticated = r.client.WithToken("enginx-agt-revoked")

	if r.recoverCredential(context.Background(), r.authenticated) {
		t.Fatal("expected no recovery attempt")
	}
}

// A failed attempt still counts. Otherwise a server that refuses every enrolment is asked again on
// every heartbeat, which is the hammering the once-only rule exists to prevent.
func TestAFailedRecoveryIsNotRetried(t *testing.T) {
	attempts := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		attempts++
		w.WriteHeader(http.StatusConflict)
	}))
	defer server.Close()

	r := runnerFor(t, server.URL, "enginx-reg-token")
	r.authenticated = r.client.WithToken("enginx-agt-revoked")

	if r.recoverCredential(context.Background(), r.authenticated) {
		t.Fatal("expected the recovery to fail")
	}
	if r.recoverCredential(context.Background(), r.authenticated) {
		t.Fatal("expected no second attempt")
	}
	if attempts != 1 {
		t.Fatalf("asked the server %d times, expected once", attempts)
	}
}

// Both loops can discover a rejected token in the same instant. The one that does not perform the
// recovery must be told to retry, not to report a failure the other has already repaired.
func TestTheLoserOfARecoveryRaceIsToldToRetry(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(client.RegisterResponse{AgentToken: "enginx-agt-reissued"})
	}))
	defer server.Close()

	r := runnerFor(t, server.URL, "enginx-reg-token")
	stale := r.client.WithToken("enginx-agt-revoked")
	r.authenticated = stale

	// The winner replaces the credential.
	if !r.recoverCredential(context.Background(), stale) {
		t.Fatal("expected the first caller to recover")
	}

	// The loser arrives holding the credential that was already replaced. Recovery is spent, but
	// it should still be told to try again, because a working credential is now in place.
	if !r.recoverCredential(context.Background(), stale) {
		t.Fatal("expected the loser to be told to retry")
	}

	// Whereas a caller holding the current credential really has been rejected, and recovery is
	// spent, so it must be told so rather than looping.
	if r.recoverCredential(context.Background(), r.authenticated) {
		t.Fatal("expected no further recovery")
	}
}
