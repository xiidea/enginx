# syntax=docker/dockerfile:1
#
# Built from the repository root, because the image needs both the Angular workspace and the
# nginx configuration that serves it.

# ---- build ----------------------------------------------------------------
# Pinned to the build platform, never the target. The output is static HTML/CSS/JS, byte-identical
# on every architecture, so building it once natively and copying it into each per-arch runtime
# image is correct — and it avoids running the Angular/npm build under QEMU emulation for arm64,
# which is pathologically slow and can hang or OOM a multi-arch release.
FROM --platform=$BUILDPLATFORM node:20-alpine AS build
WORKDIR /app

# Manifests first, so a dependency install is cached independently of source changes.
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-fund --no-audit

COPY frontend/ ./
RUN npx ng build

# ---- run ------------------------------------------------------------------
FROM nginx:1.27-alpine AS runtime

# OCI labels, so `docker inspect` answers the version question without starting the container.
ARG VERSION=dev
ARG COMMIT=unknown
LABEL org.opencontainers.image.title="Easy NGINX Admin console" \
      org.opencontainers.image.description="Web interface for the management server" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${COMMIT}" \
      org.opencontainers.image.source="https://github.com/xiidea/enginx" \
      org.opencontainers.image.licenses="Apache-2.0"


# The release this image was built for, read by the entrypoint into the console's runtime config.
ENV ENGINX_VERSION=${VERSION}

COPY --from=build /app/dist/frontend/browser /usr/share/nginx/html
COPY docker/frontend/nginx.conf /etc/nginx/conf.d/default.conf

# The issuer and API base are written at container start rather than baked into the bundle, so one
# image serves development and production without a rebuild.
COPY docker/frontend/entrypoint.sh /docker-entrypoint.d/40-enginx-config.sh
RUN chmod +x /docker-entrypoint.d/40-enginx-config.sh

EXPOSE 80
