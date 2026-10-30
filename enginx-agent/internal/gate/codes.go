package gate

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"fmt"
	"math/big"
	"sync"
	"time"
)

const (
	codeValidFor   = 10 * time.Minute
	maxCodeAttempt = 5
	maxPending     = 10000
	// FlowCookie ties a code to the browser that asked for it, so a code read off someone's
	// screen is useless in another browser.
	FlowCookie = "__Host-enginx_flow"
)

// pending is one sign-in in progress. Only the code's digest is kept.
type pending struct {
	siteID   string
	email    string
	digest   [32]byte
	expires  time.Time
	attempts int
	redirect string
}

type codeStore struct {
	mu    sync.Mutex
	flows map[string]*pending
}

func newCodeStore() *codeStore {
	return &codeStore{flows: map[string]*pending{}}
}

func randomCode() (string, error) {
	n, err := rand.Int(rand.Reader, big.NewInt(1000000))
	if err != nil {
		return "", err
	}
	return fmt.Sprintf("%06d", n.Int64()), nil
}

func randomID() (string, error) {
	b := make([]byte, 24)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return hex.EncodeToString(b), nil
}

// start records a sign-in and returns its flow id. With code empty, the flow can never succeed:
// what an address that is not allowed gets, so the page it sees is the same as everyone else's.
func (s *codeStore) start(siteID, email, code, redirect string, now time.Time) (string, error) {
	id, err := randomID()
	if err != nil {
		return "", err
	}
	digest := sha256.Sum256([]byte(code))
	if code == "" {
		if _, err := rand.Read(digest[:]); err != nil {
			return "", err
		}
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.sweep(now)
	// Full: the oldest gives way, rather than everyone being refused.
	if len(s.flows) >= maxPending {
		evictOldest(s.flows, func(flow *pending) time.Time { return flow.expires })
	}
	s.flows[id] = &pending{siteID: siteID, email: email, digest: digest, expires: now.Add(codeValidFor), redirect: redirect}
	return id, nil
}

// verify consumes an attempt. A flow is gone after success, expiry or its last failed attempt.
func (s *codeStore) verify(flowID, siteID, code string, now time.Time) (*pending, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	flow, ok := s.flows[flowID]
	if !ok || flow.siteID != siteID {
		return nil, false
	}
	if now.After(flow.expires) {
		delete(s.flows, flowID)
		return nil, false
	}
	flow.attempts++
	digest := sha256.Sum256([]byte(code))
	if subtle.ConstantTimeCompare(digest[:], flow.digest[:]) == 1 {
		delete(s.flows, flowID)
		return flow, true
	}
	if flow.attempts >= maxCodeAttempt {
		delete(s.flows, flowID)
	}
	// The flow, so the caller can say whose code was wrong; not a sign-in.
	return flow, false
}

// evictOldest removes the entry that would expire first.
func evictOldest[T any](entries map[string]T, expires func(T) time.Time) {
	oldest, first := "", time.Time{}
	for id, entry := range entries {
		if at := expires(entry); oldest == "" || at.Before(first) {
			oldest, first = id, at
		}
	}
	delete(entries, oldest)
}

func (s *codeStore) sweep(now time.Time) {
	for id, flow := range s.flows {
		if now.After(flow.expires) {
			delete(s.flows, id)
		}
	}
}
