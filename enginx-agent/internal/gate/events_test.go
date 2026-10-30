package gate

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
)

// The same vector is asserted by GateKeyDerivationTest on the platform.
func TestEventsSignatureMatchesThePlatform(t *testing.T) {
	got := eventsSignature(testSite(t), `{"siteId":"aaaaaaaa-0000-0000-0000-000000000001"}`)
	want := "6bf9ed01167328bfa0145c307a2bfb9f7a959a54e0b4c197ed8edc1b0d90ffa7"
	if got != want {
		t.Fatalf("signature %s, want %s", got, want)
	}
}

func TestEventsGoToTheRelaysSibling(t *testing.T) {
	got, err := eventsURL("https://enginx.example.com/api/v1/agents/gate/otp-mail")
	if err != nil || got != "https://enginx.example.com/api/v1/agents/gate/events" {
		t.Fatalf("eventsURL = %q, %v", got, err)
	}
}

type platform struct {
	mu       sync.Mutex
	requests []eventsRequest
	paths    []string
	status   int
}

func (p *platform) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	p.mu.Lock()
	defer p.mu.Unlock()
	body, _ := io.ReadAll(r.Body)
	var request eventsRequest
	_ = json.Unmarshal(body, &request)
	p.requests = append(p.requests, request)
	p.paths = append(p.paths, r.URL.Path)
	w.WriteHeader(p.status)
}

// basicServerAt points the basic site's relay at a test platform.
func basicServerAt(t *testing.T, target string) (*Server, *Site) {
	t.Helper()
	server, site := basicServer(t)
	site.RelayURL = target + "/api/v1/agents/gate/otp-mail"
	return server, site
}

func TestSignInsAreShippedSigned(t *testing.T) {
	p := &platform{status: http.StatusNoContent}
	ts := httptest.NewServer(p)
	defer ts.Close()
	server, site := basicServerAt(t, ts.URL)

	passwordLogin(server, "alice", "wrong")
	passwordLogin(server, "alice", "secret")
	if err := server.shipOnce(context.Background(), ts.Client()); err != nil {
		t.Fatal(err)
	}

	if len(p.requests) != 1 || p.paths[0] != "/api/v1/agents/gate/events" || len(p.requests[0].Sites) != 1 {
		t.Fatalf("requests: %+v at %v", p.requests, p.paths)
	}
	section := p.requests[0].Sites[0]
	if section.SiteID != testSiteID || section.Signature != eventsSignature(site, section.Payload) {
		t.Fatalf("section not signed over its payload: %+v", section)
	}
	var payload eventPayload
	if err := json.Unmarshal([]byte(section.Payload), &payload); err != nil {
		t.Fatal(err)
	}
	if len(payload.Events) != 2 || payload.Events[0].Kind != EventSignInFailed || payload.Events[0].Reason != "wrong-password" ||
		payload.Events[1].Kind != EventSignedIn || payload.Events[1].User != "alice" || payload.Events[1].Method != MethodPassword {
		t.Fatalf("events: %+v", payload.Events)
	}

	// Shipped once only.
	if err := server.shipOnce(context.Background(), ts.Client()); err != nil || len(p.requests) != 1 {
		t.Fatalf("a second flush sent again: %d requests, %v", len(p.requests), err)
	}
}

func TestEventsAreKeptWhileThePlatformIsUnreachable(t *testing.T) {
	p := &platform{status: http.StatusServiceUnavailable}
	ts := httptest.NewServer(p)
	defer ts.Close()
	server, _ := basicServerAt(t, ts.URL)

	passwordLogin(server, "alice", "secret")
	if err := server.shipOnce(context.Background(), ts.Client()); err == nil {
		t.Fatal("a 503 was taken as delivered")
	}
	p.status = http.StatusNoContent
	if err := server.shipOnce(context.Background(), ts.Client()); err != nil {
		t.Fatal(err)
	}
	var payload eventPayload
	_ = json.Unmarshal([]byte(p.requests[1].Sites[0].Payload), &payload)
	if len(payload.Events) != 1 || payload.Events[0].Kind != EventSignedIn {
		t.Fatalf("the retried events: %+v", payload.Events)
	}
}

func TestAFullLogCountsWhatItDrops(t *testing.T) {
	_, site := basicServer(t)
	log := newEventLog()
	for range maxPendingEvents + 5 {
		log.add(site, Event{Kind: EventSignInFailed})
	}
	var events, dropped int
	for _, batch := range log.take() {
		events += len(batch.events)
		dropped += batch.dropped
	}
	if events != maxEventsPerSection || dropped != 5 {
		t.Fatalf("first take: %d events, %d dropped", events, dropped)
	}
}

func TestASiteWithoutARelayRecordsNothing(t *testing.T) {
	server, site := basicServer(t)
	site.RelayURL = ""
	passwordLogin(server, "alice", "secret")
	if batches := server.events.take(); len(batches) != 0 {
		t.Fatalf("recorded %d batches without anywhere to send them", len(batches))
	}
}

func TestAWrongCodeNamesTheAddress(t *testing.T) {
	server, _ := newTestServer(t)
	res := httptest.NewRecorder()
	server.Handler().ServeHTTP(res, request("POST", "/.enginx/auth/login", map[string][]string{"email": {"bob@example.com"}}))
	flow := cookie(res.Result(), FlowCookie)
	verify := httptest.NewRecorder()
	server.Handler().ServeHTTP(verify, request("POST", "/.enginx/auth/verify", map[string][]string{"code": {"000000"}}, flow))
	server.Wait()

	batches := server.events.take()
	if len(batches) != 1 {
		t.Fatalf("batches: %d", len(batches))
	}
	got := batches[0].events
	last := got[len(got)-1]
	if last.Kind != EventSignInFailed || last.Email != "bob@example.com" || !strings.Contains(last.Reason, "code") {
		t.Fatalf("events: %+v", got)
	}
}
