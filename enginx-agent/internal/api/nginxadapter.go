package api

import (
	"context"

	"github.com/xiidea/enginx/enginx-agent/internal/nginx"
)

// nginxForBundle adapts the controller to the narrow interface the bundle package needs.
//
// The controller's own Reload validates before signalling, which is right for the standalone
// endpoint but redundant inside activation, where validation has just run against the same tree.
// This adapter binds Reload to the signal-only operation and keeps that decision in one place.
type nginxForBundle struct {
	controller *nginx.Controller
}

func (n nginxForBundle) Test(ctx context.Context) (bool, string) {
	result := n.controller.Test(ctx)
	return result.OK, result.Output
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
