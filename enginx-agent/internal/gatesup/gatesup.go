// Package gatesup starts and restarts the gate when the agent runs NGINX itself.
//
// Supervision only: the agent starts a fixed binary with a fixed environment, as an unprivileged
// user, and restarts it if it exits. It never talks to the gate, and nothing a bundle carries can
// change what is started. On a host whose service manager runs NGINX, it runs the gate too.
package gatesup

import (
	"context"
	"fmt"
	"log/slog"
	"os"
	"os/exec"
	"os/user"
	"path/filepath"
	"strconv"
	"syscall"
	"time"
)

// Config is what the gate is started with.
type Config struct {
	Binary   string
	User     string
	Socket   string
	SitesDir string
}

// Run supervises the gate until ctx ends. It returns at once, logging why, when there is no
// gate to run: a host without the binary has no email sign-in, which VERIFY then reports for any
// site that needs it.
func Run(ctx context.Context, cfg Config) {
	if _, err := os.Stat(cfg.Binary); err != nil {
		slog.Info("no gate binary; sites with email sign-in cannot be served on this host", "binary", cfg.Binary)
		return
	}
	account, err := user.Lookup(cfg.User)
	if err != nil {
		slog.Error("the gate's user does not exist; the gate is not started", "user", cfg.User, "error", err)
		return
	}
	uid, _ := strconv.Atoi(account.Uid)
	gid, _ := strconv.Atoi(account.Gid)

	// The socket's directory belongs to the gate's user, so the gate can replace a stale socket
	// and NGINX's workers, running as the same user, can reach it.
	dir := filepath.Dir(cfg.Socket)
	if err := os.MkdirAll(dir, 0o750); err == nil {
		_ = os.Chown(dir, uid, gid)
	}

	backoff := time.Second
	for {
		started := time.Now()
		err := runOnce(ctx, cfg, uint32(uid), uint32(gid))
		if ctx.Err() != nil {
			return
		}
		if time.Since(started) > time.Minute {
			backoff = time.Second
		}
		slog.Error("the gate exited; restarting", "error", err, "in", backoff)
		select {
		case <-ctx.Done():
			return
		case <-time.After(backoff):
		}
		if backoff < 30*time.Second {
			backoff *= 2
		}
	}
}

func runOnce(ctx context.Context, cfg Config, uid, gid uint32) error {
	cmd := exec.CommandContext(ctx, cfg.Binary)
	cmd.Env = []string{
		"GATE_SOCKET=" + cfg.Socket,
		"GATE_SITES_DIR=" + cfg.SitesDir,
		"PATH=/usr/bin:/bin",
	}
	if ca := os.Getenv("GATE_RELAY_CA"); ca != "" {
		cmd.Env = append(cmd.Env, "GATE_RELAY_CA="+ca)
	}
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	cmd.SysProcAttr = &syscall.SysProcAttr{Credential: &syscall.Credential{Uid: uid, Gid: gid}}
	cmd.Cancel = func() error { return cmd.Process.Signal(syscall.SIGTERM) }
	cmd.WaitDelay = 10 * time.Second
	if err := cmd.Start(); err != nil {
		return fmt.Errorf("starting %s: %w", cfg.Binary, err)
	}
	slog.Info("gate started", "pid", cmd.Process.Pid, "user", cfg.User)
	return cmd.Wait()
}
