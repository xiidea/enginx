# syntax=docker/dockerfile:1

# ---- build ----------------------------------------------------------------
FROM golang:1.26-alpine AS build
WORKDIR /src

COPY go.mod ./
RUN go mod download

COPY . .
# Stamped the same way the release binaries are, so a containerised agent reports a real version
# rather than "dev". Defaults to dev for a local build, which is what a local build is.
ARG VERSION=dev
ARG COMMIT=unknown
RUN CGO_ENABLED=0 go build -trimpath \
    -ldflags="-s -w \
      -X github.com/xiidea/enginx/enginx-agent/internal/version.Version=${VERSION} \
      -X github.com/xiidea/enginx/enginx-agent/internal/version.Commit=${COMMIT}" \
    -o /out/enginx-agent ./cmd/agent

# ---- run ------------------------------------------------------------------
# The agent ships in the same image as NGINX. It has to signal the NGINX process, and a process
# in another container cannot be signalled, so co-location is a requirement of the design.
FROM nginx:1.27-alpine AS runtime

# OCI labels, so `docker inspect` answers the version question without starting the container.
ARG VERSION=dev
ARG COMMIT=unknown
LABEL org.opencontainers.image.title="Easy NGINX Admin agent" \
      org.opencontainers.image.description="NGINX node agent, co-located with the NGINX it manages" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${COMMIT}" \
      org.opencontainers.image.source="https://github.com/xiidea/enginx" \
      org.opencontainers.image.licenses="Apache-2.0"


COPY --from=build /out/enginx-agent /usr/local/bin/enginx-agent

RUN mkdir -p /etc/nginx/enginx/releases /etc/enginx/pki

EXPOSE 80 8443
ENTRYPOINT ["/usr/local/bin/enginx-agent"]
