plugins {
    id("enginx.java-conventions")
}

dependencies {
    api(project(":management-domain"))
    implementation(project(":management-application"))

    implementation(platform(libs.spring.boot.bom))
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-web")
    // Token-bucket rate limiting. A library rather than a hand-rolled counter because the
    // refill arithmetic under concurrency is where naive limiters are either racy or lock the
    // world; this one is lock-free and its semantics are well specified.
    implementation(libs.bucket4j.core)

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
