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

	// AgentPushProtocol specifies the listener protocol for push mode: mtls (default), http, or grpc.
	AgentPushProtocol string
	// AgentSecretToken is the pre-shared secret token expected when AgentPushProtocol is http or grpc.
	AgentSecretToken string

	ReleasesDir string
	NginxBinary string
	NginxConf   string

	CommandTimeout time.Duration
	AgentVersion   string

	// --- pull mode ---
	//
	// When ServerURL is set the agent dials the management server instead of waiting to be
	// dialled, and needs no inbound connectivity at all. That is the point: a host behind NAT
	// can satisfy no listener contract, but it can always make an outbound call.

	// ServerURL is the management API base, e.g. https://enginx.example.com/api/v1. Empty
	// means push mode, which is the original behaviour and stays the default.
	ServerURL string
	// RegistrationToken enrols this host the first time it runs. Spent once and then unused:
	// what the agent presents thereafter is the token it was issued in exchange.
	RegistrationToken string
	// TokenFile is where the issued agent token is kept between restarts. Written 0600.
	TokenFile string
	// InstanceName is how this host will be known. Defaults to the hostname, which is almost
	// always what an operator would have typed anyway.
	InstanceName string
	Environment  string

	HeartbeatInterval time.Duration
	// PollWait is how long a request for work is held open when there is none.
	PollWait time.Duration
}

// PullMode reports whether this agent calls the platform rather than being called.
func (c Config) PullMode() bool {
	return strings.TrimSpace(c.ServerURL) != ""
}

func Load() (Config, error) {
	cfg := Config{
		ListenAddr:        env("AGENT_LISTEN_ADDR", ":8443"),
		HealthAddr:        env("AGENT_HEALTH_ADDR", "127.0.0.1:9099"),
		TLSCertFile:       env("AGENT_TLS_CERT", "/etc/enginx/pki/agent.crt"),
		TLSKeyFile:        env("AGENT_TLS_KEY", "/etc/enginx/pki/agent.key"),
		ClientCAFile:      env("AGENT_CLIENT_CA", "/etc/enginx/pki/ca.crt"),
		ClientCN:          env("AGENT_CLIENT_CN", "enginx-management"),
		AgentPushProtocol: strings.ToLower(strings.TrimSpace(env("AGENT_PUSH_PROTOCOL", "mtls"))),
		AgentSecretToken:  strings.TrimSpace(env("AGENT_SECRET_TOKEN", "")),
		ReleasesDir:       env("AGENT_RELEASES_DIR", "/etc/nginx/enginx"),
		NginxBinary:       env("AGENT_NGINX_BINARY", "/usr/sbin/nginx"),
		NginxConf:         env("AGENT_NGINX_CONF", "/etc/nginx/nginx.conf"),
		CommandTimeout:    30 * time.Second,
		AgentVersion:      env("AGENT_VERSION", version.Version),

		ServerURL:         strings.TrimRight(env("ENGINX_SERVER_URL", ""), "/"),
		RegistrationToken: env("ENGINX_REGISTRATION_TOKEN", ""),
		TokenFile:         env("ENGINX_TOKEN_FILE", "/var/lib/enginx/agent-token"),
		InstanceName:      env("ENGINX_INSTANCE_NAME", ""),
		Environment:       env("ENGINX_ENVIRONMENT", "PRODUCTION"),
		HeartbeatInterval: envDuration("ENGINX_HEARTBEAT_INTERVAL", 60*time.Second),
		PollWait:          envDuration("ENGINX_POLL_WAIT", 30*time.Second),
	}

	if cfg.PullMode() {
		if cfg.InstanceName == "" {
			host, err := os.Hostname()
			if err != nil {
				return Config{}, fmt.Errorf("ENGINX_INSTANCE_NAME is unset and the hostname is unreadable: %w", err)
			}
			cfg.InstanceName = strings.ToLower(host)
		}
	} else {
		if cfg.AgentPushProtocol == "http" || cfg.AgentPushProtocol == "grpc" {
			if cfg.AgentSecretToken == "" {
				return Config{}, fmt.Errorf("AGENT_SECRET_TOKEN is required when AGENT_PUSH_PROTOCOL is %s", cfg.AgentPushProtocol)
			}
		} else {
			// Push mode mTLS needs its listener identity up front.
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
		}
	}
	if _, err := os.Stat(cfg.NginxBinary); err != nil {
		return Config{}, fmt.Errorf("AGENT_NGINX_BINARY: %s not found: %w", cfg.NginxBinary, err)
	}

	return cfg, nil
}

func envDuration(key string, fallback time.Duration) time.Duration {
	raw, ok := os.LookupEnv(key)
	if !ok || strings.TrimSpace(raw) == "" {
		return fallback
	}
	parsed, err := time.ParseDuration(strings.TrimSpace(raw))
	if err != nil || parsed <= 0 {
		return fallback
	}
	return parsed
}

func env(key, fallback string) string {
	if value, ok := os.LookupEnv(key); ok && strings.TrimSpace(value) != "" {
		return value
	}
	return fallback
}
