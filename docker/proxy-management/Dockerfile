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
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon :management-boot:bootJar -x test

# ---- run ------------------------------------------------------------------
FROM eclipse-temurin:25-jre-alpine AS runtime

RUN apk add --no-cache wget \
    && addgroup -S enginx && adduser -S -G enginx -H enginx

WORKDIR /app
COPY --from=build /workspace/management-boot/build/libs/proxy-management.jar app.jar

USER enginx
EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
