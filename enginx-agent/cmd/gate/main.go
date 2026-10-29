// Command enginx-gate protects sites with email sign-in, for NGINX on the same host.
//
// It is a separate process from the agent on purpose. The agent is a privileged executor that
// writes configuration; the gate faces every visitor of a protected site, so it runs as NGINX's
// unprivileged user, listens on a unix socket only NGINX's workers can reach, and holds nothing
// it could be talked into writing.
package main

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	"github.com/xiidea/enginx/enginx-agent/internal/gate"
	"github.com/xiidea/enginx/enginx-agent/internal/version"
)

func env(key, fallback string) string {
	if value, ok := os.LookupEnv(key); ok && value != "" {
		return value
	}
	return fallback
}

func main() {
	showVersion := flag.Bool("version", false, "print the version and exit")
	flag.Parse()
	if *showVersion {
		fmt.Printf("enginx-gate %s (%s)\n", version.Version, version.Commit)
		return
	}

	slog.SetDefault(slog.New(slog.NewJSONHandler(os.Stderr, nil)))
	socket := env("GATE_SOCKET", "/run/enginx/gate.sock")
	sitesDir := env("GATE_SITES_DIR", "/etc/nginx/enginx/current/gate/sites")

	client, err := relayClient(os.Getenv("GATE_RELAY_CA"))
	if err != nil {
		slog.Error("could not load GATE_RELAY_CA", "error", err)
		os.Exit(1)
	}
	server := gate.NewServer(gate.HTTPRelay{Client: client})

	listener, err := listen(socket)
	if err != nil {
		slog.Error("could not listen", "socket", socket, "error", err)
		os.Exit(1)
	}

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	go server.Watch(ctx, sitesDir, 2*time.Second)

	httpServer := &http.Server{
		Handler:           server.Handler(),
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      30 * time.Second,
		MaxHeaderBytes:    32 << 10,
	}
	go func() {
		<-ctx.Done()
		shutdown, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = httpServer.Shutdown(shutdown)
	}()
	defer server.Wait()

	slog.Info("gate listening", "version", version.Version, "socket", socket, "sites", sitesDir)
	if err := httpServer.Serve(listener); err != nil && !errors.Is(err, http.ErrServerClosed) {
		slog.Error("gate stopped", "error", err)
		os.Exit(1)
	}
}

// listen opens the unix socket, replacing a stale one left by a previous run, readable and
// writable by this user and its group: NGINX's workers, when the gate runs as NGINX's user.
func listen(socket string) (net.Listener, error) {
	if err := os.MkdirAll(filepath.Dir(socket), 0o750); err != nil {
		return nil, err
	}
	if err := os.Remove(socket); err != nil && !os.IsNotExist(err) {
		return nil, err
	}
	listener, err := net.Listen("unix", socket)
	if err != nil {
		return nil, err
	}
	if err := os.Chmod(socket, 0o660); err != nil {
		listener.Close()
		return nil, err
	}
	return listener, nil
}

// relayClient trusts the system roots, plus a private CA when the platform uses one.
func relayClient(caFile string) (*http.Client, error) {
	transport := http.DefaultTransport.(*http.Transport).Clone()
	if caFile != "" {
		pem, err := os.ReadFile(caFile)
		if err != nil {
			return nil, err
		}
		pool, err := x509.SystemCertPool()
		if err != nil {
			pool = x509.NewCertPool()
		}
		if !pool.AppendCertsFromPEM(pem) {
			return nil, fmt.Errorf("no certificates in %s", caFile)
		}
		transport.TLSClientConfig = &tls.Config{RootCAs: pool, MinVersion: tls.VersionTLS12}
	}
	return &http.Client{Transport: transport, Timeout: 15 * time.Second}, nil
}
