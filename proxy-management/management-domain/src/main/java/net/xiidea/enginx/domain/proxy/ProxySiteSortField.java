package net.xiidea.enginx.domain.proxy;

/**
 * The properties a caller may sort by. An enum rather than a free string, so that a sort
 * parameter can never reach the persistence layer as an arbitrary property path.
 */
public enum ProxySiteSortField {
    NAME,
    DOMAIN,
    STATUS,
    ACTIVE_FROM,
    EXPIRES_AT,
    CREATED_AT,
    UPDATED_AT
}
