// Package runner drives an agent that calls the management server rather than being called.
package runner

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/xiidea/enginx/enginx-agent/internal/api"
	"github.com/xiidea/enginx/enginx-agent/internal/client"
	"github.com/xiidea/enginx/enginx-agent/internal/config"
)

// Runner enrols this host once, then reports on it for as long as it runs.
type Runner struct {
	cfg    config.Config
	api    *api.Server
	client *client.Client
}

func New(cfg config.Config, server *api.Server) *Runner {
	return &Runner{
		cfg: cfg,
		api: server,
		// Comfortably longer than the platform takes to answer, short enough that a black-holed
		// connection is noticed rather than hanging until the process is restarted.
		client: client.New(cfg.ServerURL, 30*time.Second),
	}
}

// Run enrols if necessary and then heartbeats until the context ends.
func (r *Runner) Run(ctx context.Context) error {
	token, err := r.establishToken(ctx)
	if err != nil {
		return err
	}
	authenticated := r.client.WithToken(token)

	slog.Info("agent registered; reporting to the management server",
		"server", r.cfg.ServerURL, "interval", r.cfg.HeartbeatInterval)

	// Immediately, then on the interval: waiting a full interval before the first report leaves
	// a freshly started host looking silent for exactly as long as the threshold that judges it.
	r.report(ctx, authenticated)

	ticker := time.NewTicker(r.cfg.HeartbeatInterval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return nil
		case <-ticker.C:
			r.report(ctx, authenticated)
		}
	}
}

// establishToken loads the stored agent token, or enrols to obtain one.
//
// Enrolment happens once in a host's life. Storing the result is what makes that true across
// restarts — without it every restart would spend another use of the registration token, and a
// token minted for one host would be exhausted by that host rebooting.
func (r *Runner) establishToken(ctx context.Context) (string, error) {
	if stored, err := r.readToken(); err == nil && stored != "" {
		slog.Info("using the stored agent token", "file", r.cfg.TokenFile)
		return stored, nil
	}

	if strings.TrimSpace(r.cfg.RegistrationToken) == "" {
		return "", fmt.Errorf("no agent token at %s and ENGINX_REGISTRATION_TOKEN is unset, "+
			"so this host cannot enrol", r.cfg.TokenFile)
	}

	hostname, err := os.Hostname()
	if err != nil {
		hostname = r.cfg.InstanceName
	}

	response, err := r.client.Register(ctx, client.RegisterRequest{
		RegistrationToken: r.cfg.RegistrationToken,
		Name:              r.cfg.InstanceName,
		Hostname:          hostname,
		Environment:       r.cfg.Environment,
	})
	if err != nil {
		return "", fmt.Errorf("enrolling with the management server: %w", err)
	}

	if err := r.writeToken(response.AgentToken); err != nil {
		// Fatal rather than carrying on with a token held only in memory: the next restart would
		// enrol again, and a registration token good for one use would already be spent.
		return "", fmt.Errorf("storing the issued agent token: %w", err)
	}

	slog.Info("enrolled with the management server", "instance", response.Name, "id", response.InstanceID)
	return response.AgentToken, nil
}

// report sends one heartbeat, reusing exactly the status the push API would have returned.
func (r *Runner) report(ctx context.Context, authenticated *client.Client) {
	status := r.api.Status(ctx)

	err := authenticated.Heartbeat(ctx, client.HeartbeatRequest{
		AgentVersion:     status.AgentVersion,
		NginxVersion:     status.NginxVersion,
		NginxRunning:     status.NginxRunning,
		ActiveBundleID:   status.ActiveBundleID,
		ConfigTestOK:     status.ConfigTestOK,
		ConfigTestOutput: status.ConfigTestOutput,
	})

	switch {
	case err == nil:
		return
	case errors.Is(err, client.ErrUnauthorized):
		// Retrying will not help: the credential has been revoked, and only an operator can
		// issue another. Loud, and left running so the host keeps serving traffic meanwhile.
		slog.Error("the management server rejected this agent's token; re-enrolment is needed",
			"file", r.cfg.TokenFile)
	default:
		// Ordinary: the server is restarting, or the network is briefly away. The next tick
		// tries again, and the platform marks this host offline if the silence lasts.
		slog.Warn("heartbeat failed", "error", err)
	}
}

func (r *Runner) readToken() (string, error) {
	raw, err := os.ReadFile(r.cfg.TokenFile)
	if err != nil {
		return "", err
	}
	return strings.TrimSpace(string(raw)), nil
}

// writeToken persists the credential 0600, creating its directory if needed.
func (r *Runner) writeToken(token string) error {
	if err := os.MkdirAll(filepath.Dir(r.cfg.TokenFile), 0o700); err != nil {
		return err
	}
	return os.WriteFile(r.cfg.TokenFile, []byte(token+"\n"), 0o600)
}
