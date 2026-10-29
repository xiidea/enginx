package bundle

import (
	"errors"
	"os"
	"path/filepath"
	"syscall"
	"testing"
)

func bundleOf(id string, files ...File) Bundle {
	for i := range files {
		files[i].SHA256 = digestOf(files[i].Content)
	}
	return Bundle{BundleID: id, Sequence: 1, ContentHash: "unused", Files: files}
}

func TestFilesAreWrittenWithTheModeTheyAskFor(t *testing.T) {
	root := t.TempDir()
	store := NewStore(root)
	store.SetWorkerGroup(os.Getgid())

	err := store.Stage(bundleOf("bundle1",
		File{Path: "conf.d/site.conf", Content: "server {}"},
		File{Path: "certs/site/privkey.pem", Content: "key", Sensitive: true, Mode: "0600"},
		File{Path: "auth/site/htpasswd", Content: "alice:$6$x$y\n", Sensitive: true, Mode: "0640"},
	))
	if err != nil {
		t.Fatalf("staging: %v", err)
	}

	for path, want := range map[string]os.FileMode{
		"conf.d/site.conf":       0o644,
		"certs/site/privkey.pem": 0o600,
		"auth/site/htpasswd":     0o640,
	} {
		info, err := os.Stat(filepath.Join(store.releasePath("bundle1"), path))
		if err != nil {
			t.Fatalf("%s: %v", path, err)
		}
		if got := info.Mode().Perm(); got != want {
			t.Errorf("%s: mode %o, want %o", path, got, want)
		}
	}

	info, _ := os.Stat(filepath.Join(store.releasePath("bundle1"), "auth/site/htpasswd"))
	if gid := int(info.Sys().(*syscall.Stat_t).Gid); gid != os.Getgid() {
		t.Errorf("htpasswd group %d, want the worker group %d", gid, os.Getgid())
	}
}

func TestAWorkerReadableFileIsRefusedWithoutAWorkerGroup(t *testing.T) {
	store := NewStore(t.TempDir())

	err := store.Stage(bundleOf("bundle1",
		File{Path: "auth/site/htpasswd", Content: "alice:$6$x$y\n", Sensitive: true, Mode: "0640"}))

	if !errors.Is(err, ErrNoWorkerGroup) {
		t.Fatalf("got %v, want ErrNoWorkerGroup", err)
	}
	if _, statErr := os.Stat(store.releasePath("bundle1")); !os.IsNotExist(statErr) {
		t.Fatalf("a refused bundle must leave no release behind")
	}
}

func TestUnknownOrUnsafeModesAreRefused(t *testing.T) {
	for name, file := range map[string]File{
		"unknown mode":                 {Path: "conf.d/a.conf", Content: "x", Mode: "0777"},
		"sensitive and world-readable": {Path: "certs/a/privkey.pem", Content: "x", Sensitive: true, Mode: "0644"},
	} {
		t.Run(name, func(t *testing.T) {
			store := NewStore(t.TempDir())
			store.SetWorkerGroup(os.Getgid())
			if err := store.Stage(bundleOf("bundle1", file)); err == nil {
				t.Fatalf("expected the bundle to be refused")
			}
		})
	}
}
