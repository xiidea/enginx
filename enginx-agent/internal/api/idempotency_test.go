package api

import (
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// A deployment sends one Idempotency-Key with every call it makes, so the stored key has to
// distinguish the operations. Keying on the header alone let an activate request replay the
// response to the stage request that preceded it.
func TestIdempotencyKeysAreScopedToTheOperation(t *testing.T) {
	server := &Server{idempotency: newIdempotencyStore(time.Minute, 16)}

	stage := httptest.NewRequest("POST", "/agent/v1/configurations", strings.NewReader("{}"))
	stage.Header.Set("Idempotency-Key", "deployment-1")
	stageRecorder := httptest.NewRecorder()
	server.withIdempotency(stageRecorder, stage, "deployment-1", func() (int, any) {
		return 201, map[string]any{"status": "STORED"}
	})

	activate := httptest.NewRequest("POST", "/agent/v1/configurations/b1/activate", strings.NewReader("{}"))
	activate.Header.Set("Idempotency-Key", "deployment-1")
	activateRecorder := httptest.NewRecorder()
	server.withIdempotency(activateRecorder, activate, "deployment-1", func() (int, any) {
		return 200, map[string]any{"status": "ACTIVE"}
	})

	if activateRecorder.Code != 200 {
		t.Fatalf("activate returned %d; it replayed the stage response instead of running",
			activateRecorder.Code)
	}
	if !strings.Contains(activateRecorder.Body.String(), "ACTIVE") {
		t.Errorf("activate body was %q", activateRecorder.Body.String())
	}
}

// Retrying the same operation must replay, which is what makes a lost response safe.
func TestRepeatingTheSameOperationReplays(t *testing.T) {
	server := &Server{idempotency: newIdempotencyStore(time.Minute, 16)}
	calls := 0

	for i := 0; i < 2; i++ {
		request := httptest.NewRequest("POST", "/agent/v1/configurations/b1/activate", nil)
		request.Header.Set("Idempotency-Key", "deployment-1")
		recorder := httptest.NewRecorder()
		server.withIdempotency(recorder, request, "deployment-1", func() (int, any) {
			calls++
			return 200, map[string]any{"status": "ACTIVE"}
		})
		if recorder.Code != 200 {
			t.Fatalf("attempt %d returned %d", i, recorder.Code)
		}
	}

	if calls != 1 {
		t.Errorf("the work ran %d times; a retry must not re-apply it", calls)
	}
}
