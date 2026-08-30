package net.xiidea.enginx.application.identity;

import net.xiidea.enginx.domain.identity.LocalUser;
import net.xiidea.enginx.domain.identity.LocalUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Decides whether a still-unexpired local token is still good.
 *
 * <p>A local access token carries the account's roles and lives for hours. Nothing about disabling
 * the account, deleting it, or changing its roles reaches a token already in a caller's hands —
 * so without a check on every request, an account revoked at 09:00 keeps its access until its
 * token expires that afternoon. That is the window an operator closes when they pull a compromised
 * credential, and it has to actually close.
 *
 * <p>The account's {@code updatedAt} is the revocation epoch. It is stamped whenever the account is
 * enabled or disabled, its roles or groups change, or its password is replaced — every change that
 * should not leave an older token standing. A token issued before that moment is spent.
 *
 * <p>Only local tokens are checked here. An OIDC token's lifecycle belongs to its issuer, which
 * revokes and expires on its own terms; re-deciding that here would be second-guessing the
 * identity provider with staler information than it has.
 */
@Service
public class LocalTokenValidator {

    /**
     * How far a token's issue time may sit before the account's {@code updatedAt} and still count
     * as issued after it. A token's {@code iat} is whole seconds, while {@code updatedAt} carries
     * sub-second precision, so a token minted in the same second as the change it should survive
     * would otherwise floor to just before it and be rejected the instant it was issued. A couple
     * of seconds also absorbs ordinary clock skew. Revocation is therefore effective within this
     * window rather than instantly, which against an hours-long token is no window at all.
     */
    private static final long GRACE_SECONDS = 2;

    private final LocalUserRepository users;

    public LocalTokenValidator(LocalUserRepository users) {
        this.users = users;
    }

    /**
     * @param userId   the account the token names ({@code sub} with its {@code local:} prefix removed)
     * @param issuedAt the token's {@code iat}
     * @return whether the token should still be honoured
     */
    @Transactional(readOnly = true)
    public boolean isStillValid(UUID userId, Instant issuedAt) {
        Optional<LocalUser> found = users.findById(userId);
        if (found.isEmpty()) {
            // Deleted since the token was issued. Nothing to authorise as.
            return false;
        }
        LocalUser user = found.get();
        if (!user.enabled()) {
            return false;
        }
        if (issuedAt == null) {
            // A local token with no issue time cannot be placed relative to the revocation epoch,
            // so it cannot be trusted to pre-date no change.
            return false;
        }
        // Issued before the last security-relevant change to the account.
        return !user.updatedAt().isAfter(issuedAt.plusSeconds(GRACE_SECONDS));
    }
}
