package net.xiidea.enginx.domain.audit;

public enum AuditAction {
    PROXY_SITE_CREATED,
    PROXY_SITE_UPDATED,
    PROXY_SITE_DELETED,
    PROXY_SITE_ENABLED,
    PROXY_SITE_DISABLED,
    PROXY_SITE_RENEWED,
    PROXY_SITE_EXPIRY_REMOVED,
    PROXY_SITE_CLONED,
    NGINX_INSTANCE_REGISTERED,
    /**
     * The host is serving a configuration the platform did not put there. Recorded, never acted
     * on: the cause is frequently a person mid-incident, and overwriting them silently is how a
     * platform loses the trust it needs to be allowed near production.
     */
    NGINX_INSTANCE_DRIFTED,
    /** A new agent certificate was pinned for a host. */
    NGINX_INSTANCE_CERT_ROTATED,

    DOMAIN_GROUP_CREATED,
    DOMAIN_GROUP_UPDATED,
    DOMAIN_GROUP_DELETED,
    DOMAIN_GROUP_MEMBER_ADDED,
    DOMAIN_GROUP_MEMBER_REMOVED,

    PERMISSION_GRANTED,
    PERMISSION_REVOKED,

    PROXY_SITE_EXPIRED,
    PROXY_SITE_ACTIVATED,

    DEPLOYMENT_REQUESTED,
    DEPLOYMENT_SUCCEEDED,
    DEPLOYMENT_FAILED,
    CONFIGURATION_ROLLED_BACK,

    CERTIFICATE_REQUESTED,
    CERTIFICATE_ISSUED,
    CERTIFICATE_UPLOADED,
    CERTIFICATE_RENEWED,
    CERTIFICATE_REVOKED,
    CERTIFICATE_DELETED,
    CERTIFICATE_FAILED,
    CERTIFICATE_EXPIRING,
    /** Stored secrets were re-encrypted under a new key-encryption key. */
    CERTIFICATE_SECRETS_REWRAPPED,

    /**
     * Part of the trail was dropped by the retention policy. Recorded in the trail itself, so a
     * gap is never ambiguous between "policy" and "someone with database access".
     */
    AUDIT_RETENTION_APPLIED,

    ACCESS_DENIED
}
