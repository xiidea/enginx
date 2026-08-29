# Releases


Pushing a tag cuts a release. Nothing else publishes.

```bash
git tag v1.2.0 && git push origin v1.2.0
```

The workflow builds and attaches:

| Artefact | Platforms |
|---|---|
| `enginx-agent-linux-amd64` | x86-64 servers |
| `enginx-agent-linux-arm64` | ARM servers, Graviton, Ampere |
| `enginx-agent-linux-armv7` | 32-bit ARM |
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

A tag matching `v*-*` — `v1.2.0-rc1` — is published as a pre-release, so it does not become the
latest. `workflow_dispatch` builds and verifies everything without publishing, which is how the
release path is rehearsed rather than tested for the first time on a real tag.

Tests run in the release workflow as well as in CI: a tag can point at a commit CI never saw, and a
release built from untested code is precisely the artefact that must not exist.
