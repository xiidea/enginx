package api

import (
	"context"
	"fmt"
	"strings"

	"github.com/xiidea/enginx/enginx-agent/internal/nginx"
)

// nginxForBundle adapts the controller to the narrow interface the bundle package needs.
//
// The controller's own Reload validates before signalling, which is right for the standalone
// endpoint but redundant inside activation, where validation has just run against the same tree.
// This adapter binds Reload to the signal-only operation and keeps that decision in one place.
type nginxForBundle struct {
	controller *nginx.Controller
	// includeDir is the directory nginx.conf must include for a bundle to be served at all.
	includeDir string
}

// Test validates the tree, and then that the tree is actually part of what NGINX loads.
//
// The second half is what nginx -t cannot say. On a host whose nginx.conf never includes the
// release tree, the bundle validates, the reload succeeds, and not one site is served: every
// phase reports success while traffic goes to whatever was there before. Failing here makes that
// a validation failure — nothing changes on the host, and the reason is in the deployment.
func (n nginxForBundle) Test(ctx context.Context) (bool, string) {
	result := n.controller.Test(ctx)
	if !result.OK {
		return false, result.Output
	}
	if loaded, err := includesDir(ctx, n.controller, n.includeDir); err == nil && !loaded {
		return false, result.Output + "\n" + MissingIncludeMessage(n.includeDir)
	}
	return true, result.Output
}

// includesDir reports whether the configuration NGINX loads includes dir, ignoring comments.
func includesDir(ctx context.Context, controller *nginx.Controller, dir string) (bool, error) {
	dump, err := controller.Dump(ctx)
	if err != nil {
		return false, err
	}
	for _, line := range strings.Split(dump, "\n") {
		line = strings.TrimSpace(line)
		if strings.HasPrefix(line, "include") && strings.Contains(line, dir) {
			return true, nil
		}
	}
	return false, nil
}

// MissingIncludeMessage says what is wrong and the one line that fixes it.
func MissingIncludeMessage(dir string) string {
	return fmt.Sprintf("enginx: the configuration NGINX loads does not include %s, so no deployed "+
		"site would be served. Add `include %s/*.conf;` inside the http block of the NGINX "+
		"configuration and reload.", dir, dir)
}

// Reload signals a running NGINX, or starts one that is not running.
//
// The second case is how a host recovers on its own. If the agent came up with an invalid
// configuration on disk, NGINX was never started; once a valid bundle has been staged and
// validated, this starts it rather than signalling a process that does not exist.
func (n nginxForBundle) Reload(ctx context.Context) error {
	if !n.controller.Running() {
		return n.controller.Start()
	}
	return n.controller.Signal(ctx)
}

func (n nginxForBundle) Version(ctx context.Context) string {
	return n.controller.Version(ctx)
}
