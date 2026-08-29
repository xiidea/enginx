plugins {
    id("enginx.java-conventions")
}

dependencies {
    api(project(":management-domain"))
    implementation(project(":management-application"))

    implementation(platform(libs.spring.boot.bom))
    implementation("org.springframework.boot:spring-boot-starter-quartz")
    implementation("org.slf4j:slf4j-api")

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.mockito:mockito-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
