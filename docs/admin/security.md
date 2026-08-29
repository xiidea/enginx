# Security posture

The properties the platform holds, and why each one is arranged the way it is.

## Security posture

- Authentication is a bearer token, whichever provider issued it. An OIDC provider and the
  platform's own local accounts produce the same claims, so there is one authorization path rather
  than two — and only one of two would ever have been exercised by the tests that matter.
- Local passwords are hashed with bcrypt at cost 12 and never leave the application: no response
  carries a hash, and the aggregate does not print one in `toString`.
- A failed local sign-in returns one message for a wrong password, a missing account and a
  disabled account alike, and hashes against a dummy value when the account is missing so the
  three take the same time. Distinguishing them turns the login form into an account enumerator.
- An account that must still change its password can do exactly that and nothing else. The check
  is a servlet filter reading the validated token, not a console screen — a console can be told to
  show a password form first, but a bearer token cannot be told to only be used by a console.
- Tokens are checked for issuer, expiry **and audience**. Without the audience check any token
  from the same realm would be accepted, including one minted for an unrelated application. With
  both providers enabled, the issuer selects which verifier runs; the audience check is the same
  either way.
- A local subject is `local:<uuid>`, never a bare UUID. `permission_grants.subject_ref` holds an
  OIDC `sub`, which *is* a bare UUID, so an unnamespaced local id could match a grant written for
  somebody else — silently, and permanently.
- The platform refuses to start with both providers disabled, rather than coming up healthy with
  nobody able to sign in.
- Authorization is enforced on application service methods, not controllers: a scheduler or
  message consumer can bypass a controller, but not the service it calls.
- Group membership is read from the token on every request, never from a mirrored table, so a
  revoked membership stops working within one access-token lifetime (300s in the dev realm).
- Authorization is scoped per domain, not merely per role, and both the single-site check and the
  listing predicate come from the same evaluator, so a listing can never show more than a direct
  fetch would allow.
- Refusals are audited in their own transaction. The operation being refused is about to roll
  back, and an audit row written inside it would be discarded exactly when it matters most.
- Untrusted input never reaches a generated NGINX directive as text. Domains, upstream hosts,
  header names and header values are each validated by a value object whose constructor is the
  only way to build one, and the same rules are repeated as database check constraints.
- The audit log is append-only in three independent ways: no update method on the repository,
  no setters on the entity, and a database trigger that raises on `UPDATE` or `DELETE`.
- The agent runs NGINX with a fixed argument vector through `exec.Command`, never a shell.
- The agent verifies the management client certificate against the CA **and** pins its common
  name. Trusting the CA alone would let any certificate it ever signed drive the host.
- A host that calls in instead of being dialled authenticates with a token, which is **weaker than
  a pinned certificate and is treated as such**: it is 256 bits from a CSPRNG, stored only as a
  digest, revocable, and stamped on every use so a dormant credential is visible. Issuing agent
  certificates is what replaces it.
- The credential that enrols a host is bounded three independent ways — expiry, use count and
  revocation — because it is the one secret that travels into manifests and provisioning scripts.
  Every refused enrolment is audited with its reason, in its own transaction, since a burst of
  them is what guessing looks like.
- Sensitive operations are rate limited by what they can damage, not by HTTP method, and the
  sensitive path list is matched by prefix so a new endpoint under it is covered on the day it is
  written rather than the day someone notices it was not.
- Reading the audit trail requires global admin and is a separate port from writing it, so no
  component that appends can gain the ability to read or remove.
- `/actuator` exposes only health and info without authentication; metrics and Prometheus require
  `SUPER_ADMIN`, and the production edge does not proxy `/actuator` at all — publishing it would
  disclose the estate's shape to anyone asking.
- `/actuator/info` now reports the running version, which is a deliberate and small disclosure: it
  is what lets an operator ask a host what it is rather than trust what somebody believes they
  deployed. It is worth naming because a version is also what an attacker matches against a list of
  known vulnerabilities. The edge not proxying `/actuator` is what keeps that off the internet; if
  you expose it, put the version behind authentication with the rest of `/actuator`.
- Production secrets are files, not environment variables, and the key that wraps every stored
  private key is generated separately and never regenerated in place.
