package api

import (
	"log/slog"
	"net/http"
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
