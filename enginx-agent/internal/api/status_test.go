package api

import (
	"os"
	"path/filepath"
	"testing"
)

func TestCertificateStatusThresholds(t *testing.T) {
	cases := []struct {
		name string
		days int
		want string
	}{
		{"already expired", -1, "EXPIRED"},
		{"expires today", 0, "EXPIRING_SOON"},
		{"on the renewal boundary", 30, "EXPIRING_SOON"},
		{"just past the boundary", 31, "VALID"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := certificateStatus(tc.days); got != tc.want {
				t.Errorf("certificateStatus(%d) = %q, want %q", tc.days, got, tc.want)
			}
		})
	}
}

func TestActiveBundleResolvesTheCurrentSymlink(t *testing.T) {
	root := t.TempDir()
	release := filepath.Join(root, "releases", "01J9ABCDEF")
	if err := os.MkdirAll(release, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(release, filepath.Join(root, "current")); err != nil {
		t.Fatal(err)
	}

	id, since := activeBundle(root)
	if id != "01J9ABCDEF" {
		t.Errorf("bundle id = %q, want %q", id, "01J9ABCDEF")
	}
	if since == "" {
		t.Error("activeSince should report when the symlink was last swapped")
	}
}

// Before the first deployment there is no `current` symlink at all. Status must still answer.
func TestActiveBundleOnAFreshHost(t *testing.T) {
	id, since := activeBundle(t.TempDir())
	if id != "" || since != "" {
		t.Errorf("expected an empty result on a host with no bundle, got %q / %q", id, since)
	}
}

func TestAvailableBundlesReturnsEmptySliceNotNil(t *testing.T) {
	// The field is serialised as a JSON array; nil would emit `null` and break clients.
	if bundles := availableBundles(t.TempDir()); bundles == nil || len(bundles) != 0 {
		t.Errorf("expected an empty non-nil slice, got %#v", bundles)
	}
}

func TestInspectCertificatesIgnoresAHostWithNoBundle(t *testing.T) {
	if certs := inspectCertificates(t.TempDir(), nowForTest()); len(certs) != 0 {
		t.Errorf("expected no certificates, got %d", len(certs))
	}
}
