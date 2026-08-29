package net.xiidea.enginx.domain.permission;

/**
 * What a subject may do within a scope. Totally ordered, so an effective level is simply the
 * maximum of the levels that apply.
 */
public enum PermissionLevel {

    /** View sites, deployments, certificates and configuration diffs. Never secrets. */
    READ(10),

    /** READ, plus enable, disable, renew expiry, deploy and roll back. Cannot change routing. */
    OPERATE(20),

    /** OPERATE, plus create, update, delete and clone; attach certificates; manage membership. */
    MANAGE(30),

    /** MANAGE, plus grant and revoke permissions within the scope. */
    ADMIN(40);

    private final int rank;

    PermissionLevel(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    public boolean satisfies(PermissionLevel required) {
        return rank >= required.rank;
    }

    public PermissionLevel max(PermissionLevel other) {
        if (other == null) {
            return this;
        }
        return rank >= other.rank ? this : other;
    }

    public PermissionLevel min(PermissionLevel other) {
        if (other == null) {
            return this;
        }
        return rank <= other.rank ? this : other;
    }

    /** Null-safe maximum, treating null as "no access at all". */
    public static PermissionLevel highest(PermissionLevel a, PermissionLevel b) {
        if (a == null) {
            return b;
        }
        return a.max(b);
    }
}
