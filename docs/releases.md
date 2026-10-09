# Releases

Pushing a tag cuts a release. Nothing else publishes.

```bash
git tag v1.2.0 && git push origin v1.2.0
```

## Before tagging

1. **Write the changelog.** `CHANGELOG.md` needs a `## <version>` section: highlights, breaking
   changes, upgrade steps. It becomes the release notes, ahead of the list generated from pull
   requests; a final release without one is refused. A candidate (`1.2.0-rc.1`) uses the section of
   the version it is a candidate for.
2. **Move the pinned versions.** The compose files, the Kubernetes manifests and the install
   examples in `docs/admin` name an exact release rather than `latest`. Update them in the same
   commit as the changelog: `grep -rn "enginx-\(management\|agent\|console\):" docker deploy docs`.
3. **Bump `frontend/package.json`** (`npm version <version> --no-git-tag-version`).
4. **Rehearse** with `workflow_dispatch` and a test version, then tag a candidate, smoke-test its
   images on a clean host, and tag the final release from the same commit.

## Versioning and compatibility

[Semantic versioning](https://semver.org), with the usual pre-1.0 reading:

- **A minor release (`0.x.0`) may break** the REST API, configuration or the agent protocol. Every
  break is listed under *Breaking changes* in the changelog, with what to do about it.
- **A patch release (`0.x.y`) never breaks.** Fixes only; a host or client that worked keeps
  working.
- **Agents before the management server**, on every upgrade
  ([production guide §2.3](admin/production.md)). Done the other way round, an agent older than the
  server reports a job type it does not know as a failed job rather than ignoring it, so the
  mistake is visible rather than silent.

**The schema only moves forward.** From 0.1.0, a changelog under
`db/changelog/changes/` is part of a release once tagged and is never edited or deleted: Liquibase
records a checksum per changeset, and a changed one makes every database upgraded from that
release refuse to start. A schema change after a release is a new numbered file. CI enforces this
against the latest release tag. The `0.0.x` tags were development snapshots, rebuilt twice from a
squashed baseline; they are excluded, and there is no upgrade path from them.

## What a release contains

The workflow builds and attaches:

| Artefact | Platforms |
|---|---|
| `enginx-agent-linux-amd64` | x86-64 servers |
| `enginx-agent-linux-arm64` | ARM servers, Graviton, Ampere |
| `enginx-agent-linux-armv7` | 32-bit ARM |
| `enginx-agent-darwin-arm64` | Apple silicon |
| `enginx-agent-darwin-amd64` | Intel Macs |
| `proxy-management-<version>.jar` | — |
| `SHA256SUMS.txt` | verifies all of the above |

It also builds and pushes three container images to the registry attached to this repository, for
`linux/amd64` and `linux/arm64`:

```
ghcr.io/xiidea/enginx-management:<version>
ghcr.io/xiidea/enginx-agent:<version>
ghcr.io/xiidea/enginx-console:<version>
```

`latest` moves to a release but never to a pre-release: somebody pulling `latest` is asking for the
version they should be running, and that is never an rc. Authentication is the workflow's own
token, so there are no registry credentials to create, rotate or leak.

The version reaches the application, not only the tag on the image. The agent is built with the
same `-ldflags` the release binaries use, the management jar is built with `-Pversion` and carries
`build-info.properties`, so a running server reports its version at `/actuator/info` and a running
agent reports it with `-version`. Both are checked after the push, for the same reason the binaries
are: an image tagged `1.2.3` whose application says something else is worse than no version at all,
because it is believed.

Agent binaries are statically linked (`CGO_ENABLED=0`), so they run on a host whose libc is not
ours to assume. The version comes from the tag — `v1.2.0` produces artefacts and a build stamped
`1.2.0` — and every binary is **executed under emulation** and asked what it is before anything is
published. That check exists because the cheap version of it does not work: an unstamped build of
this agent also contains the string "1.2.3", so grepping proves nothing.

```bash
enginx-agent -version        # enginx-agent 1.2.0 (a1b2c3d) linux/amd64
```

The macOS binaries are built on a macOS runner rather than cross-compiled, for the same reason:
the Linux job proves a binary by running it under emulation, and emulation cannot execute a Mach-O
executable at all. Cross-compiling them would mean shipping the only two binaries nobody ever ran.
arm64 runs natively on the runner and amd64 runs under Rosetta.

macOS is for running the agent on a developer's machine. A production NGINX host is Linux, and the
container images are Linux only.

A tag matching `v*-*` — `v1.2.0-rc1` — is published as a pre-release, so it does not become the
latest. `workflow_dispatch` builds and verifies everything without publishing, which is how the
release path is rehearsed rather than tested for the first time on a real tag.

Tests run in the release workflow as well as in CI: a tag can point at a commit CI never saw, and a
release built from untested code is precisely the artefact that must not exist.
