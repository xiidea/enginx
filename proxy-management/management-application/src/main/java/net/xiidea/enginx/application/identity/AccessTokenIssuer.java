package net.xiidea.enginx.application.identity;

import net.xiidea.enginx.domain.identity.LocalUser;

import java.time.Instant;

/**
 * Mints an access token for a local account.
 *
 * <p>A port so the application layer never learns how a token is signed. The token deliberately
 * carries the same claims an OIDC provider would, which is what lets every authorization decision
 * downstream stay unaware of which provider authenticated the caller.
 */
public interface AccessTokenIssuer {

    IssuedToken issue(LocalUser user);

    record IssuedToken(String token, Instant expiresAt, long expiresInSeconds) {
    }
}
