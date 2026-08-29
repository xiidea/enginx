# syntax=docker/dockerfile:1
#
# Built from the repository root, because the image needs both the Angular workspace and the
# nginx configuration that serves it.

# ---- build ----------------------------------------------------------------
FROM node:20-alpine AS build
WORKDIR /app

# Manifests first, so a dependency install is cached independently of source changes.
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-fund --no-audit

COPY frontend/ ./
RUN npx ng build

# ---- run ------------------------------------------------------------------
FROM nginx:1.27-alpine AS runtime

COPY --from=build /app/dist/frontend/browser /usr/share/nginx/html
COPY docker/frontend/nginx.conf /etc/nginx/conf.d/default.conf

# The issuer and API base are written at container start rather than baked into the bundle, so one
# image serves development and production without a rebuild.
COPY docker/frontend/entrypoint.sh /docker-entrypoint.d/40-enginx-config.sh
RUN chmod +x /docker-entrypoint.d/40-enginx-config.sh

EXPOSE 80
