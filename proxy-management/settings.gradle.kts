rootProject.name = "proxy-management"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(
    "management-domain",
    "management-application",
    "management-infrastructure",
    "management-security",
    "management-scheduler",
    "management-api",
    "management-boot",
)
