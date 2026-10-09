// Package config loads the agent's settings from the environment.
//
// Everything the agent needs is a path or an address. There is deliberately no way to configure
// a command: the NGINX binary is invoked with a fixed argument vector, never through a shell,
// so the agent has no command-injection surface to protect.
package config

import (
	"fmt"
	"os"
	"strings"
	"time"

	"github.com/xiidea/enginx/enginx-agent/internal/version"
)

// Push protocols. The listener a dialled agent opens, and so how the management server proves
// who it is.
const (
	// ProtocolMTLS requires a client certificate signed by the configured CA, with a pinned CN.
	ProtocolMTLS = "mtls"
	// ProtocolHTTP requires a pre-shared bearer token. For hosts behind a proxy that terminates
	// TLS and so cannot pass a client certificate through.
	ProtocolHTTP = "http"
)

// MinSecretTokenLength is the shortest AGENT_SECRET_TOKEN accepted. The token is the only thing
// standing between the network and a process that writes NGINX configuration and private keys,
// so a guessable one is refused at startup rather than discovered later.
const MinSecretTokenLength = 32

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

	// AgentPushProtocol is the push-mode listener: ProtocolMTLS (default) or ProtocolHTTP.
	AgentPushProtocol string
	// AgentSecretToken is the pre-shared bearer token expected under ProtocolHTTP.
	AgentSecretToken string
	// TokenTLS serves the token listener over HTTPS with TLSCertFile/TLSKeyFile. Set when both
	// are given explicitly under ProtocolHTTP. No client certificate is asked for: the token is
	// the authentication, and TLS here only keeps it, and the bundles, off the wire in clear.
	TokenTLS bool

	ReleasesDir string
	NginxBinary string
	NginxConf   string
	// NginxManaged is true when the agent starts and supervises NGINX itself, as in the container
	// image. False when the host's service manager already runs it (a distribution's
	// nginx.service): the agent then only validates and reloads, and finds the running master
	// through NginxPIDFile.
	NginxManaged bool
	// NginxPIDFile is where the running NGINX master records its pid. Read to tell whether NGINX
	// is up when it is external, and to refuse to start a second master when it is managed.
	NginxPIDFile string

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

// TokenMode reports whether a dialled agent authenticates the management server by bearer token
// rather than by client certificate.
func (c Config) TokenMode() bool {
	return !c.PullMode() && c.AgentPushProtocol == ProtocolHTTP
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
		AgentPushProtocol: strings.ToLower(strings.TrimSpace(env("AGENT_PUSH_PROTOCOL", ProtocolMTLS))),
		AgentSecretToken:  strings.TrimSpace(env("AGENT_SECRET_TOKEN", "")),
		ReleasesDir:       env("AGENT_RELEASES_DIR", "/etc/nginx/enginx"),
		NginxBinary:       env("AGENT_NGINX_BINARY", "/usr/sbin/nginx"),
		NginxConf:         env("AGENT_NGINX_CONF", "/etc/nginx/nginx.conf"),
		NginxPIDFile:      env("AGENT_NGINX_PID_FILE", "/run/nginx.pid"),
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

	managed, err := envBool("AGENT_NGINX_MANAGED", true)
	if err != nil {
		return Config{}, err
	}
	cfg.NginxManaged = managed

	if cfg.PullMode() {
		if cfg.InstanceName == "" {
			host, err := os.Hostname()
			if err != nil {
				return Config{}, fmt.Errorf("ENGINX_INSTANCE_NAME is unset and the hostname is unreadable: %w", err)
			}
			cfg.InstanceName = strings.ToLower(host)
		}
	} else {
		if cfg.AgentPushProtocol != ProtocolMTLS && cfg.AgentPushProtocol != ProtocolHTTP {
			return Config{}, fmt.Errorf("AGENT_PUSH_PROTOCOL must be %s or %s, not %q",
				ProtocolMTLS, ProtocolHTTP, cfg.AgentPushProtocol)
		}

		if cfg.AgentPushProtocol == ProtocolHTTP {
			if len(cfg.AgentSecretToken) < MinSecretTokenLength {
				return Config{}, fmt.Errorf("AGENT_SECRET_TOKEN must be at least %d characters when "+
					"AGENT_PUSH_PROTOCOL is http; generate one with: openssl rand -hex 32", MinSecretTokenLength)
			}
			certSet := strings.TrimSpace(os.Getenv("AGENT_TLS_CERT")) != ""
			keySet := strings.TrimSpace(os.Getenv("AGENT_TLS_KEY")) != ""
			if certSet != keySet {
				return Config{}, fmt.Errorf("AGENT_TLS_CERT and AGENT_TLS_KEY must be set together")
			}
			if certSet {
				for name, path := range map[string]string{
					"AGENT_TLS_CERT": cfg.TLSCertFile,
					"AGENT_TLS_KEY":  cfg.TLSKeyFile,
				} {
					if _, err := os.Stat(path); err != nil {
						return Config{}, fmt.Errorf("%s: %s is not readable: %w", name, path, err)
					}
				}
				cfg.TokenTLS = true
			}
		} else {
			// Push mode needs its listener identity up front. Pull mode does not open a listener
			// at all, so requiring these would make a certificate a precondition for a model that
			// has no use for one.
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

func envBool(key string, fallback bool) (bool, error) {
	raw := strings.ToLower(strings.TrimSpace(os.Getenv(key)))
	switch raw {
	case "":
		return fallback, nil
	case "true", "yes", "1":
		return true, nil
	case "false", "no", "0":
		return false, nil
	}
	return false, fmt.Errorf("%s must be true or false, not %q", key, raw)
}

func env(key, fallback string) string {
	if value, ok := os.LookupEnv(key); ok && strings.TrimSpace(value) != "" {
		return value
	}
	return fallback
}
