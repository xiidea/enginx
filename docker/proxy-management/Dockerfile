# syntax=docker/dockerfile:1

# ---- build ----------------------------------------------------------------
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /workspace

# Copy the build definition first so dependency resolution is cached independently of sources.
COPY gradle/ gradle/
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY buildSrc/ buildSrc/
COPY management-domain/build.gradle.kts        management-domain/
COPY management-application/build.gradle.kts   management-application/
COPY management-infrastructure/build.gradle.kts management-infrastructure/
COPY management-security/build.gradle.kts      management-security/
COPY management-api/build.gradle.kts           management-api/
COPY management-scheduler/build.gradle.kts     management-scheduler/
COPY management-boot/build.gradle.kts          management-boot/
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon :management-boot:dependencies --quiet || true

COPY . .
# Stamped so the running application can say which version it is, not only the image tag. Without
# it the jar inside a 0.0.2 image would report a SNAPSHOT, and "which version is that host
# running?" could only be answered by trusting what somebody believes they deployed.
ARG VERSION=0.1.0-SNAPSHOT
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon :management-boot:bootJar -x test -Pversion=${VERSION}

# ---- run ------------------------------------------------------------------
FROM eclipse-temurin:25-jre-alpine AS runtime

# OCI labels, so `docker inspect` answers the version question without starting the container.
# The same default as the build stage above: a label claiming one version over a jar built at
# another is worse than no label.
ARG VERSION=0.1.0-SNAPSHOT
ARG COMMIT=unknown
LABEL org.opencontainers.image.title="Easy NGINX Admin management server" \
      org.opencontainers.image.description="Control plane for NGINX reverse proxies" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${COMMIT}" \
      org.opencontainers.image.source="https://github.com/xiidea/enginx" \
      org.opencontainers.image.licenses="Apache-2.0"


RUN apk add --no-cache wget \
    && addgroup -S enginx && adduser -S -G enginx -H enginx

WORKDIR /app
COPY --from=build /workspace/management-boot/build/libs/proxy-management.jar app.jar

USER enginx
EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
