package net.xiidea.enginx.domain.certificate;

public enum CertificateStatus {
    VALID,
    /** Inside the renewal window. Still serving; a renewal is due or already running. */
    EXPIRING_SOON,
    EXPIRED,
    REVOKED,
    /** Issuance or renewal failed. The previous material, if any, is still installed. */
    ERROR;

    /** Whether material with this status may still be deployed to a host. */
    public boolean isUsable() {
        return this == VALID || this == EXPIRING_SOON;
    }
}
