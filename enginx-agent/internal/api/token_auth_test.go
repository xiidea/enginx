package api

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestRequireBearerToken(t *testing.T) {
	const token = "0123456789abcdef0123456789abcdef"
	handler := requireBearerToken(token, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))

	cases := []struct {
		name   string
		header string
		want   int
	}{
		{"valid token", "Bearer " + token, http.StatusOK},
		{"scheme is case-insensitive", "bearer " + token, http.StatusOK},
		{"missing header", "", http.StatusUnauthorized},
		{"wrong token of the same length", "Bearer 0123456789abcdef0123456789abcdeX", http.StatusUnauthorized},
		{"prefix of the token", "Bearer " + token[:16], http.StatusUnauthorized},
		{"other scheme", "Basic " + token, http.StatusUnauthorized},
		{"empty token", "Bearer ", http.StatusUnauthorized},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			req := httptest.NewRequest(http.MethodGet, "/agent/v1/status", nil)
			if c.header != "" {
				req.Header.Set("Authorization", c.header)
			}
			rec := httptest.NewRecorder()
			handler.ServeHTTP(rec, req)
			if rec.Code != c.want {
				t.Fatalf("expected %d, got %d", c.want, rec.Code)
			}
		})
	}
}
