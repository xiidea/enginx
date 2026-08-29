package net.xiidea.enginx.application.shared;

import net.xiidea.enginx.domain.permission.AuthenticatedSubject;

/**
 * Supplies the caller's identity for authorization: their subject claim, their group paths and
 * their realm roles.
 *
 * <p>Separate from {@link ActorProvider}, which answers "who should the audit row name". They
 * come from the same token but serve different purposes, and only this one is security-critical.
 */
public interface SubjectProvider {

    AuthenticatedSubject currentSubject();
}
