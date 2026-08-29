package api

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"time"

	"github.com/xiidea/enginx/enginx-agent/internal/bundle"
	"github.com/xiidea/enginx/enginx-agent/internal/defaulttls"
)

// maxBundleBytes bounds an upload. A configuration tree for hundreds of sites is well under a
// megabyte, so anything approaching this is a mistake or an attack.
const maxBundleBytes = 32 << 20

type activateRequest struct {
	Reload            *bool `json:"reload"`
	VerifyAfterReload bool  `json:"verifyAfterReload"`
}

// handleStageConfiguration accepts a bundle and writes it to disk without changing what is served.
func (s *Server) handleStageConfiguration(w http.ResponseWriter, r *http.Request) {
	key := r.Header.Get("Idempotency-Key")

	s.withIdempotency(w, r, key, func() (int, any) {
		body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, maxBundleBytes))
		if err != nil {
			return http.StatusRequestEntityTooLarge, problemBody(r, "bundle-too-large",
				"Bundle too large", "The configuration bundle exceeds the accepted size.")
		}

		var payload bundle.Bundle
		if err := json.Unmarshal(body, &payload); err != nil {
			return http.StatusBadRequest, problemBody(r, "malformed-bundle",
				"Malformed bundle", "The request body is not a valid bundle document.")
		}

		if err := s.bundles.Stage(payload); err != nil {
			switch {
			case errors.Is(err, bundle.ErrBundleExists):
				return http.StatusConflict, problemBody(r, "bundle-conflict",
					"Bundle already exists",
					"A different bundle is already stored under this id.")
			default:
				// A checksum mismatch or a path that tries to escape the release root lands
				// here. Both are refusals of the payload, not agent faults.
				slog.Warn("rejected bundle", "bundleId", payload.BundleID, "error", err)
				return http.StatusUnprocessableEntity, problemBody(r, "bundle-rejected",
					"Bundle rejected", err.Error())
			}
		}

		return http.StatusCreated, map[string]any{
			"bundleId":    payload.BundleID,
			"status":      "STORED",
			"contentHash": payload.ContentHash,
		}
	})
}

// handleActivateConfiguration validates, swaps and reloads. The only endpoint that can change
// what NGINX serves.
func (s *Server) handleActivateConfiguration(w http.ResponseWriter, r *http.Request) {
	bundleID := r.PathValue("bundleId")
	key := r.Header.Get("Idempotency-Key")

	s.withIdempotency(w, r, key, func() (int, any) {
		request := activateRequest{}
		if r.Body != nil {
			body, _ := io.ReadAll(http.MaxBytesReader(w, r.Body, 1<<20))
			if len(bytes.TrimSpace(body)) > 0 {
				if err := json.Unmarshal(body, &request); err != nil {
					return http.StatusBadRequest, problemBody(r, "malformed-request",
						"Malformed request", "The request body could not be read.")
				}
			}
		}
		reload := request.Reload == nil || *request.Reload

		// Every rendered bundle points its HTTPS catch-all at this pair. Startup creates it, but
		// a host whose releases directory was wiped, or one whose agent predates the catch-all,
		// would otherwise fail validation citing a certificate the operator never configured.
		if err := defaulttls.Ensure(s.cfg.ReleasesDir); err != nil {
			return http.StatusInternalServerError, problemBody(r, "default-tls-unavailable",
				"Default HTTPS certificate unavailable", err.Error())
		}

		result, err := s.bundles.Activate(r.Context(), bundleID, nginxForBundle{s.nginx}, reload)
		switch {
		case errors.Is(err, bundle.ErrBundleUnknown):
			return http.StatusNotFound, problemBody(r, "bundle-not-staged",
				"Bundle not staged", "Upload the bundle before activating it.")

		case errors.Is(err, bundle.ErrValidationFailed):
			// Nothing changed on the host. This is a conflict with the submitted configuration,
			// not a server fault, and it must never be retried: the same bytes fail identically.
			problem := problemBody(r, "nginx-validation-failed",
				"NGINX configuration validation failed", result.TestOutput)
			problem["status"] = http.StatusConflict
			problem["phase"] = "VALIDATE"
			problem["bundleId"] = bundleID
			return http.StatusConflict, problem

		case err != nil:
			problem := problemBody(r, "reload-failed", "NGINX reload failed", err.Error())
			problem["status"] = http.StatusInternalServerError
			problem["phase"] = "RELOAD"
			problem["bundleId"] = bundleID
			problem["rolledBack"] = result.RolledBack
			problem["previousBundleId"] = result.PreviousBundleID
			return http.StatusInternalServerError, problem
		}

		response := map[string]any{
			"bundleId":         result.BundleID,
			"previousBundleId": result.PreviousBundleID,
			"testOutput":       result.TestOutput,
			"nginxVersion":     result.NginxVersion,
			"noop":             result.Noop,
			"reloadedAt":       time.Now().UTC().Format(time.RFC3339),
		}
		if reload {
			response["status"] = "ACTIVE"
		} else {
			response["status"] = "VALIDATED"
		}
		return http.StatusOK, response
	})
}

func (s *Server) handleDeleteConfiguration(w http.ResponseWriter, r *http.Request) {
	bundleID := r.PathValue("bundleId")

	if err := s.bundles.Delete(bundleID); err != nil {
		if errors.Is(err, bundle.ErrBundleActive) {
			writeProblem(w, r, http.StatusConflict, "bundle-active",
				"Bundle is active", "The active configuration cannot be deleted.")
			return
		}
		writeProblem(w, r, http.StatusInternalServerError, "bundle-delete-failed",
			"Could not delete the bundle", err.Error())
		return
	}
	// Also 204 when it was already gone: the caller's intent is satisfied either way.
	w.WriteHeader(http.StatusNoContent)
}

// withIdempotency replays the stored response when a key repeats, and serialises concurrent
// requests carrying the same key so the work runs once.
//
// The stored key is scoped to the operation, not just to the header value. A deployment uses one
// Idempotency-Key for every call it makes, so keying on the header alone would let the activate
// request replay the response to the earlier stage request — the same key, a different question.
func (s *Server) withIdempotency(w http.ResponseWriter, r *http.Request, key string,
	work func() (int, any)) {

	if key != "" {
		key = r.Method + " " + r.URL.Path + " " + key
	}

	if key == "" {
		status, body := work()
		writeJSON(w, status, body)
		return
	}

	entry, owned := s.idempotency.begin(key)
	if !owned {
		<-entry.completed
		if entry.body == nil {
			writeProblem(w, r, http.StatusConflict, "idempotency-in-flight",
				"Request already in progress",
				"An earlier request with this Idempotency-Key did not complete. Retry it.")
			return
		}
		slog.Info("replaying idempotent response", "key", key, "status", entry.status)
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Idempotency-Replayed", "true")
		w.WriteHeader(entry.status)
		_, _ = w.Write(entry.body)
		return
	}

	status, body := work()
	encoded, err := json.Marshal(body)
	if err != nil {
		s.idempotency.abandon(entry)
		writeProblem(w, r, http.StatusInternalServerError, "internal-error",
			"Internal error", "The response could not be encoded.")
		return
	}

	s.idempotency.complete(entry, status, encoded)
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_, _ = w.Write(encoded)
}

func problemBody(r *http.Request, slug, title, detail string) map[string]any {
	return map[string]any{
		"type":     problemBase + slug,
		"title":    title,
		"detail":   detail,
		"instance": r.URL.Path,
	}
}
