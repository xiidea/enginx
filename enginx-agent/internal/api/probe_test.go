package api

import (
	"context"
	"encoding/json"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// listenLoopback starts a server on 127.0.0.1 answering with the given status, and returns its
// port. The verify probe always dials loopback, so a real listener there is the only way to
// exercise it honestly.
func listenLoopback(t *testing.T, status int, record *string) int {
	t.Helper()

	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	server := &http.Server{
		Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if record != nil {
				*record = r.Host
			}
			w.WriteHeader(status)
		}),
		ReadHeaderTimeout: time.Second,
	}
	go func() { _ = server.Serve(listener) }()
	t.Cleanup(func() { _ = server.Close() })

	return listener.Addr().(*net.TCPAddr).Port
}

func TestVerifySendsTheServerNameAsHostAndReportsTheStatus(t *testing.T) {
	var seenHost string
	port := listenLoopback(t, http.StatusNoContent, &seenHost)

	body := verifyRequest{ServerNames: []string{"demo.example.com"}, Port: port}
	response := postJSON(t, (&Server{}).handleVerify, "/agent/v1/verify", body)

	var decoded verifyResponse
	decode(t, response, &decoded)

	if len(decoded.Results) != 1 {
		t.Fatalf("expected 1 result, got %d", len(decoded.Results))
	}
	result := decoded.Results[0]
	if !result.Responded {
		t.Errorf("expected the site to respond, got error %q", result.Error)
	}
	if result.StatusCode != http.StatusNoContent {
		t.Errorf("status = %d, want %d", result.StatusCode, http.StatusNoContent)
	}
	// The name must reach NGINX as a Host header. Without it every probe would land on the
	// default server and the check would pass for a site that is not configured at all.
	if seenHost != "demo.example.com" {
		t.Errorf("Host header = %q, want demo.example.com", seenHost)
	}
}

// A redirect is a successful answer. A site with forceHttps replies 301 on port 80, and treating
// that as a failure would report every correctly configured site as broken.
func TestVerifyTreatsARedirectAsResponded(t *testing.T) {
	port := listenLoopback(t, http.StatusMovedPermanently, nil)

	body := verifyRequest{ServerNames: []string{"secure.example.com"}, Port: port}
	var decoded verifyResponse
	decode(t, postJSON(t, (&Server{}).handleVerify, "/agent/v1/verify", body), &decoded)

	if !decoded.Results[0].Responded {
		t.Fatal("a 301 must count as responded")
	}
	if decoded.Results[0].StatusCode != http.StatusMovedPermanently {
		t.Errorf("status = %d, want 301", decoded.Results[0].StatusCode)
	}
}

func TestVerifyReportsNothingListening(t *testing.T) {
	// Bind and immediately release, so the port is almost certainly closed.
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := listener.Addr().(*net.TCPAddr).Port
	_ = listener.Close()

	body := verifyRequest{ServerNames: []string{"gone.example.com"}, Port: port}
	var decoded verifyResponse
	decode(t, postJSON(t, (&Server{}).handleVerify, "/agent/v1/verify", body), &decoded)

	if decoded.Results[0].Responded {
		t.Fatal("expected no response from a closed port")
	}
	if decoded.Results[0].Error == "" {
		t.Error("expected an error describing the failure")
	}
}

// The probe must never become a way to reach an arbitrary address. Whatever name is supplied, the
// connection goes to loopback and the name is only ever a header.
func TestVerifyAlwaysDialsLoopback(t *testing.T) {
	var seenHost string
	port := listenLoopback(t, http.StatusOK, &seenHost)

	body := verifyRequest{ServerNames: []string{"169.254.169.254"}, Port: port}
	var decoded verifyResponse
	decode(t, postJSON(t, (&Server{}).handleVerify, "/agent/v1/verify", body), &decoded)

	if !decoded.Results[0].Responded {
		t.Fatal("the loopback listener should have answered")
	}
	if seenHost != "169.254.169.254" {
		t.Errorf("Host = %q; the name must travel as a header, not as an address", seenHost)
	}
}

func TestUpstreamCheckDistinguishesOpenFromClosed(t *testing.T) {
	open := listenLoopback(t, http.StatusOK, nil)

	closedListener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	closed := closedListener.Addr().(*net.TCPAddr).Port
	_ = closedListener.Close()

	body := upstreamRequest{Targets: []upstreamTarget{
		{Host: "127.0.0.1", Port: open},
		{Host: "127.0.0.1", Port: closed},
	}}
	var decoded upstreamResponse
	decode(t, postJSON(t, (&Server{}).handleUpstreamCheck, "/agent/v1/upstream-checks", body), &decoded)

	if len(decoded.Results) != 2 {
		t.Fatalf("expected 2 results, got %d", len(decoded.Results))
	}
	// Order must match the request: the caller pairs results with targets by index.
	if !decoded.Results[0].Reachable {
		t.Errorf("port %d should be reachable: %s", open, decoded.Results[0].Error)
	}
	if decoded.Results[1].Reachable {
		t.Errorf("port %d should not be reachable", closed)
	}
}

func TestUpstreamCheckRejectsNonsenseTargets(t *testing.T) {
	body := upstreamRequest{Targets: []upstreamTarget{
		{Host: "", Port: 80},
		{Host: "example.com", Port: 0},
		{Host: "example.com", Port: 70000},
	}}
	var decoded upstreamResponse
	decode(t, postJSON(t, (&Server{}).handleUpstreamCheck, "/agent/v1/upstream-checks", body), &decoded)

	for i, result := range decoded.Results {
		if result.Reachable {
			t.Errorf("result %d should not be reachable", i)
		}
		if result.Error == "" {
			t.Errorf("result %d should carry an error", i)
		}
	}
}

func TestProbeResultsKeepRequestOrder(t *testing.T) {
	// More targets than the concurrency limit, so the ordering is not incidental.
	targets := make([]upstreamTarget, 0, 20)
	ports := make([]int, 0, 20)
	for i := 0; i < 20; i++ {
		port := listenLoopback(t, http.StatusOK, nil)
		ports = append(ports, port)
		targets = append(targets, upstreamTarget{Host: "127.0.0.1", Port: port})
	}

	var decoded upstreamResponse
	decode(t, postJSON(t, (&Server{}).handleUpstreamCheck, "/agent/v1/upstream-checks",
		upstreamRequest{Targets: targets}), &decoded)

	for i, result := range decoded.Results {
		if result.Port != ports[i] {
			t.Fatalf("result %d has port %d, want %d — results must keep request order",
				i, result.Port, ports[i])
		}
	}
}

func TestSummariseKeepsTheUsefulHalfOfADialError(t *testing.T) {
	_, err := (&net.Dialer{Timeout: time.Millisecond}).DialContext(
		context.Background(), "tcp", "127.0.0.1:1")
	if err == nil {
		t.Skip("something is listening on port 1")
	}
	message := summarise(err)
	if strings.Contains(message, "127.0.0.1:1") {
		t.Errorf("summarise kept the address the caller already knows: %q", message)
	}
	if message == "" {
		t.Error("summarise returned nothing")
	}
}

// --- helpers ---------------------------------------------------------------

func postJSON(t *testing.T, handler http.HandlerFunc, path string, body any) *httptest.ResponseRecorder {
	t.Helper()

	encoded, err := json.Marshal(body)
	if err != nil {
		t.Fatal(err)
	}
	request := httptest.NewRequest(http.MethodPost, path, strings.NewReader(string(encoded)))
	recorder := httptest.NewRecorder()
	handler(recorder, request)

	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d (%s), want 200", recorder.Code, recorder.Body.String())
	}
	return recorder
}

func decode(t *testing.T, recorder *httptest.ResponseRecorder, target any) {
	t.Helper()
	if err := json.Unmarshal(recorder.Body.Bytes(), target); err != nil {
		t.Fatalf("decoding %s: %v", recorder.Body.String(), err)
	}
}
