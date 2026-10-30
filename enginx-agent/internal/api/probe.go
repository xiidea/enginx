package api

import (
	"context"
	"crypto/rand"
	"crypto/tls"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
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

// SiteMarkerPath is where every rendered site answers with its own identity. Requested by the
// probe instead of "/" for two reasons: a response there proves the platform's server block for
// that name answered, not merely that something on port 80 did; and it never reaches a backend.
const SiteMarkerPath = "/.well-known/enginx/site"

// maxMarker bounds what is read from the marker response.
const maxMarker = 256

// maxReportedMarker bounds what is reported back as the marker seen. A marker is about 60.
const maxReportedMarker = 128

type verifyTarget struct {
	ServerName string `json:"serverName"`
	// Marker is what the platform's server block for this name returns at SiteMarkerPath.
	Marker string `json:"marker"`
	// Protection is BASIC or GATE when the site must refuse a visitor who has not signed in.
	Protection string `json:"protection,omitempty"`
}

type verifyRequest struct {
	Sites []verifyTarget `json:"sites"`
	// Names without markers, as earlier servers sent them. Such a name can be found to respond
	// but never to be identified.
	ServerNames []string `json:"serverNames"`
	// Port to dial on loopback. Defaults to 80.
	Port int `json:"port"`
}

type verifyResult struct {
	ServerName string `json:"serverName"`
	Responded  bool   `json:"responded"`
	StatusCode int    `json:"statusCode,omitempty"`
	// Identified is true only when the answer was exactly this name's expected marker: the
	// configuration just deployed answered, not a default page, another server block sharing the
	// port, or an earlier render of the same site still live after a reload that did not apply.
	Identified bool `json:"identified"`
	// Marker is what came back at SiteMarkerPath, when it looked like a marker — one short line.
	// Lets the platform say *which* config answered, not only that the expected one did not.
	Marker string `json:"marker,omitempty"`
	Error  string `json:"error,omitempty"`
	// ProtectionEnforced is set for a protected site: whether a request without credentials or a
	// session was refused the way its protection refuses.
	ProtectionEnforced *bool `json:"protectionEnforced,omitempty"`
	// ProtectionDetail says what such a request got instead, when it was not refused.
	ProtectionDetail string `json:"protectionDetail,omitempty"`
}

type verifyResponse struct {
	Results []verifyResult `json:"results"`
}

// handleVerify checks that each server name is answered by the configuration just deployed.
//
// Responding is not enough. On a host that already ran NGINX, a distribution's default page
// answers any name with 200, so "something responded" was reported as success for a site that was
// not being served at all. Each rendered site answers SiteMarkerPath with its own marker, and only
// that answer counts. The path is served by NGINX itself, so a backend that is down or returns
// errors does not make a correctly deployed site look broken.
func (s *Server) handleVerify(w http.ResponseWriter, r *http.Request) {
	var req verifyRequest
	if !decodeJSON(w, r, &req) {
		return
	}
	targets := req.Sites
	for _, name := range req.ServerNames {
		targets = append(targets, verifyTarget{ServerName: name})
	}
	if len(targets) == 0 {
		writeJSON(w, http.StatusOK, verifyResponse{Results: []verifyResult{}})
		return
	}
	if len(targets) > maxProbeTargets {
		writeProblem(w, r, http.StatusUnprocessableEntity, "too-many-targets",
			"Too many server names",
			fmt.Sprintf("At most %d names may be verified in one request", maxProbeTargets))
		return
	}
	writeJSON(w, http.StatusOK, verifyResponse{Results: s.verify(r.Context(), targets, req.Port)})
}

// VerifyTarget and VerifyResult are the probe's request and answer for one site, for the pull
// runner, which is sent the same targets in a job and reports the same answers with its result.
type (
	VerifyTarget = verifyTarget
	VerifyResult = verifyResult
)

// VerifySites probes as handleVerify does, for a host that collected the request as a job.
func (s *Server) VerifySites(ctx context.Context, targets []VerifyTarget) ([]VerifyResult, error) {
	if len(targets) > maxProbeTargets {
		return nil, fmt.Errorf("at most %d sites may be verified at once, and the bundle has %d", maxProbeTargets, len(targets))
	}
	return s.verify(ctx, targets, 0), nil
}

func (s *Server) verify(ctx context.Context, targets []verifyTarget, port int) []verifyResult {
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

	return runConcurrently(ctx, targets, func(ctx context.Context, target verifyTarget) verifyResult {
		result := probeSite(ctx, client, target, port)
		if target.Protection != "" && result.Identified {
			enforced, detail := probeProtection(ctx, target, s.protectedPort)
			result.ProtectionEnforced = &enforced
			result.ProtectionDetail = detail
		}
		return result
	})
}

// markerSettle is how long a probe keeps asking before accepting that the marker is absent.
//
// A reload is asynchronous: NGINX starts new workers on the new configuration while the old ones
// finish what they hold, and for that moment a request can still reach an old worker. The probe
// runs straight after the reload, so a single attempt would sometimes report a correctly deployed
// site as answered by something else. Retrying briefly waits out the hand-over; a site that is
// genuinely not served fails just as surely, a few seconds later.
const markerSettle = 5 * time.Second

func probeSite(ctx context.Context, client *http.Client, target verifyTarget, port int) verifyResult {
	deadline := time.Now().Add(markerSettle)
	for {
		result := probeOnce(ctx, client, target, port)
		if result.Identified || target.Marker == "" || !time.Now().Before(deadline) || ctx.Err() != nil {
			return result
		}
		select {
		case <-ctx.Done():
			return result
		case <-time.After(250 * time.Millisecond):
		}
	}
}

func probeOnce(ctx context.Context, client *http.Client, target verifyTarget, port int) verifyResult {
	name := strings.TrimSpace(target.ServerName)
	if name == "" {
		return verifyResult{ServerName: target.ServerName, Error: "empty server name"}
	}

	ctx, cancel := context.WithTimeout(ctx, probeTimeout)
	defer cancel()

	request, err := http.NewRequestWithContext(ctx, http.MethodGet, "http://"+name+SiteMarkerPath, nil)
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

	result := verifyResult{ServerName: name, Responded: true, StatusCode: response.StatusCode}
	if response.StatusCode == http.StatusOK {
		body, _ := io.ReadAll(io.LimitReader(response.Body, maxMarker))
		seen := strings.TrimSpace(string(body))
		// A default page is not a marker; reporting its HTML would only fill the deployment
		// record with someone else's markup.
		if seen != "" && len(seen) <= maxReportedMarker && !strings.ContainsAny(seen, "\n<") {
			result.Marker = seen
		}
		result.Identified = target.Marker != "" && seen == target.Marker
	}
	return result
}

// --- protection -------------------------------------------------------------

// protectionProbePort is where a protected site is served: protection requires HTTPS, and the
// port-80 server of such a site only redirects.
const protectionProbePort = 443

// probeProtection asks as a visitor who has not signed in, over HTTPS on loopback, for the root
// and for a path nobody configured. The answer must be the refusal the protection gives: 401 for
// basic authentication, a redirect to the gate's sign-in page for a gated site.
//
// From the outside on purpose. `nginx -t` accepts a `return` in a host's custom file that answers
// before credentials are checked, another server block claiming the same name, and an htpasswd
// file the workers cannot open; each of them only shows when someone asks. Certificates are not
// checked: the question is who is let in, and the certificate is VERIFY's concern elsewhere.
func probeProtection(ctx context.Context, target verifyTarget, port int) (bool, string) {
	if port == 0 {
		port = protectionProbePort
	}
	name := strings.TrimSpace(target.ServerName)
	client := &http.Client{
		Timeout: probeTimeout,
		CheckRedirect: func(*http.Request, []*http.Request) error {
			return http.ErrUseLastResponse
		},
		Transport: &http.Transport{
			DialContext: func(ctx context.Context, network, _ string) (net.Conn, error) {
				dialer := &net.Dialer{Timeout: probeTimeout}
				return dialer.DialContext(ctx, network, net.JoinHostPort("127.0.0.1", strconv.Itoa(port)))
			},
			TLSClientConfig:   &tls.Config{ServerName: name, InsecureSkipVerify: true}, //nolint:gosec // see above
			DisableKeepAlives: true,
		},
	}

	random := make([]byte, 8)
	_, _ = rand.Read(random)
	paths := []string{"/", "/.enginx-verify-" + hex.EncodeToString(random)}

	// Retried, like the marker: straight after a reload an old worker can still take the request,
	// and one that predates the site answers it from the HTTPS catch-all with a 404 — which reads
	// as the site letting a visitor through when it is only not live on that worker yet. A
	// protection that is genuinely not enforced fails just as surely, a few seconds later.
	deadline := time.Now().Add(protectionSettle)
	for {
		enforced, detail := true, ""
		for _, path := range paths {
			if refused, why := askUnauthenticated(ctx, client, name, path, target.Protection); !refused {
				enforced, detail = false, why
				break
			}
		}
		if enforced || !time.Now().Before(deadline) || ctx.Err() != nil {
			return enforced, detail
		}
		select {
		case <-ctx.Done():
			return enforced, detail
		case <-time.After(250 * time.Millisecond):
		}
	}
}

// protectionSettle is how long the protection check waits out a reload's hand-over. A variable so
// tests need not wait for it.
var protectionSettle = markerSettle

func askUnauthenticated(ctx context.Context, client *http.Client, name, path, protection string) (bool, string) {
	ctx, cancel := context.WithTimeout(ctx, probeTimeout)
	defer cancel()
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, "https://"+name+path, nil)
	if err != nil {
		return false, err.Error()
	}
	request.Host = name
	request.Header.Set("User-Agent", "enginx-agent/verify")
	response, err := client.Do(request)
	if err != nil {
		return false, path + " could not be requested over HTTPS: " + summarise(err)
	}
	defer response.Body.Close()

	switch protection {
	case "BASIC":
		if response.StatusCode == http.StatusUnauthorized && response.Header.Get("WWW-Authenticate") != "" {
			return true, ""
		}
	case "GATE":
		if response.StatusCode == http.StatusFound && redirectsToSignIn(response.Header.Get("Location"), name) {
			return true, ""
		}
	default:
		return false, "unknown protection " + protection
	}
	return false, fmt.Sprintf("%s answered %d without signing in", path, response.StatusCode)
}

// redirectsToSignIn accepts the gate's sign-in page as a relative redirect, which is what the
// platform renders, or an absolute one back to the same site, which is what a host-level
// absolute_redirect would turn it into. A redirect anywhere else is not the gate refusing.
func redirectsToSignIn(location, name string) bool {
	target, err := url.Parse(location)
	if err != nil || !strings.HasPrefix(target.Path, "/.enginx/auth/login") {
		return false
	}
	return target.Host == "" || strings.EqualFold(target.Hostname(), name)
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
