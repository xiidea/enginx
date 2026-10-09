package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

const goodToken = "0123456789abcdef0123456789abcdef"

// pushEnv clears what an outer environment could leak in, and points at a binary that exists.
func pushEnv(t *testing.T, protocol, token string) {
	t.Helper()
	t.Setenv("ENGINX_SERVER_URL", "")
	t.Setenv("AGENT_TLS_CERT", "")
	t.Setenv("AGENT_TLS_KEY", "")
	t.Setenv("AGENT_PUSH_PROTOCOL", protocol)
	t.Setenv("AGENT_SECRET_TOKEN", token)
	t.Setenv("AGENT_NGINX_BINARY", os.Args[0])
}

func TestTokenProtocolNeedsAToken(t *testing.T) {
	pushEnv(t, "http", "")
	if _, err := Load(); err == nil || !strings.Contains(err.Error(), "AGENT_SECRET_TOKEN") {
		t.Fatalf("expected an AGENT_SECRET_TOKEN error, got %v", err)
	}
}

func TestShortTokensAreRefused(t *testing.T) {
	pushEnv(t, "http", goodToken[:MinSecretTokenLength-1])
	if _, err := Load(); err == nil || !strings.Contains(err.Error(), "at least") {
		t.Fatalf("expected a minimum length error, got %v", err)
	}
}

func TestTokenProtocolLoads(t *testing.T) {
	pushEnv(t, "HTTP", goodToken)
	cfg, err := Load()
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if !cfg.TokenMode() || cfg.AgentSecretToken != goodToken {
		t.Fatalf("expected token mode with the configured token, got %+v", cfg)
	}
	if cfg.TokenTLS {
		t.Fatal("TLS must stay off unless a keypair is configured")
	}
}

func TestUnknownProtocolIsRefused(t *testing.T) {
	// grpc included: there has never been a gRPC listener, so the name is refused like any other.
	for _, protocol := range []string{"https", "grpc", "token"} {
		pushEnv(t, protocol, goodToken)
		if _, err := Load(); err == nil || !strings.Contains(err.Error(), "AGENT_PUSH_PROTOCOL") {
			t.Fatalf("%s: expected an AGENT_PUSH_PROTOCOL error, got %v", protocol, err)
		}
	}
}

func TestTokenTLSNeedsBothHalvesOfTheKeypair(t *testing.T) {
	pushEnv(t, "http", goodToken)
	t.Setenv("AGENT_TLS_CERT", os.Args[0])
	if _, err := Load(); err == nil || !strings.Contains(err.Error(), "together") {
		t.Fatalf("expected a keypair error, got %v", err)
	}
}

func TestTokenTLSIsOnWhenAKeypairIsGiven(t *testing.T) {
	pushEnv(t, "http", goodToken)
	dir := t.TempDir()
	cert, key := filepath.Join(dir, "agent.crt"), filepath.Join(dir, "agent.key")
	for _, path := range []string{cert, key} {
		if err := os.WriteFile(path, []byte("x"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	t.Setenv("AGENT_TLS_CERT", cert)
	t.Setenv("AGENT_TLS_KEY", key)

	cfg, err := Load()
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if !cfg.TokenTLS {
		t.Fatal("expected TLS on the token listener")
	}
}
