#!/usr/bin/env bash
#
# Creates the production secret files, once.
#
# Refuses to overwrite anything that already exists. Regenerating crypto_key_k1 in place would
# leave every certificate private key and the ACME account key encrypted under a key that no
# longer exists — unrecoverable, and discovered at the next renewal rather than now.

set -euo pipefail

cd "$(dirname "$0")"
mkdir -p secrets

generate() {
    local name="$1" value="$2"
    if [[ -e "secrets/$name" ]]; then
        echo "  exists, left alone: $name"
        return
    fi
    printf '%s' "$value" > "secrets/$name"
    chmod 600 "secrets/$name"
    echo "  created: $name"
}

echo "Generating secrets in $(pwd)/secrets"

generate db_password              "$(openssl rand -base64 24 | tr -d '\n/+=' | cut -c1-24)"
generate keycloak_admin_password  "$(openssl rand -base64 24 | tr -d '\n/+=' | cut -c1-24)"
# 32 bytes, base64: the key-encryption key for AES-256-GCM envelope encryption.
generate crypto_key_k1            "$(openssl rand -base64 32)"
generate agent_keystore_password  "$(openssl rand -base64 18 | tr -d '\n/+=' | cut -c1-18)"
generate agent_truststore_password "$(openssl rand -base64 18 | tr -d '\n/+=' | cut -c1-18)"

chmod 700 secrets

cat <<'NOTE'

Back up secrets/crypto_key_k1 somewhere the database backup is not.

It wraps every certificate private key and the ACME account key. A database restored without it
is a database of ciphertext. Keeping the two together defeats encrypting them in the first place.
NOTE
