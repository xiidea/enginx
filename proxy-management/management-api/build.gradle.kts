plugins {
    id("enginx.java-conventions")
}

dependencies {
    api(project(":management-domain"))
    implementation(project(":management-application"))

    implementation(platform(libs.spring.boot.bom))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    // org.springframework.dao.* exception types, translated to RFC 9457 problem details.
    implementation("org.springframework:spring-tx")
    implementation(libs.springdoc.webmvc.ui)

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
