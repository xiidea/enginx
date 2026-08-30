// Command agent runs the Easy NGINX Admin node agent.
//
// The agent is the only privileged component in the system, and its privileges are narrow: write
// one directory, run the NGINX binary, and signal the process it started. The management server
// never opens an SSH session to this host.
package main

import (
	"context"
	"flag"
	"fmt"
	"log/slog"
	"os"
	"os/signal"
	"runtime"
	"syscall"
	"time"

	"github.com/xiidea/enginx/enginx-agent/internal/api"
	"github.com/xiidea/enginx/enginx-agent/internal/bundle"
	"github.com/xiidea/enginx/enginx-agent/internal/config"
	"github.com/xiidea/enginx/enginx-agent/internal/defaulttls"
	"github.com/xiidea/enginx/enginx-agent/internal/nginx"
	"github.com/xiidea/enginx/enginx-agent/internal/runner"
	"github.com/xiidea/enginx/enginx-agent/internal/version"
)

func main() {
	// Before anything else, and before logging is configured: an operator asking a binary what it
	// is should get an answer without it trying to read configuration or touch NGINX.
	showVersion := flag.Bool("version", false, "print the version and exit")
	flag.Parse()
	if *showVersion {
		fmt.Printf("enginx-agent %s (%s) %s/%s\n",
			version.Version, version.Commit, runtime.GOOS, runtime.GOARCH)
		return
	}

	slog.SetDefault(slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo})))
	slog.Info("starting", "version", version.Version, "commit", version.Commit)

	cfg, err := config.Load()
	if err != nil {
		slog.Error("configuration is invalid", "error", err)
		os.Exit(1)
	}

	// Before NGINX is asked to read anything. Every rendered bundle references this pair from
	// its HTTPS catch-all block, so a missing one fails `nginx -t` and takes the host down with
	// a message about a certificate the operator never configured.
	if err := defaulttls.Ensure(cfg.ReleasesDir); err != nil {
		slog.Error("could not prepare the default HTTPS certificate", "error", err)
		os.Exit(1)
	}

	controller := nginx.New(cfg.NginxBinary, cfg.NginxConf, cfg.CommandTimeout)

	startupCtx, cancelStartup := context.WithTimeout(context.Background(), cfg.CommandTimeout)
	startupCheck := controller.Test(startupCtx)
	cancelStartup()

	if startupCheck.OK {
		if err := controller.Start(); err != nil {
			slog.Error("could not start nginx", "error", err)
			os.Exit(1)
		}
		slog.Info("nginx started", "pid", controller.MasterPID(), "version", controller.Version(context.Background()))
	} else {
		// Loud, but not fatal. The agent's API comes up regardless so the management server can
		// deploy a corrected bundle; exiting here would mean the only way to repair a host is to
		// log into it by hand, which is the thing this platform exists to avoid. It also happens
		// for a mundane reason on a cold start: an upstream hostname that is not resolvable yet.
		slog.Error("nginx was not started: the configuration on disk is invalid. "+
			"The agent is running so a corrected configuration can be deployed.",
			"output", startupCheck.Output)
	}

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	server := api.NewServer(cfg, controller, bundle.NewStore(cfg.ReleasesDir))
	// Two writers in pull mode: the runner and the health listener. Buffered for both, so the
	// loser of the race does not block forever on a send nobody will receive.
	serverErr := make(chan error, 2)

	if cfg.PullMode() {
		// No listener anything can reach: pull mode needs no inbound connectivity, so opening a
		// port would widen this host's surface for nothing. The loopback health listener is the
		// exception, and is not reachable from off the host — without it there would be nothing
		// on this host to probe, and a container could only be called healthy by not asking.
		go func() { serverErr <- runner.New(cfg, server).Run(ctx) }()
		go func() { serverErr <- server.RunHealth(ctx) }()
		slog.Info("agent running in pull mode",
			"server", cfg.ServerURL, "instance", cfg.InstanceName, "health", cfg.HealthAddr)
	} else {
		go func() { serverErr <- server.Run(ctx) }()
		slog.Info("agent listening", "mtls", cfg.ListenAddr, "health", cfg.HealthAddr,
			"expectedClientCN", cfg.ClientCN)
	}

	select {
	case err := <-serverErr:
		if err != nil {
			slog.Error("agent stopped", "error", err)
			shutdown(controller)
			os.Exit(1)
		}
	case <-controller.Exited():
		// NGINX dying is different from never having started: something that was serving traffic
		// has stopped, and the container should be replaced rather than left half-alive.
		slog.Error("nginx exited; shutting down the agent")
		os.Exit(1)
	case <-ctx.Done():
		slog.Info("shutdown signal received")
	}

	shutdown(controller)
	slog.Info("agent stopped")
}

func shutdown(controller *nginx.Controller) {
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	controller.Stop(ctx)
}
