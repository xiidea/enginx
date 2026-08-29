package api

import (
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

// acmeChallengeDir lives outside the release tree on purpose.
//
// An HTTP-01 token has to be reachable within seconds and is discarded just as fast. Putting it in
// a bundle would mean a full render-validate-activate-reload cycle to publish it and another to
// remove it, twice per certificate, with the site's real configuration churning each time. A fixed
// directory that nginx.conf serves directly keeps issuance off the deployment path entirely.
const acmeChallengeDir = "acme-challenge"

// ACME tokens are base64url; anything else is not a token this agent will write to disk.
var acmeTokenPattern = regexp.MustCompile(`^[A-Za-z0-9_-]{16,128}$`)

type acmeChallengeRequest struct {
	Authorization string `json:"authorization"`
}

// handlePutAcmeChallenge publishes a challenge response for the authority to fetch.
func (s *Server) handlePutAcmeChallenge(w http.ResponseWriter, r *http.Request) {
	token := r.PathValue("token")
	if !acmeTokenPattern.MatchString(token) {
		// The token becomes a filename. Validating its shape is what stops a crafted value
		// writing anywhere other than the challenge directory.
		writeProblem(w, r, http.StatusBadRequest, "invalid-acme-token",
			"Invalid ACME token", "The token is not a valid ACME challenge token.")
		return
	}

	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 1<<16))
	if err != nil {
		writeProblem(w, r, http.StatusBadRequest, "malformed-request",
			"Malformed request", "The request body could not be read.")
		return
	}

	var request acmeChallengeRequest
	if err := json.Unmarshal(body, &request); err != nil || strings.TrimSpace(request.Authorization) == "" {
		writeProblem(w, r, http.StatusBadRequest, "malformed-request",
			"Malformed request", "An authorization value is required.")
		return
	}

	directory := filepath.Join(s.cfg.ReleasesDir, acmeChallengeDir)
	if err := os.MkdirAll(directory, 0o755); err != nil {
		writeProblem(w, r, http.StatusInternalServerError, "acme-challenge-failed",
			"Could not stage the challenge", err.Error())
		return
	}

	// World-readable: NGINX serves this file as an unauthenticated worker, and the content is a
	// public value the authority is about to fetch over plain HTTP anyway.
	if err := os.WriteFile(filepath.Join(directory, token), []byte(request.Authorization), 0o644); err != nil {
		writeProblem(w, r, http.StatusInternalServerError, "acme-challenge-failed",
			"Could not stage the challenge", err.Error())
		return
	}

	slog.Info("staged ACME challenge", "token", token)
	writeJSON(w, http.StatusCreated, map[string]any{"token": token, "status": "STAGED"})
}

// handleDeleteAcmeChallenge removes a challenge once validation has finished, successfully or not.
func (s *Server) handleDeleteAcmeChallenge(w http.ResponseWriter, r *http.Request) {
	token := r.PathValue("token")
	if !acmeTokenPattern.MatchString(token) {
		writeProblem(w, r, http.StatusBadRequest, "invalid-acme-token",
			"Invalid ACME token", "The token is not a valid ACME challenge token.")
		return
	}

	if err := os.Remove(filepath.Join(s.cfg.ReleasesDir, acmeChallengeDir, token)); err != nil && !os.IsNotExist(err) {
		writeProblem(w, r, http.StatusInternalServerError, "acme-challenge-failed",
			"Could not remove the challenge", err.Error())
		return
	}
	// 204 whether or not it was there: the caller wanted it gone.
	w.WriteHeader(http.StatusNoContent)
}
