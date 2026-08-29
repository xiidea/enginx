// Package version carries the build's identity.
//
// A variable rather than a constant so the linker can set it: builds are stamped with
// -ldflags "-X github.com/xiidea/enginx/enginx-agent/internal/version.Version=1.2.3". The default is
// deliberately "dev" rather than a plausible number — a binary reporting 0.1.0 because nobody
// stamped it is indistinguishable from a real 0.1.0, and the version an agent reports is what the
// platform records and what an operator reads during an incident.
package version

// Version is the release this binary was built from, or "dev" for a local build.
var Version = "dev"

// Commit is the revision, when the build supplies one.
var Commit = "unknown"
