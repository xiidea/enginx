package api

import (
	"log/slog"
	"net/http"
	"strings"
	"time"
)

// requireClientCN enforces the certificate pin for mTLS.
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

// requireBearerToken enforces shared token authentication for HTTP/gRPC push mode.
func requireBearerToken(expected string, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		authHeader := r.Header.Get("Authorization")
		token := ""
		if strings.HasPrefix(authHeader, "Bearer ") {
			token = strings.TrimPrefix(authHeader, "Bearer ")
		} else if strings.HasPrefix(authHeader, "bearer ") {
			token = strings.TrimPrefix(authHeader, "bearer ")
		}

		if token == "" || token != expected {
			slog.Warn("rejected push agent request: invalid or missing bearer token")
			writeProblem(w, r, http.StatusUnauthorized, "unauthorized",
				"Unauthorized", "A valid agent secret token is required.")
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
