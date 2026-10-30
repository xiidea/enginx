package gate

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"sync"
	"time"
)

// What the gate tells the platform happened at its door, for the audit trail. The vocabulary is
// part of the protocol: the platform maps each kind to an audit action.
const (
	EventSignedIn     = "signed-in"
	EventSignInFailed = "sign-in-failed"
	EventAccessDenied = "access-denied"
	EventSignedOut    = "signed-out"
)

// How a visitor tried to get in.
const (
	MethodPassword = "password"
	MethodHeader   = "header"
	MethodCode     = "code"
	MethodOIDC     = "oidc"
)

const (
	// maxPendingEvents bounds what one site holds while the platform cannot be reached. Past it,
	// events are counted rather than kept, and the count is reported with the next batch.
	maxPendingEvents = 1000
	// maxEventsPerSection keeps one request small; the rest go with the next flush.
	maxEventsPerSection = 200
	// maxEventText bounds what a visitor typed, so a username field cannot fill the audit trail.
	maxEventText = 128
)

// Event is one thing that happened at the gate, as sent to the platform.
type Event struct {
	At     int64  `json:"at"` // Unix milliseconds
	Kind   string `json:"kind"`
	Method string `json:"method"`
	User   string `json:"user,omitempty"`
	Email  string `json:"email,omitempty"`
	IP     string `json:"ip,omitempty"`
	Reason string `json:"reason,omitempty"`
}

type pendingEvents struct {
	site    *Site
	events  []Event
	dropped int
}

// eventLog holds events per site until they are shipped.
type eventLog struct {
	mu     sync.Mutex
	bySite map[string]*pendingEvents
}

func newEventLog() *eventLog {
	return &eventLog{bySite: map[string]*pendingEvents{}}
}

func (l *eventLog) add(site *Site, event Event) {
	l.mu.Lock()
	defer l.mu.Unlock()
	pending := l.bySite[site.ID]
	if pending == nil {
		pending = &pendingEvents{}
		l.bySite[site.ID] = pending
	}
	// The latest copy of the site, whose key the platform will hold once it is deployed.
	pending.site = site
	if len(pending.events) >= maxPendingEvents {
		pending.dropped++
		return
	}
	pending.events = append(pending.events, event)
}

// take removes up to maxEventsPerSection events from every site that has any.
func (l *eventLog) take() []*pendingEvents {
	l.mu.Lock()
	defer l.mu.Unlock()
	var out []*pendingEvents
	for id, pending := range l.bySite {
		n := min(len(pending.events), maxEventsPerSection)
		taken := &pendingEvents{site: pending.site, events: pending.events[:n:n], dropped: pending.dropped}
		pending.events = pending.events[n:]
		pending.dropped = 0
		if len(pending.events) == 0 {
			delete(l.bySite, id)
		}
		out = append(out, taken)
	}
	return out
}

// putBack returns events that could not be shipped, ahead of any recorded since.
func (l *eventLog) putBack(batch *pendingEvents) {
	l.mu.Lock()
	defer l.mu.Unlock()
	pending := l.bySite[batch.site.ID]
	if pending == nil {
		pending = &pendingEvents{site: batch.site}
		l.bySite[batch.site.ID] = pending
	}
	merged := append(append([]Event{}, batch.events...), pending.events...)
	if over := len(merged) - maxPendingEvents; over > 0 {
		// The newest are dropped, as add would have dropped them.
		merged = merged[:maxPendingEvents]
		pending.dropped += over
	}
	pending.events = merged
	pending.dropped += batch.dropped
}

// record notes an event for the site's audit trail. A site without a relay address, which the
// platform renders only when it has one configured, keeps its events in the gate's log alone.
func (s *Server) record(site *Site, r *http.Request, event Event) {
	if site.RelayURL == "" {
		return
	}
	event.At = s.now().UnixMilli()
	event.IP = clip(r.Header.Get("X-Real-IP"))
	event.User = clip(event.User)
	event.Email = clip(event.Email)
	s.events.add(site, event)
}

func clip(value string) string {
	if len(value) > maxEventText {
		return value[:maxEventText]
	}
	return value
}

// The events request: one section per site, each signed with that site's relay key over its
// payload exactly as sent, so the platform verifies the bytes it parses.
type eventsRequest struct {
	Sites []eventSection `json:"sites"`
}

type eventSection struct {
	SiteID    string `json:"siteId"`
	Payload   string `json:"payload"`
	Signature string `json:"signature"`
}

type eventPayload struct {
	SiteID  string  `json:"siteId"`
	BatchID string  `json:"batchId"`
	SentAt  int64   `json:"sentAt"` // Unix seconds
	Dropped int     `json:"dropped"`
	Events  []Event `json:"events"`
}

// eventsSignature mirrors GateKeyDerivation.eventsSignature on the platform. The "events" prefix
// keeps it apart from a code request's signature, which starts with the site id.
func eventsSignature(site *Site, payload string) string {
	mac := hmac.New(sha256.New, site.relayKey)
	mac.Write([]byte("events\n" + payload))
	return hex.EncodeToString(mac.Sum(nil))
}

// eventsURL is the relay address's sibling: …/gate/otp-mail becomes …/gate/events.
func eventsURL(relayURL string) (string, error) {
	base, err := url.Parse(relayURL)
	if err != nil {
		return "", err
	}
	return base.ResolveReference(&url.URL{Path: "events"}).String(), nil
}

// ShipEvents sends what the gate recorded to the platform every interval, and once more when ctx
// ends. Events that could not be sent are kept for the next attempt, up to maxPendingEvents a site.
func (s *Server) ShipEvents(ctx context.Context, client *http.Client, every time.Duration) {
	ticker := time.NewTicker(every)
	defer ticker.Stop()
	failing := false
	for {
		select {
		case <-ctx.Done():
			flush, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			s.shipOnce(flush, client)
			cancel()
			return
		case <-ticker.C:
		}
		err := s.shipOnce(ctx, client)
		// Said once when it starts failing and once when it recovers, not on every attempt.
		if err != nil && !failing {
			slog.Warn("could not send sign-in events to the platform; keeping them to retry", "error", err)
		} else if err == nil && failing {
			slog.Info("sending sign-in events to the platform again")
		}
		failing = err != nil
	}
}

func (s *Server) shipOnce(ctx context.Context, client *http.Client) error {
	batches := s.events.take()
	if len(batches) == 0 {
		return nil
	}
	// Grouped by address: every site normally shares one, but a site rendered before the address
	// changed may still carry the old one.
	byURL := map[string][]*pendingEvents{}
	for _, batch := range batches {
		target, err := eventsURL(batch.site.RelayURL)
		if err != nil {
			continue
		}
		byURL[target] = append(byURL[target], batch)
	}
	var failed error
	for target, group := range byURL {
		if err := s.post(ctx, client, target, group); err != nil {
			failed = err
			for _, batch := range group {
				s.events.putBack(batch)
			}
		}
	}
	return failed
}

func (s *Server) post(ctx context.Context, client *http.Client, target string, group []*pendingEvents) error {
	var request eventsRequest
	sentAt := s.now().Unix()
	for _, batch := range group {
		batchID, err := randomID()
		if err != nil {
			return err
		}
		payload, _ := json.Marshal(eventPayload{SiteID: batch.site.ID, BatchID: batchID, SentAt: sentAt,
			Dropped: batch.dropped, Events: batch.events})
		request.Sites = append(request.Sites, eventSection{SiteID: batch.site.ID, Payload: string(payload),
			Signature: eventsSignature(batch.site, string(payload))})
	}
	body, _ := json.Marshal(request)

	ctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, target, bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	if client == nil {
		client = http.DefaultClient
	}
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusNoContent || resp.StatusCode == http.StatusOK {
		return nil
	}
	detail, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
	if resp.StatusCode == http.StatusNotFound {
		// A platform older than the gate: nothing to retry until it is upgraded.
		s.oldPlatform.Do(func() {
			slog.Warn("the platform does not accept sign-in events; upgrade it to record them", "url", target)
		})
		return nil
	}
	return fmt.Errorf("the platform answered %d: %s", resp.StatusCode, bytes.TrimSpace(detail))
}
