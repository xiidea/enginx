plugins {
    id("enginx.java-conventions")
}

// The domain module deliberately has NO production dependencies.
// No Spring, no JPA, no Jakarta. See architecture decision AD-1.
dependencies {
    testImplementation(platform(libs.spring.boot.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testImplementation(libs.archunit.junit5)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
