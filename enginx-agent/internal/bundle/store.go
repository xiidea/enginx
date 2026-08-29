// Package bundle stages, activates and prunes configuration bundles on the host.
//
// The activation sequence is the reason this agent exists: validate the staged tree, swap a
// symlink atomically, reload, and restore the previous release if the reload itself fails. Every
// step is ordered so that NGINX is never asked to load a configuration that has not already
// passed nginx -t.
package bundle

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
)

const (
	releasesDir   = "releases"
	currentLink   = "current"
	stagingSuffix = ".staging"
	defaultRetain = 10
	sensitiveMode = os.FileMode(0o600)
	regularMode   = os.FileMode(0o644)
	directoryMode = os.FileMode(0o755)
)

// File is one member of a bundle as it arrives over the wire.
type File struct {
	Path      string `json:"path"`
	Content   string `json:"content"`
	SHA256    string `json:"sha256"`
	Mode      string `json:"mode,omitempty"`
	Sensitive bool   `json:"sensitive,omitempty"`
}

// Bundle is the payload of POST /agent/v1/configurations.
type Bundle struct {
	BundleID    string `json:"bundleId"`
	Sequence    int64  `json:"sequence"`
	ContentHash string `json:"contentHash"`
	Files       []File `json:"files"`
}

// Store owns the release directory.
type Store struct {
	root   string
	retain int

	// Activation mutates a shared symlink, so it is serialised within this process. Two agents
	// on one host would be a deployment error, not a case to coordinate against.
	mu sync.Mutex
}

func NewStore(root string) *Store {
	return &Store{root: root, retain: defaultRetain}
}

var (
	// ErrBundleExists means a different bundle is already stored under this id.
	ErrBundleExists = errors.New("a different bundle is already stored under this id")
	// ErrBundleUnknown means activation was asked for a bundle that was never staged.
	ErrBundleUnknown = errors.New("bundle has not been staged")
	// ErrBundleActive means a delete was attempted on the running configuration.
	ErrBundleActive = errors.New("bundle is currently active")
)

// Stage writes a bundle into its own release directory without touching what is being served.
//
// Writing happens into a temporary directory that is renamed into place only once every file has
// been verified, so an interrupted upload can never leave a half-written release that a later
// activation might pick up.
func (s *Store) Stage(b Bundle) error {
	if err := validateBundle(b); err != nil {
		return err
	}

	target := s.releasePath(b.BundleID)
	if existing, err := os.Stat(target); err == nil && existing.IsDir() {
		same, err := s.matchesStoredHash(b)
		if err != nil {
			return err
		}
		if same {
			// Idempotent: the same bundle arriving twice is a retry, not a conflict.
			return nil
		}
		return ErrBundleExists
	}

	staging := target + stagingSuffix
	if err := os.RemoveAll(staging); err != nil {
		return fmt.Errorf("clearing stale staging directory: %w", err)
	}
	if err := os.MkdirAll(staging, directoryMode); err != nil {
		return fmt.Errorf("creating staging directory: %w", err)
	}

	if err := writeFiles(staging, b.Files); err != nil {
		_ = os.RemoveAll(staging)
		return err
	}
	if err := writeManifest(staging, b); err != nil {
		_ = os.RemoveAll(staging)
		return err
	}

	if err := os.Rename(staging, target); err != nil {
		_ = os.RemoveAll(staging)
		return fmt.Errorf("moving staged bundle into place: %w", err)
	}
	return nil
}

func writeFiles(root string, files []File) error {
	for _, file := range files {
		clean, err := safeJoin(root, file.Path)
		if err != nil {
			return err
		}
		if err := os.MkdirAll(filepath.Dir(clean), directoryMode); err != nil {
			return fmt.Errorf("creating %s: %w", filepath.Dir(file.Path), err)
		}

		// Verify before writing. A file whose digest does not match was corrupted or tampered
		// with in transit, and there is no safe way to use it.
		sum := sha256.Sum256([]byte(file.Content))
		if got := hex.EncodeToString(sum[:]); !strings.EqualFold(got, file.SHA256) {
			return fmt.Errorf("checksum mismatch for %s", file.Path)
		}

		mode := regularMode
		if file.Sensitive || file.Mode == "0600" {
			mode = sensitiveMode
		}
		if err := os.WriteFile(clean, []byte(file.Content), mode); err != nil {
			return fmt.Errorf("writing %s: %w", file.Path, err)
		}
		// WriteFile honours the mode only when it creates the file, so set it explicitly.
		if err := os.Chmod(clean, mode); err != nil {
			return fmt.Errorf("setting permissions on %s: %w", file.Path, err)
		}
	}
	return nil
}

// safeJoin refuses any path that would escape the release root.
//
// The management server validates these too. Checking again here is deliberate: this process is
// the one with write access to the host, so it does not trust its caller to have been careful.
func safeJoin(root, rel string) (string, error) {
	if rel == "" || strings.HasPrefix(rel, "/") || strings.Contains(rel, `\`) {
		return "", fmt.Errorf("invalid bundle path %q", rel)
	}

	// Reject traversal rather than normalising it away. Cleaning "conf.d/../../x.conf" would
	// yield a path safely inside the root, but not the one the caller named, so the write would
	// silently land somewhere else and could clobber another file in the bundle. A path that
	// does not mean what it says is refused.
	for _, element := range strings.Split(rel, "/") {
		if element == "" || element == "." || element == ".." {
			return "", fmt.Errorf("bundle path %q must not contain traversal or empty segments", rel)
		}
	}

	joined := filepath.Join(root, rel)
	if !strings.HasPrefix(joined, filepath.Clean(root)+string(os.PathSeparator)) {
		return "", fmt.Errorf("bundle path %q escapes the release directory", rel)
	}
	return joined, nil
}

func validateBundle(b Bundle) error {
	if strings.TrimSpace(b.BundleID) == "" {
		return errors.New("bundleId is required")
	}
	if strings.ContainsAny(b.BundleID, "/\\.") {
		// The id becomes a directory name, so it must not be able to point anywhere else.
		return fmt.Errorf("invalid bundleId %q", b.BundleID)
	}
	if len(b.Files) == 0 {
		return errors.New("a bundle must contain at least one file")
	}
	return nil
}

func writeManifest(root string, b Bundle) error {
	paths := make([]string, 0, len(b.Files))
	for _, f := range b.Files {
		paths = append(paths, f.Path+" "+strings.ToLower(f.SHA256))
	}
	sort.Strings(paths)
	manifest := fmt.Sprintf("bundleId %s\nsequence %d\ncontentHash %s\n%s\n",
		b.BundleID, b.Sequence, b.ContentHash, strings.Join(paths, "\n"))
	return os.WriteFile(filepath.Join(root, ".enginx-manifest"), []byte(manifest), regularMode)
}

func (s *Store) matchesStoredHash(b Bundle) (bool, error) {
	raw, err := os.ReadFile(filepath.Join(s.releasePath(b.BundleID), ".enginx-manifest"))
	if err != nil {
		return false, nil
	}
	for _, line := range strings.Split(string(raw), "\n") {
		if rest, ok := strings.CutPrefix(line, "contentHash "); ok {
			return rest == b.ContentHash, nil
		}
	}
	return false, nil
}

func (s *Store) releasePath(bundleID string) string {
	return filepath.Join(s.root, releasesDir, bundleID)
}

// Exists reports whether a bundle has been staged.
func (s *Store) Exists(bundleID string) bool {
	info, err := os.Stat(s.releasePath(bundleID))
	return err == nil && info.IsDir()
}

// Active returns the bundle id the current symlink points at, or "" before the first deployment.
func (s *Store) Active() string {
	target, err := os.Readlink(filepath.Join(s.root, currentLink))
	if err != nil {
		return ""
	}
	return filepath.Base(target)
}

// Available lists the staged bundles, which are the rollback targets still present on disk.
func (s *Store) Available() []string {
	entries, err := os.ReadDir(filepath.Join(s.root, releasesDir))
	if err != nil {
		return []string{}
	}
	bundles := make([]string, 0, len(entries))
	for _, entry := range entries {
		if entry.IsDir() && !strings.HasSuffix(entry.Name(), stagingSuffix) {
			bundles = append(bundles, entry.Name())
		}
	}
	sort.Strings(bundles)
	return bundles
}

// Delete removes a staged bundle. The active one is refused.
func (s *Store) Delete(bundleID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.Active() == bundleID {
		return ErrBundleActive
	}
	return os.RemoveAll(s.releasePath(bundleID))
}

// Prune keeps the most recent releases so rollback targets remain on disk.
func (s *Store) Prune() {
	available := s.Available()
	if len(available) <= s.retain {
		return
	}
	active := s.Active()
	for _, id := range available[:len(available)-s.retain] {
		if id != active {
			_ = os.RemoveAll(s.releasePath(id))
		}
	}
}

// Root is the directory the store owns.
func (s *Store) Root() string {
	return s.root
}

// Lock guards activation, which mutates the shared current symlink.
func (s *Store) Lock() {
	s.mu.Lock()
}

func (s *Store) Unlock() {
	s.mu.Unlock()
}
