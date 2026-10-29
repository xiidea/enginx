package gate

import (
	"sync"
	"time"
)

// limiter allows a number of events per key within a sliding window. In memory, per gate: the
// platform applies its own limits before mailing anything, so these only keep this host's
// visitors from spending them.
type limiter struct {
	mu     sync.Mutex
	limit  int
	window time.Duration
	events map[string][]time.Time
}

func newLimiter(limit int, window time.Duration) *limiter {
	return &limiter{limit: limit, window: window, events: map[string][]time.Time{}}
}

func (l *limiter) allow(key string, now time.Time) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	if len(l.events) > 10000 {
		// Bounded wholesale, as the platform's limiter is.
		l.events = map[string][]time.Time{}
	}
	cutoff := now.Add(-l.window)
	kept := l.events[key][:0]
	for _, at := range l.events[key] {
		if at.After(cutoff) {
			kept = append(kept, at)
		}
	}
	if len(kept) >= l.limit {
		l.events[key] = kept
		return false
	}
	l.events[key] = append(kept, now)
	return true
}
