package net.xiidea.enginx.domain.audit;

public enum AuditAction {
    PROXY_SITE_CREATED,
    PROXY_SITE_UPDATED,
    PROXY_SITE_DELETED,
    PROXY_SITE_ENABLED,
    PROXY_SITE_DISABLED,
    PROXY_SITE_RENEWED,
    /** Who is told about this site, and whether. Not a change to what it serves. */
    PROXY_SITE_NOTIFICATIONS_UPDATED,
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
    /** A new agent token was trusted for a host. The token itself is never recorded. */
    NGINX_INSTANCE_TOKEN_ROTATED,
    /** Whether the platform or the host itself answers names no site matches. */
    NGINX_INSTANCE_DEFAULT_SERVER_CHANGED,

    DOMAIN_GROUP_CREATED,
    DOMAIN_GROUP_UPDATED,
    DOMAIN_GROUP_DELETED,
    DOMAIN_GROUP_MEMBER_ADDED,
    DOMAIN_GROUP_MEMBER_REMOVED,

    LOCAL_USER_CREATED,
    LOCAL_USER_UPDATED,
    LOCAL_USER_DELETED,
    LOCAL_USER_ENABLED,
    LOCAL_USER_DISABLED,
    LOCAL_USER_PASSWORD_CHANGED,
    LOCAL_USER_LOGGED_IN,
    /** A rejected login. Recorded as a denial, with the reason the caller was not told. */
    LOCAL_USER_LOGIN_FAILED,

    /** A subject removed from the identity directory. The account, if any, is untouched. */
    IDENTITY_SUBJECT_FORGOTTEN,

    /** A credential minted so a host can enrol itself. */
    AGENT_REGISTRATION_TOKEN_CREATED,
    AGENT_REGISTRATION_TOKEN_REVOKED,
    /** A host enrolled itself and was issued a token. Recorded whether or not it succeeded. */
    AGENT_REGISTERED,
    AGENT_REGISTRATION_REFUSED,
    AGENT_TOKEN_REVOKED,

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
