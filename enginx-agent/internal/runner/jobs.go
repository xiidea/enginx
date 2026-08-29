package runner

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"github.com/xiidea/enginx/enginx-agent/internal/bundle"
	"github.com/xiidea/enginx/enginx-agent/internal/client"
)

// collectWork asks for a job, runs it, reports it, and asks again.
//
// The request is long-polled, so this is not a busy loop: it spends nearly all its time parked in
// a request the server answers the moment work appears.
func (r *Runner) collectWork(ctx context.Context) {
	for {
		if ctx.Err() != nil {
			return
		}

		authenticated := r.current()
		job, err := authenticated.RequestJob(ctx, r.cfg.PollWait)
		switch {
		case ctx.Err() != nil:
			return
		case errors.Is(err, client.ErrUnauthorized):
			if r.recoverCredential(ctx, authenticated) {
				continue
			}
			// Retrying the same credential cannot help. Back off hard rather than hammering, and
			// keep serving traffic meanwhile.
			slog.Error("the management server rejected this agent's token; re-enrolment is needed")
			sleep(ctx, 60*time.Second)
			continue
		case err != nil:
			// Ordinary: the server is restarting, or the network is briefly away.
			slog.Warn("could not collect work", "error", err)
			sleep(ctx, 5*time.Second)
			continue
		case job == nil:
			// Nothing to do, which is the common case. Straight back to waiting.
			continue
		}

		result := r.execute(ctx, authenticated, *job)
		if err := authenticated.ReportJob(ctx, job.JobID, result); err != nil {
			// The lease will expire and the job will be handed out again. Safe: every operation
			// is idempotent, so a job that did complete replays to the same state.
			slog.Warn("could not report a job result; it will be retried after its lease expires",
				"job", job.JobID, "error", err)
		}
	}
}

// execute performs one job, translating whatever happens into a result the platform can record.
//
// Never returns an error: a job that failed is a result, not an exception. The distinction that
// matters to the platform is validation failure — nothing changed on this host — from everything
// else, and that is carried in the result rather than in an error type.
func (r *Runner) execute(ctx context.Context, authenticated *client.Client, job client.Job) client.JobResult {
	slog.Info("running job", "type", job.Type, "bundle", job.BundleID, "job", job.JobID)

	switch job.Type {
	case "STAGE_BUNDLE":
		return r.stage(ctx, authenticated, job)
	case "ACTIVATE_BUNDLE":
		return r.activate(ctx, job)
	case "DISCARD_BUNDLE":
		if err := r.api.DiscardBundle(job.BundleID); err != nil {
			return failed(fmt.Sprintf("discarding the bundle: %v", err))
		}
		return client.JobResult{Succeeded: true}
	default:
		// A server newer than this agent. Reporting it rather than ignoring it means the platform
		// sees a host that cannot do what it was asked, instead of a job that never comes back.
		return failed("this agent does not understand job type " + job.Type)
	}
}

func (r *Runner) stage(ctx context.Context, authenticated *client.Client, job client.Job) client.JobResult {
	// Fetched separately rather than carried in the job: a bundle holds every site's private key
	// and can be large, and the queue is not the place for either.
	fetched, err := authenticated.FetchBundle(ctx, job.BundleID)
	if err != nil {
		return failed(fmt.Sprintf("fetching the bundle: %v", err))
	}

	files := make([]bundle.File, 0, len(fetched.Files))
	for _, file := range fetched.Files {
		files = append(files, bundle.File{
			Path:      file.Path,
			Content:   file.Content,
			SHA256:    file.SHA256,
			Sensitive: file.Sensitive,
			Mode:      file.Mode,
		})
	}

	err = r.api.StageBundle(bundle.Bundle{
		BundleID:    fetched.BundleID,
		Sequence:    fetched.Sequence,
		ContentHash: fetched.ContentHash,
		Files:       files,
	})
	if err != nil {
		// Includes a checksum mismatch and a path trying to escape the release root. Both are
		// refusals of the payload, and neither is worth retrying unchanged.
		return failed(fmt.Sprintf("staging the bundle: %v", err))
	}
	return client.JobResult{Succeeded: true}
}

func (r *Runner) activate(ctx context.Context, job client.Job) client.JobResult {
	result, err := r.api.ActivateBundle(ctx, job.BundleID, job.Reload)

	if errors.Is(err, bundle.ErrValidationFailed) {
		// Nothing changed on this host: it is still serving what it was. Reported as its own kind
		// of failure because the platform must not retry it — identical bytes fail identically.
		return client.JobResult{
			ValidationFailed: true,
			TestOutput:       result.TestOutput,
			Error:            "NGINX rejected the configuration",
		}
	}
	if err != nil {
		return client.JobResult{
			TestOutput: result.TestOutput,
			Error:      fmt.Sprintf("activating the bundle: %v", err),
		}
	}

	return client.JobResult{
		Succeeded:        true,
		TestOutput:       result.TestOutput,
		NginxVersion:     result.NginxVersion,
		PreviousBundleID: result.PreviousBundleID,
		Noop:             result.Noop,
		RolledBack:       result.RolledBack,
	}
}

func failed(reason string) client.JobResult {
	return client.JobResult{Error: reason}
}

// sleep waits, or returns early if the agent is shutting down.
func sleep(ctx context.Context, d time.Duration) {
	timer := time.NewTimer(d)
	defer timer.Stop()
	select {
	case <-ctx.Done():
	case <-timer.C:
	}
}
