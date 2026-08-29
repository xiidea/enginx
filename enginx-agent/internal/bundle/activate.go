package bundle

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
)

// Nginx is the part of the NGINX controller activation needs.
//
// Reload here is the signal-only operation: this package validates immediately beforehand and
// would otherwise cause the configuration tree to be parsed twice for a single deployment.
type Nginx interface {
	Test(ctx context.Context) (ok bool, output string)
	Reload(ctx context.Context) error
	Version(ctx context.Context) string
}

// Result describes what activation did.
type Result struct {
	BundleID         string
	PreviousBundleID string
	TestOutput       string
	NginxVersion     string
	Noop             bool
	RolledBack       bool
}

// ErrValidationFailed means nginx -t rejected the staged tree. Nothing was changed.
var ErrValidationFailed = errors.New("nginx rejected the configuration")

// Activate makes the host serve a staged bundle.
//
// The order is the entire contract of this agent:
//
//  1. point the symlink at the candidate release,
//  2. run nginx -t, which now reads the candidate through that symlink,
//  3. on failure, put the symlink back and report — nothing was reloaded, so the previous
//     configuration is still the one being served,
//  4. on success, reload; if the reload itself fails, restore the previous release and reload
//     again so the host is never left serving a configuration that could not start.
//
// Step 1 has to come before step 2 because the include in nginx.conf resolves through `current`;
// validating the candidate any other way would be validating a different tree from the one that
// would actually load. The window between 1 and 2 is not a risk: NGINX only re-reads its
// configuration on reload, and no reload happens in between.
func (s *Store) Activate(ctx context.Context, bundleID string, nginx Nginx, reload bool) (Result, error) {
	s.Lock()
	defer s.Unlock()

	if !s.Exists(bundleID) {
		return Result{}, ErrBundleUnknown
	}

	previous := s.Active()
	if previous == bundleID {
		ok, output := nginx.Test(ctx)
		if !ok {
			// The active tree is broken by something outside this bundle. Say so rather than
			// reporting a cheerful no-op.
			return Result{BundleID: bundleID, TestOutput: output}, ErrValidationFailed
		}
		return Result{
			BundleID:     bundleID,
			TestOutput:   output,
			NginxVersion: nginx.Version(ctx),
			Noop:         true,
		}, nil
	}

	if err := s.pointCurrentAt(bundleID); err != nil {
		return Result{}, err
	}

	ok, output := nginx.Test(ctx)
	if !ok {
		if previous != "" {
			if restoreErr := s.pointCurrentAt(previous); restoreErr != nil {
				// Serious: the symlink is left pointing at an invalid tree. NGINX keeps serving
				// its already-loaded configuration, but the next reload would fail, so this has
				// to be loud.
				slog.Error("could not restore the previous release after a failed validation",
					"previous", previous, "error", restoreErr)
			}
		} else {
			_ = os.Remove(filepath.Join(s.root, currentLink))
		}
		return Result{BundleID: bundleID, PreviousBundleID: previous, TestOutput: output}, ErrValidationFailed
	}

	if !reload {
		// Validated and staged, deliberately not serving. This is what the dry-run path uses to
		// prove a configuration is loadable without touching live traffic.
		if previous != "" {
			if restoreErr := s.pointCurrentAt(previous); restoreErr != nil {
				return Result{}, restoreErr
			}
		} else {
			_ = os.Remove(filepath.Join(s.root, currentLink))
		}
		return Result{BundleID: bundleID, PreviousBundleID: previous, TestOutput: output}, nil
	}

	if err := nginx.Reload(ctx); err != nil {
		rolledBack := false
		if previous != "" {
			if restoreErr := s.pointCurrentAt(previous); restoreErr == nil {
				if reloadErr := nginx.Reload(ctx); reloadErr == nil {
					rolledBack = true
				} else {
					slog.Error("could not reload after restoring the previous release",
						"previous", previous, "error", reloadErr)
				}
			}
		}
		return Result{
			BundleID:         bundleID,
			PreviousBundleID: previous,
			TestOutput:       output,
			RolledBack:       rolledBack,
		}, fmt.Errorf("nginx reload failed: %w", err)
	}

	s.Prune()

	return Result{
		BundleID:         bundleID,
		PreviousBundleID: previous,
		TestOutput:       output,
		NginxVersion:     nginx.Version(ctx),
	}, nil
}

// pointCurrentAt swaps the symlink atomically.
//
// A symlink cannot be edited in place, so the new one is created under a temporary name and
// rename(2)d over the old. On a single filesystem that replacement is atomic: any reader sees
// either the old target or the new one, never a missing link.
func (s *Store) pointCurrentAt(bundleID string) error {
	link := filepath.Join(s.root, currentLink)
	temporary := link + ".swap"

	if err := os.RemoveAll(temporary); err != nil {
		return fmt.Errorf("clearing the temporary symlink: %w", err)
	}
	// Relative target, so the release tree can be moved or bind-mounted without breaking.
	if err := os.Symlink(filepath.Join(releasesDir, bundleID), temporary); err != nil {
		return fmt.Errorf("creating the replacement symlink: %w", err)
	}
	if err := os.Rename(temporary, link); err != nil {
		_ = os.Remove(temporary)
		return fmt.Errorf("swapping the current symlink: %w", err)
	}
	return nil
}
