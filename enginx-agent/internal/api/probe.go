package api

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Probes run from the host, because the host is where the answer means something.
//
// Both of these could in principle be done from the management server, and both would be wrong
// there. The upstream check would be a server-side request forgery primitive — the host and port
// come from a user, and the management server sits on a network it has no business dialling
// arbitrary addresses on. The site check would be answering a question about *this* host from a
// machine that may reach it by a different route, or not at all.
//
// The site check cannot be pointed anywhere at all: it always dials loopback, and the name it is
// given becomes a Host header, never an address.
//
// The upstream check does dial an arbitrary host and port, and that is not an escalation here. Only
// the management server can ask — mTLS with a pinned common name — and it only asks about upstreams
// a user has configured, which NGINX on this host will dial itself the moment that configuration
// loads. The agent is reaching exactly where the thing it manages is about to reach.

const (
	probeTimeout       = 3 * time.Second
	maxProbeTargets    = 64
	maxProbeConcurrent = 8
)

// --- site verification -----------------------------------------------------

type verifyRequest struct {
	ServerNames []string `json:"serverNames"`
	// Port to dial on loopback. Defaults to 80.
	Port int `json:"port"`
}

type verifyResult struct {
	ServerName string `json:"serverName"`
	Responded  bool   `json:"responded"`
	StatusCode int    `json:"statusCode,omitempty"`
	Error      string `json:"error,omitempty"`
}

type verifyResponse struct {
	Results []verifyResult `json:"results"`
}

// handleVerify checks that each server name is answered by the NGINX now running.
//
// The question is whether the server *responded*, not whether it returned 200. A site with
// forceHttps answers port 80 with a 301, and a backend behind it may legitimately return 404 or
// 502 for the site root — none of which mean the configuration failed to take effect. What this
// catches is the case the whole phase exists for: the reload succeeded, and the name is
// nonetheless served by nothing.
func (s *Server) handleVerify(w http.ResponseWriter, r *http.Request) {
	var req verifyRequest
	if !decodeJSON(w, r, &req) {
		return
	}
	if len(req.ServerNames) == 0 {
		writeJSON(w, http.StatusOK, verifyResponse{Results: []verifyResult{}})
		return
	}
	if len(req.ServerNames) > maxProbeTargets {
		writeProblem(w, r, http.StatusUnprocessableEntity, "too-many-targets",
			"Too many server names",
			fmt.Sprintf("At most %d names may be verified in one request", maxProbeTargets))
		return
	}
	port := req.Port
	if port == 0 {
		port = 80
	}

	client := &http.Client{
		Timeout: probeTimeout,
		// Never follow a redirect. A 301 to HTTPS is a successful answer and following it would
		// turn one probe into a second connection with different failure modes.
		CheckRedirect: func(*http.Request, []*http.Request) error {
			return http.ErrUseLastResponse
		},
		Transport: &http.Transport{
			// Always loopback, whatever the name. The name is a Host header, never an address.
			DialContext: func(ctx context.Context, network, _ string) (net.Conn, error) {
				dialer := &net.Dialer{Timeout: probeTimeout}
				return dialer.DialContext(ctx, network, net.JoinHostPort("127.0.0.1", strconv.Itoa(port)))
			},
			DisableKeepAlives: true,
		},
	}

	results := runConcurrently(r.Context(), req.ServerNames, func(ctx context.Context, name string) verifyResult {
		return probeSite(ctx, client, name, port)
	})

	writeJSON(w, http.StatusOK, verifyResponse{Results: results})
}

func probeSite(ctx context.Context, client *http.Client, serverName string, port int) verifyResult {
	name := strings.TrimSpace(serverName)
	if name == "" {
		return verifyResult{ServerName: serverName, Error: "empty server name"}
	}

	ctx, cancel := context.WithTimeout(ctx, probeTimeout)
	defer cancel()

	request, err := http.NewRequestWithContext(ctx, http.MethodGet, "http://"+name+"/", nil)
	if err != nil {
		return verifyResult{ServerName: name, Error: err.Error()}
	}
	request.Host = name
	request.Header.Set("User-Agent", "enginx-agent/verify")

	response, err := client.Do(request)
	if err != nil {
		return verifyResult{ServerName: name, Error: summarise(err)}
	}
	defer response.Body.Close()

	return verifyResult{ServerName: name, Responded: true, StatusCode: response.StatusCode}
}

// --- upstream reachability -------------------------------------------------

type upstreamTarget struct {
	Host string `json:"host"`
	Port int    `json:"port"`
}

type upstreamRequest struct {
	Targets []upstreamTarget `json:"targets"`
}

type upstreamResult struct {
	Host      string `json:"host"`
	Port      int    `json:"port"`
	Reachable bool   `json:"reachable"`
	Error     string `json:"error,omitempty"`
}

type upstreamResponse struct {
	Results []upstreamResult `json:"results"`
}

// handleUpstreamCheck opens a TCP connection to each upstream and closes it again.
//
// A connect is all that is attempted. Sending a request would mean guessing a protocol, and an
// upstream that refuses a malformed request is not the same as an upstream that is down.
//
// This is advisory. NGINX OSS resolves upstream names once at load time, so a name that resolves
// now may still fail later — the check narrows the common case of a typo or a stopped service,
// and cannot promise more than that.
func (s *Server) handleUpstreamCheck(w http.ResponseWriter, r *http.Request) {
	var req upstreamRequest
	if !decodeJSON(w, r, &req) {
		return
	}
	if len(req.Targets) == 0 {
		writeJSON(w, http.StatusOK, upstreamResponse{Results: []upstreamResult{}})
		return
	}
	if len(req.Targets) > maxProbeTargets {
		writeProblem(w, r, http.StatusUnprocessableEntity, "too-many-targets",
			"Too many targets",
			fmt.Sprintf("At most %d upstreams may be checked in one request", maxProbeTargets))
		return
	}

	results := runConcurrently(r.Context(), req.Targets, func(ctx context.Context, t upstreamTarget) upstreamResult {
		return probeUpstream(ctx, t)
	})

	writeJSON(w, http.StatusOK, upstreamResponse{Results: results})
}

func probeUpstream(ctx context.Context, target upstreamTarget) upstreamResult {
	result := upstreamResult{Host: target.Host, Port: target.Port}

	if strings.TrimSpace(target.Host) == "" {
		result.Error = "empty host"
		return result
	}
	if target.Port < 1 || target.Port > 65535 {
		result.Error = "port out of range"
		return result
	}

	ctx, cancel := context.WithTimeout(ctx, probeTimeout)
	defer cancel()

	dialer := &net.Dialer{}
	conn, err := dialer.DialContext(ctx, "tcp", net.JoinHostPort(target.Host, strconv.Itoa(target.Port)))
	if err != nil {
		result.Error = summarise(err)
		return result
	}
	_ = conn.Close()

	result.Reachable = true
	return result
}

// --- shared ----------------------------------------------------------------

// runConcurrently probes every input, bounded so that a site with many upstreams cannot make one
// request open an unbounded number of connections at once. Results keep the input order, because
// a caller matching them up by index is the obvious thing to do and would otherwise be wrong.
func runConcurrently[In any, Out any](ctx context.Context, inputs []In,
	probe func(context.Context, In) Out) []Out {

	results := make([]Out, len(inputs))
	semaphore := make(chan struct{}, maxProbeConcurrent)
	var wg sync.WaitGroup

	for i, input := range inputs {
		wg.Add(1)
		go func(index int, value In) {
			defer wg.Done()
			semaphore <- struct{}{}
			defer func() { <-semaphore }()
			// Each goroutine owns one slot of the slice, so no lock is needed to write it.
			results[index] = probe(ctx, value)
		}(i, input)
	}
	wg.Wait()
	return results
}

// decodeJSON reads a small request body and reports whether it parsed, having already written a
// problem document if it did not. Bounded, because a probe request is a list of names and there
// is no reason to read a megabyte to find that out.
func decodeJSON(w http.ResponseWriter, r *http.Request, target any) bool {
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 64<<10))
	if err != nil {
		writeProblem(w, r, http.StatusRequestEntityTooLarge, "body-too-large",
			"Request body too large", "A probe request is limited to 64 KiB")
		return false
	}
	if err := json.Unmarshal(body, target); err != nil {
		writeProblem(w, r, http.StatusBadRequest, "malformed-json",
			"Malformed request body", err.Error())
		return false
	}
	return true
}

// summarise keeps the useful half of a dial error and drops the rest.
//
// Go's network errors read like `dial tcp 10.0.0.5:8080: connect: connection refused`, which
// repeats the address the caller already knows and buries the one word that matters.
func summarise(err error) string {
	var netErr net.Error
	if errors.As(err, &netErr) && netErr.Timeout() {
		return "timed out"
	}
	var opErr *net.OpError
	if errors.As(err, &opErr) && opErr.Err != nil {
		message := opErr.Err.Error()
		if index := strings.LastIndex(message, ": "); index >= 0 {
			return message[index+2:]
		}
		return message
	}
	if errors.Is(err, context.DeadlineExceeded) {
		return "timed out"
	}
	return err.Error()
}
