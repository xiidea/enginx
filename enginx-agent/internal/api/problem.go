package api

import (
	"encoding/json"
	"log/slog"
	"net/http"
)

// Problem is an RFC 9457 problem document. The agent and the management server speak the same
// error shape so one client-side mapper covers both.
type Problem struct {
	Type     string `json:"type"`
	Title    string `json:"title"`
	Status   int    `json:"status"`
	Detail   string `json:"detail,omitempty"`
	Instance string `json:"instance,omitempty"`
	Phase    string `json:"phase,omitempty"`
	BundleID string `json:"bundleId,omitempty"`
}

const problemBase = "https://enginx.dev/problems/"

func writeProblem(w http.ResponseWriter, r *http.Request, status int, slug, title, detail string) {
	w.Header().Set("Content-Type", "application/problem+json")
	w.WriteHeader(status)
	body := Problem{
		Type:     problemBase + slug,
		Title:    title,
		Status:   status,
		Detail:   detail,
		Instance: r.URL.Path,
	}
	if err := json.NewEncoder(w).Encode(body); err != nil {
		slog.Error("writing problem response", "error", err)
	}
}

func writeJSON(w http.ResponseWriter, status int, payload any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	if err := json.NewEncoder(w).Encode(payload); err != nil {
		slog.Error("writing json response", "error", err)
	}
}
