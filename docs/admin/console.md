# The admin console

The web interface.

## The admin console

Angular 21, standalone components and signals, every feature lazily loaded — 98 kB initial
transfer. Authentication is whatever the server reports at `GET /api/v1/auth/methods`: a username
and password form for local accounts, a redirect for an identity provider, or both. The redirect is
authorization code with PKCE against the same realm the API trusts.

```bash
cd frontend && npm install && npm start   # http://localhost:4200 against a local API
```

### What it shows

**Dashboard** leads with what needs attention — sites expiring within a fortnight, sites already
expired, failed deployments, certificates approaching renewal — and says so explicitly when
nothing does. **Proxy sites** filters and sorts in the query, so the page count is honest.
**Site detail** carries the configuration preview, which renders the exact NGINX directives a
deployment would produce without touching live traffic, and the groups the site is filed under —
the same question as who else can reach it, since a group grant reaches everything beneath it. **Certificates**, **Deployments** with
per-phase history, **Permissions** with domain groups — expandable to manage which sites each one
contains — **NGINX instances**, and **Directory**, which lists everyone who has signed in and
removes the entries that no longer resolve. **Local users**
appears only when the platform authenticates people itself, and only for a global admin.

### Hidden actions are a courtesy, not a control

The console asks the server what the current user may do to each site and hides what would be
refused. That is presentation only: every action is re-authorised on the request itself. Verified
both ways — a read-only user is offered no mutating action, and the same account's token is still
refused `403` when the request is made directly.

Domain-scoped access shows through: a `READ_ONLY` user with no grants sees an empty list, and one
granted READ on a single site sees exactly that site.

### Design

Cool, faintly green-biased neutrals with a muted pine accent, continuing the palette from the
architecture document so the tool and the document that describes it read as one system. IBM Plex
Sans for interface text, IBM Plex Mono for domains, hashes and identifiers — the things operators
compare character by character.

State is encoded in form as well as colour: status pills carry a dot, rows needing attention carry
a left stripe. Ochre and brick are reserved strictly for state, never decoration, so a warning
colour always means a warning. Light and dark are both designed, with the un-stamped "system"
case working from `prefers-color-scheme` alone.

### Runtime configuration

The issuer and API base are written to `config.js` at container start rather than baked into the
bundle, so one image serves several environments without a rebuild.

### Preview

`GET /proxy-sites/{id}/preview` renders without deploying and reports which bundle files would
change. The renderer is a pure function, so this costs nothing and is the cheapest way to trust
the platform.

### Golden files

`management-domain/src/test/resources/golden/` holds expected renderer output, diffed on every
build. Update after an intended change with `-Dgolden.update=true` and read the diff before
committing. Without them a renderer regression stays invisible until a deployment fails
validation — or worse, passes it and routes traffic somewhere new.
