// Package client talks to the management server from the agent's side.
//
// This exists only in pull mode, where the connection runs the other way round: the host dials
// the platform and asks what it should be doing. Nothing here listens, so a host with no inbound
// connectivity at all is managed exactly like one sitting on the management network.
package client

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

// Client is the agent's view of the management API.
type Client struct {
	baseURL string
	token   string
	http    *http.Client
}

func New(baseURL string, timeout time.Duration) *Client {
	return &Client{
		baseURL: strings.TrimRight(baseURL, "/"),
		http:    &http.Client{Timeout: timeout},
	}
}

// WithToken returns a client that presents the agent token on every call.
func (c *Client) WithToken(token string) *Client {
	return &Client{baseURL: c.baseURL, token: token, http: c.http}
}

// RegisterRequest enrols this host.
type RegisterRequest struct {
	RegistrationToken string `json:"registrationToken"`
	Name              string `json:"name"`
	Hostname          string `json:"hostname"`
	Environment       string `json:"environment,omitempty"`
}

// RegisterResponse carries the agent token, which is shown exactly once.
type RegisterResponse struct {
	InstanceID string `json:"instanceId"`
	Name       string `json:"name"`
	AgentToken string `json:"agentToken"`
}

func (c *Client) Register(ctx context.Context, request RegisterRequest) (RegisterResponse, error) {
	var response RegisterResponse
	err := c.call(ctx, http.MethodPost, "/agents/register", request, &response)
	return response, err
}

// HeartbeatRequest is what this host reports about itself.
//
// Deliberately the same fields the push model's status call returns: the platform interprets both
// with one piece of code, so the two connectivity models cannot come to disagree about what a
// healthy host looks like.
type HeartbeatRequest struct {
	AgentVersion     string `json:"agentVersion,omitempty"`
	NginxVersion     string `json:"nginxVersion,omitempty"`
	NginxRunning     bool   `json:"nginxRunning"`
	ActiveBundleID   string `json:"activeBundleId,omitempty"`
	ConfigTestOK     bool   `json:"configTestOk"`
	ConfigTestOutput string `json:"configTestOutput,omitempty"`
}

func (c *Client) Heartbeat(ctx context.Context, request HeartbeatRequest) error {
	return c.call(ctx, http.MethodPost, "/agents/heartbeat", request, nil)
}

// ErrUnauthorized means the platform does not recognise this agent's token.
//
// Worth its own type: the remedy is re-enrolment by an operator, not a retry, and a runner that
// cannot tell the two apart will retry a revoked credential until someone notices.
var ErrUnauthorized = fmt.Errorf("the management server did not accept this agent's token")

func (c *Client) call(ctx context.Context, method, path string, body any, out any) error {
	var payload io.Reader
	if body != nil {
		encoded, err := json.Marshal(body)
		if err != nil {
			return fmt.Errorf("encoding the request: %w", err)
		}
		payload = bytes.NewReader(encoded)
	}

	request, err := http.NewRequestWithContext(ctx, method, c.baseURL+path, payload)
	if err != nil {
		return fmt.Errorf("building the request: %w", err)
	}
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Accept", "application/json")
	if c.token != "" {
		request.Header.Set("Authorization", "Bearer "+c.token)
	}

	response, err := c.http.Do(request)
	if err != nil {
		return fmt.Errorf("calling %s: %w", path, err)
	}
	defer func() { _ = response.Body.Close() }()

	if response.StatusCode == http.StatusUnauthorized || response.StatusCode == http.StatusForbidden {
		return ErrUnauthorized
	}
	if response.StatusCode >= 300 {
		// Bounded: a problem document is small, and an HTML error page from something in the
		// path should not be read into memory in full.
		detail, _ := io.ReadAll(io.LimitReader(response.Body, 2048))
		return fmt.Errorf("%s returned %d: %s", path, response.StatusCode, strings.TrimSpace(string(detail)))
	}

	if out == nil {
		return nil
	}
	if err := json.NewDecoder(response.Body).Decode(out); err != nil {
		return fmt.Errorf("decoding the response from %s: %w", path, err)
	}
	return nil
}
