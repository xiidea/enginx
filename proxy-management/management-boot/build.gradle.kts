plugins {
    id("enginx.java-conventions")
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":management-domain"))
    implementation(project(":management-application"))
    implementation(project(":management-infrastructure"))
    implementation(project(":management-security"))
    implementation(project(":management-scheduler"))
    implementation(project(":management-api"))

    implementation(platform(libs.spring.boot.bom))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Prometheus scrape endpoint. The registry is what turns the meters below into
    // something an alerting system can act on.
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    // Waiting for a scheduled job to fire is inherently a poll; awaitility makes that
    // explicit and bounded rather than a sleep that is either flaky or slow.
    testImplementation("org.awaitility:awaitility")
    // Generating real X.509 material in tests, so metadata parsing and expiry monitoring
    // are exercised against genuine certificates rather than stub strings.
    testImplementation(libs.bouncycastle.pkix)
    // The security and persistence modules expose these through `implementation`, which is not
    // transitive, so the test source set asks for them directly.
    testImplementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa")
    testImplementation("org.springframework.boot:spring-boot-starter-quartz")
    // Integration tests run against a real PostgreSQL. The permission model leans on partial
    // indexes, check constraints and prefix matching, none of which an in-memory database
    // reproduces faithfully, so testing against H2 would prove the wrong thing.
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

/**
 * Writes META-INF/build-info.properties into the jar, which is what makes the running application
 * able to say which version it is. Without it the version exists only in the artefact's filename
 * and the image tag — so a deployed server could not be asked, and "which version is that host
 * running?" would be answered by looking at what somebody believes they deployed.
 */
springBoot {
    buildInfo {
        properties {
            // The Gradle version, which the release sets from the tag via -Pversion.
            version = project.version.toString()
        }
    }
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName = "proxy-management.jar"
}
