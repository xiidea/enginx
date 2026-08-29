// Package config loads the agent's settings from the environment.
//
// Everything the agent needs is a path or an address. There is deliberately no way to configure
// a command: the NGINX binary is invoked with a fixed argument vector, never through a shell,
// so the agent has no command-injection surface to protect.
package config

import (
	"fmt"
	"github.com/xiidea/enginx/enginx-agent/internal/version"
	"os"
	"strings"
	"time"
)

type Config struct {
	// ListenAddr is the mTLS API listener. Bind it to the management network only.
	ListenAddr string
	// HealthAddr serves an unauthenticated liveness probe. Loopback only: it must never
	// become a way to read host detail without a client certificate.
	HealthAddr string

	TLSCertFile  string
	TLSKeyFile   string
	ClientCAFile string
	// ClientCN is pinned in addition to CA verification. Trusting the CA alone would let any
	// certificate it ever signed drive this host.
	ClientCN string

	ReleasesDir string
	NginxBinary string
	NginxConf   string

	CommandTimeout time.Duration
	AgentVersion   string
}

func Load() (Config, error) {
	cfg := Config{
		ListenAddr:     env("AGENT_LISTEN_ADDR", ":8443"),
		HealthAddr:     env("AGENT_HEALTH_ADDR", "127.0.0.1:9099"),
		TLSCertFile:    env("AGENT_TLS_CERT", "/etc/enginx/pki/agent.crt"),
		TLSKeyFile:     env("AGENT_TLS_KEY", "/etc/enginx/pki/agent.key"),
		ClientCAFile:   env("AGENT_CLIENT_CA", "/etc/enginx/pki/ca.crt"),
		ClientCN:       env("AGENT_CLIENT_CN", "enginx-management"),
		ReleasesDir:    env("AGENT_RELEASES_DIR", "/etc/nginx/enginx"),
		NginxBinary:    env("AGENT_NGINX_BINARY", "/usr/sbin/nginx"),
		NginxConf:      env("AGENT_NGINX_CONF", "/etc/nginx/nginx.conf"),
		CommandTimeout: 30 * time.Second,
		AgentVersion:   env("AGENT_VERSION", version.Version),
	}

	for name, path := range map[string]string{
		"AGENT_TLS_CERT":  cfg.TLSCertFile,
		"AGENT_TLS_KEY":   cfg.TLSKeyFile,
		"AGENT_CLIENT_CA": cfg.ClientCAFile,
	} {
		if _, err := os.Stat(path); err != nil {
			return Config{}, fmt.Errorf("%s: %s is not readable: %w", name, path, err)
		}
	}
	if strings.TrimSpace(cfg.ClientCN) == "" {
		return Config{}, fmt.Errorf("AGENT_CLIENT_CN must name the expected management certificate CN")
	}
	if _, err := os.Stat(cfg.NginxBinary); err != nil {
		return Config{}, fmt.Errorf("AGENT_NGINX_BINARY: %s not found: %w", cfg.NginxBinary, err)
	}

	return cfg, nil
}

func env(key, fallback string) string {
	if value, ok := os.LookupEnv(key); ok && strings.TrimSpace(value) != "" {
		return value
	}
	return fallback
}
