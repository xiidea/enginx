// Package api exposes the agent's HTTPS control surface.
//
// Phase 2 implements observation and the two NGINX operations that cannot change what is served
// without validating first. Bundle staging, activation and deletion arrive in Phase 4, together
// with the idempotency store that makes them safe to retry.
package api

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"fmt"
	"net/http"
	"os"
	"time"

	"github.com/xiidea/enginx/enginx-agent/internal/bundle"
	"github.com/xiidea/enginx/enginx-agent/internal/config"
	"github.com/xiidea/enginx/enginx-agent/internal/nginx"
)

type Server struct {
	cfg         config.Config
	nginx       *nginx.Controller
	bundles     *bundle.Store
	idempotency *idempotencyStore
	startedAt   time.Time
}

func NewServer(cfg config.Config, controller *nginx.Controller, bundles *bundle.Store) *Server {
	return &Server{
		cfg:     cfg,
		nginx:   controller,
		bundles: bundles,
		// Long enough to cover the management server's retry schedule, which gives up well
		// before this expires.
		idempotency: newIdempotencyStore(24*time.Hour, 2048),
		startedAt:   time.Now(),
	}
}

// Handler builds the mTLS-protected routes.
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /agent/v1/status", s.handleStatus)
	mux.HandleFunc("POST /agent/v1/nginx/test", s.handleTest)
	mux.HandleFunc("POST /agent/v1/nginx/reload", s.handleReload)
	mux.HandleFunc("POST /agent/v1/configurations", s.handleStageConfiguration)
	mux.HandleFunc("POST /agent/v1/configurations/{bundleId}/activate", s.handleActivateConfiguration)
	mux.HandleFunc("DELETE /agent/v1/configurations/{bundleId}", s.handleDeleteConfiguration)
	// Probes. Both dial from this host, which is the only place their answer means anything.
	mux.HandleFunc("POST /agent/v1/verify", s.handleVerify)
	mux.HandleFunc("POST /agent/v1/upstream-checks", s.handleUpstreamCheck)
	mux.HandleFunc("PUT /agent/v1/acme-challenges/{token}", s.handlePutAcmeChallenge)
	mux.HandleFunc("DELETE /agent/v1/acme-challenges/{token}", s.handleDeleteAcmeChallenge)

	return logRequests(requireClientCN(s.cfg.ClientCN, mux))
}

// HealthHandler is served on a separate loopback listener without client certificates, so an
// orchestrator can probe liveness. It reveals nothing about the host.
func (s *Server) HealthHandler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /agent/v1/health", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]string{"status": "UP"})
	})
	return mux
}

// TLSConfig requires and verifies a client certificate signed by the configured CA.
func (s *Server) TLSConfig() (*tls.Config, error) {
	certificate, err := tls.LoadX509KeyPair(s.cfg.TLSCertFile, s.cfg.TLSKeyFile)
	if err != nil {
		return nil, fmt.Errorf("loading agent keypair: %w", err)
	}

	caPEM, err := os.ReadFile(s.cfg.ClientCAFile)
	if err != nil {
		return nil, fmt.Errorf("reading client CA: %w", err)
	}
	pool := x509.NewCertPool()
	if !pool.AppendCertsFromPEM(caPEM) {
		return nil, fmt.Errorf("client CA %s contains no usable certificate", s.cfg.ClientCAFile)
	}

	return &tls.Config{
		Certificates: []tls.Certificate{certificate},
		ClientAuth:   tls.RequireAndVerifyClientCert,
		ClientCAs:    pool,
		MinVersion:   tls.VersionTLS13,
	}, nil
}

func (s *Server) handleStatus(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, s.Status(r.Context()))
}

// Status describes this host right now.
//
// Exported because a pull-mode agent reports the same thing on its own schedule rather than
// waiting to be asked. One implementation, so the two connectivity models cannot drift into
// disagreeing about what this host looks like.
func (s *Server) Status(ctx context.Context) StatusResponse {
	test := s.nginx.Test(ctx)
	bundleID, since := activeBundle(s.cfg.ReleasesDir)

	return StatusResponse{
		AgentVersion:     s.cfg.AgentVersion,
		NginxVersion:     s.nginx.Version(ctx),
		NginxRunning:     s.nginx.Running(),
		NginxMasterPID:   s.nginx.MasterPID(),
		ActiveBundleID:   bundleID,
		ActiveSince:      since,
		AvailableBundles: s.bundles.Available(),
		ConfigTestOK:     test.OK,
		ConfigTestOutput: test.Output,
		UptimeSeconds:    int64(time.Since(s.startedAt).Seconds()),
		Certificates:     inspectCertificates(s.cfg.ReleasesDir, time.Now()),
	}
}

// handleTest returns 200 whether or not the configuration is valid: the caller asked a question
// and received an answer. An error status is reserved for the agent failing to answer it.
func (s *Server) handleTest(w http.ResponseWriter, r *http.Request) {
	result := s.nginx.Test(r.Context())
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":     result.OK,
		"output": result.Output,
	})
}

func (s *Server) handleReload(w http.ResponseWriter, r *http.Request) {
	result, err := s.nginx.Reload(r.Context())
	if err != nil {
		status, slug := http.StatusInternalServerError, "reload-failed"
		title, detail := "NGINX reload failed", err.Error()
		if !result.OK {
			// Validation failed, so nothing was signalled and the previous configuration is
			// still being served. That is a conflict, not a server error.
			status, slug = http.StatusConflict, "nginx-validation-failed"
			title, detail = "NGINX configuration validation failed", result.Output
		}
		writeProblem(w, r, status, slug, title, detail)
		return
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"status":       "RELOADED",
		"testOutput":   result.Output,
		"nginxVersion": s.nginx.Version(r.Context()),
		"reloadedAt":   time.Now().UTC().Format(time.RFC3339),
	})
}

// Run starts both listeners and blocks until the context is cancelled.
func (s *Server) Run(ctx context.Context) error {
	tlsConfig, err := s.TLSConfig()
	if err != nil {
		return err
	}

	apiServer := &http.Server{
		Addr:              s.cfg.ListenAddr,
		Handler:           s.Handler(),
		TLSConfig:         tlsConfig,
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       60 * time.Second,
		WriteTimeout:      120 * time.Second,
		IdleTimeout:       90 * time.Second,
	}
	healthServer := &http.Server{
		Addr:              s.cfg.HealthAddr,
		Handler:           s.HealthHandler(),
		ReadHeaderTimeout: 5 * time.Second,
	}

	errs := make(chan error, 2)
	go func() {
		if err := apiServer.ListenAndServeTLS("", ""); err != nil && err != http.ErrServerClosed {
			errs <- fmt.Errorf("mTLS listener: %w", err)
		}
	}()
	go func() {
		if err := healthServer.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			errs <- fmt.Errorf("health listener: %w", err)
		}
	}()

	select {
	case err := <-errs:
		return err
	case <-ctx.Done():
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		_ = apiServer.Shutdown(shutdownCtx)
		_ = healthServer.Shutdown(shutdownCtx)
		return nil
	}
}
