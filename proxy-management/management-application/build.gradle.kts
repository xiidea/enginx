plugins {
    id("enginx.java-conventions")
}

dependencies {
    api(project(":management-domain"))

    implementation(platform(libs.spring.boot.bom))
    // Transaction demarcation and DI only. No web, no persistence.
    implementation("org.springframework:spring-tx")
    implementation("org.springframework:spring-context")
    // @PreAuthorize lives on service methods, not controllers: a scheduler or message consumer
    // can bypass a controller, but it cannot bypass the service it calls.
    implementation("org.springframework.security:spring-security-core")
    // The dispatcher logs what it did to a remote host; that is operational evidence, not noise.
    implementation("org.slf4j:slf4j-api")
    // Property binding only — the core jar, not a starter, so this stays "no web, no persistence".
    // Notification thresholds and recipient lists are operator configuration and belong in
    // application.yml rather than in constants.
    implementation("org.springframework.boot:spring-boot")

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.mockito:mockito-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
