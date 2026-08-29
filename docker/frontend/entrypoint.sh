#!/bin/sh
# Writes runtime configuration the bundle reads from window.__enginx.
#
# The alternative is baking the issuer and API base into the JavaScript, which would mean a
# rebuild to move the same version between environments.
set -eu

cat > /usr/share/nginx/html/config.js <<CONFIG
window.__enginx = {
  apiBase: "${API_BASE:-http://localhost:8080/api/v1}",
  oidcAuthority: "${OIDC_AUTHORITY:-http://localhost:8081/realms/enginx}",
  oidcClientId: "${OIDC_CLIENT_ID:-enginx-frontend}"
};
CONFIG

# Injected before the application bundle so the values exist when it starts.
if ! grep -q "config.js" /usr/share/nginx/html/index.html; then
  sed -i 's|</head>|  <script src="/config.js"></script>\n</head>|' /usr/share/nginx/html/index.html
fi
