// Package nginx wraps the NGINX binary.
//
// Every invocation uses a fixed argument vector through exec.Command. Nothing from an API
// request ever reaches this package as a command or an argument.
package nginx

import (
	"context"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"syscall"
	"time"
)

var versionPattern = regexp.MustCompile(`nginx/(\S+)`)

// Controller owns the NGINX process on this host.
type Controller struct {
	binary   string
	confPath string
	timeout  time.Duration

	mu      sync.Mutex
	process *exec.Cmd
	exited  chan struct{}
}

func New(binary, confPath string, timeout time.Duration) *Controller {
	return &Controller{binary: binary, confPath: confPath, timeout: timeout}
}

// TestResult is the outcome of `nginx -t`. A failing test is a successful call: the caller
// asked whether the configuration is valid and got a truthful answer.
type TestResult struct {
	OK     bool
	Output string
}

// Test validates the configuration currently on disk without touching the running process.
func (c *Controller) Test(ctx context.Context) TestResult {
	out, err := c.run(ctx, "-t")
	return TestResult{OK: err == nil, Output: strings.TrimSpace(out)}
}

// Reload validates first and refuses to signal NGINX if validation fails.
//
// This ordering is the whole point of the agent: it is not possible to reach a reload from
// outside without a successful `nginx -t` immediately beforehand.
func (c *Controller) Reload(ctx context.Context) (TestResult, error) {
	result := c.Test(ctx)
	if !result.OK {
		return result, ErrValidationFailed
	}
	if _, err := c.run(ctx, "-s", "reload"); err != nil {
		return result, fmt.Errorf("reload failed: %w", err)
	}
	return result, nil
}

var ErrValidationFailed = errors.New("nginx configuration validation failed")

// Signal sends a reload without validating first.
//
// The precondition is the caller's to hold: it must have just run Test against the very tree
// NGINX is about to read. Only the bundle activation sequence uses this, immediately after its
// own validation step, so that the tree is not parsed twice for one deployment. Anything else
// wanting a reload should call Reload, which validates for itself.
func (c *Controller) Signal(ctx context.Context) error {
	if _, err := c.run(ctx, "-s", "reload"); err != nil {
		return fmt.Errorf("reload failed: %w", err)
	}
	return nil
}

// Version reports the running NGINX version. `nginx -v` writes to stderr.
func (c *Controller) Version(ctx context.Context) string {
	out, err := c.run(ctx, "-v")
	if err != nil && out == "" {
		return ""
	}
	if match := versionPattern.FindStringSubmatch(out); len(match) == 2 {
		return match[1]
	}
	return ""
}

// Start launches NGINX in the foreground as a supervised child.
//
// The agent runs NGINX rather than sitting beside it because signalling a process in another
// container is not possible; co-location is a requirement of the design, not a convenience.
func (c *Controller) Start() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.process != nil {
		return errors.New("nginx is already running")
	}

	cmd := exec.Command(c.binary, "-c", c.confPath, "-g", "daemon off;")
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	if err := cmd.Start(); err != nil {
		return fmt.Errorf("starting nginx: %w", err)
	}

	c.process = cmd
	c.exited = make(chan struct{})
	go func(done chan struct{}) {
		_ = cmd.Wait()
		close(done)
	}(c.exited)
	return nil
}

// Exited is closed when the supervised NGINX process terminates.
func (c *Controller) Exited() <-chan struct{} {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.exited
}

// Stop asks NGINX to shut down gracefully, then waits for the process to leave.
func (c *Controller) Stop(ctx context.Context) {
	c.mu.Lock()
	process, done := c.process, c.exited
	c.mu.Unlock()
	if process == nil || process.Process == nil {
		return
	}

	_ = process.Process.Signal(syscall.SIGQUIT)
	select {
	case <-done:
	case <-ctx.Done():
		_ = process.Process.Kill()
	}
}

// Running reports whether the supervised process is still alive.
func (c *Controller) Running() bool {
	c.mu.Lock()
	done := c.exited
	c.mu.Unlock()
	if done == nil {
		return false
	}
	select {
	case <-done:
		return false
	default:
		return true
	}
}

// MasterPID is the pid of the supervised process, or 0 when nothing is running.
func (c *Controller) MasterPID() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.process == nil || c.process.Process == nil {
		return 0
	}
	return c.process.Process.Pid
}

func (c *Controller) run(ctx context.Context, args ...string) (string, error) {
	ctx, cancel := context.WithTimeout(ctx, c.timeout)
	defer cancel()

	full := append([]string{"-c", c.confPath}, args...)
	cmd := exec.CommandContext(ctx, c.binary, full...)

	// `nginx -t` and `nginx -v` both report on stderr; merging keeps one output field.
	combined, err := cmd.CombinedOutput()
	output := string(combined)
	if err != nil {
		return output, fmt.Errorf("%s %s: %w", filepath.Base(c.binary), strings.Join(full, " "), err)
	}
	return output, nil
}
