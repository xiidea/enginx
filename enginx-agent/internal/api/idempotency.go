package api

import (
	"container/list"
	"sync"
	"time"
)

// idempotencyStore remembers the outcome of a mutating request so a retry returns the same
// answer instead of applying the change twice.
//
// A response lost in flight is indistinguishable, from the caller's side, from a request that
// never arrived. Without this, the management server's retry would re-run an activation that had
// already happened. Bounded by both size and age: this is a safety net for retries within a
// deployment, not a durable log.
type idempotencyStore struct {
	mu      sync.Mutex
	ttl     time.Duration
	maxSize int
	entries map[string]*list.Element
	order   *list.List
}

type idempotencyEntry struct {
	key       string
	status    int
	body      []byte
	storedAt  time.Time
	inFlight  bool
	completed chan struct{}
}

func newIdempotencyStore(ttl time.Duration, maxSize int) *idempotencyStore {
	return &idempotencyStore{
		ttl:     ttl,
		maxSize: maxSize,
		entries: make(map[string]*list.Element),
		order:   list.New(),
	}
}

// begin claims a key. It returns the stored entry when this key has been seen, and whether the
// caller now owns the work.
func (s *idempotencyStore) begin(key string) (*idempotencyEntry, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.evictExpiredLocked()

	if element, ok := s.entries[key]; ok {
		s.order.MoveToBack(element)
		return element.Value.(*idempotencyEntry), false
	}

	entry := &idempotencyEntry{key: key, storedAt: time.Now(), inFlight: true, completed: make(chan struct{})}
	s.entries[key] = s.order.PushBack(entry)
	s.evictOverflowLocked()
	return entry, true
}

func (s *idempotencyStore) complete(entry *idempotencyEntry, status int, body []byte) {
	s.mu.Lock()
	entry.status = status
	entry.body = body
	entry.inFlight = false
	entry.storedAt = time.Now()
	s.mu.Unlock()
	close(entry.completed)
}

// abandon drops a key whose work failed to produce a replayable answer, so a later retry is
// allowed to try again rather than replaying a non-answer forever.
func (s *idempotencyStore) abandon(entry *idempotencyEntry) {
	s.mu.Lock()
	if element, ok := s.entries[entry.key]; ok {
		s.order.Remove(element)
		delete(s.entries, entry.key)
	}
	s.mu.Unlock()
	close(entry.completed)
}

func (s *idempotencyStore) evictExpiredLocked() {
	cutoff := time.Now().Add(-s.ttl)
	for element := s.order.Front(); element != nil; {
		entry := element.Value.(*idempotencyEntry)
		next := element.Next()
		if !entry.inFlight && entry.storedAt.Before(cutoff) {
			s.order.Remove(element)
			delete(s.entries, entry.key)
		}
		element = next
	}
}

func (s *idempotencyStore) evictOverflowLocked() {
	for s.order.Len() > s.maxSize {
		element := s.order.Front()
		entry := element.Value.(*idempotencyEntry)
		if entry.inFlight {
			break
		}
		s.order.Remove(element)
		delete(s.entries, entry.key)
	}
}
