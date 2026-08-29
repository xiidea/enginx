#!/usr/bin/env bash
#
# Generates a development PKI for management-to-agent mTLS.
#
# This is for local development only. Production agent certificates are a Phase 4 decision:
# either the platform issues and rotates them itself, or it consumes an existing internal CA.
# Either way, the private keys below must never leave a developer machine.
set -euo pipefail

OUT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/out"
DAYS=825
# The compose service that runs the agent is called `nginx` (it supervises NGINX in the
# same container), so that name must be in the SAN or hostname verification rejects it.
AGENT_HOSTS="${AGENT_HOSTS:-nginx,enginx-agent,localhost,127.0.0.1}"

mkdir -p "$OUT_DIR"
cd "$OUT_DIR"

if [[ -f ca.crt && "${FORCE:-0}" != "1" ]]; then
  echo "PKI already present in $OUT_DIR. Re-run with FORCE=1 to regenerate."
  exit 0
fi

echo "==> Certificate authority"
openssl req -x509 -newkey rsa:4096 -sha256 -days 3650 -nodes \
  -keyout ca.key -out ca.crt \
  -subj "/CN=Easy NGINX Admin Development CA/O=enginx" \
  -addext "basicConstraints=critical,CA:TRUE,pathlen:0" \
  -addext "keyUsage=critical,keyCertSign,cRLSign" 2>/dev/null

san=""
IFS=',' read -ra hosts <<< "$AGENT_HOSTS"
for host in "${hosts[@]}"; do
  if [[ "$host" =~ ^[0-9.]+$ ]]; then san+="IP:$host,"; else san+="DNS:$host,"; fi
done
san="${san%,}"

echo "==> Agent server certificate (SAN: $san)"
openssl req -newkey rsa:2048 -sha256 -nodes -keyout agent.key -out agent.csr \
  -subj "/CN=enginx-agent/O=enginx" 2>/dev/null
openssl x509 -req -in agent.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
  -out agent.crt -days "$DAYS" -sha256 \
  -extfile <(printf "subjectAltName=%s\nextendedKeyUsage=serverAuth\nkeyUsage=critical,digitalSignature,keyEncipherment\n" "$san") 2>/dev/null

echo "==> Management client certificate"
openssl req -newkey rsa:2048 -sha256 -nodes -keyout management.key -out management.csr \
  -subj "/CN=enginx-management/O=enginx" 2>/dev/null
openssl x509 -req -in management.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
  -out management.crt -days "$DAYS" -sha256 \
  -extfile <(printf "extendedKeyUsage=clientAuth\nkeyUsage=critical,digitalSignature\n") 2>/dev/null

rm -f agent.csr management.csr ca.srl

echo "==> Java keystores"
# The JDK cannot load a bare PEM key pair, so the management server gets PKCS#12 bundles. The
# password is not a secret: these are development files, and what protects them is the mode set
# below.
if ! command -v keytool >/dev/null 2>&1; then
  echo "ERROR: keytool is required to build the truststore. Install a JDK and re-run." >&2
  exit 1
fi

# Remove any earlier stores first. keytool refuses to import an alias that already exists, and a
# partially updated truststore fails at TLS handshake time with an error that points nowhere near
# this script.
rm -f management.p12 truststore.p12

openssl pkcs12 -export -out management.p12 -inkey management.key -in management.crt \
  -certfile ca.crt -name enginx-management -passout pass:changeit
keytool -importcert -noprompt -alias enginx-ca -file ca.crt \
  -keystore truststore.p12 -storetype PKCS12 -storepass changeit >/dev/null

# Prove the stores are usable rather than assuming it; a silent failure here surfaces much later
# as an unexplained handshake error.
keytool -list -keystore truststore.p12 -storepass changeit 2>/dev/null | grep -q trustedCertEntry \
  || { echo "ERROR: truststore.p12 has no trusted certificate entry" >&2; exit 1; }

chmod 600 ./*.key ./*.p12

fingerprint=$(openssl x509 -in agent.crt -noout -fingerprint -sha256 | cut -d= -f2 | tr -d ':')

cat <<SUMMARY

PKI written to $OUT_DIR

  ca.crt              trust anchor for both sides
  agent.crt/key       presented by the agent
  management.crt/key  presented by the management server
  management.p12      the same client identity, for the JVM (password: changeit)
  truststore.p12      the CA, for the JVM (password: changeit)

Agent certificate SHA-256 fingerprint (register the instance with this value):

  $fingerprint

SUMMARY

# ---------------------------------------------------------------------------
# Pebble's root, for local ACME testing.
#
# Pebble signs its ACME endpoint with a throwaway root that no public trust store knows. The JVM
# has to trust it to talk to the local authority at all. The truststore starts as a copy of the
# JDK's own cacerts so that public certificate authorities remain trusted too — replacing the
# default outright would break every other TLS connection the platform makes.
if command -v docker >/dev/null 2>&1 && [[ "${SKIP_PEBBLE:-0}" != "1" ]]; then
  echo "==> Pebble truststore (local ACME testing)"
  # docker cp rather than a shell in the container: the Pebble image is distroless and has no
  # cat, ls or sh to read the file with.
  pebble_container=$(docker create ghcr.io/letsencrypt/pebble:2.10.1 2>/dev/null || true)
  if [[ -n "$pebble_container" ]] \
      && docker cp "$pebble_container:/test/certs/pebble.minica.pem" ./pebble-ca.pem >/dev/null 2>&1 \
      && [[ -s pebble-ca.pem ]]; then
    docker rm -f "$pebble_container" >/dev/null 2>&1 || true

    cacerts="${JAVA_HOME:-$(/usr/libexec/java_home 2>/dev/null)}/lib/security/cacerts"
    rm -f truststore-pebble.p12 pebble-truststore.p12
    if [[ -f "$cacerts" ]]; then
      cp "$cacerts" pebble-truststore.p12
      keytool -storepasswd -keystore pebble-truststore.p12 -storepass changeit -new changeit >/dev/null 2>&1 || true
    fi
    keytool -importcert -noprompt -alias pebble-root -file pebble-ca.pem \
      -keystore pebble-truststore.p12 -storetype PKCS12 -storepass changeit >/dev/null
    chmod 644 pebble-truststore.p12
    echo "    pebble-truststore.p12 written (JDK roots plus Pebble's)"
  else
    [[ -n "${pebble_container:-}" ]] && docker rm -f "$pebble_container" >/dev/null 2>&1 || true
    echo "    skipped: could not read the Pebble image"
  fi
fi
