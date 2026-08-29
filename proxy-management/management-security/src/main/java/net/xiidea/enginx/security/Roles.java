package net.xiidea.enginx.security;

/**
 * Keycloak realm roles. These establish a global floor only; the domain-scoped grants that make
 * up the rest of the permission model arrive in Phase 3.
 */
public final class Roles {

    public static final String SUPER_ADMIN = "SUPER_ADMIN";
    public static final String ADMIN = "ADMIN";
    public static final String OPERATOR = "OPERATOR";
    public static final String READ_ONLY = "READ_ONLY";

    /** Anyone who may read. */
    public static final String HAS_ANY_ROLE =
            "hasAnyRole('SUPER_ADMIN','ADMIN','OPERATOR','READ_ONLY')";

    /** Enable, disable, renew, deploy. */
    public static final String CAN_OPERATE = "hasAnyRole('SUPER_ADMIN','ADMIN','OPERATOR')";

    /** Create, update, delete, clone. */
    public static final String CAN_MANAGE = "hasAnyRole('SUPER_ADMIN','ADMIN')";

    /** Register NGINX instances, administer permissions. */
    public static final String IS_SUPER_ADMIN = "hasRole('SUPER_ADMIN')";

    private Roles() {
    }
}
