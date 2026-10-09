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
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

var versionPattern = regexp.MustCompile(`nginx/(\S+)`)

// Controller drives the NGINX on this host.
//
// Two ways round. Managed, the default and what the container image does: the agent starts NGINX
// as its own child and supervises it. External: the host's service manager (systemd, typically
// the distribution's nginx.service) runs NGINX, and the agent only validates and reloads it,
// finding the running master through its pid file. The second is what a host that already runs
// NGINX needs — two masters cannot both bind the same ports.
type Controller struct {
	binary   string
	confPath string
	timeout  time.Duration
	// external is set when NGINX belongs to the host's service manager, not to this process.
	external bool
	pidFile  string

	mu      sync.Mutex
	process *exec.Cmd
	exited  chan struct{}
}

// ErrExternallyManaged refuses to start NGINX on a host whose service manager owns it.
var ErrExternallyManaged = errors.New(
	"NGINX is run by this host's service manager (AGENT_NGINX_MANAGED=false); start it there, e.g. systemctl start nginx")

// New controls an NGINX this agent starts and supervises itself.
func New(binary, confPath string, timeout time.Duration) *Controller {
	return &Controller{binary: binary, confPath: confPath, timeout: timeout}
}

// NewExternal controls an NGINX the host's service manager runs, found through its pid file.
func NewExternal(binary, confPath string, timeout time.Duration, pidFile string) *Controller {
	return &Controller{binary: binary, confPath: confPath, timeout: timeout, external: true, pidFile: pidFile}
}

// External reports whether the host's service manager, not this agent, runs NGINX.
func (c *Controller) External() bool {
	return c.external
}

// Dump returns the whole configuration NGINX would load, every included file inlined (`nginx -T`).
func (c *Controller) Dump(ctx context.Context) (string, error) {
	return c.run(ctx, "-T")
}

// PIDAlive reads an NGINX pid file and reports whether that process is running.
//
// A pid file can outlive its process, and the number can be reused, so where /proc exists the
// process is also checked to be NGINX; elsewhere a live pid is taken at its word.
func PIDAlive(pidFile string) (int, bool) {
	raw, err := os.ReadFile(pidFile)
	if err != nil {
		return 0, false
	}
	pid, err := strconv.Atoi(strings.TrimSpace(string(raw)))
	if err != nil || pid <= 0 {
		return 0, false
	}
	if err := syscall.Kill(pid, 0); err != nil && !errors.Is(err, syscall.EPERM) {
		return 0, false
	}
	if cmdline, err := os.ReadFile(fmt.Sprintf("/proc/%d/cmdline", pid)); err == nil &&
		!strings.Contains(string(cmdline), "nginx") {
		return 0, false
	}
	return pid, true
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
	if c.external {
		return ErrExternallyManaged
	}
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

// Exited is closed when the supervised NGINX process terminates. Nil, and so never ready, when
// NGINX is external: its lifecycle is the service manager's to watch, not this agent's.
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

// Running reports whether NGINX is up: the supervised child, or the master in the pid file.
func (c *Controller) Running() bool {
	if c.external {
		_, alive := PIDAlive(c.pidFile)
		return alive
	}
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
	if c.external {
		pid, _ := PIDAlive(c.pidFile)
		return pid
	}
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
