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
	"sync"
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

	// Guards the credential, which two goroutines read and either may replace: the heartbeat and
	// the job loop can discover a rejected token at the same moment, and only one of them should
	// spend a registration token recovering from it.
	mu            sync.Mutex
	authenticated *client.Client
	reEnrolled    bool
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
	r.authenticated = r.client.WithToken(token)

	slog.Info("agent registered; reporting to the management server",
		"server", r.cfg.ServerURL, "interval", r.cfg.HeartbeatInterval)

	// Collecting work runs alongside reporting rather than between reports: a long-poll parks for
	// up to half a minute, and a heartbeat that waited behind it would drift late enough to have
	// the platform judge this host silent.
	go r.collectWork(ctx)

	// Immediately, then on the interval: waiting a full interval before the first report leaves
	// a freshly started host looking silent for exactly as long as the threshold that judges it.
	r.report(ctx)

	ticker := time.NewTicker(r.cfg.HeartbeatInterval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return nil
		case <-ticker.C:
			r.report(ctx)
		}
	}
}

// current returns the credential to call with.
func (r *Runner) current() *client.Client {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.authenticated
}

// recoverCredential enrols again after the platform rejected the token the caller used.
//
// Once, and only when a registration token is present. The restriction is the whole design: a
// runner that re-enrolled on every rejection would spend a fresh registration on every restart
// against a misconfigured server, and a single-use token would be gone before anyone noticed. But
// refusing outright is worse than it sounds — a host whose credential was revoked could then never
// recover, even with an operator standing over it having supplied a new token.
//
// @param used the credential that was rejected, so the loser of a race is told to retry rather
//
//	than reporting a failure that the winner has already repaired
//
// @return whether the caller should try again
func (r *Runner) recoverCredential(ctx context.Context, used *client.Client) bool {
	r.mu.Lock()
	defer r.mu.Unlock()

	// Both loops can discover a rejected token in the same instant. If the other one has already
	// replaced it, there is nothing to recover from and everything to retry.
	if used != nil && r.authenticated != used {
		return true
	}
	if r.reEnrolled {
		return false
	}
	if strings.TrimSpace(r.cfg.RegistrationToken) == "" {
		return false
	}
	// Set before trying, so a failed attempt is not retried on the next tick either. One attempt
	// per process start, whatever the outcome.
	r.reEnrolled = true

	slog.Warn("the stored agent token was rejected; enrolling again with the configured " +
		"registration token")

	token, err := r.enrol(ctx)
	if err != nil {
		// A conflict here means the instance still exists under this name and only its credential
		// was revoked. Worth saying plainly: the remedy is to reissue or remove that instance, and
		// nothing this host does on its own will help.
		slog.Error("could not enrol again; this host needs an operator", "error", err)
		return false
	}

	r.authenticated = r.client.WithToken(token)
	slog.Info("enrolled again; the previous credential is no longer used")
	return true
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
	return r.enrol(ctx)
}

// enrol spends the registration token and stores what it buys.
func (r *Runner) enrol(ctx context.Context) (string, error) {
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
func (r *Runner) report(ctx context.Context) {
	status := r.api.Status(ctx)

	authenticated := r.current()
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
		if r.recoverCredential(ctx, authenticated) {
			return
		}
		// Retrying the same credential will not help. Loud, and left running so the host keeps
		// serving traffic while somebody attends to it.
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
