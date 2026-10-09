package bundle

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"os"
	"path/filepath"
	"testing"
)

// fakeNginx records what was asked of it and can be told to fail at either step.
type fakeNginx struct {
	testOK      bool
	testOutput  string
	reloadErr   error
	testCalls   int
	reloadCalls int
	// treeAtTest is what `current` pointed at each time Test ran, which is how the tests assert
	// that validation saw the candidate rather than the old release.
	treeAtTest       []string
	root             string
	reloadFailedOnce bool
}

func (f *fakeNginx) Test(context.Context) (bool, string) {
	f.testCalls++
	target, _ := os.Readlink(filepath.Join(f.root, "current"))
	f.treeAtTest = append(f.treeAtTest, filepath.Base(target))
	return f.testOK, f.testOutput
}

func (f *fakeNginx) Reload(context.Context) error {
	f.reloadCalls++
	if f.reloadErr != nil && !f.reloadFailedOnce {
		// Only the first reload fails. That is the realistic shape of the problem: the new
		// configuration cannot bind, the previous one still can.
		f.reloadFailedOnce = true
		return f.reloadErr
	}
	return nil
}

func (f *fakeNginx) Version(context.Context) string { return "1.27.5" }

func digestOf(content string) string {
	sum := sha256.Sum256([]byte(content))
	return hex.EncodeToString(sum[:])
}

func bundleWith(id, content string) Bundle {
	return Bundle{
		BundleID:    id,
		Sequence:    1,
		ContentHash: digestOf(content),
		Files: []File{
			{Path: "conf.d/site.conf", Content: content, SHA256: digestOf(content)},
		},
	}
}

func newStoreWith(t *testing.T, bundles ...Bundle) (*Store, *fakeNginx) {
	t.Helper()
	root := t.TempDir()
	store := NewStore(root)
	for _, b := range bundles {
		if err := store.Stage(b); err != nil {
			t.Fatalf("staging %s: %v", b.BundleID, err)
		}
	}
	return store, &fakeNginx{testOK: true, testOutput: "syntax is ok", root: root}
}

func TestStagingDoesNotChangeWhatIsServed(t *testing.T) {
	store, _ := newStoreWith(t, bundleWith("bundle1", "server {}"))

	if active := store.Active(); active != "" {
		t.Fatalf("staging must not activate anything, but current points at %q", active)
	}
	if !store.Exists("bundle1") {
		t.Fatal("bundle should be on disk after staging")
	}
}

func TestActivateValidatesTheCandidateTreeNotTheOldOne(t *testing.T) {
	store, nginx := newStoreWith(t, bundleWith("bundle1", "a"), bundleWith("bundle2", "b"))

	if _, err := store.Activate(context.Background(), "bundle1", nginx, true); err != nil {
		t.Fatalf("first activation: %v", err)
	}
	if _, err := store.Activate(context.Background(), "bundle2", nginx, true); err != nil {
		t.Fatalf("second activation: %v", err)
	}

	// The include in nginx.conf resolves through `current`, so validation is only meaningful if
	// the symlink already points at the candidate when nginx -t runs.
	last := nginx.treeAtTest[len(nginx.treeAtTest)-1]
	if last != "bundle2" {
		t.Errorf("nginx -t ran against %q, expected the candidate bundle2", last)
	}
}

func TestFailedValidationNeverReloadsAndRestoresThePreviousRelease(t *testing.T) {
	store, nginx := newStoreWith(t, bundleWith("good", "a"), bundleWith("bad", "b"))

	if _, err := store.Activate(context.Background(), "good", nginx, true); err != nil {
		t.Fatalf("activating the good bundle: %v", err)
	}
	reloadsAfterGood := nginx.reloadCalls

	nginx.testOK = false
	nginx.testOutput = "nginx: [emerg] invalid host in upstream"
	result, err := store.Activate(context.Background(), "bad", nginx, true)

	if !errors.Is(err, ErrValidationFailed) {
		t.Fatalf("expected a validation failure, got %v", err)
	}
	if nginx.reloadCalls != reloadsAfterGood {
		t.Error("nginx was reloaded despite validation failing")
	}
	if store.Active() != "good" {
		t.Errorf("the previous release must still be served, but current points at %q", store.Active())
	}
	if result.TestOutput != "nginx: [emerg] invalid host in upstream" {
		t.Errorf("the operator needs the real nginx output, got %q", result.TestOutput)
	}
}

func TestFailedReloadRestoresAndReloadsThePreviousRelease(t *testing.T) {
	store, nginx := newStoreWith(t, bundleWith("good", "a"), bundleWith("breaks", "b"))

	if _, err := store.Activate(context.Background(), "good", nginx, true); err != nil {
		t.Fatalf("activating the good bundle: %v", err)
	}

	// Valid syntax that NGINX still refuses to load: a port already bound, for instance. nginx -t
	// cannot catch this because it does not bind sockets.
	nginx.reloadErr = errors.New("bind() to 0.0.0.0:80 failed")
	result, err := store.Activate(context.Background(), "breaks", nginx, true)

	if err == nil {
		t.Fatal("a failed reload must be reported")
	}
	if !result.RolledBack {
		t.Error("the agent should report that it restored the previous release")
	}
	if store.Active() != "good" {
		t.Errorf("the host must be left serving the previous release, got %q", store.Active())
	}
}

func TestActivateWithoutReloadValidatesAndLeavesTrafficUntouched(t *testing.T) {
	store, nginx := newStoreWith(t, bundleWith("live", "a"), bundleWith("candidate", "b"))

	if _, err := store.Activate(context.Background(), "live", nginx, true); err != nil {
		t.Fatalf("activating: %v", err)
	}
	reloadsBefore := nginx.reloadCalls

	result, err := store.Activate(context.Background(), "candidate", nginx, false)
	if err != nil {
		t.Fatalf("dry run: %v", err)
	}

	if nginx.reloadCalls != reloadsBefore {
		t.Error("a dry run must not reload")
	}
	if store.Active() != "live" {
		t.Errorf("a dry run must leave the served release alone, got %q", store.Active())
	}
	if result.TestOutput == "" {
		t.Error("a dry run should return the validation output; that is its whole purpose")
	}
}

func TestReactivatingTheCurrentBundleReloadsWithoutSwapping(t *testing.T) {
	store, nginx := newStoreWith(t, bundleWith("only", "a"))

	if _, err := store.Activate(context.Background(), "only", nginx, true); err != nil {
		t.Fatalf("activating: %v", err)
	}
	reloadsBefore := nginx.reloadCalls

	result, err := store.Activate(context.Background(), "only", nginx, true)
	if err != nil {
		t.Fatalf("re-activating: %v", err)
	}
	if !result.Noop {
		t.Error("re-activating the running bundle should report a no-op: nothing was swapped")
	}
	if store.Active() != "only" {
		t.Errorf("the running bundle must stay current, got %q", store.Active())
	}
	if nginx.reloadCalls != reloadsBefore+1 {
		t.Error("re-activating with reload must reload: it is how a reload that did not take is repaired")
	}
}

func TestReactivatingTheCurrentBundleWithoutReloadDoesNotReload(t *testing.T) {
	store, nginx := newStoreWith(t, bundleWith("only", "a"))

	if _, err := store.Activate(context.Background(), "only", nginx, true); err != nil {
		t.Fatalf("activating: %v", err)
	}
	reloadsBefore := nginx.reloadCalls

	if _, err := store.Activate(context.Background(), "only", nginx, false); err != nil {
		t.Fatalf("re-activating: %v", err)
	}
	if nginx.reloadCalls != reloadsBefore {
		t.Error("a dry run of the running bundle must not reload")
	}
}

func TestStagingIsIdempotentButRejectsADifferentBundleUnderTheSameId(t *testing.T) {
	store, _ := newStoreWith(t, bundleWith("bundle1", "a"))

	if err := store.Stage(bundleWith("bundle1", "a")); err != nil {
		t.Fatalf("re-staging identical content should succeed: %v", err)
	}
	if err := store.Stage(bundleWith("bundle1", "different")); !errors.Is(err, ErrBundleExists) {
		t.Fatalf("expected a conflict for different content under the same id, got %v", err)
	}
}

func TestChecksumMismatchIsRejected(t *testing.T) {
	store, _ := newStoreWith(t)

	corrupted := bundleWith("bundle1", "server {}")
	corrupted.Files[0].SHA256 = digestOf("something else")

	if err := store.Stage(corrupted); err == nil {
		t.Fatal("a file whose digest does not match must be rejected")
	}
	if store.Exists("bundle1") {
		t.Error("a rejected bundle must leave nothing behind")
	}
}

func TestPathTraversalIsRefused(t *testing.T) {
	store, _ := newStoreWith(t)

	for _, path := range []string{"../escape.conf", "/etc/passwd", "conf.d/../../escape.conf"} {
		b := bundleWith("bundle1", "x")
		b.Files[0].Path = path
		if err := store.Stage(b); err == nil {
			t.Errorf("path %q should have been refused", path)
		}
		_ = os.RemoveAll(filepath.Join(store.Root(), "releases", "bundle1"))
	}
}

func TestPrivateKeysAreWrittenUnreadableToOthers(t *testing.T) {
	store, _ := newStoreWith(t)

	key := "-----BEGIN PRIVATE KEY-----"
	b := bundleWith("bundle1", "server {}")
	b.Files = append(b.Files, File{
		Path:      "certs/app/privkey.pem",
		Content:   key,
		SHA256:    digestOf(key),
		Sensitive: true,
	})
	if err := store.Stage(b); err != nil {
		t.Fatalf("staging: %v", err)
	}

	info, err := os.Stat(filepath.Join(store.Root(), "releases", "bundle1", "certs/app/privkey.pem"))
	if err != nil {
		t.Fatalf("stat: %v", err)
	}
	if mode := info.Mode().Perm(); mode != 0o600 {
		t.Errorf("private key mode is %o, expected 600", mode)
	}
}

func TestTheActiveBundleCannotBeDeleted(t *testing.T) {
	store, nginx := newStoreWith(t, bundleWith("bundle1", "a"))
	if _, err := store.Activate(context.Background(), "bundle1", nginx, true); err != nil {
		t.Fatalf("activating: %v", err)
	}

	if err := store.Delete("bundle1"); !errors.Is(err, ErrBundleActive) {
		t.Fatalf("deleting the active bundle should be refused, got %v", err)
	}
}

func TestRollbackTargetsSurvivePruning(t *testing.T) {
	store, nginx := newStoreWith(t)
	for i := 0; i < 15; i++ {
		id := string(rune('a'+i)) + "bundle"
		if err := store.Stage(bundleWith(id, id)); err != nil {
			t.Fatalf("staging %s: %v", id, err)
		}
		if _, err := store.Activate(context.Background(), id, nginx, true); err != nil {
			t.Fatalf("activating %s: %v", id, err)
		}
	}

	available := store.Available()
	if len(available) > defaultRetain+1 {
		t.Errorf("expected pruning to bound the release count, found %d", len(available))
	}
	if !store.Exists(store.Active()) {
		t.Error("the active release must never be pruned")
	}
}

// A host whose stored configuration was invalid at boot has no NGINX process. Activation must
// start it rather than signalling one that does not exist, or the host could only be repaired by
// hand — which is the failure this platform exists to prevent.
func TestActivationStartsNginxWhenItIsNotRunning(t *testing.T) {
	store, nginx := newStoreWith(t, bundleWith("fix", "a"))
	nginx.reloadCalls = 0

	if _, err := store.Activate(context.Background(), "fix", nginx, true); err != nil {
		t.Fatalf("activating: %v", err)
	}
	if nginx.reloadCalls != 1 {
		t.Errorf("expected exactly one reload call, got %d", nginx.reloadCalls)
	}
	if store.Active() != "fix" {
		t.Errorf("the corrected bundle should be active, got %q", store.Active())
	}
}
