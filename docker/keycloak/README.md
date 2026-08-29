# Development realm

`realm-enginx.json` is imported on Keycloak startup by `--import-realm`. Keycloak deserialises it
into `RealmRepresentation` with unknown-field rejection enabled, so the file cannot carry comment
keys of any kind — a stray `_comment` field fails the whole import. The notes therefore live here.

## Choices worth knowing

**`accessTokenLifespan` is 300 seconds.** Group membership is read from the token on every
request rather than from a mirrored table, so the access-token lifespan is the upper bound on how
long a revoked group membership keeps working. That is architecture risk R6, and five minutes is
the mitigation.

**`enginx-frontend` has `directAccessGrantsEnabled: true`.** The password grant exists so local
development and integration tests can obtain a token in one request. Turn it off in any deployed
realm; the browser flow is authorization code with PKCE, which is what the client is otherwise
configured for.

**`enginx-api` starts no flows.** It exists so tokens can name it as an audience. The API rejects
any token whose `aud` does not contain it, which is what stops a token minted for an unrelated
application in the same realm from being accepted here.

**The audience mapper is on `enginx-frontend`, not `enginx-api`.** Keycloak adds an audience to
the tokens a client requests, so the mapper has to sit on the client doing the requesting.

**`full.path` is true on the groups mapper.** Group claims arrive as `/platform/production`
rather than `production`, so the domain-group grants added in Phase 3 can distinguish two
similarly named groups under different parents.

## Users

All passwords are development-only.

| Username | Password | Realm role |
|---|---|---|
| `admin` | `admin123` | `SUPER_ADMIN` |
| `manager` | `manager123` | `ADMIN` |
| `operator` | `operator123` | `OPERATOR` |
| `viewer` | `viewer123` | `READ_ONLY` |

## Re-importing after a change

The realm is only imported when it does not already exist. To pick up an edit:

```bash
docker compose down -v keycloak && docker compose up -d keycloak
```
