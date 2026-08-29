# Certificates

Issuance, renewal, wildcards, and the agent's own certificate.

## Certificates

`CertificateProvider` is the abstraction; ACME is one implementation of it, and manual upload is
another. A private CA can be added behind the same interface without the rest of the system
learning about it.

| Endpoint | Purpose |
|---|---|
| `POST /api/v1/certificates` | Request over ACME (HTTP-01) |
| `POST /api/v1/certificates/upload` | Store material you already have |
| `POST /api/v1/certificates/{id}/renew` | Renew now |
| `PUT /api/v1/certificates/{id}/renewal` | Configure automatic renewal |
| `POST /api/v1/certificates/{id}/revoke` | Revoke |
| `GET`/`DELETE` `/api/v1/certificates[/{id}]` | Inventory; delete refused while in use |

### Keys are encrypted at rest, with a rotatable seam

Envelope encryption with AES-256-GCM. Each secret gets its own data key, encrypted with a
key-encryption key held outside the database. A dump is inert without the KEK, and rotating the
KEK means re-wrapping small data keys rather than decrypting every secret.

`KekProvider` is an interface from the first line of code, exactly as architecture risk R1 asked.
`EnvironmentKekProvider` reads keys from configuration; a deployment holding certificates for real
domains should put a KMS behind the same two methods. Several keys can be configured at once, so a
rotation proceeds gradually: the newest wraps new secrets while older ones stay available to
unwrap what they already protect.

GCM rather than CBC because it authenticates as well as encrypts — otherwise ciphertext in a table
an attacker can write to could be altered into different plaintext, and the first sign would be
NGINX loading a key nobody chose.

**The platform refuses to start without a key.** An application that came up and then could not
read any certificate it already held would be a far worse way to find out.

```bash
CRYPTO_ACTIVE_KEY_ID=prod CRYPTO_ACTIVE_KEY=$(openssl rand -base64 32)
```

### The private key never leaves through the API

There is no field for it on any response type, at any permission level — so no handler, mapper or
future edit can leak one by accident. It is decrypted in exactly one place, when a deployment
bundle is assembled, marked sensitive the moment it enters a bundle file, and written `0600` on
the host.

### ACME with HTTP-01

Validation needs nothing but the NGINX hosts this platform already controls. Challenge responses
are published straight to the agents, outside the release tree: routing a token through
render-validate-activate-reload would churn the real configuration twice for every certificate, to
publish something that lives for seconds.

Every rendered server block serves `/.well-known/acme-challenge/`, including the redirect server
of an HTTPS-only site — otherwise forcing HTTPS would make a site unable to renew the certificate
that made it HTTPS.

Defaults are deliberately cautious. **Staging** unless told otherwise, because production limits
are counted per week and a misconfiguration found there can lock an account out for days. Terms of
service are never accepted implicitly. Wildcards are refused before an order is placed, since they
require DNS-01 — the answer is a DNS provider behind the same interface, not a silent failure.

### Renewal and monitoring

An hourly job refreshes status from each certificate's own dates and renews what is inside its
window. Status is derived, never asserted: `VALID`, `EXPIRING_SOON`, `EXPIRED`, `REVOKED`, `ERROR`.

- A **failed renewal keeps the working certificate.** Taking material away because a renewal
  failed would break the sites it is protecting.
- A certificate that has **never been issued is not auto-renewed.** It is either mid-issuance —
  where the sweep would race the request that created it — or failed because the domain does not
  resolve here yet, where retrying on a timer spends rate limit on something no retry can fix.
  First issuance is explicit; only renewal is automatic.
- **Expired and revoked material is withheld from deployment.** Shipping it would replace a working
  certificate with one every client rejects.

## Wildcard certificates and DNS-01

An authority will only accept DNS-01 for a wildcard, because proving control of `*.example.com`
means proving control of the zone rather than of any one host. Configure a DNS provider and
wildcards work; without one they are refused up front, before any rate-limit budget is spent
discovering it at the authority.

```yaml
enginx:
  acme:
    dns:
      # Development only — challtestsrv answers every query for every name.
      challtestsrv-url: http://challtestsrv:8055
      # Use DNS-01 even for non-wildcards. Worth setting where port 80 is not reachable from the
      # public internet, which is the usual reason HTTP-01 fails in an internal estate.
      prefer-dns-01: false
```

The challenge type is chosen per authorization by asking the authority what it offers, so an order
mixing `example.com` and `*.example.com` validates the first over HTTP and the second over DNS.

**No cloud DNS provider is included.** `DnsChallengePublisher` is the seam and an implementation is
a single class, but one written without an account to test against would be worse than an honest
gap.

## Rotating an agent certificate

The platform pins each agent's certificate fingerprint in addition to verifying its chain, so
replacing a certificate makes the host unreachable until the new fingerprint is trusted:

```bash
curl -X PUT -H "Authorization: Bearer $TOKEN" \
  -d '{"agentCertFingerprint":"<64 hex chars>"}' \
  "$API/nginx-instances/$ID/agent-certificate"
```

Swap the certificate on the host, then call this. The window between the two is a loss of control,
never of traffic — the host keeps serving throughout, it simply cannot be changed. Both fingerprints
are recorded in the audit trail.
