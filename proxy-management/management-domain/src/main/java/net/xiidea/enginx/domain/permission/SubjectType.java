package net.xiidea.enginx.domain.permission;

public enum SubjectType {
    /** {@code subjectRef} is the Keycloak {@code sub} claim. */
    USER,
    /** {@code subjectRef} is a Keycloak group path, for example {@code /platform/production}. */
    GROUP
}
