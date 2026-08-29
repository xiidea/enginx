# Security posture

The properties the platform holds, and why each one is arranged the way it is.

## Security posture

- Keycloak is the only identity provider. There is no password handling in this codebase.
- Tokens are checked for issuer, expiry **and audience**. Without the audience check any token
  from the same realm would be accepted, including one minted for an unrelated application.
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
- Sensitive operations are rate limited by what they can damage, not by HTTP method, and the
  sensitive path list is matched by prefix so a new endpoint under it is covered on the day it is
  written rather than the day someone notices it was not.
- Reading the audit trail requires global admin and is a separate port from writing it, so no
  component that appends can gain the ability to read or remove.
- `/actuator` exposes only health and info without authentication; metrics and Prometheus require
  `SUPER_ADMIN`, and the production edge does not proxy `/actuator` at all — publishing it would
  disclose the estate's shape to anyone asking.
- Production secrets are files, not environment variables, and the key that wraps every stored
  private key is generated separately and never regenerated in place.
