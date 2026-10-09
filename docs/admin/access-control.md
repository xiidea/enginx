# Access control

Who may do what, and how to grant it without granting more than you meant to.

## The permission model

Two layers. Global roles set a floor and a ceiling that apply everywhere; scoped grants in
`permission_grants` decide who reaches which domains.

A global role may come from a Keycloak **realm role** or a **client role on the console's client**
(`OIDC_ROLE_CLIENT_ID`, default `enginx-frontend`). Either is enough, and a user holding both gets
the union. The same set decides both the coarse endpoint checks and the scoped permission
evaluation, so a role cannot count in one and not the other.

Both layers read the caller's token and nothing else, so it makes no difference whether an identity
provider or the platform's own local account store authenticated them.

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
   grant. In the console this is done from either side: expand a group on the **Permissions** page
   and add a site, or open a site and add it to a group from its **Domain groups** section. Both
   call the same endpoint and are refused the same way.
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

## Local accounts

When `AUTH_LOCAL_ENABLED` is on, the platform holds its own accounts and the console gains a
**Local users** page. Administering them requires a global admin role, because a local account
carries global roles — anyone able to create one could otherwise grant themselves `SUPER_ADMIN`.

An account has a username, a bcrypt-hashed password, a set of realm roles, and a set of group
paths. Those group paths are matched against group grants exactly as an identity provider's groups
are, so one grant can cover both kinds of member.

A few behaviours are worth knowing before you rely on them:

- **The last enabled `SUPER_ADMIN` cannot be deleted or disabled.** Removing it would lock everyone
  out of a running platform, and the only remedy is a database edit. Create a second administrator
  first.
- **Changing your own password requires the current one; an administrator's reset does not** — and
  must not, since a reset exists precisely for the case where the old password is unavailable.
- **A new account must change its password before it can do anything else.** Its token is refused
  on every endpoint but its own password change, so the password an administrator typed is never
  one the account keeps.
- **Deleting an account does not remove its grants.** They are keyed by subject, and a subject that
  no longer resolves grants nothing; but recreating a username issues a new id, so the old grants
  do not come back with it.

## The subject directory

`GET /api/v1/users` lists everyone who has ever presented a token, so a grant can be addressed by
name instead of by pasting a subject claim. It is a convenience index and never an authorization
input: every access decision reads roles and groups from the caller's own token, so a stale or
missing entry changes nothing about what anybody may do.

It is paged and searchable (`search`, `page`, `size`), because it gains a row for every person who
signs in and is not a list this platform controls the size of. Search matches username and display
name, case-insensitively.

Two things keep it honest about who still exists:

- **Deleting a local account removes its entry.** Otherwise the picker keeps offering somebody who
  can no longer authenticate, and a grant made to them silently does nothing.
- **A local subject with no account is marked `present: false`** rather than hidden — from an
  account deleted before pruning existed, for instance. A grant may still name it, and saying it is
  gone explains a stale row that quietly omitting it would not.

Subjects from an external identity provider are always reported present. This platform cannot ask
that provider whether an account still exists, and guessing would mean reporting every federated
user as departed.

### Cleaning it up

The **Directory** page, and `POST /api/v1/users/cleanup`, remove entries that no longer earn their
place. Both require global admin, because the list is shared by everyone who authors a grant, and
both are audited as `IDENTITY_SUBJECT_FORGOTTEN`.

Worth being exact about what removal does, because the words matter: an entry is a **name**.
Removing it takes away the name the console offers when authoring a grant. It revokes no access,
deletes no account, and is undone the moment its owner signs in again.

Two things count as stale:

- **Departed** — a `local:` subject whose account no longer exists. Certain, and the default.
- **Dormant** — not seen for `dormantForDays`. Opt-in, and at least 30 days: anything shorter
  describes a holiday, and a cleanup that quietly emptied the directory would look like a bug.

An entry a permission grant still names is **skipped**, and the listing says which those are
(`hasGrants`). Removing one leaves the grant showing a bare subject reference nobody can
attribute — an access rule made unreadable, which is worse than an untidy picker. `includeGranted`
overrides that, and still does not revoke anything: deciding that access should end is a separate
and deliberate act.
