package config

import (
	"os"
	"testing"
)

func TestConfigPushProtocolValidation(t *testing.T) {
	nginxBin := os.Args[0] // existing executable file for stat check

	t.Run("http protocol requires secret token", func(t *testing.T) {
		t.Setenv("AGENT_PUSH_PROTOCOL", "http")
		t.Setenv("AGENT_SECRET_TOKEN", "")
		t.Setenv("AGENT_NGINX_BINARY", nginxBin)

		_, err := Load()
		if err == nil {
			t.Fatal("expected error when AGENT_SECRET_TOKEN is missing for http push protocol")
		}
		expected := "AGENT_SECRET_TOKEN is required when AGENT_PUSH_PROTOCOL is http"
		if err.Error() != expected {
			t.Fatalf("expected error '%s', got '%v'", expected, err)
		}
	})

	t.Run("http protocol succeeds with secret token", func(t *testing.T) {
		t.Setenv("AGENT_PUSH_PROTOCOL", "http")
		t.Setenv("AGENT_SECRET_TOKEN", "enginx-sec-secret123")
		t.Setenv("AGENT_NGINX_BINARY", nginxBin)

		cfg, err := Load()
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if cfg.AgentPushProtocol != "http" {
			t.Errorf("expected http, got %s", cfg.AgentPushProtocol)
		}
		if cfg.AgentSecretToken != "enginx-sec-secret123" {
			t.Errorf("expected enginx-sec-secret123, got %s", cfg.AgentSecretToken)
		}
	})

	t.Run("grpc protocol requires secret token", func(t *testing.T) {
		t.Setenv("AGENT_PUSH_PROTOCOL", "grpc")
		t.Setenv("AGENT_SECRET_TOKEN", "")
		t.Setenv("AGENT_NGINX_BINARY", nginxBin)

		_, err := Load()
		if err == nil {
			t.Fatal("expected error when AGENT_SECRET_TOKEN is missing for grpc push protocol")
		}
		expected := "AGENT_SECRET_TOKEN is required when AGENT_PUSH_PROTOCOL is grpc"
		if err.Error() != expected {
			t.Fatalf("expected error '%s', got '%v'", expected, err)
		}
	})

	t.Run("grpc protocol succeeds with secret token", func(t *testing.T) {
		t.Setenv("AGENT_PUSH_PROTOCOL", "grpc")
		t.Setenv("AGENT_SECRET_TOKEN", "enginx-sec-secret123")
		t.Setenv("AGENT_NGINX_BINARY", nginxBin)

		cfg, err := Load()
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if cfg.AgentPushProtocol != "grpc" {
			t.Errorf("expected grpc, got %s", cfg.AgentPushProtocol)
		}
		if cfg.AgentSecretToken != "enginx-sec-secret123" {
			t.Errorf("expected enginx-sec-secret123, got %s", cfg.AgentSecretToken)
		}
	})
}
