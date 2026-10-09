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
	"github.com/xiidea/enginx/enginx-agent/internal/defaulttls"
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

// Handler builds the authenticated routes.
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

	if s.cfg.TokenMode() {
		return logRequests(requireBearerToken(s.cfg.AgentSecretToken, mux))
	}
	return logRequests(requireClientCN(s.cfg.ClientCN, mux))
}

// HealthHandler is served on a separate loopback listener without client certificates, so an
// orchestrator can probe the agent. It reveals nothing about the host beyond whether it is
// serving.
func (s *Server) HealthHandler() http.Handler {
	mux := http.NewServeMux()

	// Liveness: is the agent itself alive. Deliberately says nothing about NGINX, and that is the
	// whole point of keeping the two apart. The agent stays up when NGINX will not start, so a
	// corrected bundle can be deployed to it — if this reported the failure, an orchestrator would
	// restart the container, the agent would find the same broken configuration, and the only
	// route to repairing the host would be destroyed by the thing meant to protect it.
	mux.HandleFunc("GET /agent/v1/health", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]string{"status": "UP"})
	})

	// Readiness: is this host actually serving traffic. Separate from liveness because the answer
	// "no" means take it out of rotation and tell somebody, never restart it.
	//
	// Checks the supervised process rather than running `nginx -t`: a probe runs every few seconds
	// and forking a validation each time would cost more than the question is worth. Whether the
	// process is up is the signal that was missing — a host whose NGINX died read as healthy for
	// as long as the agent beside it kept answering.
	mux.HandleFunc("GET /agent/v1/ready", func(w http.ResponseWriter, r *http.Request) {
		if !s.nginx.Running() {
			writeJSON(w, http.StatusServiceUnavailable, map[string]any{
				"status": "DOWN",
				"reason": "nginx is not running on this host",
			})
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{
			"status":   "UP",
			"nginxPid": s.nginx.MasterPID(),
		})
	})
	return mux
}

// RunHealth serves only the loopback health listener.
//
// Split out so a pull-mode agent has one too. It never starts the mTLS listener — nothing dials
// it — and without this it exposed nothing to probe at all, so a container running that way could
// only be described as healthy by not asking.
func (s *Server) RunHealth(ctx context.Context) error {
	healthServer := &http.Server{
		Addr:              s.cfg.HealthAddr,
		Handler:           s.HealthHandler(),
		ReadHeaderTimeout: 5 * time.Second,
	}

	errs := make(chan error, 1)
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
		_ = healthServer.Shutdown(shutdownCtx)
		return nil
	}
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

// Run starts the API and health listeners and blocks until the context is cancelled.
//
// The API listener is mutual TLS by default. In token mode it asks for no client certificate,
// and serves HTTPS only when a server keypair was configured; Handler chooses the matching
// authentication either way.
func (s *Server) Run(ctx context.Context) error {
	var tlsConfig *tls.Config
	var err error
	switch {
	case !s.cfg.TokenMode():
		tlsConfig, err = s.TLSConfig()
	case s.cfg.TokenTLS:
		tlsConfig, err = s.tokenTLSConfig()
	}
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
		var err error
		if tlsConfig != nil {
			err = apiServer.ListenAndServeTLS("", "")
		} else {
			err = apiServer.ListenAndServe()
		}
		if err != nil && err != http.ErrServerClosed {
			errs <- fmt.Errorf("API listener: %w", err)
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

// tokenTLSConfig serves HTTPS for token mode. Server identity only: the bearer token is what
// authenticates the caller, so no client certificate is requested.
func (s *Server) tokenTLSConfig() (*tls.Config, error) {
	certificate, err := tls.LoadX509KeyPair(s.cfg.TLSCertFile, s.cfg.TLSKeyFile)
	if err != nil {
		return nil, fmt.Errorf("loading agent keypair: %w", err)
	}
	return &tls.Config{
		Certificates: []tls.Certificate{certificate},
		MinVersion:   tls.VersionTLS12,
	}, nil
}

// StageBundle stores a bundle without changing what is served.
//
// Exported so a pull-mode runner performs exactly the operation the HTTP handler performs. Two
// copies of staging and atomic activation is precisely the drift this platform exists to prevent,
// and it would be invisible until the two connectivity models behaved differently on one host.
func (s *Server) StageBundle(b bundle.Bundle) error {
	return s.bundles.Stage(b)
}

// ActivateBundle validates the staged bundle, swaps it in and reloads.
func (s *Server) ActivateBundle(ctx context.Context, bundleID string, reload bool) (bundle.Result, error) {
	// Every rendered bundle points its HTTPS catch-all at this pair, and a host whose releases
	// directory was wiped would otherwise fail validation citing a certificate nobody configured.
	if err := defaulttls.Ensure(s.cfg.ReleasesDir); err != nil {
		return bundle.Result{}, err
	}
	return s.bundles.Activate(ctx, bundleID, nginxForBundle{s.nginx}, reload)
}

// DiscardBundle removes a superseded bundle.
func (s *Server) DiscardBundle(bundleID string) error {
	return s.bundles.Delete(bundleID)
}
