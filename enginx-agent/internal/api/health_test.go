package api

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/xiidea/enginx/enginx-agent/internal/bundle"
	"github.com/xiidea/enginx/enginx-agent/internal/config"
	"github.com/xiidea/enginx/enginx-agent/internal/nginx"
)

// A controller that never started NGINX, which is the state a host is in when its configuration
// on disk is invalid — the agent runs so a corrected bundle can be deployed to it.
func serverWithStoppedNginx(t *testing.T) *Server {
	t.Helper()
	cfg := config.Config{ReleasesDir: t.TempDir(), NginxBinary: "/bin/true", NginxConf: "/dev/null"}
	return NewServer(cfg, nginx.New(cfg.NginxBinary, cfg.NginxConf, 0), bundle.NewStore(cfg.ReleasesDir))
}

func probe(t *testing.T, s *Server, path string) (int, map[string]any) {
	t.Helper()
	recorder := httptest.NewRecorder()
	s.HealthHandler().ServeHTTP(recorder, httptest.NewRequest(http.MethodGet, path, nil))

	var body map[string]any
	_ = json.Unmarshal(recorder.Body.Bytes(), &body)
	return recorder.Code, body
}

/*
The distinction the two endpoints exist to make, and the reason liveness must not consult NGINX.

The agent deliberately stays up when NGINX will not start, so the management server can deploy a
corrected bundle to it. If liveness reported that failure, an orchestrator would restart the
container, the agent would find the same broken configuration on disk, and the only route to
repairing the host would be destroyed by the thing meant to protect it.
*/
func TestLivenessIgnoresNginx(t *testing.T) {
	server := serverWithStoppedNginx(t)

	status, body := probe(t, server, "/agent/v1/health")

	if status != http.StatusOK {
		t.Fatalf("liveness returned %d with nginx down; it must not depend on nginx", status)
	}
	if body["status"] != "UP" {
		t.Fatalf("liveness reported %v", body["status"])
	}
}

// The signal that was missing: a host whose NGINX had died read as healthy for as long as the
// agent beside it kept answering.
func TestReadinessReportsThatNginxIsNotServing(t *testing.T) {
	server := serverWithStoppedNginx(t)

	status, body := probe(t, server, "/agent/v1/ready")

	if status != http.StatusServiceUnavailable {
		t.Fatalf("readiness returned %d with nginx down, expected 503", status)
	}
	if body["status"] != "DOWN" {
		t.Fatalf("readiness reported %v", body["status"])
	}
	if body["reason"] == nil || body["reason"] == "" {
		t.Fatal("readiness should say why, or it sends somebody looking in the wrong place")
	}
}

// Loopback only, and unauthenticated: it must never become a way to read host detail without a
// client certificate. Whether NGINX is up is the most it says.
func TestHealthListenerRevealsNothingElse(t *testing.T) {
	server := serverWithStoppedNginx(t)

	for _, path := range []string{"/agent/v1/status", "/agent/v1/configurations"} {
		if status, _ := probe(t, server, path); status != http.StatusNotFound {
			t.Fatalf("%s answered %d on the health listener; it belongs behind mTLS", path, status)
		}
	}
}
