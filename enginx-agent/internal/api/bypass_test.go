package api

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func write(t *testing.T, path, content string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}

const protectedSite = "server {\n    listen 443 ssl;\n    server_name private.example.com;\n\n    auth_basic \"Restricted\";\n}\n"
const publicSite = "server {\n    listen 80;\n    server_name public.example.com;\n}\n"

func TestABypassInAProtectedSitesCustomFileIsReported(t *testing.T) {
	root := t.TempDir()
	release, custom, confDir := filepath.Join(root, "release"), filepath.Join(root, "custom"), filepath.Join(root, "etc")
	write(t, filepath.Join(release, "conf.d", "private.example.com.conf"), protectedSite)
	write(t, filepath.Join(release, "conf.d", "public.example.com.conf"), publicSite)
	write(t, filepath.Join(custom, "sites", "private.example.com", "health.conf"),
		"# return 200 is fine in a comment\nlocation = /health {\n    return 200 \"ok\";\n}\n")
	// A public site's own hook is no concern of this check.
	write(t, filepath.Join(custom, "sites", "public.example.com", "x.conf"), "return 301 https://elsewhere;\n")
	// One level down, through an include in the shared hook, relative to the config directory.
	write(t, filepath.Join(custom, "server", "shared.conf"), "include snippets/legacy.conf;\n")
	write(t, filepath.Join(confDir, "snippets", "legacy.conf"), "rewrite ^/old /new permanent;\ninclude snippets/legacy.conf;\n")

	warnings := protectionWarnings(release, custom, confDir)

	if len(warnings) != 2 {
		t.Fatalf("expected 2 warnings, got %d: %v", len(warnings), warnings)
	}
	joined := strings.Join(warnings, "\n")
	for _, want := range []string{"health.conf:3 has `return`", "legacy.conf:1 has `rewrite`", "private.example.com is protected"} {
		if !strings.Contains(joined, want) {
			t.Errorf("missing %q in %v", want, warnings)
		}
	}
	if strings.Contains(joined, "public.example.com") {
		t.Errorf("a public site was reported: %v", warnings)
	}
}

func TestNoProtectedSitesNoWarnings(t *testing.T) {
	root := t.TempDir()
	write(t, filepath.Join(root, "release", "conf.d", "public.example.com.conf"), publicSite)
	write(t, filepath.Join(root, "custom", "server", "x.conf"), "return 444;\n")
	if warnings := protectionWarnings(filepath.Join(root, "release"), filepath.Join(root, "custom"), root); len(warnings) != 0 {
		t.Fatalf("got %v", warnings)
	}
}

func TestWarningsFollowTheValidationOutput(t *testing.T) {
	out := withProtectionWarnings("nginx: configuration file test is successful\n", []string{"a", "b"})
	if out != "nginx: configuration file test is successful\n\nwarning: a\nwarning: b" {
		t.Fatalf("got %q", out)
	}
	if withProtectionWarnings("ok", nil) != "ok" {
		t.Fatal("no warnings must leave the output alone")
	}
}
