package api

import (
	"crypto/subtle"
	"log/slog"
	"net/http"
	"strings"
	"time"
)

// requireClientCN enforces the certificate pin.
//
// TLS has already verified that the peer holds a certificate signed by our CA. That proves the
// caller is *someone* the CA trusts, not that it is the management server, so the common name
// is checked as well.
func requireClientCN(expected string, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.TLS == nil || len(r.TLS.PeerCertificates) == 0 {
			writeProblem(w, r, http.StatusUnauthorized, "client-certificate-required",
				"Client certificate required", "This API requires mutual TLS.")
			return
		}
		if cn := r.TLS.PeerCertificates[0].Subject.CommonName; cn != expected {
			slog.Warn("rejected client certificate", "presentedCN", cn, "expectedCN", expected)
			writeProblem(w, r, http.StatusForbidden, "client-certificate-rejected",
				"Client certificate rejected", "The presented certificate is not authorised for this agent.")
			return
		}
		next.ServeHTTP(w, r)
	})
}

// requireBearerToken enforces the pre-shared token, for an agent the management server cannot
// reach with a client certificate.
//
// Compared in constant time: this token is the only authentication on the listener, and a
// comparison that returns at the first differing byte lets a caller recover it by timing.
func requireBearerToken(expected string, next http.Handler) http.Handler {
	want := []byte(expected)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		scheme, token, found := strings.Cut(r.Header.Get("Authorization"), " ")
		if !found || !strings.EqualFold(scheme, "Bearer") || token == "" ||
			subtle.ConstantTimeCompare([]byte(token), want) != 1 {
			slog.Warn("rejected request without a valid agent token", "remote", r.RemoteAddr)
			writeProblem(w, r, http.StatusUnauthorized, "agent-token-required",
				"Agent token required", "A valid agent token is required.")
			return
		}
		next.ServeHTTP(w, r)
	})
}

type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (r *statusRecorder) WriteHeader(code int) {
	r.status = code
	r.ResponseWriter.WriteHeader(code)
}

func logRequests(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		started := time.Now()
		recorder := &statusRecorder{ResponseWriter: w, status: http.StatusOK}
		next.ServeHTTP(recorder, r)
		slog.Info("request",
			"method", r.Method,
			"path", r.URL.Path,
			"status", recorder.status,
			"durationMs", time.Since(started).Milliseconds())
	})
}
