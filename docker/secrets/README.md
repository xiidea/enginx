# Secrets

Each file here holds one credential, as raw bytes with no trailing newline convention beyond what
the consumer expects. They are mounted into containers at `/run/secrets/` by
`docker-compose.prod.yml`, never passed as environment variables — an environment variable is
visible in `docker inspect`, in `/proc`, and in any tool that dumps its environment on error.

Generate them with `./generate-secrets.sh`, which refuses to overwrite anything that already
exists. Losing `crypto_key_k1` means every stored certificate private key and the ACME account key
become unreadable, so it is the one file here that needs a backup of its own.

| File | Used by | Notes |
|---|---|---|
| `db_password` | PostgreSQL, Keycloak, management API | Rotating it means rotating it in the database too |
| `keycloak_admin_password` | Keycloak bootstrap admin | Only used on first start |
| `crypto_key_k1` | Management API | Base64 of 32 random bytes. Wraps every stored secret |
| `agent_keystore_password` | Management API | Protects the mTLS client keystore |
| `agent_truststore_password` | Management API | Protects the agent CA truststore |
