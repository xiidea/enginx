# Access control

Who may do what, and how to grant it without granting more than you meant to.

## The permission model

Two layers. Keycloak realm roles set a floor and a ceiling that apply everywhere; scoped grants
in `permission_grants` decide who reaches which domains.

| Level | Grants |
|---|---|
| `READ` | View sites, deployments, certificates, diffs. Never secrets. |
| `OPERATE` | READ, plus enable, disable, renew. Cannot change routing. |
| `MANAGE` | OPERATE, plus create, update, delete, clone, membership. |
| `ADMIN` | MANAGE, plus grant and revoke within that scope. |

A grant attaches to one of four scopes: `GLOBAL`, `DOMAIN_GROUP` (inherited by every descendant
group), `DOMAIN_PATTERN` (`*.test.example.com`), or `SITE`.

Effective access is the **maximum** over every applicable grant. There is no DENY: the rule is
order-independent, computable as a SQL predicate, and therefore decomposable into the listing
query. Exclusions are expressed by granting more narrowly.

### Roles

| Role | Effect |
|---|---|
| `SUPER_ADMIN` | ADMIN everywhere. |
| `ADMIN` | MANAGE everywhere. |
| `OPERATOR` | Nothing implicit. Acts only where granted. |
| `READ_ONLY` | A **ceiling**, not a floor: confers nothing on its own, and caps any grant at READ. |

### Rules that exist to stop escalation

Each of these is a path a scoped permission system invites, and each has a test:

1. **A group grant cannot create a domain.** If it could, MANAGE on any group would become
   authority over the whole namespace — just file the new domain under your own group.
   Creation needs a global or pattern grant.
2. **Adding a site to a group needs MANAGE on the site as well as the group.** Otherwise a group
   administrator could adopt a site they have no rights to and inherit control through their own
   grant.
3. **Nobody may grant more than they hold**, and delegating requires ADMIN on the scope —
   MANAGE is not enough.
4. **A pattern grant may only be narrowed.** Holding `*.test.example.com` lets you delegate
   `*.inner.test.example.com`, never `*.example.com`.
5. **Unreadable means invisible.** A caller without READ gets 404, not 403, so the status code
   is not an oracle for which domains exist. The refusal is still audited.

### Wildcards without a table scan

`*.test.example.com` is a suffix match, which no B-tree can serve. Both sides store a reversed
form — the site keeps `domain_reversed = 'com.example.app'`, the grant keeps
`pattern_reversed = 'com.example.test.'` — turning the match into an indexed **prefix** scan. The
trailing dot is what stops a wildcard matching its own apex, and stops `*.test.example.com`
leaking into `testing.example.com`.

### Listing

`?search=`, `?status=ACTIVE&status=EXPIRED`, `?nginxInstanceId=`, `?sslEnabled=`,
`?expiringBefore=`, `?page=&size=`, `?sort=domain,asc` (repeat `sort` for multiple keys).

Filtering and sorting are applied in the query, never over an already-fetched page: post-filtering
produces wrong totals, and it would leak the existence of sites a user cannot see. Sort fields
resolve to an enum, so an unrecognised value is a 422 rather than an arbitrary property path
reaching Hibernate.

### Concurrent edits

Reads return the aggregate version as an `ETag`. Send it back as `If-Match` on `PUT`, `DELETE`
and `renew` and a concurrent edit is rejected with `409`. Hibernate's `@Version` alone only
protects writes inside a single transaction; `If-Match` is what makes a lost update detectable
across two separate requests.

## Granting access safely

`*.example.com` at MANAGE is one line of configuration and authority over every subdomain — the
ones that exist, and the ones created afterwards. Two things guard that.

**Preview before granting.** `POST /api/v1/permissions/preview` takes the same body as a grant and
returns what it would reach, without creating it. The **Preview reach** button on the permissions
page shows the same thing:

```json
{ "totalMatched": 3, "newlyVisibleToSubject": 2, "coversFutureDomains": true,
  "matched": [ { "domain": "app.example.com", "alreadyReachable": true }, … ] }
```

`newlyVisibleToSubject` is the number worth reading — a grant duplicating access someone already
holds is a different decision from one opening a namespace to them. `coversFutureDomains` is the
caveat that keeps the list honest: a wildcard is a rule, and the list is a snapshot of it.

Previewing is authorised exactly like granting, so it discloses nothing a caller could not disclose
to themselves by making the grant.

**A pattern grant needs authority over a namespace containing it.** Holding ADMIN over
`*.test.example.com` confers nothing over `*.example.com`, so nobody can escalate from a subdomain
to its parent — and therefore to every sibling.
