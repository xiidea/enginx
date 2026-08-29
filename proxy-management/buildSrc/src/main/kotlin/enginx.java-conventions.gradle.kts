plugins {
    `java-library`
}

group = "net.xiidea.enginx"
// Overridable from the command line, which is how a release stamps the tag into the artefacts:
// ./gradlew build -Pversion=1.2.3. Gradle sets the property before this script runs, so assigning
// unconditionally here would quietly discard it — and the release would ship a jar named for the
// tag containing a manifest that says SNAPSHOT.
version = (findProperty("version") as String?)
    ?.takeIf { it.isNotBlank() && it != "unspecified" }
    ?: "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // Retain parameter names: required by Spring's constructor binding and by record deserialization.
    options.compilerArgs.add("-parameters")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Gradle does not forward daemon system properties to the test JVM. Golden-file tests need
    // this one to switch from asserting to regenerating.
    System.getProperty("golden.update")?.let { systemProperty("golden.update", it) }
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
