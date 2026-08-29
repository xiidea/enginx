package net.xiidea.enginx.domain.permission;

public enum ScopeType {
    /** Every site. */
    GLOBAL,
    /** Sites in a domain group, or in any of its descendants. */
    DOMAIN_GROUP,
    /** Sites whose domain matches a pattern, which may be a wildcard. */
    DOMAIN_PATTERN,
    /** One specific site. */
    SITE
}
