# The API

A versioned REST API under `/api/v1`. Every error is an RFC 9457 problem document, and every
mutating request is audited.


Versioned under `/api/v1`. Errors are RFC 9457 problem documents — branch on the stable `type`
URI, never on the human-readable `detail`.

| Method | Path | Required role (Phase 2) |
|---|---|---|
| `GET` | `/api/v1/proxy-sites` | any |
| `GET` | `/api/v1/proxy-sites/{id}` | any |
| `POST` | `/api/v1/proxy-sites` | `ADMIN` |
| `PUT` | `/api/v1/proxy-sites/{id}` | `ADMIN` |
| `DELETE` | `/api/v1/proxy-sites/{id}` | `ADMIN` |
| `POST` | `/api/v1/proxy-sites/{id}/clone` | `ADMIN` |
| `POST` | `/api/v1/proxy-sites/{id}/enable` | `OPERATOR` |
| `POST` | `/api/v1/proxy-sites/{id}/disable` | `OPERATOR` |
| `POST` | `/api/v1/proxy-sites/{id}/renew` | `OPERATOR` |
| `DELETE` | `/api/v1/proxy-sites/{id}/expiration` | `OPERATOR` |
| `GET` | `/api/v1/nginx-instances` | any |
| `POST` | `/api/v1/nginx-instances` | `SUPER_ADMIN` |
| `GET`/`POST` | `/api/v1/domain-groups` | READ / MANAGE on the parent |
| `PUT`/`DELETE` | `/api/v1/domain-groups/{id}` | MANAGE / ADMIN on the group |
| `POST`/`DELETE` | `/api/v1/domain-groups/{id}/members/{siteId}` | MANAGE on **both** |
| `GET`/`POST` | `/api/v1/permissions` | ADMIN on the scope |
| `DELETE` | `/api/v1/permissions/{id}` | ADMIN on the scope |
| `GET` | `/api/v1/permissions/effective?siteId=` | READ on the site |
| `GET` | `/api/v1/users`, `/api/v1/groups` | ADMIN on any scope |
| `GET` | `/api/v1/proxy-sites/{id}/preview` | READ on the site |
| `POST` | `/api/v1/proxy-sites/{id}/deploy` | OPERATE on the site |
| `POST` | `/api/v1/nginx-instances/{id}/deploy` | global ADMIN |
| `GET` | `/api/v1/deployments`, `/deployments/{id}` | READ on the instance |
| `POST` | `/api/v1/deployments/{id}/rollback` | global ADMIN |
| `GET` | `/api/v1/nginx-instances/{id}/bundles`, `/config-bundles/{id}` | READ on the instance |
| `GET` | `/api/v1/auth/methods` | none — this is what a caller reads before it has a token |
| `POST` | `/api/v1/auth/login` | none — rate limited as a sensitive operation |
| `GET`/`POST` | `/api/v1/local-users` | global admin |
| `PUT`/`DELETE` | `/api/v1/local-users/{id}` | global admin |
| `POST` | `/api/v1/local-users/{id}/enable`, `/disable` | global admin |
| `PUT` | `/api/v1/local-users/{id}/password` | your own, or global admin for anyone else |

`/api/v1/auth/methods` and `/api/v1/auth/login` are the only two endpoints that do not require a
token. Neither reveals whether an account exists.

A request that never reaches a handler is reported as the caller's mistake, not the server's:
`404 no-such-endpoint` for an unknown path, `405 method-not-allowed` (with `Allow`) for the wrong
method, `415 unsupported-media-type` and `406 not-acceptable` for a body or a representation this
API cannot handle. An unknown path still answers `401` without a token, so it cannot be used to
enumerate endpoints.

## Generated client


The console's TypeScript types are generated from the server's own OpenAPI document, so a field
renamed or removed on the server becomes a compile error rather than a value that is quietly
`undefined` at runtime.

```bash
cd frontend && npm run generate:api
```

`docs/api/openapi.json` is the committed snapshot; CI regenerates from it and fails if the checked-in
types differ. See [the OpenAPI contract](#the-openapi-contract) below for what this catches and what it does
not — the snapshot is a commit, not a live read, so refreshing it is part of changing the API.

---

## The OpenAPI contract

`openapi.json` is a snapshot of the document the running server publishes at `/v3/api-docs`. The
console's TypeScript types are generated from it, so a field renamed or removed on the server
becomes a compile error in the console rather than a value that is quietly `undefined` at runtime.

## Refreshing it

The spec comes from a running application, because it is produced by inspecting live controllers:

```bash
cd docker && docker compose up -d proxy-management
curl -s http://localhost:8080/v3/api-docs \
  | python3 -c "import json,sys; json.dump(json.load(sys.stdin), open('../docs/api/openapi.json','w'), indent=2, sort_keys=True)"

cd ../frontend && npm run generate:api   # writes src/app/core/api/schema.d.ts
```

Commit both. CI regenerates the types from the committed spec and fails if they differ, so the
generated file cannot drift by hand-editing.

## What this does and does not catch

**Caught:** a renamed, removed or retyped field; a removed endpoint; a changed required set. The
console stops compiling.

**Not caught automatically:** a server change that nobody refreshed the snapshot for. The snapshot
is a commit, not a live read. Refreshing it is part of changing the API, the same way updating a
test is.

## Required means non-null

`requiredProperties` on the response DTOs is what makes the generated types worth having. Without
it springdoc marks every field optional — Java records carry no nullability information it can read
— and every field arrives as `T | undefined`, which is both untrue and unusable.

A field is listed as required when it is always present **and** never null. Genuinely nullable
fields are left optional on purpose: that is what tells any generated client it must handle their
absence.
