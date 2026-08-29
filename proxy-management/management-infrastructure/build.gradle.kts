plugins {
    id("enginx.java-conventions")
}

dependencies {
    api(project(":management-domain"))
    implementation(project(":management-application"))

    implementation(platform(libs.spring.boot.bom))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    // Email delivery for notifications. The channel only registers when spring.mail.host is set,
    // so a deployment without SMTP carries the dependency but never an unusable channel.
    implementation("org.springframework.boot:spring-boot-starter-mail")
    // The starter, not org.liquibase:liquibase-core. Spring Boot 4 moved auto-configuration
    // into per-technology modules, so the bare library brings no LiquibaseAutoConfiguration
    // and migrations silently never run.
    implementation("org.springframework.boot:spring-boot-starter-liquibase")
    // Audit before/after payloads are written to jsonb columns.
    implementation("tools.jackson.core:jackson-databind")
    // ACME (RFC 8555). One implementation of the CertificateProvider port, not the only
    // possible one: a private CA can be added behind the same interface.
    implementation(libs.acme4j.client)
    runtimeOnly("org.postgresql:postgresql")

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.assertj:assertj-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
